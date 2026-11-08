#!/usr/bin/env node
/**
 * Differential validation of Algorithm 1 as the paper prints it.
 *
 * WHY
 *
 * The paper's Proposition establishes only that downward and upward reachability decide the same
 * predicate. It does not establish that the ALGORITHM decides it, and the algorithm does four
 * further things: it memoises across questions within a request, it batches by type, it takes a
 * maximum over several parents through a fixpoint over a materialised parent map rather than
 * carrying provenance, and it stops at a depth backstop. Every one of those is a place a correct
 * idea can be implemented into a wrong answer, and three of the four were in fact wrong in an
 * earlier draft of the pseudocode.
 *
 * This script therefore executes the pseudocode AS PRINTED — the transcription in `setWise` below
 * follows Algorithm 1 line for line — against a deliberately stupid reference evaluator, over
 * randomly generated instance graphs built to contain exactly the features the deployed model has
 * and that a naive implementation gets wrong: cycles, self-referential chains, several parents per
 * vertex, shared ancestors reached by routes of different lengths, absent (nullable) edges,
 * join-table-style multi-parent edges, grants at every level, and a university-wide grant.
 *
 * The reference evaluator answers each row independently by collecting every vertex reachable from
 * it and taking the maximum grant level found. It is quadratic, has no memo, no batching and no
 * fixpoint, and is obviously correct by inspection — which is the only property wanted of it.
 *
 * WHAT IS COMPARED
 *
 * For each random graph, a sequence of questions is asked of ONE evaluator instance, so the memo is
 * carried across them and a memo that poisons a later answer is caught. Every question is compared
 * per row, not in aggregate: an answer that is right on average and wrong on one row is a security
 * bug, and comparing sets or counts would hide it.
 *
 * Usage: node algocheck.mjs [--graphs N] [--seed S] [--out <figures dir>]
 */
