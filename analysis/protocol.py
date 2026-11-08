"""Cost of the domain-lock protocol (Section 8.6): results/protocol/*.csv -> results/summary/protocol-summary.json.

Single client: median latency per variant over all measured units of all runs (and per run).
Concurrent clients: mean and range over runs of the updates per second, median of the per-cell p50.
"""
import csv, glob, json, os, statistics
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
P = os.path.join(ROOT, 'results', 'protocol'); S = os.path.join(ROOT, 'results', 'summary')
os.makedirs(S, exist_ok=True)
single, per_run, conc = {}, {}, {}
for f in sorted(glob.glob(os.path.join(P, 'protocol-single-run*.csv'))):
    for r in csv.DictReader(open(f)):
        if r['phase'] != 'measure': continue
        single.setdefault(r['variant'], []).append(int(r['ns']) / 1e6)
        per_run.setdefault((r['variant'], r['run']), []).append(int(r['ns']) / 1e6)
for f in sorted(glob.glob(os.path.join(P, 'protocol-concurrent-run*.csv'))):
    for r in csv.DictReader(open(f)):
        conc.setdefault((r['variant'], int(r['clients']), r['authz_changes']), []).append(
            (float(r['ops_per_s']), float(r['p50_ms']), float(r['p95_ms'])))
out = {'single_median_ms': {v: round(statistics.median(x), 2) for v, x in single.items()},
       'single_run_medians': {f'{v}/{run}': round(statistics.median(x), 2) for (v, run), x in sorted(per_run.items())},
       'concurrent': {f'{v}/{k}/{c}': {'runs': len(xs),
                                       'ops_per_s_mean': round(statistics.mean(a for a, _, _ in xs), 0),
                                       'ops_per_s_range': [min(a for a, _, _ in xs), max(a for a, _, _ in xs)],
                                       'p50_ms_median': round(statistics.median(b for _, b, _ in xs), 2)}
                      for (v, k, c), xs in sorted(conc.items())}}
json.dump(out, open(os.path.join(S, 'protocol-summary.json'), 'w'), indent=1, ensure_ascii=False)
print(json.dumps(out, indent=1, ensure_ascii=False))
