#!/usr/bin/env node
/**
 * permedges.mjs — derive the permission cascade from the application source.
 *
 * WHY THIS EXISTS. `permission-edges.tsv` was transcribed by hand on 2026-08-12, and the paper's
 * threats section said so, offering the file for a reader to diff against the source. That was the
 * honest thing to do while the Java lived on a different machine from the scripts. It is no longer
 * the best thing to do: a transcription is a copy that ages, and this one aged. Between the
 * transcription and today the application renamed an entity — `Specialty` became `DegreeProgram`,
 * migration `V8__rename_specialties_to_degree_programs.sql` — and the paper went on printing the
 * old name in a table captioned as a transcription of the source.
 *
 * So the edge set is now PARSED. The .tsv is still written, still reviewable and still the input to
 * `permgraph.mjs`; what changed is that a script produces it and a human no longer can.
 *
 * WHAT IT REFUSES TO DO. It does not overwrite silently. Run without `--write` it parses, compares
 * against the committed .tsv and reports the difference; the paper's numbers do not move until
 * someone has read that difference. A regenerator that quietly rewrites the file it is checking is
 * the failure this collection's measurement paper calls "a filter that is a no-op on today's data".
 *
 *   node permedges.mjs [--src <path to lnu-timetable>] [--write]
 *
 * The default source path is the sandbox checkout; pass --src for a copy elsewhere.
 */
import { readFileSync, writeFileSync, readdirSync, existsSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const HERE = dirname(fileURLToPath(import.meta.url));
const argv = process.argv.slice(2);
const arg = (name, fallback) => {
  const i = argv.indexOf(name);
  return i >= 0 && argv[i + 1] ? argv[i + 1] : fallback;
};
const WRITE = argv.includes('--write');

// Repository layout: the application sources are in ../app (pass --src for another checkout).
const SRC = arg('--src', join(HERE, '..', 'app'));
const DOMAIN = join(SRC, 'src/main/java/org/lnu/timetable/domain');
const FRAMEWORK = join(SRC, 'src/main/java/org/lnu/timetable/framework');
const CONFIG = join(SRC, 'src/main/java/org/lnu/timetable/config');
for (const [label, p] of [['domain', DOMAIN], ['framework', FRAMEWORK], ['config', CONFIG]]) {
  if (!existsSync(p)) throw new Error(`${label} sources not found at ${p} — pass --src`);
}

const javaFiles = (dir) => readdirSync(dir, { withFileTypes: true })
  .flatMap((e) => (e.isDirectory() ? javaFiles(join(dir, e.name))
    : e.name.endsWith('.java') ? [join(dir, e.name)] : []));

// ── parse the domain classes ─────────────────────────────────────────────────
//
// Every regex below is checked against a count of the raw annotation occurrences afterwards. A
// pattern that silently matches nothing is the defect this whole collection is about: an earlier
// version of the thesis's equivalent parser missed `@PermissionParent(value = X.class, ...)`
// because it only allowed the shorthand form, and reported a cascade of ZERO edges without
// erroring.

const ENTITY_RE = /@GraphQLEntity\s*\(([^)]*)\)/;
const PARENT_RE = /@PermissionParent\s*\(\s*(?:value\s*=\s*)?([A-Za-z0-9_]+)\.class([^)]*)\)/g;
const JOIN_RE = /@PermissionJoinParent\s*\(\s*(?:value\s*=\s*)?([A-Za-z0-9_]+)\.class([^)]*)\)/g;
const ROOT_RE = /@PermissionRoot\b/;

const entities = [];          // { name, table, root, parents: [{parent, kind, nullable}] }
let rawParents = 0, rawJoins = 0;

/**
 * Comments are stripped before anything is matched.
 *
 * Not defensive tidying — the first run of this script died on it. `LecturerWorkloadOnlineClass`
 * discusses `@GraphQLEntity(key = ...)` in its Javadoc, three lines above the real annotation, and
 * the pattern matched the prose first and found no `table`. It threw, which is the good case; had
 * the sentence happened to contain a plausible `table = "..."` it would have parsed the comment as
 * the schema. The same hazard applies to a commented-out `@PermissionParent`, which would otherwise
 * add an edge the running system does not have.
 */
