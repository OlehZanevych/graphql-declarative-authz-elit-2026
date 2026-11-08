package org.lnu.timetable.cons;

import org.lnu.timetable.domain.Course;
import org.lnu.timetable.domain.Department;
import org.lnu.timetable.domain.LecturerWorkload;
import org.lnu.timetable.security.AccessLevel;
import org.lnu.timetable.security.PermissionEvaluator;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Deterministic schedules for the consistency behaviour of the deployed evaluator ("baseline": one
 * request-scoped evaluator, autocommit statements, no shared synchronization) and of the two
 * experimental prototypes (RequestAuthzContext, AuthzProtocol). Interleavings are forced with
 * statement gates, never with sleeps; the 300 ms waits only confirm that a blocked party is still
 * blocked. Two ConsEnv objects with separate pools play two application instances.
 *
 * Fixture (created in a scratch copy of the database): caller 2001, department 9001, courses 9101
 * and 9102 in that department, group 901, and the existing faculties 1 and 2.
 */
public final class ConsistencyTests {

    static final long U = 2001, D = 9001, C = 9101, C2 = 9102, G = 901, W = 1, L = 99;
    static final Duration T = Duration.ofSeconds(20);

    record Row(String id, String scenario, String schedule, String baseline, String prototype,
               String expected, boolean pass) {}