import { writeFileSync, existsSync, mkdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const HERE = dirname(fileURLToPath(import.meta.url));
const arg = (n, d) => { const i = process.argv.indexOf(n); return i > 0 && process.argv[i + 1] ? process.argv[i + 1] : d; };
// Repository layout: outputs go to ../results/replay unless --out is given.
const OUT = arg('--out', join(HERE, '..', 'results', 'replay'));
const GRAPHS = Number(arg('--graphs', '2000'));
let seed = Number(arg('--seed', '20260830'));

/** Deterministic PRNG, so a failure is reproducible from the seed printed in the output. */
const rnd = () => (seed = (seed * 1103515245 + 12345) & 0x7fffffff) / 0x7fffffff;
const pick = (a) => a[Math.floor(rnd() * a.length)];
const chance = (p) => rnd() < p;

// ── the lattice ──────────────────────────────────────────────────────────────
// Three levels, ordered as the application's AccessLevel (EDIT < FULL < MANAGE). `null` is bottom: no access.
const LEVELS = ['EDIT', 'FULL', 'MANAGE'];   // the application's AccessLevel values; no grant is null
const rank = (l) => (l === null ? -1 : LEVELS.indexOf(l));
const max = (a, b) => (rank(a) >= rank(b) ? a : b);

// ── random instance graphs ───────────────────────────────────────────────────

/**
 * A graph of typed row vertices with parent edges.
 *
 * Types matter because the algorithm batches by them, and the generator deliberately lets a type's
 * rows sit at several distances from the query so that the same type is expanded on several levels
 * — the case that broke the paper's earlier cost model and that the fixpoint has to survive.
 */
function randomGraph() {
  const typeCount = 2 + Math.floor(rnd() * 4);
  const types = Array.from({ length: typeCount }, (_, i) => `T${i}`);
  const vertices = [];
  const perType = {};
  for (const t of types) {
    perType[t] = [];
    const n = 1 + Math.floor(rnd() * 6);
    for (let i = 0; i < n; i++) {
      const v = `${t}#${i}`;
      vertices.push({ id: v, type: t, parents: [] });
      perType[t].push(v);
    }
  }
  const byId = Object.fromEntries(vertices.map((v) => [v.id, v]));

  // Edges. Unrestricted: a parent may be of any type including the vertex's own, and may point
  // back down, so cycles and self-loops occur without being special-cased into existence.
  for (const v of vertices) {
    const degree = Math.floor(rnd() * 3);          // 0..2 — 0 gives roots and absent edges
    for (let d = 0; d < degree; d++) {
      const w = pick(vertices).id;
      if (w !== v.id || chance(0.3)) {             // allow a genuine self-edge sometimes
        if (!v.parents.includes(w)) v.parents.push(w);
      }
    }
    if (chance(0.15)) v.parents.push(v.id);        // explicit self-loop
  }

  // Grants: some vertices carry one, and sometimes the caller holds a university-wide grant.
  const grants = {};
  for (const v of vertices) if (chance(0.25)) grants[v.id] = pick(LEVELS);
  const global = chance(0.15) ? pick(LEVELS) : null;

  return { types, vertices, byId, perType, grants, global };
}

// ── the reference evaluator: obviously correct, hopelessly slow ──────────────

function reference(g, type, ids) {
  const out = {};
  for (const id of ids) {
    const start = `${type}#${id}`;
    const seen = new Set([start]);
    const stack = [start];
    let best = g.global;                            // a global grant covers every row
    while (stack.length) {
      const v = stack.pop();
      best = max(best, g.grants[v] ?? null);
      for (const w of g.byId[v]?.parents ?? []) {
        if (!seen.has(w)) { seen.add(w); stack.push(w); }
      }
    }
    out[id] = best;
  }
  return out;
}

// ── Algorithm 1, transcribed from the paper ─────────────────────────────────

/**
 * One request's evaluator. `memo` lives for the life of this object, which is what makes a
 * cross-question memo defect reachable by the test.
 */
function makeEvaluator(g, maxDepth) {
  const memo = new Map();

  return function levels(type, ids) {
    const G = (v) => max(g.global, g.grants[v] ?? null);   // §7: G[v] includes the global grant
    const start = ids.map((i) => `${type}#${i}`);

    const N = new Set(start);
    const P = new Map();
    let truncated = false;

    // F is grouped by type, and only carries vertices not already resolved.
    let F = new Map();
    const notMemoised = start.filter((v) => !memo.has(v));
    if (notMemoised.length) F.set(type, new Set(notMemoised));

    let depth = 0;
    while (F.size) {
      if (depth === maxDepth) { truncated = true; break; }
      const next = new Map();
      for (const [, S] of F) {
        for (const v of S) {
          for (const w of g.byId[v]?.parents ?? []) {
            (P.get(v) ?? P.set(v, new Set()).get(v)).add(w);
            if (!N.has(w)) {
              N.add(w);
              if (!memo.has(w)) {
                const wt = g.byId[w].type;
                (next.get(wt) ?? next.set(wt, new Set()).get(wt)).add(w);
              }
            }
          }
        }
      }
      F = next;
      depth++;
    }

    const L = new Map();
    for (const v of N) L.set(v, memo.has(v) ? memo.get(v) : G(v));
    let changed = true;
    while (changed) {
      changed = false;
      for (const v of N) {
        let best = L.get(v);
        for (const w of P.get(v) ?? []) best = max(best, L.get(w) ?? null);
        if (best !== L.get(v)) { L.set(v, best); changed = true; }
      }
    }
    if (!truncated) for (const [v, l] of L) memo.set(v, l);

    const out = {};
    for (const i of ids) out[i] = L.get(`${type}#${i}`) ?? null;
    return { out, truncated };
  };
}

// ── the comparison ───────────────────────────────────────────────────────────

const MAX_DEPTH = 32;
let graphs = 0, questions = 0, rows = 0, truncations = 0;
const failures = [];

for (let n = 0; n < GRAPHS; n++) {
  const g = randomGraph();
  graphs++;
  const evaluate = makeEvaluator(g, MAX_DEPTH);

  // Several questions against one evaluator, so the memo is exercised across them, and the same
  // question is sometimes asked twice so a memo that changes an answer is caught.
  const askCount = 1 + Math.floor(rnd() * 4);
  const asked = [];
  for (let q = 0; q < askCount; q++) {
    const type = pick(g.types);
    const all = g.perType[type].map((v) => Number(v.split('#')[1]));
    const ids = all.filter(() => chance(0.7));
    if (!ids.length) continue;
    asked.push([type, ids]);
  }
  if (asked.length) asked.push(asked[0]);          // repeat the first, memoised this time

  for (const [type, ids] of asked) {
    const { out, truncated } = evaluate(type, ids);
    if (truncated) { truncations++; continue; }    // a truncated answer is allowed to be low
    const want = reference(g, type, ids);
    questions++;
    for (const id of ids) {
      rows++;
      if (out[id] !== want[id]) {
        failures.push({ seed, type, id, got: out[id], want: want[id], graph: n });
      }
    }
  }
}

// ── the depth backstop, deliberately reached ─────────────────────────────────
// The random graphs above are small, so none of them reaches MAX_DEPTH. This deterministic case
// does: a chain C#0 -> C#1 -> ... -> C#40 with the only grant (MANAGE) on C#40 and EDIT on C#5.
// From C#0 the walk must stop at the backstop and may under-approximate (EDIT instead of MANAGE),
// and nothing it computed may be memoised: a later question about C#10, whose ancestry fits within
// the backstop, must still get MANAGE from the same evaluator.
const backstop = (() => {
  const len = 40;
  const vertices = Array.from({ length: len + 1 }, (_, i) => ({ id: `C#${i}`, type: 'C', parents: i < len ? [`C#${i + 1}`] : [] }));
  const g = { types: ['C'], vertices, byId: Object.fromEntries(vertices.map((v) => [v.id, v])),
              perType: { C: vertices.map((v) => v.id) }, grants: { 'C#40': 'MANAGE', 'C#5': 'EDIT' }, global: null };
  const evaluate = makeEvaluator(g, MAX_DEPTH);
  const first = evaluate('C', [0]);
  const later = evaluate('C', [10]);
  const ok = first.truncated && rank(first.out[0]) <= rank(reference(g, 'C', [0])[0])
    && !later.truncated && later.out[10] === reference(g, 'C', [10])[10];
  return { ok, first: first.out[0], firstTruncated: first.truncated, later: later.out[10] };
})();
console.log(`depth backstop case: truncated=${backstop.firstTruncated} (answer ${backstop.first}, under-approximation allowed); ` +
  `later question within the backstop answered ${backstop.later} — ${backstop.ok ? 'OK' : 'FAILED'}`);
if (!backstop.ok) { console.error('the depth-backstop case failed'); process.exit(1); }

console.log(`${graphs} random instance graphs, ${questions} questions, ${rows} row answers`);
console.log(`${truncations} evaluation(s) hit the depth backstop and were not compared`);
if (failures.length) {
  console.error(`\n${failures.length} DISAGREEMENT(S) with the reference evaluator:`);
  for (const f of failures.slice(0, 10)) {
    console.error(`  graph ${f.graph}: ${f.type}#${f.id} — algorithm ${f.got}, reference ${f.want}`);
  }
  process.exit(1);
}
console.log('Algorithm 1 agreed with the reference evaluator on every row of every question.');

mkdirSync(OUT, { recursive: true });
const n = (x) => x.toLocaleString('en-US').replace(/,/g, '\\,');
writeFileSync(join(OUT, 'algo-facts.tex'),
  '% generated by algocheck.mjs\n' +
  `\\newcommand{\\algoGraphs}{${n(graphs)}}\n` +
  `\\newcommand{\\algoQuestions}{${n(questions)}}\n` +
  `\\newcommand{\\algoRows}{${n(rows)}}\n`);
console.log(`wrote ${OUT}/algo-facts.tex`);
