package org.lnu.timetable.cons;

import org.lnu.timetable.domain.Course;
import org.lnu.timetable.security.AccessLevel;
import org.lnu.timetable.security.PermissionEvaluator;
import reactor.core.publisher.Mono;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * What the prototype protocol costs one protected update (check + write of one course row):
 *   deployed  - fresh evaluator, check, UPDATE, autocommit (the released behaviour);
 *   shared    - one transaction, FOR SHARE on the domain row, fresh evaluator, check, UPDATE;
 *   exclusive - the same with FOR UPDATE for every unit (all protected writes serialized).
 * Part 1: one client, interleaved triplets. Part 2: k concurrent clients for a fixed time, with
 * and without a concurrent authorization change every 50 ms.
 *
 * usage: ProtocolCost <db> <runId> <out-prefix>
 */
public final class ProtocolCost {

    static final long U = 2001;
    static final Duration T = Duration.ofSeconds(30);

    public static void main(String[] args) throws Exception {
        String dbName = args[0];
        int run = Integer.parseInt(args[1]);
        String prefix = args[2];
        try (ConsEnv env = new ConsEnv(dbName, 64); ConsEnv admin = new ConsEnv(dbName, 4)) {
            setup(env);
            AuthzProtocol shared = new AuthzProtocol(env, false), excl = new AuthzProtocol(env, true);
            AuthzProtocol adminP = new AuthzProtocol(admin, false);

            // ---- part 1: single client
            Random rnd = new Random(7919L * run);
            List<String> variants = List.of("deployed", "shared", "exclusive");
            try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(Path.of(prefix + "-single-run" + run + ".csv")))) {
                w.println("run,phase,rep,variant,ns");
                for (int rep = 1; rep <= 400; rep++) {
                    String phase = rep <= 100 ? "warmup" : "measure";
                    List<String> order = new ArrayList<>(variants);
                    Collections.shuffle(order, rnd);
                    for (String v : order) {
                        long t0 = System.nanoTime();
                        Boolean ok = op(env, v, shared, excl, 9201, "n" + rep).block(T);
                        long ns = System.nanoTime() - t0;
                        if (!Boolean.TRUE.equals(ok)) throw new IllegalStateException("denied?");
                        w.printf("%d,%s,%d,%s,%d%n", run, phase, rep <= 100 ? rep : rep - 100, v, ns);
                    }
                }
            }