    static ConsEnv a, b;      // two "application instances"
    static final List<Row> rows = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        String dbName = args.length > 0 ? args[0] : "lnu_cons";
        a = new ConsEnv(dbName, 16);
        b = new ConsEnv(dbName, 16);
        try {
            fixture();
            t1RevocationBeforeLaterField();
            t2OperationEntersFirst();
            t3ReparentingRace();
            t0MixedStateWithinOneEvaluation();
            t4SameRequestAncestorChange();
            t5GroupMembership();
            t6DescendantViaJoinEdge();
            t7NegativeCache();
            t8RollbackAndRetry();
            t9InFlightOldGeneration();
            t10RandomizedTwoInstances(args.length > 1 ? Integer.parseInt(args[1]) : 400);
        } finally {
            a.close();
            b.close();
        }
        Path out = Path.of(args.length > 2 ? args[2] : "results/consistency.tsv");
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out))) {
            w.println("id\tscenario\tschedule\tdeployed_baseline\tprototype\texpected\tpass");
            for (Row r : rows) {
                w.printf("%s\t%s\t%s\t%s\t%s\t%s\t%s%n", r.id, r.scenario, r.schedule, r.baseline, r.prototype, r.expected, r.pass ? "PASS" : "FAIL");
                System.out.printf("%-4s %-5s %s%n     baseline:  %s%n     prototype: %s%n", r.id, r.pass ? "PASS" : "FAIL", r.scenario, r.baseline, r.prototype);
            }
        }
        long failed = rows.stream().filter(r -> !r.pass).count();
        System.out.println(failed == 0 ? "ALL PASS (" + rows.size() + ")" : failed + " FAILED");
        System.exit(failed == 0 ? 0 : 1);
    }

    // ---------------------------------------------------------------- fixture helpers

    static void sql(String s) {
        a.db.sql(s).fetch().rowsUpdated().block(T);
    }

    static long one(String s) {
        return a.db.sql(s).map(r -> ((Number) r.get(0)).longValue()).one().block(T);
    }

    static void fixture() {
        sql("CREATE TABLE IF NOT EXISTS authz_domain (id int PRIMARY KEY, epoch bigint NOT NULL)");
        sql("INSERT INTO authz_domain VALUES (1, 0) ON CONFLICT DO NOTHING");
        sql("INSERT INTO users (id, email, first_name, last_name, password_hash, must_change_password) "
            + "VALUES (2001, 'consistency-caller@example.invalid', 'C', 'T', 'x', false) ON CONFLICT DO NOTHING");
        sql("INSERT INTO groups (id, name) VALUES (901, 'TEST consistency group') ON CONFLICT DO NOTHING");
        sql("INSERT INTO departments (id, name, faculty_id) VALUES (9001, 'TEST department', 1) ON CONFLICT DO NOTHING");
        sql("INSERT INTO courses (id, name, department_id) VALUES (9101, 'TEST course', 9001), (9102, 'TEST course 2', 9001) ON CONFLICT DO NOTHING");
        reset();
    }

    /** Back to: no grants for the caller, not in the group, department under faculty 1. */
    static void reset() {
        sql("DELETE FROM permissions WHERE user_id = 2001 OR group_id = 901");
        sql("DELETE FROM user_groups WHERE user_id = 2001");
        sql("UPDATE departments SET faculty_id = 1 WHERE id = 9001");
        sql("UPDATE courses SET name = 'TEST course' WHERE id = 9101");
        sql("UPDATE courses SET name = 'TEST course 2' WHERE id = 9102");
        sql("INSERT INTO lecturer_workload_lecturers VALUES (1, 99) ON CONFLICT DO NOTHING");
    }

    static void grant(String type, long id, String level) {
        sql("INSERT INTO permissions (grantee_type, user_id, resource_type, resource_id, level) VALUES ('USER', 2001, '"
            + type + "', " + id + ", '" + level + "')");
    }

    static String res(Class<?> c) {
        return a.registry.getMetadata(c).resourceType();
    }

    static String courseName(long id) {
        return a.db.sql("SELECT name FROM courses WHERE id = " + id).map(r -> (String) r.get(0)).one().block(T);
    }

    /** The deployed mutation shape for a non-scope-moving update: check, then write, autocommit. */
    static Mono<Boolean> baselineUpdate(ConsEnv env, PermissionEvaluator ev, long course, String name) {
        return ev.allows(Course.class, course, AccessLevel.EDIT).flatMap(ok -> !ok ? Mono.just(false)
            : env.db.sql("UPDATE courses SET name = :n WHERE id = :id").bind("n", name).bind("id", course)
                .fetch().rowsUpdated().thenReturn(true));
    }

    /** The same update as one protected unit of the prototype protocol. */
    static Mono<Boolean> protocolUpdate(AuthzProtocol p, ConsEnv env, long course, String name) {
        return p.run(AuthzProtocol.Mode.SHARED, U, ev -> ev.allows(Course.class, course, AccessLevel.EDIT)
            .flatMap(ok -> !ok ? Mono.just(false)
                : env.db.sql("UPDATE courses SET name = :n WHERE id = :id").bind("n", name).bind("id", course)
                    .fetch().rowsUpdated().thenReturn(true)));
    }

    static <X> CompletableFuture<X> async(Mono<X> m) {
        return m.subscribeOn(Schedulers.boundedElastic()).toFuture();
    }

    static boolean stillRunningAfter300ms(CompletableFuture<?> f) throws InterruptedException {
        Thread.sleep(300);
        return !f.isDone();
    }

    // ---------------------------------------------------------------- the schedules

    /** Revocation commits between two mutation fields of one request. */
    static void t1RevocationBeforeLaterField() throws Exception {
        reset();
        grant(res(Department.class), D, "EDIT");
        PermissionEvaluator ev = a.service.newEvaluator(U);          // the request's evaluator
        baselineUpdate(a, ev, C2, "x1").block(T);                     // field 1 loads and caches grants
        sql("DELETE FROM permissions WHERE user_id = 2001");          // another request revokes, commits
        boolean allowed = baselineUpdate(a, ev, C, "x2").block(T);    // field 2 reuses the cached grants
        String base = allowed ? "field 2 ALLOWED from cached grants; write committed after the revocation" : "denied";

        reset();
        grant(res(Department.class), D, "EDIT");
        AuthzProtocol p = new AuthzProtocol(a, false), pb = new AuthzProtocol(b, false);
        protocolUpdate(p, a, C2, "y1").block(T);
        pb.run(AuthzProtocol.Mode.EXCLUSIVE, 2002, e -> b.db.sql("DELETE FROM permissions WHERE user_id = 2001").fetch().rowsUpdated()).block(T);
        boolean allowedP = protocolUpdate(p, a, C, "y2").block(T);
        String proto = allowedP ? "ALLOWED" : "field 2 denied (fresh evaluator inside its own unit); course unchanged=" + courseName(C).equals("TEST course");
        rows.add(new Row("S1", "Revocation committed before a protected operation enters synchronization",
            "request field 1 → revoke commits → request field 2", base, proto,
            "denied", !allowedP && courseName(C).equals("TEST course")));
    }

    /** The operation enters first and is paused just before its write; a revocation arrives. */
    static void t2OperationEntersFirst() throws Exception {
        // deployed baseline
        reset();
        grant(res(Department.class), D, "EDIT");
        ConsEnv.Gate g = new ConsEnv.Gate("UPDATE courses SET name");
        a.gate = g;
        CompletableFuture<Boolean> w = async(baselineUpdate(a, a.service.newEvaluator(U), C, "after-check"));
        g.reached.get(10, TimeUnit.SECONDS);                          // checked, about to write
        CompletableFuture<Long> rv = async(b.db.sql("DELETE FROM permissions WHERE user_id = 2001").fetch().rowsUpdated());
        boolean revokeBlocked = stillRunningAfter300ms(rv);
        rv.get(10, TimeUnit.SECONDS);
        g.release.complete(null);
        boolean wrote = w.get(10, TimeUnit.SECONDS);
        a.gate = null;
        String base = "revocation " + (revokeBlocked ? "waited" : "committed while the writer was paused")
            + "; write " + (wrote ? "committed AFTER it, justified only by the revoked grant" : "denied");

        // prototype
        reset();
        grant(res(Department.class), D, "EDIT");
        AuthzProtocol p = new AuthzProtocol(a, false), pb = new AuthzProtocol(b, false);
        g = new ConsEnv.Gate("UPDATE courses SET name");
        a.gate = g;
        CompletableFuture<Boolean> wp = async(protocolUpdate(p, a, C, "after-check"));
        g.reached.get(10, TimeUnit.SECONDS);
        CompletableFuture<Long> rvp = async(pb.run(AuthzProtocol.Mode.EXCLUSIVE, 2002,
            e -> b.db.sql("DELETE FROM permissions WHERE user_id = 2001").fetch().rowsUpdated()));
        boolean blocked = stillRunningAfter300ms(rvp);
        g.release.complete(null);
        boolean wroteP = wp.get(10, TimeUnit.SECONDS);
        long writerDone = System.nanoTime();
        rvp.get(10, TimeUnit.SECONDS);
        long revokeDone = System.nanoTime();
        a.gate = null;
        boolean grantGone = one("SELECT count(*) FROM permissions WHERE user_id = 2001") == 0;
        String proto = "revocation " + (blocked ? "blocked on the domain lock until the write committed" : "NOT blocked")
            + "; write " + (wroteP ? "committed" : "denied") + "; revocation then committed=" + grantGone;
        rows.add(new Row("S2", "Authorized operation enters first; revocation starts while it is in progress",
            "writer checks and pauses before UPDATE → revoke issued (other instance) → writer released", base, proto,
            "serial order write→revoke; revocation waits", blocked && wroteP && grantGone && writerDone <= revokeDone));
    }

    /** Re-parenting of the course's department races with a write to the course. */
    static void t3ReparentingRace() throws Exception {
        String move = "UPDATE departments SET faculty_id = 2 WHERE id = 9001";
        reset();
        grant(res(org.lnu.timetable.domain.Faculty.class), 1, "EDIT");
        ConsEnv.Gate g = new ConsEnv.Gate("UPDATE courses SET name");
        a.gate = g;
        CompletableFuture<Boolean> w = async(baselineUpdate(a, a.service.newEvaluator(U), C, "moved?"));
        g.reached.get(10, TimeUnit.SECONDS);
        b.db.sql(move).fetch().rowsUpdated().block(T);               // re-parent commits mid-operation
        g.release.complete(null);
        boolean wrote = w.get(10, TimeUnit.SECONDS);
        a.gate = null;
        boolean nowAllowed = a.service.newEvaluator(U).allows(Course.class, C, AccessLevel.EDIT).block(T);
        String base = "write " + (wrote ? "committed" : "denied") + " after the department left faculty 1; a fresh check now says "
            + (nowAllowed ? "allowed" : "denied");

        reset();
        grant(res(org.lnu.timetable.domain.Faculty.class), 1, "EDIT");
        AuthzProtocol p = new AuthzProtocol(a, false), pb = new AuthzProtocol(b, false);
        g = new ConsEnv.Gate("UPDATE courses SET name");
        a.gate = g;
        CompletableFuture<Boolean> wp = async(protocolUpdate(p, a, C, "moved?"));
        g.reached.get(10, TimeUnit.SECONDS);
        CompletableFuture<Long> mv = async(pb.run(AuthzProtocol.Mode.EXCLUSIVE, 2002, e -> b.db.sql(move).fetch().rowsUpdated()));
        boolean blocked = stillRunningAfter300ms(mv);
        g.release.complete(null);
        boolean wroteP = wp.get(10, TimeUnit.SECONDS);
        mv.get(10, TimeUnit.SECONDS);
        a.gate = null;
        // and the other order: re-parent first, then the write must be denied
        reset();
        grant(res(org.lnu.timetable.domain.Faculty.class), 1, "EDIT");
        pb.run(AuthzProtocol.Mode.EXCLUSIVE, 2002, e -> b.db.sql(move).fetch().rowsUpdated()).block(T);
        boolean afterMove = protocolUpdate(p, a, C, "late").block(T);
        String proto = "re-parent " + (blocked ? "waited for the write" : "did NOT wait") + "; write " + (wroteP ? "committed first" : "denied")
            + "; in the opposite order the write is " + (afterMove ? "ALLOWED" : "denied");
        rows.add(new Row("S3", "Re-parenting an ancestor while another operation checks and writes a descendant",
            "writer checks C (via faculty 1) and pauses → department moved to faculty 2 → writer released; and the reverse order", base, proto,
            "each commit justified by one state", blocked && wroteP && !afterMove));
    }

    /** Grants are read before the swap, ancestry after it: a state that never existed. */
    static void t0MixedStateWithinOneEvaluation() throws Exception {
        String fac = res(org.lnu.timetable.domain.Faculty.class);
        // S0: grant on faculty 1, department under faculty 2.  S1: grant on faculty 2, department under faculty 1.
        // In neither committed state does the caller reach course C.
        reset();
        grant(fac, 1, "EDIT");
        sql("UPDATE departments SET faculty_id = 2 WHERE id = 9001");
        ConsEnv.Gate g = new ConsEnv.Gate("FROM courses WHERE");       // first traversal statement
        a.gate = g;
        CompletableFuture<Boolean> ev = async(a.service.newEvaluator(U).allows(Course.class, C, AccessLevel.EDIT));
        g.reached.get(10, TimeUnit.SECONDS);                          // grants already loaded
        b.inConnectionTx("UPDATE permissions SET resource_id = 2 WHERE user_id = 2001", "UPDATE departments SET faculty_id = 1 WHERE id = 9001");
        g.release.complete(null);
        boolean mixed = ev.get(10, TimeUnit.SECONDS);
        a.gate = null;
        String base = mixed ? "ALLOWED: grants from S0 combined with ancestry from S1" : "denied";

        // prototype: the evaluation runs inside a SHARED unit; the swap is an EXCLUSIVE unit
        reset();
        grant(fac, 1, "EDIT");
        sql("UPDATE departments SET faculty_id = 2 WHERE id = 9001");
        AuthzProtocol p = new AuthzProtocol(a, false), pb = new AuthzProtocol(b, false);
        g = new ConsEnv.Gate("FROM courses WHERE");
        a.gate = g;
        CompletableFuture<Boolean> evp = async(p.run(AuthzProtocol.Mode.SHARED, U, e -> e.allows(Course.class, C, AccessLevel.EDIT)));
        g.reached.get(10, TimeUnit.SECONDS);
        CompletableFuture<Integer> sw = async(pb.run(AuthzProtocol.Mode.EXCLUSIVE, 2002, e ->
            b.db.sql("UPDATE permissions SET resource_id = 2 WHERE user_id = 2001").fetch().rowsUpdated()
                .then(b.db.sql("UPDATE departments SET faculty_id = 1 WHERE id = 9001").fetch().rowsUpdated()).thenReturn(1)));
        boolean blocked = stillRunningAfter300ms(sw);
        g.release.complete(null);
        boolean lockAnswer = evp.get(10, TimeUnit.SECONDS);
        sw.get(10, TimeUnit.SECONDS);
        a.gate = null;

        // advisory alternative: one REPEATABLE READ snapshot for the whole evaluation, no lock
        reset();
        grant(fac, 1, "EDIT");
        sql("UPDATE departments SET faculty_id = 2 WHERE id = 9001");
        g = new ConsEnv.Gate("FROM courses WHERE");
        a.gate = g;
        CompletableFuture<Boolean> evs = async(AuthzProtocol.snapshot(a, a.service.newEvaluator(U).allows(Course.class, C, AccessLevel.EDIT)));
        g.reached.get(10, TimeUnit.SECONDS);
        b.inConnectionTx("UPDATE permissions SET resource_id = 2 WHERE user_id = 2001", "UPDATE departments SET faculty_id = 1 WHERE id = 9001");
        g.release.complete(null);
        boolean snapAnswer = evs.get(10, TimeUnit.SECONDS);
        a.gate = null;
        String proto = "lock: swap " + (blocked ? "waited" : "did NOT wait") + ", answer " + (lockAnswer ? "ALLOWED" : "denied (S0)")
            + "; RR snapshot (advisory, no lock): swap committed during it, answer " + (snapAnswer ? "ALLOWED" : "denied (S0)");
        rows.add(new Row("S0", "Grants and ancestry change together while one evaluation is between its statements",
            "evaluation loads grants and pauses → grant and department swapped in one transaction → evaluation resumes", base, proto,
            "an answer of S0 or S1 (here: denied)", blocked && !lockAnswer && !snapAnswer));
    }

    /** One mutation request: field 1 moves the department, field 2 deletes a course under it. */
    static void t4SameRequestAncestorChange() {
        String fac = res(org.lnu.timetable.domain.Faculty.class);
        reset();
        grant(fac, 1, "MANAGE");
        grant(fac, 2, "EDIT");
        // field 1: updateDepartment(9001, facultyId: 2) — authorized as the deployed provider does it
        PermissionEvaluator ev = a.service.newEvaluator(U);
        boolean pre = ev.allows(Department.class, D, AccessLevel.EDIT).block(T);
        java.util.Map<String, Object> input = java.util.Map.of("facultyId", 2L);
        boolean post = ev.levelAfterUpdate(Department.class, D, input, java.util.Map.of()).map(l -> l.allows(AccessLevel.EDIT)).defaultIfEmpty(false).block(T);
        boolean attach = ev.allowsIntroducedScopes(Department.class, D, input, java.util.Map.of(), AccessLevel.EDIT).block(T);
        sql("UPDATE departments SET faculty_id = 2 WHERE id = 9001");
        // field 2: deleteCourse(9101) needs FULL; after the move the caller holds only EDIT over it
        boolean staleDelete = ev.allows(Course.class, C, AccessLevel.FULL).block(T);
        String base = "field 1 authorized (pre=" + pre + ", post=" + post + ", attach=" + attach + "); field 2 delete "
            + (staleDelete ? "ALLOWED from the memoised MANAGE of the department" : "denied");

        reset();
        grant(fac, 1, "MANAGE");
        grant(fac, 2, "EDIT");
        RequestAuthzContext ctx = new RequestAuthzContext(a.service, U);
        ctx.evaluator().allows(Department.class, D, AccessLevel.EDIT).block(T);
        sql("UPDATE departments SET faculty_id = 2 WHERE id = 9001");
        ctx.authorizationStateChanged();                               // field 1 changed a permission-bearing FK
        AccessLevel fresh = ctx.evaluator().levelFor(Course.class, C).block(T);
        boolean freshDelete = AccessLevel.allows(fresh, AccessLevel.FULL);
        String proto = "evaluator discarded after field 1; field 2 sees level " + fresh + ", delete " + (freshDelete ? "ALLOWED" : "denied");
        rows.add(new Row("S4", "Same request: an earlier mutation changes ancestry, a later one checks a descendant",
            "mutation { updateDepartment(faculty 1→2); deleteCourse } with MANAGE on faculty 1, EDIT on faculty 2", base, proto,
            "delete denied (EDIT < FULL)", pre && post && attach && !freshDelete));
    }

    static void t5GroupMembership() {
        reset();
        sql("INSERT INTO permissions (grantee_type, group_id, resource_type, resource_id, level) VALUES ('GROUP', 901, '"
            + res(Department.class) + "', 9001, 'EDIT')");
        sql("INSERT INTO user_groups VALUES (2001, 901)");
        PermissionEvaluator ev = a.service.newEvaluator(U);
        boolean before = ev.allows(Course.class, C, AccessLevel.EDIT).block(T);
        sql("DELETE FROM user_groups WHERE user_id = 2001 AND group_id = 901");
        boolean stale = ev.allows(Course.class, C, AccessLevel.EDIT).block(T);
        RequestAuthzContext ctx = new RequestAuthzContext(a.service, U);
        sql("INSERT INTO user_groups VALUES (2001, 901)");
        ctx.evaluator().allows(Course.class, C, AccessLevel.EDIT).block(T);
        sql("DELETE FROM user_groups WHERE user_id = 2001 AND group_id = 901");
        ctx.authorizationStateChanged();
        boolean fresh = ctx.evaluator().allows(Course.class, C, AccessLevel.EDIT).block(T);
        rows.add(new Row("S5", "Caller removed from the group that carries the grant",
            "evaluate → membership deleted → evaluate again", "before=" + before + "; after, same evaluator: " + (stale ? "ALLOWED (cached group grants)" : "denied"),
            "after invalidation: " + (fresh ? "ALLOWED" : "denied (membership reloaded)"), "denied", before && !fresh));
    }

    static void t6DescendantViaJoinEdge() {
        reset();
        grant(res(org.lnu.timetable.domain.Lecturer.class), L, "EDIT");
        PermissionEvaluator ev = a.service.newEvaluator(U);
        boolean before = ev.allows(LecturerWorkload.class, W, AccessLevel.EDIT).block(T);
        sql("DELETE FROM lecturer_workload_lecturers WHERE lecturer_workload_id = 1 AND lecturer_id = 99");
        boolean stale = ev.allows(LecturerWorkload.class, W, AccessLevel.EDIT).block(T);
        sql("INSERT INTO lecturer_workload_lecturers VALUES (1, 99)");
        RequestAuthzContext ctx = new RequestAuthzContext(a.service, U);
        ctx.evaluator().allows(LecturerWorkload.class, W, AccessLevel.EDIT).block(T);
        sql("DELETE FROM lecturer_workload_lecturers WHERE lecturer_workload_id = 1 AND lecturer_id = 99");
        ctx.authorizationStateChanged();
        boolean fresh = ctx.evaluator().allows(LecturerWorkload.class, W, AccessLevel.EDIT).block(T);
        sql("INSERT INTO lecturer_workload_lecturers VALUES (1, 99)");
        rows.add(new Row("S6", "Cached descendant after its join-table edge to the granted ancestor is removed",
            "evaluate workload (via lecturer grant) → join row deleted → evaluate again",
            "before=" + before + "; after, same evaluator: " + (stale ? "ALLOWED (memoised)" : "denied"),
            "after invalidation: " + (fresh ? "ALLOWED" : "denied (recomputed)"), "denied", before && !fresh));
    }

    static void t7NegativeCache() {
        reset();
        PermissionEvaluator ev = a.service.newEvaluator(U);
        boolean before = ev.allows(Course.class, C, AccessLevel.EDIT).block(T);
        grant(res(Department.class), D, "EDIT");
        boolean stale = ev.allows(Course.class, C, AccessLevel.EDIT).block(T);
        reset();
        RequestAuthzContext ctx = new RequestAuthzContext(a.service, U);
        ctx.evaluator().allows(Course.class, C, AccessLevel.EDIT).block(T);
        grant(res(Department.class), D, "EDIT");
        ctx.authorizationStateChanged();
        boolean fresh = ctx.evaluator().allows(Course.class, C, AccessLevel.EDIT).block(T);
        rows.add(new Row("S7", "Cached denial, then a valid grant is added",
            "evaluate (denied) → grant inserted → evaluate again",
            "before=" + before + "; after, same evaluator: " + (stale ? "allowed" : "DENIED (cached denial)"),
            "after invalidation: " + (fresh ? "allowed" : "DENIED"), "allowed", !before && fresh));
    }

    static void t8RollbackAndRetry() {
        // deployed baseline: no transaction, so a failure after the write leaves the write in place
        reset();
        grant(res(Department.class), D, "EDIT");
        try {
            baselineUpdate(a, a.service.newEvaluator(U), C, "partial").then(Mono.error(new IllegalStateException("injected"))).block(T);
        } catch (Exception expected) { }
        String base = "failure after the write: write " + ("partial".equals(courseName(C)) ? "REMAINS committed" : "absent");

        reset();
        grant(res(Department.class), D, "EDIT");
        long epoch0 = one("SELECT epoch FROM authz_domain WHERE id = 1");
        AuthzProtocol p = new AuthzProtocol(a, false), pb = new AuthzProtocol(b, false);
        int[] attempt = {0};
        @SuppressWarnings("unchecked") CompletableFuture<Long>[] revocation = new CompletableFuture[1];
        Mono<Boolean> unit = Mono.defer(() -> p.run(AuthzProtocol.Mode.SHARED, U, ev -> ev.allows(Course.class, C, AccessLevel.EDIT)
            .flatMap(ok -> !ok ? Mono.just(false)
                : a.db.sql("UPDATE courses SET name = 'attempt' WHERE id = 9101").fetch().rowsUpdated().then(Mono.defer(() -> {
                    if (attempt[0]++ == 0) {
                        // the revocation is queued while attempt 1 still holds the share lock...
                        revocation[0] = async(pb.run(AuthzProtocol.Mode.EXCLUSIVE, 2002, e -> b.db.sql("DELETE FROM permissions WHERE user_id = 2001").fetch().rowsUpdated()));
                        return Mono.error(new IllegalStateException("injected failure after the write"));
                    }
                    return Mono.just(true);
                })))));
        // retry once, after the rollback has let the queued revocation commit
        Boolean result = unit.retryWhen(Retry.from(signals -> signals.concatMap(sig -> sig.totalRetries() < 1 ? Mono.fromFuture(revocation[0]) : Mono.error(sig.failure())))).block(T);
        long epoch1 = one("SELECT epoch FROM authz_domain WHERE id = 1");
        String name = courseName(C);
        String proto = "attempt 1 rolled back (course name '" + name + "'); retry built evaluator #" + p.evaluatorsCreated.get()
            + " after the revocation and was " + (Boolean.TRUE.equals(result) ? "ALLOWED" : "denied") + "; epoch advanced only by the revocation (" + epoch0 + "→" + epoch1 + ")";
        rows.add(new Row("S8", "Failure after evaluation and write; retry after a revocation",
            "attempt 1 checks+writes, revocation queued, attempt 1 fails → rollback → retry", base, proto,
            "no partial write; retry re-authorizes and is denied",
            "TEST course".equals(name) && p.evaluatorsCreated.get() == 2 && !Boolean.TRUE.equals(result) && epoch1 == epoch0 + 1));
    }

    static void t9InFlightOldGeneration() throws Exception {
        reset();
        grant(res(org.lnu.timetable.domain.Faculty.class), 1, "EDIT");
        RequestAuthzContext ctx = new RequestAuthzContext(a.service, U);
        PermissionEvaluator old = ctx.evaluator();
        ConsEnv.Gate g = new ConsEnv.Gate("FROM courses WHERE");
        a.gate = g;
        CompletableFuture<Boolean> inFlight = async(old.allows(Course.class, C, AccessLevel.EDIT));
        g.reached.get(10, TimeUnit.SECONDS);
        a.gate = null;
        sql("UPDATE departments SET faculty_id = 2 WHERE id = 9001");
        ctx.authorizationStateChanged();
        boolean fresh1 = ctx.evaluator().allows(Course.class, C, AccessLevel.EDIT).block(T);
        g.release.complete(null);
        boolean oldAnswer = inFlight.get(10, TimeUnit.SECONDS);
        boolean fresh2 = ctx.evaluator().allows(Course.class, C, AccessLevel.EDIT).block(T);
        boolean distinct = ctx.evaluator() != old;
        rows.add(new Row("S9", "Old asynchronous computation completes after invalidation",
            "old evaluation paused → ancestry change, generation advanced → new evaluation → old one released",
            "(the deployed evaluator is never replaced within a request)",
            "old computation returned " + oldAnswer + " into the discarded evaluator; current generation answers " + fresh1 + " before and " + fresh2 + " after it",
            "current generation unaffected (denied)", !fresh1 && !fresh2 && distinct));
    }

    /**
     * Randomized race between two instances: instance A performs protected writes, instance B
     * toggles the caller's only grant. Every toggle increments authz_domain.epoch in its own
     * transaction and logs (epoch after, granted after). Every write logs the epoch read when its
     * authorization started and, inside the INSERT itself, the epoch current when it was written.
     * A write is unjustified if the grant was absent in the state it was written in.
     */
    static void t10RandomizedTwoInstances(int ops) throws Exception {
        sql("CREATE TABLE IF NOT EXISTS authz_audit (seq bigserial, kind text, epoch_check bigint, epoch_write bigint, granted boolean)");
        String base = race(ops, false), proto = race(ops, true);
        rows.add(new Row("S10", "Two application instances: protected writes vs grant revoke/restore, concurrently",
            ops + " write attempts on instance A while instance B toggles the grant, no gates", base, proto,
            "0 unjustified writes", proto.startsWith("0 ")));
    }

    static String race(int ops, boolean protocol) throws Exception {
        reset();
        sql("TRUNCATE authz_audit");
        grant(res(Department.class), D, "EDIT");
        long e0 = one("SELECT epoch FROM authz_domain WHERE id = 1");
        AuthzProtocol p = new AuthzProtocol(a, false), pb = new AuthzProtocol(b, false);
        String logWrite = "INSERT INTO authz_audit (kind, epoch_check, epoch_write) VALUES ('write', :e, (SELECT epoch FROM authz_domain WHERE id = 1))";
        String readEpoch = "SELECT epoch FROM authz_domain WHERE id = 1";
        CompletableFuture<Void> writers = CompletableFuture.runAsync(() -> {
            for (int i = 0; i < ops; i++) {
                if (protocol) {
                    p.run(AuthzProtocol.Mode.SHARED, U, ev -> a.db.sql(readEpoch).map(r -> ((Number) r.get(0)).longValue()).one()
                        .flatMap(e -> ev.allows(Course.class, C, AccessLevel.EDIT)
                            .flatMap(ok -> !ok ? Mono.just(0L) : a.db.sql(logWrite).bind("e", e).fetch().rowsUpdated()))).block(T);
                } else {
                    PermissionEvaluator ev = a.service.newEvaluator(U);
                    a.db.sql(readEpoch).map(r -> ((Number) r.get(0)).longValue()).one()
                        .flatMap(e -> ev.allows(Course.class, C, AccessLevel.EDIT)
                            .flatMap(ok -> !ok ? Mono.just(0L) : a.db.sql(logWrite).bind("e", e).fetch().rowsUpdated())).block(T);
                }
            }
        });
        java.util.Random rnd = new java.util.Random(42);
        boolean granted = true;
        while (!writers.isDone()) {
            String change = granted
                ? "DELETE FROM permissions WHERE user_id = 2001"
                : "INSERT INTO permissions (grantee_type, user_id, resource_type, resource_id, level) VALUES ('USER', 2001, '" + res(Department.class) + "', 9001, 'EDIT')";
            boolean after = !granted;
            if (protocol) {
                // the unit bumps the epoch after its body, in the same transaction
                String log = "INSERT INTO authz_audit (kind, epoch_write, granted) SELECT 'toggle', epoch + 1, " + after + " FROM authz_domain WHERE id = 1";
                pb.run(AuthzProtocol.Mode.EXCLUSIVE, 2002, e -> b.db.sql(change).fetch().rowsUpdated().then(b.db.sql(log).fetch().rowsUpdated())).block(T);
            } else {
                b.inConnectionTx(change, "UPDATE authz_domain SET epoch = epoch + 1 WHERE id = 1",
                    "INSERT INTO authz_audit (kind, epoch_write, granted) SELECT 'toggle', epoch, " + after + " FROM authz_domain WHERE id = 1");
            }
            granted = after;
            Thread.sleep(1 + rnd.nextInt(10));
        }
        writers.get();
        java.util.Map<Long, Boolean> stateAt = new java.util.HashMap<>();
        stateAt.put(e0, true);
        a.db.sql("SELECT epoch_write, granted FROM authz_audit WHERE kind = 'toggle'").map(r -> new Object[]{r.get(0), r.get(1)}).all()
            .doOnNext(r -> stateAt.put(((Number) r[0]).longValue(), (Boolean) r[1])).blockLast(T);
        List<long[]> writes = a.db.sql("SELECT epoch_check, epoch_write FROM authz_audit WHERE kind = 'write'")
            .map(r -> new long[]{((Number) r.get(0)).longValue(), ((Number) r.get(1)).longValue()}).all().collectList().block(T);
        int unjustified = 0, stale = 0;
        for (long[] w : writes) {
            if (w[0] != w[1]) stale++;
            if (!Boolean.TRUE.equals(stateAt.get(w[1]))) unjustified++;
        }
        return unjustified + " unjustified of " + writes.size() + " committed writes; " + stale
            + " written in a different authorization state than the one their check began in; " + (stateAt.size() - 1) + " grant toggles";
    }
}