const stripComments = (s) => s
  .replace(/\/\*[\s\S]*?\*\//g, '')
  .replace(/^\s*\/\/.*$/gm, '');

for (const file of javaFiles(DOMAIN).sort()) {
  const src = stripComments(readFileSync(file, 'utf8'));
  const name = file.split('/').pop().replace(/\.java$/, '');
  const ent = ENTITY_RE.exec(src);
  if (!ent) continue;                       // not an exposed entity
  const table = /table\s*=\s*"([^"]+)"/.exec(ent[1])?.[1] ?? null;
  if (!table) throw new Error(`${name}: @GraphQLEntity without a table`);
  // The key column defaults to `id`; @GraphQLEntity(key = "...") names a real one for the entity
  // whose identity is its parent's key. instancegraph.mjs needs it to read rows out of data.sql.
  const key = /key\s*=\s*"([^"]+)"/.exec(ent[1])?.[1] ?? 'id';

  rawParents += (src.match(/@PermissionParent\s*\(/g) || []).length;
  rawJoins += (src.match(/@PermissionJoinParent\s*\(/g) || []).length;

  const attr = (rest, key) => new RegExp(`${key}\\s*=\\s*"([^"]+)"`).exec(rest)?.[1] ?? null;
  const parents = [];
  for (const [, parent, rest] of src.matchAll(PARENT_RE)) {
    const joinColumn = attr(rest, 'joinColumn');
    if (!joinColumn) throw new Error(`${name}: @PermissionParent(${parent}) without a joinColumn`);
    parents.push({ parent, kind: 'fk', nullable: /nullable\s*=\s*true/.test(rest),
      authority: !/authority\s*=\s*false/.test(rest), ref: joinColumn });
  }
  for (const [, parent, rest] of src.matchAll(JOIN_RE)) {
    const joinTable = attr(rest, 'joinTable');
    const selfColumn = attr(rest, 'selfColumn');
    const parentColumn = attr(rest, 'parentColumn');
    if (!joinTable || !selfColumn || !parentColumn) {
      throw new Error(`${name}: @PermissionJoinParent(${parent}) missing joinTable/selfColumn/parentColumn`);
    }
    parents.push({ parent, kind: 'join', nullable: /nullable\s*=\s*true/.test(rest),
      authority: !/authority\s*=\s*false/.test(rest),
      ref: `${joinTable}:${selfColumn}:${parentColumn}` });
  }
  entities.push({ name, table, key, root: ROOT_RE.test(src), parents });
}

if (!entities.length) throw new Error('no @GraphQLEntity classes parsed — the pattern changed');

const parsedFk = entities.reduce((a, e) => a + e.parents.filter((p) => p.kind === 'fk').length, 0);
const parsedJoin = entities.reduce((a, e) => a + e.parents.filter((p) => p.kind === 'join').length, 0);
if (parsedFk !== rawParents) {
  throw new Error(`parsed ${parsedFk} @PermissionParent but the files hold ${rawParents}`);
}
if (parsedJoin !== rawJoins) {
  throw new Error(`parsed ${parsedJoin} @PermissionJoinParent but the files hold ${rawJoins}`);
}

// Every named parent must itself be an entity: a typo'd class name would otherwise become a node
// with no annotations and a silently shorter cascade.
const known = new Set(entities.map((e) => e.name));
for (const e of entities) {
  for (const p of e.parents) {
    if (!known.has(p.parent)) throw new Error(`${e.name} names parent ${p.parent}, which is not an entity`);
  }
}

// ── the .tsv, in the shape permgraph.mjs already reads ───────────────────────

const HEADER = `# The permission cascade of the deployed schema.
#
# GENERATED by permedges.mjs from the @PermissionParent / @PermissionJoinParent annotations on the
# domain classes. It was transcribed by hand until 2026-08-29, which is how the paper came to print
# "Specialty" for eight days after the application renamed it to "DegreeProgram". Do not edit:
# re-run the script, read the diff it prints, then pass --write.
#
#   child<TAB>parent<TAB>kind<TAB>nullable<TAB>authority<TAB>childTable<TAB>childKey<TAB>ref
#
# kind is "fk" for a foreign key and "join" for a many-to-many edge; nullable is the FK's
# nullability, which is what decides whether an unauthorised parent removes the child or is
# simply absent from it.
#
# "authority" is false for an edge that confers a path downward without conferring ownership — a
# shared resource the child merely references. It changes nothing about the cascade or the
# traversal; it decides only whether a mutation that points the edge somewhere new must be
# authorized against the destination.
#
# The last three columns are the physical names the edge is realised by: the child's table and key
# column, and then either the foreign-key column (fk) or joinTable:selfColumn:parentColumn (join).
# They are here so that instancegraph.mjs can build the ROW-instance graph out of the deployed
# data dump; without them the analysis can only ever be about types, which is precisely the gap a
# reviewer identified in the counts this collection publishes.`;

const rows = entities.flatMap((e) =>
  e.parents.map((p) => `${e.name}\t${p.parent}\t${p.kind}\t${p.nullable}\t${p.authority}` +
    `\t${e.table}\t${e.key}\t${p.ref}`));
const tsv = `${HEADER}\n${rows.join('\n')}\n`;

// ── compare with what is committed ───────────────────────────────────────────

const TSV_PATH = join(HERE, 'permission-edges.tsv');
const oldRows = existsSync(TSV_PATH)
  ? readFileSync(TSV_PATH, 'utf8').split('\n').map((l) => l.trim())
      .filter((l) => l && !l.startsWith('#'))
  : [];
const newRows = rows.slice();
const key = (r) => r;
const removed = oldRows.filter((r) => !newRows.includes(key(r)));
const added = newRows.filter((r) => !oldRows.includes(key(r)));

console.log(`parsed ${entities.length} @GraphQLEntity classes from ${DOMAIN}`);
console.log(`  ${parsedFk} foreign-key edges, ${parsedJoin} join-table edges, ` +
  `${entities.filter((e) => e.root).length} root(s)`);
if (!removed.length && !added.length) {
  console.log('permission-edges.tsv already matches the source: no drift');
} else {
  console.log(`\nDRIFT against the committed permission-edges.tsv ` +
    `(${removed.length} gone, ${added.length} new):`);
  for (const r of removed) console.log(`  - ${r.replace(/\t/g, '  ')}`);
  for (const r of added) console.log(`  + ${r.replace(/\t/g, '  ')}`);
  if (!WRITE) console.log('\n(nothing written — pass --write once the difference above is understood)');
}
if (WRITE) {
  writeFileSync(TSV_PATH, tsv);
  console.log(`\nwrote ${TSV_PATH}`);
}

// ── source metrics the paper quotes ──────────────────────────────────────────

const loc = (files) => files.reduce((a, f) =>
  a + readFileSync(f, 'utf8').split('\n').filter((l) => l.trim()).length, 0);
/**
 * The size of the annotation vocabulary, counted rather than remembered.
 *
 * The paper says the policy is declared by "N attributes across two annotations". That N was typed
 * in, in four places, and went stale the moment `authority` was added — which is the same failure
 * as the entity count that was wrong by four for eight days. It is now read out of the annotation
 * declarations: every `T name();` member of the two permission annotations, counted once per
 * distinct name, since `value` and `authority` appear in both.
 */
const permissionAttributes = (() => {
  const names = new Set();
  for (const file of ['PermissionParent.java', 'PermissionJoinParent.java']) {
    const path = join(FRAMEWORK, 'annotation', file);
    const src = stripComments(readFileSync(path, 'utf8'));
    const body = src.slice(src.indexOf('{'));
    for (const m of body.matchAll(/^\s*(?:[A-Za-z<>?,\s.]+?)\s+([a-zA-Z]\w*)\s*\(\s*\)\s*(?:default[^;]*)?;/gm)) {
      names.add(m[1]);
    }
  }
  if (names.size < 4) throw new Error(`only ${names.size} annotation attributes parsed — the pattern broke`);
  return names;
})();

const fw = javaFiles(FRAMEWORK);
const cfg = javaFiles(CONFIG).filter((f) => /SchemaConfig\.java$/.test(f));

const cfgSrc = cfg.map((f) => readFileSync(f, 'utf8')).join('\n');
const count = (re) => (cfgSrc.match(re) || []).length;
const metrics = {
  entities: entities.length,
  frameworkFiles: fw.length,
  frameworkLines: loc(fw),
  declarationFiles: cfg.length,
  declarationLines: loc(cfg),
  types: count(/\bs\.type\s*\(/g),
  queries: count(/\bs\.query\s*\(/g),
  mutations: count(/\bs\.mutation\s*\(/g),
  paged: count(/\.connection\s*\(/g),
  relationFilters: count(/\.relationFilter\s*\(/g)
};

/**
 * The other escape hatch, and the one the paper did not have when it was written.
 *
 * `relationFilter` escapes inside a generated query; `HandWrittenApi` escapes the generated
 * machinery altogether, for an area that is not a row keyed by an id. Counting its implementations
 * is the only honest way to report it: an escape hatch is characterised by how often it is reached
 * for, and a paper that describes one without saying how much of the surface went through it is
 * describing an intention rather than a system.
 *
 * Scanned over the whole application package, because the implementations deliberately live beside
 * the areas they serve rather than in the framework.
 */
const APP = join(SRC, 'src/main/java/org/lnu/timetable');
const hand = javaFiles(APP)
  .filter((f) => /implements\s+HandWrittenApi\b/.test(stripComments(readFileSync(f, 'utf8'))))
  .sort();
if (!hand.length) throw new Error('no HandWrittenApi implementations found — the pattern changed');
metrics.handWrittenAreas = hand.length;
metrics.handWrittenLines = loc(hand);
metrics.handWrittenNames = hand.map((f) => f.split('/').pop().replace(/\.java$/, ''));
for (const [k, v] of Object.entries(metrics)) {
  if (Array.isArray(v) ? !v.length : !v) {
    throw new Error(`metric ${k} came out empty — its pattern no longer matches`);
  }
}
console.log('\nsource metrics:', metrics);

const WORD = ['zero', 'one', 'two', 'three', 'four', 'five', 'six', 'seven', 'eight', 'nine', 'ten'];
/**
 * A stamp identifying the run that produced this bundle.
 *
 * The reason it exists: a reviewer received a submission in which trace-facts.tex still held the
 * previous revision's numbers while the PDF and the other generated files held the current ones.
 * Nothing in the build could notice, because each file is individually well-formed — what was
 * wrong was that they did not come from one run. The stamp is the edge-set digest every generator
 * derives its numbers from, so checkfacts.mjs can require the whole bundle to agree on it, and a
 * file left behind by an earlier run fails loudly instead of being read as current.
 */
const STAMP = `% edges: ${createHash('sha256').update(tsv).digest('hex').slice(0, 12)}`;

const facts = ['% generated by permedges.mjs — do not edit', STAMP,
  `\\newcommand{\\srcFramework}{${metrics.frameworkLines.toLocaleString('en-US').replace(',', '\\,')}}`,
  `\\newcommand{\\srcFiles}{${metrics.frameworkFiles}}`,
  `\\newcommand{\\srcDeclaration}{${metrics.declarationLines.toLocaleString('en-US').replace(',', '\\,')}}`,
  `\\newcommand{\\srcConfigClasses}{${WORD[metrics.declarationFiles] ?? metrics.declarationFiles}}`,
  `\\newcommand{\\srcTypes}{${metrics.types}}`,
  `\\newcommand{\\srcQueries}{${metrics.queries}}`,
  `\\newcommand{\\srcMutations}{${metrics.mutations}}`,
  `\\newcommand{\\srcPaged}{${metrics.paged}}`,
  `\\newcommand{\\srcRelationFilters}{${metrics.relationFilters}}`,
  // permgraph.mjs used to emit this as a literal 36. It is 32, and it is counted here.
  `\\newcommand{\\permEntities}{${metrics.entities}}`,
  `\\newcommand{\\permAttributes}{${WORD[permissionAttributes.size] ?? permissionAttributes.size}}`,
  `\\newcommand{\\srcHandAreas}{${WORD[metrics.handWrittenAreas] ?? metrics.handWrittenAreas}}`,
  `\\newcommand{\\srcHandLines}{${metrics.handWrittenLines.toLocaleString('en-US').replace(',', '\\\\,')}}`
].join('\n') + '\n';

const OUT = join(HERE, '..', 'results', 'replay', 'source-facts.tex');
if (WRITE) { writeFileSync(OUT, facts); console.log(`wrote ${OUT}`); }
else console.log(`\n(source-facts.tex not written; --write to emit)\n${facts}`);