            // ---- part 2: k concurrent clients, each on its own course row, 3 s per cell
            try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(Path.of(prefix + "-concurrent-run" + run + ".csv")))) {
                w.println("run,variant,clients,authz_changes,ops,seconds,ops_per_s,p50_ms,p95_ms");
                for (boolean changes : new boolean[]{false, true}) {
                    for (int k : new int[]{1, 4, 16}) {
                        List<String> order = new ArrayList<>(variants);
                        Collections.shuffle(order, rnd);
                        for (String v : order) {
                            cell(env, admin, adminP, v, shared, excl, k, changes, run, w, Duration.ofMillis(300)); // warm-up
                            cell(env, admin, adminP, v, shared, excl, k, changes, run, w, Duration.ofSeconds(3));
                        }
                    }
                }
            }
        }
    }

    static void setup(ConsEnv env) {
        String[] s = {
            "CREATE TABLE IF NOT EXISTS authz_domain (id int PRIMARY KEY, epoch bigint NOT NULL)",
            "INSERT INTO authz_domain VALUES (1, 0) ON CONFLICT DO NOTHING",
            "DELETE FROM permissions WHERE user_id = 2001",
            "DELETE FROM user_groups WHERE user_id = 2001",
            "UPDATE departments SET faculty_id = 1 WHERE id = 9001",
            "INSERT INTO permissions (grantee_type, user_id, resource_type, resource_id, level) VALUES ('USER', 2001, 'FACULTY', 1, 'EDIT')",
            "INSERT INTO courses (id, name, department_id) SELECT 9200 + g, 'TEST bench ' || g, 9001 FROM generate_series(1, 16) g ON CONFLICT DO NOTHING",
            // a second grant that the background change toggles; the caller's access never depends on it
            "INSERT INTO groups (id, name) VALUES (902, 'TEST toggled group') ON CONFLICT DO NOTHING"
        };
        for (String q : s) env.db.sql(q).fetch().rowsUpdated().block(T);
    }

    static Mono<Boolean> op(ConsEnv env, String variant, AuthzProtocol shared, AuthzProtocol excl, long course, String name) {
        java.util.function.Function<PermissionEvaluator, Mono<Boolean>> body = ev -> ev.allows(Course.class, course, AccessLevel.EDIT)
            .flatMap(ok -> !ok ? Mono.just(false)
                : env.db.sql("UPDATE courses SET name = :n WHERE id = :id").bind("n", name).bind("id", course).fetch().rowsUpdated().thenReturn(true));
        return switch (variant) {
            case "deployed" -> Mono.defer(() -> body.apply(env.service.newEvaluator(U)));
            case "shared" -> shared.run(AuthzProtocol.Mode.SHARED, U, body);
            default -> excl.run(AuthzProtocol.Mode.SHARED, U, body);   // allExclusive turns this into FOR UPDATE
        };
    }

    static void cell(ConsEnv env, ConsEnv admin, AuthzProtocol adminP, String v, AuthzProtocol shared, AuthzProtocol excl,
                     int k, boolean changes, int run, PrintWriter w, Duration length) throws Exception {
        AtomicBoolean stop = new AtomicBoolean();
        List<List<Long>> lat = new ArrayList<>();
        CountDownLatch done = new CountDownLatch(k);
        for (int c = 0; c < k; c++) {
            List<Long> mine = new ArrayList<>();
            lat.add(mine);
            long course = 9201 + c;
            Thread.ofPlatform().start(() -> {
                int i = 0;
                while (!stop.get()) {
                    long t0 = System.nanoTime();
                    op(env, v, shared, excl, course, "c" + (i++)).block(T);
                    mine.add(System.nanoTime() - t0);
                }
                done.countDown();
            });
        }
        Thread changer = null;
        if (changes) {
            changer = Thread.ofPlatform().start(() -> {
                boolean on = false;
                while (!stop.get()) {
                    String q = on ? "DELETE FROM permissions WHERE group_id = 902"
                        : "INSERT INTO permissions (grantee_type, group_id, resource_type, resource_id, level) VALUES ('GROUP', 902, 'FACULTY', 2, 'EDIT')";
                    if (v.equals("deployed")) admin.db.sql(q).fetch().rowsUpdated().block(T);
                    else adminP.run(AuthzProtocol.Mode.EXCLUSIVE, 1, e -> admin.db.sql(q).fetch().rowsUpdated()).block(T);
                    on = !on;
                    try { Thread.sleep(50); } catch (InterruptedException e) { return; }
                }
            });
        }
        long t0 = System.nanoTime();
        Thread.sleep(length.toMillis());
        stop.set(true);
        done.await();
        double secs = (System.nanoTime() - t0) / 1e9;
        if (changer != null) changer.join();
        admin.db.sql("DELETE FROM permissions WHERE group_id = 902").fetch().rowsUpdated().block(T);
        if (length.toMillis() < 1000) return;
        List<Long> all = new ArrayList<>();
        lat.forEach(all::addAll);
        Collections.sort(all);
        double p50 = all.get(all.size() / 2) / 1e6, p95 = all.get((int) Math.floor(all.size() * 0.95)) / 1e6;
        w.printf("%d,%s,%d,%s,%d,%.3f,%.1f,%.2f,%.2f%n", run, v, k, changes, all.size(), secs, all.size() / secs, p50, p95);
        w.flush();
    }
}
