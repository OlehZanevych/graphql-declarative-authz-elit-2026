package org.lnu.timetable.itc;

import org.lnu.timetable.bench.BenchEnv;
import org.lnu.timetable.bench.PerRowEvaluator;
import org.lnu.timetable.security.AccessLevel;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The same latency protocol as LatencyBench, over ITC 2019 instances loaded into separate
 * PostgreSQL databases (itc_<instance>), each with the class hierarchy declared by the ItcClass,
 * ItcSubpart, ItcConfig, ItcCourse and ItcRoom annotations. The question asked is the class-list
 * question: the caller's level on the first k classes by id, and on all classes.
 *
 * usage: ItcBench <runId> <pairs> <warmupRounds> <out.csv> <instance>...
 */
public final class ItcBench {

    record Config(String instance, BenchEnv env, List<Long> ids) {
        String key() { return instance + "/" + ids.size(); }
    }

    public static void main(String[] args) throws Exception {
        int runId = Integer.parseInt(args[0]);
        int pairs = Integer.parseInt(args[1]);
        int warmup = Integer.parseInt(args[2]);
        Path out = Path.of(args[3]);
        List<String> instances = List.of(args).subList(4, args.length);
        long caller = 1001L;

        Map<String, BenchEnv> envs = new LinkedHashMap<>();
        Map<String, PerRowEvaluator> perRows = new LinkedHashMap<>();
        List<Config> configs = new ArrayList<>();
        try {
            for (String inst : instances) {
                BenchEnv env = new BenchEnv("itc_" + inst.replace('-', '_'), 4, 512);
                envs.put(inst, env);
                perRows.put(inst, new PerRowEvaluator(env.registry, env.graphRepo, env.permissionRepo));
                List<Long> all = env.ids(ItcClass.class, 0);
                for (int n : new int[]{1, 10, 50, 200}) if (n < all.size()) configs.add(new Config(inst, env, List.copyOf(all.subList(0, n))));
                configs.add(new Config(inst, env, all));
            }
            Random rnd = new Random(2_000_003L * runId + 29);
            try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out))) {
                w.println("run,phase,rep,workload,rows,strategy,position,ns,statements,answered,mismatch");
                for (Config c : configs) {
                    Sample a = run(c, perRows.get(c.instance), "set", caller);
                    Sample b = run(c, perRows.get(c.instance), "perrow", caller);
                    boolean same = a.result.equals(b.result);
                    System.out.printf("check %-24s set=%5d perrow=%7d answered=%d/%d equal=%s%n", c.key(), a.statements,
                        b.statements, a.result.size(), c.ids.size(), same);
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
                        Sample s1 = run(c, perRows.get(c.instance), first, caller);
                        Sample s2 = run(c, perRows.get(c.instance), second, caller);
                        boolean mismatch = !s1.result.equals(s2.result);
                        int rep = round <= warmup ? round : round - warmup;
                        write(w, runId, phase, rep, c, first, 0, s1, mismatch);
                        write(w, runId, phase, rep, c, second, 1, s2, mismatch);
                    }
                    w.flush();
                    System.out.printf("run %d %s round %d done%n", runId, phase, round);
                }
            }
        } finally {
            envs.values().forEach(BenchEnv::close);
        }
    }

    record Sample(long ns, long statements, Map<Long, AccessLevel> result) {}

    static Sample run(Config c, PerRowEvaluator perRow, String strategy, long caller) {
        c.env.statements.set(0);
        long t0 = System.nanoTime();
        Map<Long, AccessLevel> r = strategy.equals("set")
            ? c.env.service.newEvaluator(caller).levelsFor(ItcClass.class, c.ids).block()
            : perRow.levelsFor(caller, ItcClass.class, c.ids).block();
        long ns = System.nanoTime() - t0;
        return new Sample(ns, c.env.statements.get(), r);
    }

    static void write(PrintWriter w, int run, String phase, int rep, Config c, String strategy, int pos, Sample s, boolean mismatch) {
        w.printf("%d,%s,%d,%s,%d,%s,%d,%d,%d,%d,%d%n", run, phase, rep, c.instance, c.ids.size(), strategy, pos, s.ns,
            s.statements, s.result.size(), mismatch ? 1 : 0);
    }
}
