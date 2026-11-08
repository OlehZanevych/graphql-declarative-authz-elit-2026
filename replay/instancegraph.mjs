#!/usr/bin/env node
/**
 * What the evaluator actually costs over the deployed data, rather than over the schema.
 *
 * WHY THIS SCRIPT EXISTS
 *
 * The statement counts this collection published were computed from the SCHEMA graph G_S: each
 * reachable entity type charged once. A reviewer pointed out that this cannot be right, because the
 * evaluator's breadth-first frontier holds ROWS, and rows of one type reached by paths of different
 * lengths land on different levels. `Course` has a parent-course edge, so a chain c1 -> c2 -> c3
 * puts that one type on three consecutive levels and costs three statements, not one. The schema
 * alone cannot say how often that happens; two successive attempts to bracket it with schema-only
 * "scenarios" produced numbers that were neither lower nor upper bounds of anything.
 *
 * The honest fix is to stop guessing. The application ships its deployed dataset as a pg_dump
 * data-only backup in `src/main/resources/db/data.sql`, so the row-instance graph G_I is available,
 * public and reproducible. This script reads it, builds G_I for the permission edges, and runs the
 * SAME breadth-first walk `PermissionEvaluator` performs — one statement per (level, type) frontier
 * for the foreign-key parents of that type, plus one per join-table parent — counting the
 * statements it issues for the exact row sets the paper's table reports.
 *
 * The result is an exact count for those requests over that dataset. Not a bound, not a scenario,
 * and not a latency: an enumeration of the statements the deployed algorithm issues over the
 * deployed data, which is what the paper should have been claiming all along.
 *
 * Usage:
 *   node instancegraph.mjs --data <path to data.sql> [--out <figures dir>]
 */
import { readFileSync, writeFileSync, existsSync, mkdirSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const HERE = dirname(fileURLToPath(import.meta.url));
const arg = (name, fallback) => {
  const i = process.argv.indexOf(name);
  return i > 0 && process.argv[i + 1] ? process.argv[i + 1] : fallback;
};

// Repository layout: outputs go to ../results/replay unless --out is given.
const OUT = arg('--out', join(HERE, '..', 'results', 'replay'));
const DATA = arg('--data', null);            // the deployment's pg_dump
const DATASET = arg('--dataset', null);      // or the reduced extract this script emits
const EMIT = arg('--emit-dataset', null);    // write that extract and stop
if (!DATA && !DATASET) {
  throw new Error('pass --data <db/data.sql> or --dataset <permission-instance.tsv>');
}
if (DATA && !existsSync(DATA)) throw new Error(`${DATA} does not exist`);
if (DATASET && !existsSync(DATASET)) throw new Error(`${DATASET} does not exist`);
mkdirSync(OUT, { recursive: true });

// ── the permission edges, with the physical names they are realised by ───────

const TSV = readFileSync(join(HERE, 'permission-edges.tsv'), 'utf8');
const STAMP = `% edges: ${createHash('sha256').update(TSV).digest('hex').slice(0, 12)}`;

/** child type -> { table, key, fk: [{parent, column, nullable}], join: [{parent, table, self, parentCol}] } */
const SPEC = {};
for (const line of TSV.split('\n')) {
  const row = line.trim();
  if (!row || row.startsWith('#')) continue;
  const [child, parent, kind, nullable, authority, table, key, ref] = row.split('\t');
  if (!ref) throw new Error(`permission-edges.tsv row has no physical names: "${row}" ` +
    '— re-run permedges.mjs, which emits them');
  const e = (SPEC[child] ??= { table, key, fk: [], join: [] });
  if (e.table !== table || e.key !== key) throw new Error(`${child} has two different tables`);
  if (kind === 'fk') e.fk.push({ parent, column: ref, nullable: nullable === 'true' });
  else {
    const [joinTable, self, parentCol] = ref.split(':');
    e.join.push({ parent, table: joinTable, self, parentCol });
  }
}
const TYPE_OF_TABLE = Object.fromEntries(Object.entries(SPEC).map(([t, e]) => [e.table, t]));

// ── read the dump ────────────────────────────────────────────────────────────

/**
 * Split one INSERT's VALUES list.
 *
 * Hand-rolled rather than regexed because the data is Ukrainian prose: course names contain commas
 * and apostrophes, and an apostrophe inside a SQL string is doubled. A naive split on "," turns
 * «Історія української культури, ч.2» into two columns and silently shifts every value after it —
 * which for this script would mean reading a course's `department_id` out of its `semester`.
 */
function splitValues(text) {
  const out = [];
  let cur = '', inStr = false;
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (inStr) {
      if (c === "'" && text[i + 1] === "'") { cur += "''"; i++; continue; }
      if (c === "'") { inStr = false; cur += c; continue; }
      cur += c;
    } else if (c === "'") { inStr = true; cur += c; }
    else if (c === ',') { out.push(cur.trim()); cur = ''; }
    else cur += c;
  }
  out.push(cur.trim());
  return out;
}

