"""Markdown summary tables of every experiment: results/summary/tables.md, also inserted into README.md
between the markers <!-- results:begin --> and <!-- results:end -->."""
import csv, json, math, os, re
from scipy import stats as st
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
R = os.path.join(ROOT, 'results'); S = os.path.join(R, 'summary')
J = lambda *p: json.load(open(os.path.join(*p)))
lat, itc = J(S, 'latency-summary.json'), J(S, 'itc2019-summary.json')
prot, cons, env = J(S, 'protocol-summary.json'), J(S, 'consistency-summary.json'), J(S, 'environment-summary.json')
stats = J(ROOT, 'data', 'itc2019', 'stats.json')

def th(n): return '{:,}'.format(n).replace(',', ' ')
def ms(x): return th(round(x)) if x >= 100 else ('%.1f' % x if x >= 10 else '%.2f' % x)
def sp(x): return th(round(x)) if x >= 100 else ('%.1f' % x if x >= 10 else '%.2f' % x)
def ci(r): return '%s–%s' % tuple(sp(v) for v in r['speedup_ci95'])
out = []; w = out.append

w('### Statement counts replayed on the snapshot (article Table 2)\n')
w('Exact counts of the statements each strategy issues for the question "which of these rows may this caller edit?", '
  'replayed by `replay/instancegraph.mjs` over `data/lnu/data-anonymised.sql`.\n')
w('| Request | Rows | Per-row statements | Set-at-a-time statements | Ratio |'); w('|---|---:|---:|---:|---:|')
q = open(os.path.join(R, 'replay', 'queries.tex')).read()
for m in re.finditer(r'\\texttt\{(\w+)\} & ([\d\\,]+) & ([\d\\,]+) & ([\d\\,]+) & ([\d\\,]+)\$', q):
    g = [x.replace('\\,', ' ') for x in m.groups()]
    w('| %s | %s | %s | %s | %s× |' % tuple(g))
w('\n### Level-by-level statement trace of the TimetableEntry request (§7.5; Table 4 of the reviewed version)\n')
w('All 1 381 timetable entries; level 0 is the two setup statements (the caller\'s memberships and grants).\n')
w('| Level | Types expanded | Statements | Cumulative |'); w('|---:|---|---:|---:|')
t = open(os.path.join(R, 'replay', 'trace.tex')).read()
for m in re.finditer(r'^(\d+) & (.*?) & (\d+) & (\d+) \\\\$', t, flags=re.M):
    types = re.sub(r'\\texttt\{(.*?)\}', r'\1', m[2]).replace('\\-', '').replace('\\emph{', '').replace('}', '')
    w('| %s | %s | %s | %s |' % (m[1], types.replace("the caller's grants", "*the caller's grants*"), m[3], m[4]))
a = open(os.path.join(R, 'replay', 'algocheck.log')).read()
m = re.search(r'(\d+) random instance graphs, (\d+) questions, (\d+) row answers', a)
agreed = 'agreed with the reference evaluator on every row' in a
w('\n### Algorithm 1 check (§6.3)\n')
w('Algorithm 1 against a reference evaluator (`replay/algocheck.mjs`): %s random instance graphs, %s questions, '
  '%s row answers; %s.\n' % (th(int(m[1])), th(int(m[2])), th(int(m[3])), 'all agree' if agreed else '**DISAGREEMENT**'))

for cond, title in (('loopback', 'co-located with the database'), ('relay', 'through the relay (250 µs each way)')):
    L = lat[cond]
    w('### Measured latency on the snapshot, %s (article Table 3)\n' % title)
    w('%d JVM runs × 30 measured pairs after 10 warm-up rounds; mismatching answers between the strategies: %d. '
      'Speedup = geometric mean over runs of the per-run median of paired per-row/set-at-a-time ratios, with a 95 %% '
      't-interval (n = runs).\n' % (L['rows'][0]['runs'], L['mismatching_pairs']))
    w('| Request | Rows | Statements set / per-row | Median ms set / per-row | Speedup | 95% CI | Pairs per-row faster |')
    w('|---|---:|---:|---:|---:|---:|---:|')
    for r in L['rows']:
        w('| %s | %s | %d / %s | %s / %s | %s× | %s | %d of %d |' % (r['workload'], th(r['rows']), r['stmts_set'][0], th(r['stmts_perrow'][0]),
          ms(r['med_set_ms']), ms(r['med_perrow_ms']), sp(r['speedup_gm']), ci(r), r['pairs_perrow_faster'], r['pairs']))
    w('')

I = itc['itc']
w('### ITC 2019 instances used (§7.7)\n')
w('One instance per contributing university. Source files: `data/itc2019/instances.tsv` (URL and git blob SHA-1); '
  'structure counted by `data/itc2019/extract.py`.\n')
w('| Instance | Set | Git blob SHA-1 | Courses | Configs | Subparts | Classes | Rooms | Class–room rows | Classes with parent | Max parent chain |')
w('|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|')
for r in csv.DictReader(open(os.path.join(ROOT, 'data', 'itc2019', 'instances.tsv')), delimiter='\t'):
    x = stats[r['instance']]
    w('| %s | %s | `%s` | %s | %s | %s | %s | %s | %s | %s | %s |' % (r['instance'], r['set'], r['git_blob_sha1'], th(x['courses']),
      th(x['configs']), th(x['subparts']), th(x['classes']), th(x['rooms']), th(x['class_room_rows']), th(x['classes_with_parent']),
      x['max_parent_chain']))
