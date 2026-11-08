"""Environment summary: results/environment.txt and results/latency/rtt.txt -> results/summary/environment-summary.json."""
import json, os, re, statistics
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
R = os.path.join(ROOT, 'results')
env = open(os.path.join(R, 'environment.txt')).read()
rtt = {}
for line in open(os.path.join(R, 'latency', 'rtt.txt')):
    m = re.match(r'port (\d+): (\d+) us per select', line)
    if m: rtt.setdefault(m[1], []).append(int(m[2]))
direct, relay = (rtt[p] for p in sorted(rtt, key=lambda p: statistics.mean(rtt[p])))
mem = re.search(r'Mem:\s+(\d+)', env)
out = dict(pg=re.search(r'PostgreSQL ([\d.]+)', env)[1], java=re.search(r'version "([^"]+)"', env)[1],
           cpus=int(re.search(r'cpus: (\d+)', env)[1]), mem_gib=round(int(mem[1]) / 1024, 1),
           cpu_model=(re.search(r'model name\s*:\s*(.*)', env) or [None, ''])[1].strip(),
           rtt_direct_us=direct, rtt_relay_us=relay)
json.dump(out, open(os.path.join(R, 'summary', 'environment-summary.json'), 'w'), indent=1)
print(json.dumps(out, indent=1))