const NEEDED_TABLES = new Set();
for (const e of Object.values(SPEC)) {
  NEEDED_TABLES.add(e.table);
  for (const j of e.join) NEEDED_TABLES.add(j.table);
}

/**
 * The snapshot the input was cut from, taken from whichever header carries it: `pg_dump` writes
 * "generated by … on <date>T…", and the reduced dataset copies that date into "# snapshot: <date>".
 * Emitted into the paper, so a figure and the data behind it name the same day.
 */
const SNAPSHOT_DATE = (() => {
  const head = readFileSync(DATA ?? DATASET, 'utf8').slice(0, 4096);
  return (head.match(/on (\d{4}-\d{2}-\d{2})T/) ?? head.match(/# snapshot: (\d{4}-\d{2}-\d{2})/) ?? [])[1] ?? null;
})();

/**
 * The columns this analysis reads, per table — and the only ones it has any use for.
 *
 * Every one of them is an integer key or foreign key. No name, e-mail address, password hash,
 * record-book number or free-text field appears anywhere in the traversal, which is what makes it
 * possible to publish a reproducibility dataset that carries no personal data at all: see
 * `--emit-dataset` below.
 */
const NEEDED_COLUMNS = (() => {
  const cols = {};
  const want = (t, c) => ((cols[t] ??= new Set()).add(c));
  for (const e of Object.values(SPEC)) {
    want(e.table, e.key);
    for (const fk of e.fk) want(e.table, fk.column);
    for (const j of e.join) { want(j.table, j.self); want(j.table, j.parentCol); }
  }
  return Object.fromEntries(Object.entries(cols).map(([t, s]) => [t, [...s]]));
})();

/** table -> array of {} column maps, only for the columns any permission edge needs. */
const ROWS = {};
let parsedRows = 0;

if (DATA) {
  const INSERT_RE = /^INSERT INTO public\.([a-z_0-9]+) \(([^)]*)\) VALUES \((.*)\);\s*$/;
  for (const line of readFileSync(DATA, 'utf8').split('\n')) {
    const m = INSERT_RE.exec(line);
    if (!m) continue;
    const [, table, colText, valText] = m;
    if (!NEEDED_TABLES.has(table)) continue;
    const cols = colText.split(',').map((c) => c.trim());
    const vals = splitValues(valText);
    if (cols.length !== vals.length) {
      throw new Error(`${table}: ${cols.length} columns but ${vals.length} values — the value ` +
        `splitter is wrong, not the dump:\n  ${line.slice(0, 200)}`);
    }
    const keep = NEEDED_COLUMNS[table] ?? [];
    const row = {};
    for (let i = 0; i < cols.length; i++) {
      if (!keep.includes(cols[i])) continue;          // everything else is dropped on read
      const v = vals[i];
      row[cols[i]] = v === 'NULL' ? null : (/^-?\d+$/.test(v) ? Number(v) : v);
    }
    (ROWS[table] ??= []).push(row);
    parsedRows++;
  }
  if (!parsedRows) throw new Error('no INSERT rows parsed — the dump format changed');
} else {
  // The reduced extract: `@table <name> <col> <col> …` then one tab-separated row per line.
  let table = null, cols = [];
  for (const line of readFileSync(DATASET, 'utf8').split('\n')) {
    if (!line || line.startsWith('#')) continue;
    if (line.startsWith('@table ')) {
      const parts = line.slice(7).trim().split(/\s+/);
      table = parts[0]; cols = parts.slice(1);
      continue;
    }
    if (!table) throw new Error('a data line appeared before any @table header');
    const vals = line.split('\t');
    if (vals.length !== cols.length) {
      throw new Error(`${table}: ${cols.length} columns but ${vals.length} values in "${line}"`);
    }
    const row = {};
    for (let i = 0; i < cols.length; i++) row[cols[i]] = vals[i] === '' ? null : Number(vals[i]);
    (ROWS[table] ??= []).push(row);
    parsedRows++;
  }
  if (!parsedRows) throw new Error('the dataset held no rows');
}

/**
 * Emit the reduced reproducibility dataset and stop.
 *
 * WHY THIS EXISTS
 *
 * The paper's counts are reproducible only if the data they are computed over is available, and the
 * obvious way to make it available — publish the deployment's pg_dump — publishes a great deal that
 * has nothing to do with the counts. That dump carries a bcrypt password hash, the names of 22
 * students, and e-mail addresses for 1,915 lecturers. None of it is read here: the traversal follows
 * integer keys, and `NEEDED_COLUMNS` above is the whole of what it touches.
 *
 * So this writes exactly that — the key and permission-bearing foreign-key columns of the tables the
 * walk visits, and nothing else. It reproduces every figure in the paper and identifies nobody. The
 * provenance header records the snapshot it was cut from and the edge digest it matches, so a reader
 * can tell which manuscript it belongs to.
 */
if (EMIT) {
  const lines = [
    '# Reduced reproducibility dataset for the permission-cascade evaluation.',
    '#',
    '# GENERATED by instancegraph.mjs --emit-dataset from the deployment snapshot. It carries the key',
    '# and permission-bearing foreign-key columns of the tables the traversal visits, and nothing',
    '# else: no names, e-mail addresses, password hashes, record-book numbers or free text. Every',
    '# value below is an integer identifier, and the analysis reads no other column, so this file',
    '# reproduces Tables 3 and 4 of the paper while identifying nobody.',
    '#',
    `# snapshot: ${SNAPSHOT_DATE ?? 'unknown'}`,
    `# ${STAMP.slice(2)}`,
    '#',
    '#   @table <name> <column> <column> …   then one tab-separated row per line, empty = NULL',
  ];
  let emitted = 0;
  for (const table of Object.keys(NEEDED_COLUMNS).sort()) {
    const cols = NEEDED_COLUMNS[table];
    lines.push(`@table ${table} ${cols.join(' ')}`);
    for (const r of ROWS[table] ?? []) {
      lines.push(cols.map((c) => (r[c] == null ? '' : r[c])).join('\t'));
      emitted++;
    }
  }
  writeFileSync(EMIT, lines.join('\n') + '\n');
  console.log(`wrote ${EMIT}: ${emitted.toLocaleString('en-US')} rows across ` +
    `${Object.keys(NEEDED_COLUMNS).length} tables, identifiers only`);
  process.exit(0);
}

/** table -> Map(id -> row), for the tables an edge starts from. */
const BY_ID = {};
for (const [type, e] of Object.entries(SPEC)) {
  const map = new Map();
  for (const r of ROWS[e.table] ?? []) map.set(r[e.key], r);
  BY_ID[type] = map;
}
/** joinTable -> Map(selfId -> [parentId]) */
const JOIN_INDEX = {};
for (const e of Object.values(SPEC)) {
  for (const j of e.join) {
    if (JOIN_INDEX[j.table]) continue;
    const map = new Map();
    for (const r of ROWS[j.table] ?? []) {
      if (r[j.self] == null || r[j.parentCol] == null) continue;
      (map.get(r[j.self]) ?? map.set(r[j.self], []).get(r[j.self])).push(r[j.parentCol]);
    }
    JOIN_INDEX[j.table] = map;
  }
}

// ── the walk the evaluator performs, over G_I ────────────────────────────────

const MAX_DEPTH = 32;                 // PermissionEvaluator.MAX_DEPTH
const FIXED_PER_REQUEST = 2;          // group memberships, then grants

/**
 * Breadth-first over row vertices, exactly as `PermissionEvaluator.expand` does it: a frontier
 * grouped by type, one statement covering all foreign-key parent columns of a type together and
 * one per join-table parent, a vertex expanded at most once, and the level at which each type's
 * frontier is nonempty deciding how often that type is charged.
 *
 * Returns the statement count and the per-level trace.
 */
function walk(startType, startIds) {
  let frontier = new Map([[startType, new Set(startIds)]]);
  const seen = new Set(startIds.map((id) => `${startType}#${id}`));
  const steps = [{ level: 0, from: ['(caller)'], queries: FIXED_PER_REQUEST }];
  let total = FIXED_PER_REQUEST;
  let level = 1;

  while (frontier.size && level <= MAX_DEPTH) {
    const next = new Map();
    let queries = 0;
    const expanded = [];
    for (const [type, ids] of frontier) {
      if (!ids.size) continue;
      const spec = SPEC[type];
      if (!spec) continue;                       // a root: no parents, nothing to read
      expanded.push(type);
      if (spec.fk.length) queries += 1;          // one SELECT for every FK column of this table
      queries += spec.join.length;               // one per join table

      for (const id of ids) {
        const row = BY_ID[type].get(id);
        for (const e of spec.fk) {
          const pid = row?.[e.column];
          if (pid == null) continue;
          const kkey = `${e.parent}#${pid}`;
          if (seen.has(kkey)) continue;
          seen.add(kkey);
          (next.get(e.parent) ?? next.set(e.parent, new Set()).get(e.parent)).add(pid);
        }
        for (const j of spec.join) {
          for (const pid of JOIN_INDEX[j.table].get(id) ?? []) {
            const kkey = `${j.parent}#${pid}`;
            if (seen.has(kkey)) continue;
            seen.add(kkey);
            (next.get(j.parent) ?? next.set(j.parent, new Set()).get(j.parent)).add(pid);
          }
        }
      }
    }
    if (!queries) break;
    total += queries;
    steps.push({ level, from: expanded, queries });
    frontier = next;
    level++;
  }
  return { total, steps, levels: steps.length - 1, vertices: seen.size };
}

/**
 * The naive per-row strategy, over the same data: for each row independently, climb its ancestry
 * following every distinct path, one statement per parent identifier fetched, remembering nothing
 * between rows. Counted over G_I rather than over the schema, so an unpopulated optional edge costs
 * nothing here — which is why this number is smaller than the schema-derived one it replaces.
 */
function walkPerRow(startType, startIds) {
  let q = 0;
  const climb = (type, id, onPath) => {
    const spec = SPEC[type];
    if (!spec) return;
    const row = BY_ID[type].get(id);
    for (const e of spec.fk) {
      const pid = row?.[e.column];
      if (pid == null) continue;
      q++;
      const k = `${e.parent}#${pid}`;
      if (onPath.has(k)) continue;
      onPath.add(k); climb(e.parent, pid, onPath); onPath.delete(k);
    }
    for (const j of spec.join) {
      for (const pid of JOIN_INDEX[j.table].get(id) ?? []) {
        q++;
        const k = `${j.parent}#${pid}`;
        if (onPath.has(k)) continue;
        onPath.add(k); climb(j.parent, pid, onPath); onPath.delete(k);
      }
    }
  };
  for (const id of startIds) climb(startType, id, new Set([`${startType}#${id}`]));
  return q + FIXED_PER_REQUEST;
}

// ── the requests the paper reports ───────────────────────────────────────────

const allIds = (type) => [...BY_ID[type].keys()];

/**
 * The row sets. Two are "everything of this type in the deployed data", which is what the page or
 * the screen shows; the course page is the first 200 by id, which is what an ordered first page is.
 */
const SCENARIOS = [
  { type: 'Course', pick: (ids) => ids.slice().sort((a, b) => a - b).slice(0, 200),
    note: 'a course list page' },
  { type: 'TimetableEntry', pick: (ids) => ids, note: 'the whole deployed timetable' },
  { type: 'LecturerWorkload', pick: (ids) => ids.slice().sort((a, b) => a - b).slice(0, 500),
    note: "a department's workload sheet" },
  { type: 'LecturerWorkloadCandidate', pick: (ids) => ids,
    note: 'the deepest entity the deployed data populates' }
];

/**
 * The paper used to report a fourth request over `LecturerWorkloadCandidateConstraint`, the deepest
 * entity in the model, at a made-up 50 rows. The dump holds none: the constraint table is empty in
 * this deployment. Reporting a count for it would have been reporting a count for a request nobody
 * can issue, which is exactly what moving from the schema to the data is meant to stop, so the row
 * is now its populated parent and the paper says why.
 */
const UNPOPULATED = Object.keys(SPEC).filter((t) => (BY_ID[t]?.size ?? 0) === 0).sort();

const results = [];
for (const s of SCENARIOS) {
  const ids = s.pick(allIds(s.type));
  if (!ids.length) throw new Error(`${s.type}: the dump holds no rows, so no request can be counted`);
  const set = walk(s.type, ids);
  const perRow = walkPerRow(s.type, ids);
  results.push({ ...s, rows: ids.length, perRow, setWise: set.total, trace: set,
    ratio: (perRow / set.total) });
}

// ── the recursive chain the paper makes a claim about ────────────────────────
//
// The paper says the deepest parent-course chain in the deployed data is one link. That was an
// assertion with no check behind it; here it is computed, and it is emitted so the sentence in the
// paper cannot drift away from the data the way the entity count once did.
const courseParent = SPEC.Course?.fk.find((e) => e.parent === 'Course');
let chainDepth = 0;
if (courseParent) {
  const depthOf = new Map();
  const measure = (id, onPath) => {
    if (depthOf.has(id)) return depthOf.get(id);
    if (onPath.has(id)) return Infinity;                   // a cycle in the data
    const pid = BY_ID.Course.get(id)?.[courseParent.column];
    onPath.add(id);
    const d = pid == null ? 0 : 1 + measure(pid, onPath);
    onPath.delete(id);
    depthOf.set(id, d);
    return d;
  };
  for (const id of BY_ID.Course.keys()) chainDepth = Math.max(chainDepth, measure(id, new Set()));
  if (!Number.isFinite(chainDepth)) throw new Error('the deployed data holds a parent-course cycle');
}

// ── output ───────────────────────────────────────────────────────────────────

const SNAPSHOT = SNAPSHOT_DATE;
if (!SNAPSHOT) throw new Error('the input carries no snapshot date in its header');

const n = (x) => x.toLocaleString('en-US').replace(/,/g, '\\,');

let tex = `% generated by instancegraph.mjs\n${STAMP}\n\\begin{table}[t]\n\\centering\n`;
tex += '\\caption{Database statements issued to answer "which of these rows may this caller ' +
  'edit?", counted over the row-instance graph $G_I$ of the deployed dataset. The naive per-row ' +
  'strategy is the implementation the framework replaced; the set-at-a-time strategy is the one ' +
  'it uses. These are exact counts of the statements each strategy issues for these row sets over ' +
  `this data --- a script replays the traversal over the dump of ${SNAPSHOT} described in ` +
  'Section~\\ref{sec:method} --- and not latencies, and not a bound over other datasets.}\n';
tex += '\\label{tab:queries}\n\\small\n\\begin{tabular}{@{}lrrrr@{}}\n\\toprule\n';
tex += 'request & rows & naive per-row & set-at-a-time & ratio \\\\\n\\midrule\n';
for (const r of results) {
  tex += `\\texttt{${r.type}} & ${n(r.rows)} & ${n(r.perRow)} & ${r.setWise} & ` +
    `${Math.round(r.ratio).toLocaleString('en-US').replace(/,/g, '\\,')}$\\times$ \\\\\n`;
}
tex += '\\bottomrule\n\\end{tabular}\n\\end{table}\n';
writeFileSync(join(OUT, 'queries.tex'), tex);

const TRACE_TYPE = 'TimetableEntry';
const tr = results.find((r) => r.type === TRACE_TYPE).trace;
{
  let t = `% generated by instancegraph.mjs\n${STAMP}\n\\begin{table}[t]\n\\centering\n`;
  t += `\\caption{Every statement one \\lstinline|accessLevels| evaluation issues, for the ` +
    `identifiers a \\texttt{${TRACE_TYPE}} listing returns --- the whole deployed timetable. Level ` +
    `$0$ is the two setup statements: the caller's group memberships, and the grants held by the ` +
    `caller and those groups. Each later level then issues, for every type with a nonempty ` +
    `frontier on it, one statement covering all of that type's foreign-key parent columns together ` +
    `plus one per join-table permission parent --- so four foreign-key parents cost one statement, ` +
    `not four, and the number of identifiers in the frontier does not enter. Replayed over the ` +
    `deployed data, not over the schema: a type appears on as many levels as the data puts it on.}\n`;
  t += '\\label{tab:trace}\n\\small\n' +
    '\\begin{tabular}{@{}r>{\\raggedright\\arraybackslash}p{0.58\\textwidth}rr@{}}\n\\toprule\n';
  t += 'level & types expanded & statements & cumulative \\\\\n\\midrule\n';
  let cum = 0;
  for (const st of tr.steps) {
    cum += st.queries;
    const names = st.from.map((f) => (f === '(caller)' ? '\\emph{the caller\'s grants}'
      : `\\texttt{${f.replace(/([a-z])([A-Z])/g, '$1\\-$2')}}`)).join(', ');
    t += `${st.level} & ${names} & ${st.queries} & ${cum} \\\\\n`;
  }
  t += '\\bottomrule\n\\end{tabular}\n\\end{table}\n';
  writeFileSync(join(OUT, 'trace.tex'), t);

  const widest = tr.steps.reduce((a, b) => (b.queries > a.queries ? b : a));
  const deep = results[results.length - 1];
  const small = results.find((r) => r.type === 'Course');
  const big = results.find((r) => r.type === TRACE_TYPE);
  const tables = new Set(tr.steps.flatMap((s) => s.from).filter((f) => f !== '(caller)'));
  writeFileSync(join(OUT, 'trace-facts.tex'),
    `% generated by instancegraph.mjs\n${STAMP}\n` +
    `\\newcommand{\\traceType}{\\texttt{${TRACE_TYPE}}}\n` +
    `\\newcommand{\\traceTotal}{${tr.total}}\n` +
    `\\newcommand{\\traceLevels}{${tr.levels}}\n` +
    `\\newcommand{\\traceWidestLevel}{${widest.level}}\n` +
    `\\newcommand{\\traceWidestQueries}{${widest.queries}}\n` +
    `\\newcommand{\\traceTables}{${tables.size}}\n` +
    `\\newcommand{\\traceVertices}{${n(tr.vertices)}}\n` +
    `\\newcommand{\\traceSecondLevelQueries}{${tr.steps.find((s) => s.level === 2)?.queries ?? 0}}\n` +
    `\\newcommand{\\traceSecondLevelTables}{${tr.steps.find((s) => s.level === 2)?.from.length ?? 0}}\n` +
    `\\newcommand{\\traceRows}{${n(big.rows)}}\n` +
    `\\newcommand{\\pageRows}{${n(small.rows)}}\n` +
    `\\newcommand{\\pagePerRow}{${n(small.perRow)}}\n` +
    `\\newcommand{\\pageSetWise}{${small.setWise}}\n` +
    `\\newcommand{\\tracePerRow}{${n(big.perRow)}}\n` +
    `\\newcommand{\\dataSnapshot}{${SNAPSHOT}}\n` +
    `\\newcommand{\\dataRows}{${n(parsedRows)}}\n` +
    `\\newcommand{\\courseChain}{${chainDepth}}\n` +
    // The deepest POPULATED entity's row, quoted in the prose beside the timetable one. It used to
    // be quoted with \traceTotal, which is the timetable's figure — the paper stated 25 where the
    // table said 18, because one macro was standing in for two different requests.
    `\\newcommand{\\deepType}{\\texttt{${deep.type}}}\n` +
    `\\newcommand{\\deepRows}{${n(deep.rows)}}\n` +
    `\\newcommand{\\deepSetWise}{${deep.setWise}}\n` +
    // The first three frontier widths, in types expanded, so that Figure 4's boxes are drawn from
    // the replay instead of from three numbers somebody typed once and never revisited.
    `\\newcommand{\\traceWidthOne}{${tr.steps[1]?.from.length ?? 0}}\n` +
    `\\newcommand{\\traceWidthTwo}{${tr.steps[2]?.from.length ?? 0}}\n` +
    `\\newcommand{\\traceWidthThree}{${tr.steps[3]?.from.length ?? 0}}\n` +
    `\\newcommand{\\emptyEntities}{${UNPOPULATED.length}}\n`);
}

console.log(`data.sql of ${SNAPSHOT}: ${parsedRows.toLocaleString('en-US')} rows parsed ` +
  `across ${NEEDED_TABLES.size} tables`);
console.log(`deepest parent-course chain in the data: ${chainDepth} link(s)`);
console.log(`entity types with no rows in this dump (${UNPOPULATED.length}): ${UNPOPULATED.join(', ')}`);
console.table(results.map((r) => ({
  type: r.type, rows: r.rows, perRow: r.perRow, setWise: r.setWise,
  levels: r.trace.levels, ratio: `${Math.round(r.ratio)}x`
})));
for (const st of tr.steps) {
  console.log(`  L${st.level}  ${String(st.queries).padStart(2)}  ${st.from.join(', ')}`);
}
console.log(`wrote ${OUT}/queries.tex, trace.tex, trace-facts.tex`);
