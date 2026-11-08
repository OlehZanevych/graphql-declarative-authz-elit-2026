"""Extract the permission-relevant structure of ITC 2019 instances into TSV files.

course -> config -> subpart -> class (with optional parent class), and class <-> room (the
rooms a class may use). Only integer identifiers are kept. Each instance's ids are used as given.
"""
import os, sys, json, xml.etree.ElementTree as ET
src, out = sys.argv[1], sys.argv[2]
os.makedirs(out, exist_ok=True)
stats = {}
for f in sorted(os.listdir(src)):
    if not f.endswith('.xml'):
        continue
    name = f[:-4]
    d = os.path.join(out, name); os.makedirs(d, exist_ok=True)
    courses, configs, subparts, classes, rooms, class_rooms = [], [], [], [], [], []
    root = ET.parse(os.path.join(src, f)).getroot()
    for r in root.find('rooms'):
        rooms.append(int(r.get('id')))
    for c in root.find('courses'):
        cid = int(c.get('id')); courses.append(cid)
        for cf in c.findall('config'):
            fid = int(cf.get('id')); configs.append((fid, cid))
            for sp in cf.findall('subpart'):
                sid = int(sp.get('id')); subparts.append((sid, fid))
                for cl in sp.findall('class'):
                    k = int(cl.get('id')); p = cl.get('parent')
                    classes.append((k, sid, int(p) if p else None))
                    for rm in cl.findall('room'):
                        class_rooms.append((k, int(rm.get('id'))))
    def w(fn, rows):
        with open(os.path.join(d, fn), 'w') as fh:
            for r in rows:
                if not isinstance(r, tuple): r = (r,)
                fh.write('\t'.join('\\N' if v is None else str(v) for v in r) + '\n')
    w('courses.tsv', courses); w('configs.tsv', configs); w('subparts.tsv', subparts)
    w('classes.tsv', classes); w('rooms.tsv', rooms); w('class_rooms.tsv', sorted(set(class_rooms)))
    parent = {k: p for k, s, p in classes}
    def depth(k, seen=()):
        p = parent.get(k)
        return 0 if p is None or p in seen else 1 + depth(p, seen + (k,))
    depths = [depth(k) for k, _, _ in classes]
    per_class = {}
    for k, r in set(class_rooms): per_class[k] = per_class.get(k, 0) + 1
    stats[name] = dict(courses=len(courses), configs=len(configs), subparts=len(subparts), classes=len(classes),
        rooms=len(rooms), class_room_rows=len(set(class_rooms)),
        classes_with_parent=sum(1 for _, _, p in classes if p is not None),
        max_parent_chain=max(depths), classes_without_rooms=sum(1 for k, _, _ in classes if k not in per_class),
        mean_rooms_per_class=round(len(set(class_rooms)) / len(classes), 2))
    print(name, stats[name], flush=True)
json.dump(stats, open(os.path.join(out, 'stats.json'), 'w'), indent=1)
