package org.lnu.timetable.bench;

import org.lnu.timetable.domain.Course;
import org.lnu.timetable.domain.LecturerWorkload;
import org.lnu.timetable.domain.LecturerWorkloadCandidate;
import org.lnu.timetable.domain.TimetableEntry;
import org.lnu.timetable.security.AccessLevel;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Authorization-evaluation latency, per-row vs set-at-a-time, over the deployed LNU snapshot in a
 * local PostgreSQL. One process = one independent run. Timing boundary: from building the
 * evaluation (fresh request-scoped evaluator, groups and grants not yet loaded) to the complete
 * Map<Long, AccessLevel> having been delivered (block()), so database I/O, reactive scheduling and
 * the fixpoint are all inside it.
 *
 * usage: LatencyBench <runId> <pairs> <warmupRounds> <out.csv> [callerUserId] [configs...]
 */
public final class LatencyBench {

    record Config(String workload, Class<?> cls, List<Long> ids) {
        String key() { return workload + "/" + ids.size(); }
    }

    public static void main(String[] args) throws Exception {
        int runId = Integer.parseInt(args[0]);
        int pairs = Integer.parseInt(args[1]);
        int warmup = Integer.parseInt(args[2]);
        Path out = Path.of(args[3]);
        long caller = args.length > 4 ? Long.parseLong(args[4]) : 1001L;
        List<String> only = args.length > 5 ? List.of(args).subList(5, args.length) : List.of();

        try (BenchEnv env = new BenchEnv(System.getProperty("db", "lnu_timetable"))) {
            PerRowEvaluator perRow = new PerRowEvaluator(env.registry, env.graphRepo, env.permissionRepo);

            // The article Table 2 row sets: first 200 courses and first 500 workloads by id, all 1,381
            // timetable entries, all 6 candidates; the smaller sizes are prefixes of the same lists.
            List<Config> configs = new ArrayList<>();
            addSizes(configs, "Course", Course.class, env.ids(Course.class, 200), new int[]{1, 10, 50, 200});
            addSizes(configs, "TimetableEntry", TimetableEntry.class, env.ids(TimetableEntry.class, 0), new int[]{1, 10, 50, 200, 1381});
            addSizes(configs, "LecturerWorkload", LecturerWorkload.class, env.ids(LecturerWorkload.class, 500), new int[]{1, 10, 50, 200, 500});
            addSizes(configs, "LecturerWorkloadCandidate", LecturerWorkloadCandidate.class, env.ids(LecturerWorkloadCandidate.class, 0), new int[]{1, 6});
            if (!only.isEmpty()) configs.removeIf(c -> !only.contains(c.key()));

            Random rnd = new Random(1_000_003L * runId + 17);
            try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out))) {
                w.println("run,phase,rep,workload,rows,strategy,position,ns,statements,answered,mismatch");

                // Equivalence and statement counts first, once per config: both strategies must
                // return the same map, or the timing below would compare different questions.
                for (Config c : configs) {
                    Sample a = run(env, perRow, c, "set", caller);
                    Sample b = run(env, perRow, c, "perrow", caller);
                    boolean same = a.result.equals(b.result);
                    System.out.printf("check %-30s set=%6d stmts  perrow=%7d stmts  answered=%d/%d  equal=%s%n",
                        c.key(), a.statements, b.statements, a.result.size(), c.ids().size(), same);
                    write(w, runId, "check", 0, c, "set", 0, a, !same);
                    write(w, runId, "check", 0, c, "perrow", 1, b, !same);
                    if (!same) throw new IllegalStateException("strategies disagree on " + c.key());
                }
                w.flush();

                for (int round = 1; round <= warmup + pairs; round++) {
                    String phase = round <= warmup ? "warmup" : "measure";
                    List<Config> order = new ArrayList<>(configs);
                    Collections.shuffle(order, rnd);
                    for (Config c : order) {
                        boolean setFirst = rnd.nextBoolean();
                        String first = setFirst ? "set" : "perrow", second = setFirst ? "perrow" : "set";
                        Sample s1 = run(env, perRow, c, first, caller);
                        Sample s2 = run(env, perRow, c, second, caller);
                        boolean mismatch = !s1.result.equals(s2.result);
                        int rep = round <= warmup ? round : round - warmup;
                        write(w, runId, phase, rep, c, first, 0, s1, mismatch);
                        write(w, runId, phase, rep, c, second, 1, s2, mismatch);
                    }
                    w.flush();
                    System.out.printf("run %d %s round %d done%n", runId, phase, round);
                }
            }
        }
    }

    record Sample(long ns, long statements, Map<Long, AccessLevel> result) {}

    static Sample run(BenchEnv env, PerRowEvaluator perRow, Config c, String strategy, long caller) {
        env.statements.set(0);
        long t0 = System.nanoTime();
        Map<Long, AccessLevel> r = strategy.equals("set")
            ? env.service.newEvaluator(caller).levelsFor(c.cls(), c.ids()).block()
            : perRow.levelsFor(caller, c.cls(), c.ids()).block();
        long ns = System.nanoTime() - t0;
        return new Sample(ns, env.statements.get(), r);
    }

    static void write(PrintWriter w, int run, String phase, int rep, Config c, String strategy, int pos,
                      Sample s, boolean mismatch) {
        w.printf("%d,%s,%d,%s,%d,%s,%d,%d,%d,%d,%d%n", run, phase, rep, c.workload(), c.ids().size(), strategy,
            pos, s.ns, s.statements, s.result.size(), mismatch ? 1 : 0);
    }

    static void addSizes(List<Config> out, String name, Class<?> cls, List<Long> all, int[] sizes) {
        for (int n : sizes) if (n <= all.size()) out.add(new Config(name, cls, List.copyOf(all.subList(0, n))));
    }
}