w('')
w('### ITC 2019 instances, all classes (article Table 4)\n')
w('Class-list question over all classes of each instance, co-located, %d runs × 30 pairs after 5 warm-up rounds; '
  'mismatching answers: %d. Grants are constructed (see `data/itc2019/grants.sql`).\n' % (I['rows'][0]['runs'], I['mismatching_pairs']))
w('| Instance | Classes | Rooms per class | Statements set / per-row | Median ms set / per-row | Speedup | 95% CI |')
w('|---|---:|---:|---:|---:|---:|---:|')
full = {}
for r in I['rows']:
    if r['workload'] not in full or r['rows'] > full[r['workload']]['rows']: full[r['workload']] = r
for inst in sorted(full):
    r = full[inst]
    w('| %s | %s | %.2f | %d / %s | %s / %s | %s× | %s |' % (inst, th(r['rows']), stats[inst]['mean_rooms_per_class'], r['stmts_set'][0],
      th(r['stmts_perrow'][0]), ms(r['med_set_ms']), ms(r['med_perrow_ms']), sp(r['speedup_gm']), ci(r)))
insts = sorted(full)
rho = st.spearmanr([full[i]['speedup_gm'] for i in insts], [stats[i]['mean_rooms_per_class'] for i in insts]).statistic
rho_c = st.spearmanr([full[i]['speedup_gm'] for i in insts], [stats[i]['classes'] for i in insts]).statistic
w('\nExploratory Spearman correlation of the speedup with rooms per class: ρ = %.2f; with the class count: ρ = %.2f. '
  'Smaller sizes (1, 10, 50, 200 classes) are in `results/summary/itc2019-summary.json`.\n' % (rho, rho_c))

w('### Consistency under concurrent changes (article Table 5)\n')
s10 = cons['s10']
w('%d of %d schedules pass for the prototype (Read Committed; `results/consistency/consistency.tsv` describes each schedule '
  'and both outcomes).\n' % (cons['passed'], cons['schedules']))
w('| Id | Scenario | Released implementation | Prototype | Result |'); w('|---|---|---|---|---|')
crow = {r['id']: r for r in csv.DictReader(open(os.path.join(R, 'consistency', 'consistency.tsv')), delimiter='\t')}
for k in cons['results']:
    r = crow[k]
    w('| %s | %s | %s | %s | %s |' % (k, r['scenario'], r['deployed_baseline'], r['prototype'], r['pass']))
w('\nS10, two application instances, randomized: released implementation %d unjustified of %d committed writes '
  '(%d written in a different authorization state than their check began in); prototype %d of %d (%d).\n' % (
  s10['released']['unjustified'], s10['released']['committed'], s10['released']['state_changed'],
  s10['prototype']['unjustified'], s10['prototype']['committed'], s10['prototype']['state_changed']))

w('### Cost of the protocol (Section 8.6)\n')
sm = prot['single_median_ms']
w('One client, protected update of one course (median over 5 runs × 300 units): released %.2f ms, share locks %.2f ms, '
  'exclusive lock %.2f ms.\n' % (sm['deployed'], sm['shared'], sm['exclusive']))
w('| Variant | Clients | Authorization changes | Updates/s (mean over runs) | Range | p50 ms |'); w('|---|---:|---|---:|---:|---:|')
names = {'deployed': 'released', 'shared': 'share locks', 'exclusive': 'exclusive lock'}
order = lambda k: (list(names).index(k.split('/')[0]), k.split('/')[2] == 'true', int(k.split('/')[1]))
for k in sorted(prot['concurrent'], key=order):
    v = prot['concurrent'][k]; var, clients, ch = k.split('/')
    w('| %s | %s | %s | %s | %s–%s | %.2f |' % (names.get(var, var), clients, 'every 50 ms' if ch == 'true' else 'none',
      th(round(v['ops_per_s_mean'])), th(round(v['ops_per_s_range'][0])), th(round(v['ops_per_s_range'][1])), v['p50_ms_median']))

w('\n### Environment\n')
w('%d vCPU (%s), %.1f GiB RAM; PostgreSQL %s; Java %s. Round trip per `SELECT 1`: %s µs directly, %s µs through the relay '
  '(`results/latency/rtt.txt`).' % (env['cpus'], env['cpu_model'], env['mem_gib'], env['pg'], env['java'],
  ' / '.join(map(str, env['rtt_direct_us'])), ' / '.join(map(str, env['rtt_relay_us']))))
md = '\n'.join(out) + '\n'
open(os.path.join(S, 'tables.md'), 'w').write(md)
readme = os.path.join(ROOT, 'README.md')
if os.path.exists(readme):
    t = open(readme).read()
    t2 = re.sub(r'(<!-- results:begin -->\n).*?(<!-- results:end -->)', lambda m: m[1] + md + m[2], t, flags=re.S)
    open(readme, 'w').write(t2)
print(md)
