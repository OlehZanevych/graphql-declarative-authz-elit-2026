"""Summarise the paired latency measurements.

For each (condition, workload, rows):
  * statements per strategy (deterministic; checked to be constant across runs);
  * median latency per strategy over all measured pairs of all runs, with the range of the
    per-run medians;
  * speedup: within each run, the median over pairs of perrow_ns / set_ns; across runs, the
    geometric mean of those per-run values with a 95 % t-interval on the log scale (runs are the
    independent units, n = number of runs);
  * the number of pairs in which per-row was faster (unfavourable cases are reported, not hidden).

usage: python3 analyse.py <out.json> <condition>=<glob> ...
"""
import csv, glob, json, math, os, statistics, sys
from collections import defaultdict
from scipy import stats as st

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
out = sys.argv[1]
result = {}
for arg in sys.argv[2:]:
    cond, pattern = arg.split('=', 1)
    files = sorted(glob.glob(pattern))
    pairs = defaultdict(lambda: defaultdict(dict))   # (wl, rows) -> (run, rep) -> strategy -> ns
    stmts = defaultdict(lambda: defaultdict(set))
    mismatches = 0
    for f in files:
        for r in csv.DictReader(open(f)):
            key = (r['workload'], int(r['rows']))
            stmts[key][r['strategy']].add(int(r['statements']))
            if r['phase'] != 'measure':
                continue
            mismatches += int(r['mismatch'])
            pairs[key][(int(r['run']), int(r['rep']))][r['strategy']] = int(r['ns'])
    rows = []
    for key in sorted(pairs, key=lambda k: (k[0], k[1])):
        p = pairs[key]
        runs = sorted({run for run, _ in p})
        per_run_ratio, per_run_med = [], {'set': [], 'perrow': []}
        all_ns = {'set': [], 'perrow': []}
        slower = 0
        for run in runs:
            ratios = []
            ns = {'set': [], 'perrow': []}
            for (rr, rep), v in p.items():
                if rr != run or len(v) != 2:
                    continue
                ratios.append(v['perrow'] / v['set'])
                for s in ns: ns[s].append(v[s])
                if v['perrow'] < v['set']: slower += 1
            per_run_ratio.append(statistics.median(ratios))
            for s in ns:
                per_run_med[s].append(statistics.median(ns[s]))
                all_ns[s] += ns[s]
        logs = [math.log(x) for x in per_run_ratio]
        n = len(logs)
        gm = math.exp(statistics.mean(logs))
        if n > 1:
            half = st.t.ppf(0.975, n - 1) * statistics.stdev(logs) / math.sqrt(n)
            lo, hi = math.exp(statistics.mean(logs) - half), math.exp(statistics.mean(logs) + half)
        else:
            lo = hi = float('nan')
        def q(v, x):
            v = sorted(v); return v[min(len(v) - 1, int(math.floor(x * len(v))))]
        rows.append(dict(
            workload=key[0], rows=key[1], runs=n, pairs=len(all_ns['set']),
            stmts_set=sorted(stmts[key]['set']), stmts_perrow=sorted(stmts[key]['perrow']),
            stmt_ratio=round(min(stmts[key]['perrow']) / min(stmts[key]['set']), 1),
            med_set_ms=round(statistics.median(all_ns['set']) / 1e6, 2),
            med_perrow_ms=round(statistics.median(all_ns['perrow']) / 1e6, 2),
            p95_set_ms=round(q(all_ns['set'], 0.95) / 1e6, 2), p95_perrow_ms=round(q(all_ns['perrow'], 0.95) / 1e6, 2),
            run_median_range_set_ms=[round(min(per_run_med['set']) / 1e6, 2), round(max(per_run_med['set']) / 1e6, 2)],
            run_median_range_perrow_ms=[round(min(per_run_med['perrow']) / 1e6, 2), round(max(per_run_med['perrow']) / 1e6, 2)],
            speedup_gm=round(gm, 2), speedup_ci95=[round(lo, 2), round(hi, 2)],
            per_run_speedup=[round(x, 2) for x in per_run_ratio],
            pairs_perrow_faster=slower))
    result[cond] = dict(files=[os.path.relpath(f, ROOT) for f in files], mismatching_pairs=mismatches, rows=rows)
    print(f'== {cond}: {len(files)} runs, mismatching pairs {mismatches}')
    print(f'{"workload":28s}{"rows":>6s}{"stmts":>14s}{"set ms":>9s}{"perrow ms":>11s}{"speedup [95% CI]":>24s}{"pr<set":>7s}')
    for r in rows:
        print(f'{r["workload"]:28s}{r["rows"]:6d}{str(r["stmts_set"][0])+"/"+str(r["stmts_perrow"][0]):>14s}'
              f'{r["med_set_ms"]:9.2f}{r["med_perrow_ms"]:11.2f}'
              f'{r["speedup_gm"]:9.2f} [{r["speedup_ci95"][0]:.2f}, {r["speedup_ci95"][1]:.2f}]{r["pairs_perrow_faster"]:7d}')
json.dump(result, open(out, 'w'), indent=1)
