"""Consistency schedules (Section 8.6): results/consistency/consistency.tsv -> results/summary/consistency-summary.json.

Counts the passing schedules and extracts the S10 counts (unjustified / committed writes) for both
implementations from the outcome texts the test wrote.
"""
import csv, json, os, re
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
rows = list(csv.DictReader(open(os.path.join(ROOT, 'results', 'consistency', 'consistency.tsv')), delimiter='\t'))
def s10(text):
    m = re.search(r'(\d+) unjustified of (\d+) committed writes; (\d+) written in a different authorization state.*?; (\d+) grant toggles', text)
    return dict(unjustified=int(m[1]), committed=int(m[2]), state_changed=int(m[3]), toggles=int(m[4]))
r10 = next(r for r in rows if r['id'] == 'S10')
out = dict(schedules=len(rows), passed=sum(r['pass'] == 'PASS' for r in rows),
           results={r['id']: r['pass'] for r in sorted(rows, key=lambda r: int(r['id'][1:]))},
           s10=dict(released=s10(r10['deployed_baseline']), prototype=s10(r10['prototype'])))
os.makedirs(os.path.join(ROOT, 'results', 'summary'), exist_ok=True)
json.dump(out, open(os.path.join(ROOT, 'results', 'summary', 'consistency-summary.json'), 'w'), indent=1)
print(json.dumps(out, indent=1))
