#!/usr/bin/env node
/**
 * The permission-cascade graph, and what walking it costs.
 *
 * The graph below is read from `permission-edges.tsv`, which permedges.mjs PARSES out of the
 * `@PermissionParent` / `@PermissionJoinParent` annotations in `org.lnu.timetable.domain`. It used
 * to be transcribed by hand, on the argument that a table a reader can diff against the source is
 * more checkable than a parser nobody runs again; that argument did not survive a rename in the
 * application, so the table is still emitted for diffing but nobody types it any more.
 *
 * What this emits is the SHAPE of that graph: node and edge counts, how many edges are optional or
 * run through a join table, how many entities have more than one parent, and the longest simple
 * path. It no longer models what walking the graph costs. It used to, from the schema alone, and
 * that was wrong in a way no amount of care with the formula could fix: the evaluator's frontier
 * holds rows, so how many levels a type appears on is a fact about the data. instancegraph.mjs
 * replays the walk over the deployed dump and owns every cost figure the paper prints.
 */
import { readFileSync, writeFileSync, mkdirSync, existsSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

/**
 * The output directory, resolved from THIS SCRIPT'S location rather than hard-coded.
 *
 * It used to be an absolute `/home/claude/articles/...`, which is the path inside the container the
 * papers were written in and nowhere else. Running the collection's own rebuild on a different
 * machine on 2026-08-15 is what exposed it: six scripts failed with EACCES or ENOENT on a directory
 * that does not exist there, while the other thirteen — which already resolved from `import.meta.url`
 * — worked. No check inside the container could have found this, because in the container the
 * hard-coded path was the correct answer.
 */
const HERE_DIR = dirname(fileURLToPath(import.meta.url));

// Repository layout: outputs go to ../results/replay unless a directory is given.
const OUT = process.argv[2] ?? join(HERE_DIR, '..', 'results', 'replay');
mkdirSync(OUT, { recursive: true });

/**
 * The permission cascade, read from `permission-edges.tsv` beside this script.
 *
 * It used to be a literal here. It is a data file because the paper's threats section says the
 * transcribed edge set is published so that a reader can diff it against the application source,
 * and an object literal inside an analysis script is a worse thing to diff than a table: the
 * reader has to strip syntax that carries no information about the schema. The extraction is
 * mechanical and the emitted figures are unchanged by it.
 *
 * Shape, once parsed: child -> [[parent, kind, nullable]]. `kind: 'join'` is a many-to-many edge.
 */
const TSV = readFileSync(new URL('permission-edges.tsv', import.meta.url), 'utf8');

/**
 * The stamp that ties this run's output files together.
 *
 * permedges.mjs writes the same digest into source-facts.tex, and checkfacts.mjs requires every
 * generated file to carry one identical value. That is not decoration: a submitted bundle once
 * contained a trace-facts.tex left over from the previous revision, holding numbers the PDF and
 * the other generated files had already moved past. Each file was individually well-formed, so
 * nothing failed; what was wrong was that they had not been produced together.
 */
const STAMP = `% edges: ${createHash('sha256').update(TSV).digest('hex').slice(0, 12)}`;

const EDGES = (() => {
  const out = {};
  let n = 0;
  for (const line of TSV.split('\n')) {
    const row = line.trim();
    if (!row || row.startsWith('#')) continue;
    const [child, parent, kind, nullable, authority, table, key, ref] = row.split('\t');
    if (!child || !parent || !kind || nullable === undefined || authority === undefined
        || !table || !key || !ref) {
      throw new Error(`permission-edges.tsv: malformed row "${row}"`);
    }
    if (kind !== 'fk' && kind !== 'join') throw new Error(`unknown edge kind "${kind}"`);
    if (nullable !== 'true' && nullable !== 'false') throw new Error(`bad nullable "${nullable}"`);
    (out[child] ??= []).push([parent, kind, nullable === 'true', authority === 'true']);
    n++;
  }
  if (n === 0) throw new Error('permission-edges.tsv held no edges');
  return out;
})();

// The entity total used to be a literal 36 here. It was wrong — the source carries 32 — and it
// was wrong in the abstract, the introduction and the startup figure with it. It is now emitted by
// permedges.mjs, which counts the classes rather than remembering them.
const parentsOf = (t) => (EDGES[t] ?? []).map(([p, kind]) => ({ p, kind }));

// ── graph statistics ───────────────────────────────────────────────────────
const nodes = new Set(Object.keys(EDGES));
for (const es of Object.values(EDGES)) for (const [p] of es) nodes.add(p);
const edgeCount = Object.values(EDGES).reduce((s, e) => s + e.length, 0);
const joinEdges = Object.values(EDGES).flat().filter(([, k]) => k === 'join').length;
const nullableEdges = Object.values(EDGES).flat().filter(([, , n]) => n).length;
// Edges that confer a path downward without conferring ownership, so that pointing one somewhere
// new is not a transfer. Mutation-only; the cascade treats both kinds identically.
const sharedEdges = Object.values(EDGES).flat().filter(([, , , a]) => !a).length;
const multiParent = Object.entries(EDGES).filter(([, e]) => e.length > 1).length;
const selfRef = Object.entries(EDGES).filter(([c, e]) => e.some(([p]) => p === c)).map(([c]) => c);

/** Longest simple upward path from each node — the depth the walk actually has to cover. */
function longestPath(start) {
  let best = 0;
  const walk = (t, seen, d) => {
    best = Math.max(best, d);
    for (const { p } of parentsOf(t)) {
      if (seen.has(p)) continue;      // the visited-set, exactly as the implementation does it
      seen.add(p); walk(p, seen, d + 1); seen.delete(p);
    }
  };
  walk(start, new Set([start]), 0);
  return best;
}
const depths = [...nodes].map((t) => ({ t, d: longestPath(t) })).sort((a, b) => b.d - a.d);

// ── this script's remaining job ────────────────────────────────────────────
//
// It used to also model what walking the graph costs, from the schema alone. That model is gone:
// the frontier the evaluator advances holds ROWS, so the schema cannot say how many levels a type
// appears on, and two successive attempts to bracket the answer with schema-only "scenarios"
// produced numbers that were not bounds. instancegraph.mjs replays the walk over the deployed data
// dump instead, and owns queries.tex, trace.tex and trace-facts.tex. What is left here is what the
// schema really does determine: the shape of the graph, and the longest simple path in it.

/** The longest simple upward path from a type, in edges — a property of G_S, not of the data. */
function longestPathFrom(t) { return longestPath(t); }

writeFileSync(`${OUT}/graph-facts.tex`,
  `% generated by permgraph.mjs\n${STAMP}\n` +
  `\\newcommand{\\permNodes}{${nodes.size}}\n` +
  `\\newcommand{\\permEdges}{${edgeCount}}\n` +
  `\\newcommand{\\permJoinEdges}{${joinEdges}}\n` +
  `\\newcommand{\\permNullableEdges}{${nullableEdges}}\n` +
  `\\newcommand{\\permSharedEdges}{${sharedEdges}}\n` +
  `\\newcommand{\\permMultiParent}{${multiParent}}\n` +
  `\\newcommand{\\permDepth}{${depths[0].d}}\n` +
  `\\newcommand{\\permDeepest}{\\texttt{${depths[0].t}}}\n` +
  `\\newcommand{\\traceLongestPath}{${longestPathFrom('TimetableEntry')}}\n`);

console.log(`nodes ${nodes.size}, edges ${edgeCount} (${joinEdges} join, ${nullableEdges} nullable)`);
console.log(`entities with more than one parent: ${multiParent}; self-referential: ${selfRef.join(', ') || 'none'}`);
console.log(`deepest chain: ${depths[0].d} edges from ${depths[0].t}`);
console.log('top depths:', depths.slice(0, 5).map((x) => `${x.t}=${x.d}`).join(', '));
console.log(`wrote ${OUT}/graph-facts.tex`);
