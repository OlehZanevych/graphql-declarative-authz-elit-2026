# graphql-declarative-authz-elit-2026

Code, data, scripts and results for the article

> O. Zanevych, V. Kukharskyy. *Generating a GraphQL Surface and Its Authorization from One Declarative
> Entity Specification.* Electronics and Information Technologies (ELIT), 2026 (revision 2).

The repository holds:

- **the GraphQL framework and the application built on it** (`app/`): the LNU Timetable service, a
  Spring Boot 4 / WebFlux / Spring for GraphQL / R2DBC application. Its framework (`app/src/main/java/org/lnu/timetable/framework`)
  generates the GraphQL schema, the SQL handlers and the cascading authorization from annotated entities
  (`@GraphQLEntity`, `@PermissionParent`, `@PermissionJoinParent`, `@PermissionRoot`). The set-at-a-time
  permission evaluator the article describes is `app/src/main/java/org/lnu/timetable/security/PermissionEvaluator.java`;
- **the experiment code** (`app/src/experiments/java`), compiled into the same jar by the Maven profile
  `experiments`: the benchmark harness, the per-row baseline, the TCP delay relay, the ITC 2019 entities and
  the consistency prototype with its tests. These are evaluation components; they are not part of the
  released application;
- **all input data** (`data/`), **all scripts** (`scripts/`, `replay/`, `analysis/`) and **all results**
  (`results/`) of the latest run.

Licence: [PolyForm Noncommercial 1.0.0](LICENSE.md) (data and third-party components keep their own
terms; see the licence file).

**Versions.** The article cites this repository at the release tag `elit-2026-rev2`. Three revisions are
involved and kept apart: the application source (LNU Timetable commit `6c2a082`, identical to `6ec3e9a` for
`app/`), the evaluation code and data (this repository at that tag), and the collection date of the raw
measurements (2026-11-08).

**Revision note (2026-11-08, after review).** The replayed per-row count (`replay/instancegraph.mjs`,
`walkPerRow`) used to charge one statement per populated parent link, which gave 1 017, 52 044, 19 172 and
172 for the four requests of article Table 2. It now charges what the executed baseline
(`PerRowEvaluator`) issues: per visited row, one statement for all foreign-key parent columns of its table
and one per join-table parent edge, which gives 812, 43 033, 16 192 and 152, identical to the counts
measured on PostgreSQL. The set-at-a-time counts are unchanged. `replay/algocheck.mjs` now uses the
application's three access levels (it had an extra `VIEW` level) and adds a case that reaches the depth
backstop; its totals are unchanged. Only the replay and the analysis were rerun for this note; the timed
measurements are those collected on 2026-11-08.

## Summary of the results

All numbers below and in the article come from the files in `results/`, produced on 2026-11-08 by one pass
of the scripts in the order of `scripts/run-all.sh`, on the environment listed at the end of this section.
`scripts/analyse.sh` regenerates the section. The jar was built from this repository with
`scripts/build.sh -o -Djava.version=21` (JDK 21 compiler, `--release 21`; no JDK 25 compiler was available
in the evaluation environment) and run on a Java 25.0.2 runtime; the project itself targets Java 25.

<!-- results:begin -->
### Statement counts replayed on the snapshot (article Table 2)

Exact counts of the statements each strategy issues for the question "which of these rows may this caller edit?", replayed by `replay/instancegraph.mjs` over `data/lnu/data-anonymised.sql`. The per-row model charges what the executed baseline issues: per visited row, one statement for all foreign-key parent columns of its table (if any) and one per join-table parent edge; the counts equal those measured on PostgreSQL below.

| Request | Rows | Per-row statements | Set-at-a-time statements | Ratio |
|---|---:|---:|---:|---:|
| Course | 200 | 812 | 6 | 135× |
| TimetableEntry | 1 381 | 43 033 | 25 | 1 721× |
| LecturerWorkload | 500 | 16 192 | 23 | 704× |
| LecturerWorkloadCandidate | 6 | 152 | 18 | 8× |

### Level-by-level statement trace of the TimetableEntry request (§7.5; Table 4 of the reviewed version)

All 1 381 timetable entries; level 0 is the two setup statements (the caller's memberships and grants).

| Level | Types expanded | Statements | Cumulative |
|---:|---|---:|---:|
| 0 | *the caller's grants* | 2 | 2 |
| 1 | TimetableEntry | 1 | 3 |
| 2 | LecturerWorkload, Room | 5 | 8 |
| 3 | WorkingCurriculumItem, Lecturer, AcademicGroup, CombinedWorkingCurriculumItem, Faculty | 5 | 13 |
| 4 | Department, CurriculumItemHours, Course, DegreeProgram, WorkingCurriculumItem | 5 | 18 |
| 5 | Faculty, CurriculumItem, Course, CurriculumItemHours | 4 | 22 |
| 6 | Course, CurriculumItem | 2 | 24 |
| 7 | Course | 1 | 25 |

### Algorithm 1 check (§6.3)

Algorithm 1 against a reference evaluator (`replay/algocheck.mjs`): 2 000 random instance graphs, 6 775 questions, 17 942 row answers; all agree. A separate 40-link chain reaches the depth backstop: the truncated answer only under-approximates and is not memoised, and a later question within the backstop gets the exact level (OK).

### Measured latency on the snapshot, co-located with the database (article Table 3)

5 JVM runs × 30 measured pairs after 10 warm-up rounds; mismatching answers between the strategies: 0. Speedup = geometric mean over runs of the per-run median of paired per-row/set-at-a-time ratios, with a 95 % t-interval (n = runs).

| Request | Rows | Statements set / per-row | Median ms set / per-row | Speedup | 95% CI | Pairs per-row faster |
|---|---:|---:|---:|---:|---:|---:|
| Course | 1 | 6 / 6 | 1.38 / 1.44 | 1.00× | 0.90–1.12 | 78 of 150 |
| Course | 10 | 6 / 42 | 1.55 / 4.29 | 2.92× | 2.73–3.12 | 3 of 150 |
| Course | 50 | 6 / 192 | 1.86 / 16.4 | 9.04× | 8.47–9.65 | 0 of 150 |
| Course | 200 | 6 / 812 | 2.92 / 60.0 | 20.6× | 19.1–22.3 | 0 of 150 |
| LecturerWorkload | 1 | 16 / 62 | 2.98 / 6.47 | 2.22× | 2.09–2.35 | 0 of 150 |
| LecturerWorkload | 10 | 16 / 494 | 3.82 / 39.6 | 10.4× | 9.77–11.0 | 0 of 150 |
| LecturerWorkload | 50 | 18 / 2 576 | 6.67 / 193 | 28.4× | 26.8–30.1 | 0 of 150 |
| LecturerWorkload | 200 | 23 / 8 980 | 15.8 / 675 | 42.3× | 39.7–45.2 | 0 of 150 |
| LecturerWorkload | 500 | 23 / 16 192 | 19.8 / 1 205 | 60.3× | 58.2–62.6 | 0 of 150 |
| LecturerWorkloadCandidate | 1 | 17 / 28 | 2.92 / 3.81 | 1.31× | 1.23–1.39 | 27 of 150 |
| LecturerWorkloadCandidate | 6 | 18 / 152 | 3.43 / 15.1 | 4.37× | 3.85–4.95 | 0 of 150 |
| TimetableEntry | 1 | 18 / 38 | 3.06 / 4.54 | 1.47× | 1.41–1.53 | 10 of 150 |
| TimetableEntry | 10 | 24 / 371 | 4.44 / 32.5 | 7.17× | 6.88–7.46 | 0 of 150 |
| TimetableEntry | 50 | 24 / 2 014 | 5.71 / 150 | 25.7× | 24.4–27.0 | 0 of 150 |
| TimetableEntry | 200 | 25 / 7 559 | 10.7 / 566 | 53.5× | 51.6–55.4 | 0 of 150 |
| TimetableEntry | 1 381 | 25 / 43 033 | 32.7 / 3 226 | 99.2× | 96.1–102 | 0 of 150 |

### Measured latency on the snapshot, through the relay (250 µs each way) (article Table 3)

5 JVM runs × 30 measured pairs after 10 warm-up rounds; mismatching answers between the strategies: 0. Speedup = geometric mean over runs of the per-run median of paired per-row/set-at-a-time ratios, with a 95 % t-interval (n = runs).

| Request | Rows | Statements set / per-row | Median ms set / per-row | Speedup | 95% CI | Pairs per-row faster |
|---|---:|---:|---:|---:|---:|---:|
| Course | 1 | 6 / 6 | 5.91 / 5.98 | 1.01× | 0.99–1.02 | 71 of 150 |
| Course | 10 | 6 / 42 | 6.17 / 9.86 | 1.59× | 1.52–1.67 | 5 of 150 |
| Course | 50 | 6 / 192 | 6.31 / 28.4 | 4.52× | 4.32–4.72 | 0 of 150 |
| Course | 200 | 6 / 812 | 7.33 / 97.9 | 13.3× | 12.9–13.8 | 0 of 150 |
| LecturerWorkload | 1 | 16 / 62 | 10.3 / 16.2 | 1.57× | 1.51–1.63 | 1 of 150 |
| LecturerWorkload | 10 | 16 / 494 | 10.8 / 68.3 | 6.17× | 5.91–6.45 | 0 of 150 |
| LecturerWorkload | 50 | 18 / 2 576 | 13.7 / 305 | 21.8× | 21.1–22.6 | 0 of 150 |
| LecturerWorkload | 200 | 23 / 8 980 | 22.3 / 1 021 | 45.5× | 43.9–47.1 | 0 of 150 |
| LecturerWorkload | 500 | 23 / 16 192 | 26.5 / 1 853 | 67.8× | 66.8–68.7 | 0 of 150 |
| LecturerWorkloadCandidate | 1 | 17 / 28 | 9.41 / 11.8 | 1.24× | 1.19–1.30 | 7 of 150 |
| LecturerWorkloadCandidate | 6 | 18 / 152 | 10.6 / 27.3 | 2.55× | 2.44–2.67 | 0 of 150 |
| TimetableEntry | 1 | 18 / 38 | 10.4 / 13.7 | 1.30× | 1.27–1.33 | 2 of 150 |
| TimetableEntry | 10 | 24 / 371 | 12.6 / 54.5 | 4.26× | 4.16–4.36 | 0 of 150 |
| TimetableEntry | 50 | 24 / 2 014 | 13.6 / 239 | 17.5× | 16.8–18.2 | 0 of 150 |
| TimetableEntry | 200 | 25 / 7 559 | 18.4 / 871 | 46.6× | 44.9–48.4 | 0 of 150 |
| TimetableEntry | 1 381 | 25 / 43 033 | 41.1 / 4 882 | 117× | 115–120 | 0 of 150 |

### ITC 2019 instances used (§7.7)

One instance per contributing university. Source files: `data/itc2019/instances.tsv` (URL and git blob SHA-1); structure counted by `data/itc2019/extract.py`.

| Instance | Set | Git blob SHA-1 | Courses | Configs | Subparts | Classes | Rooms | Class–room rows | Classes with parent | Max parent chain |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| agh-ggis-spr17 | early | `cd65580c6e1f034d36f70e69fedbe04aa823358e` | 272 | 272 | 1 224 | 1 852 | 44 | 12 377 | 353 | 2 |
| bet-fal17 | early | `99f1f7d3ed4a0b8f29251f96d25d84ff6ecd30ee` | 353 | 398 | 633 | 983 | 62 | 21 971 | 331 | 4 |
| iku-fal17 | early | `f6635d6d9cd9ae1ee6a4b055f2df39e3f1630907` | 1 206 | 1 211 | 1 636 | 2 641 | 214 | 73 681 | 899 | 2 |
| lums-spr18 | middle | `34ed74e6bb007f940942c8d8c6abc1fb908c0ce9` | 313 | 313 | 325 | 487 | 73 | 13 216 | 52 | 3 |
| mary-spr17 | early | `aa2d721856c77ec93e2611f3562c44ce89a3938d` | 544 | 544 | 548 | 882 | 90 | 10 818 | 5 | 1 |
| muni-fi-spr16 | early | `89d30dbc860624924b63fac76c9bbbbb125b96b2` | 228 | 229 | 327 | 575 | 35 | 2 409 | 39 | 5 |
| nbi-spr18 | middle | `5b232d5ef4a3079c866dd83f31a4e6cfe83ef7af` | 404 | 404 | 770 | 782 | 67 | 3 638 | 106 | 2 |
| pu-llr-spr17 | early | `0fa5fdcb97ba31cad942ee1dacbc747ca11bb5fd` | 687 | 707 | 789 | 1 001 | 75 | 14 726 | 62 | 2 |
| tg-fal17 | early | `e288a6d25a71e702d7580a60d16dc0ff2cc76afe` | 36 | 36 | 341 | 711 | 23 | 3 072 | 274 | 1 |
| yach-fal17 | middle | `734042cbcc6d7a60ba6f177d81b08239303c84fc` | 91 | 93 | 265 | 417 | 33 | 1 923 | 270 | 1 |

### ITC 2019 instances, all classes (article Table 4)

Class-list question over all classes of each instance, co-located, 5 runs × 30 pairs after 5 warm-up rounds; mismatching answers: 0. Grants are constructed (see `data/itc2019/grants.sql`).

| Instance | Classes | Rooms per class | Statements set / per-row | Median ms set / per-row | Speedup | 95% CI |
|---|---:|---:|---:|---:|---:|---:|
| agh-ggis-spr17 | 1 852 | 6.68 | 6 / 9 078 | 29.4 / 675 | 22.6× | 21.8–23.3 |
| bet-fal17 | 983 | 22.35 | 6 / 5 634 | 37.9 / 476 | 12.6× | 12.2–13.0 |
| iku-fal17 | 2 641 | 27.90 | 6 / 14 778 | 111 / 1 229 | 11.0× | 10.6–11.5 |
| lums-spr18 | 487 | 27.14 | 6 / 2 206 | 23.2 / 192 | 8.36× | 7.90–8.84 |
| mary-spr17 | 882 | 12.27 | 6 / 3 550 | 23.8 / 308 | 12.8× | 12.5–13.1 |
| muni-fi-spr16 | 575 | 4.19 | 6 / 2 634 | 10.3 / 217 | 21.4× | 21.0–21.9 |
| nbi-spr18 | 782 | 4.65 | 6 / 3 562 | 14.8 / 288 | 19.9× | 19.0–21.0 |
| pu-llr-spr17 | 1 001 | 14.71 | 6 / 4 262 | 29.4 / 348 | 11.7× | 11.2–12.3 |
| tg-fal17 | 711 | 4.32 | 6 / 3 942 | 11.1 / 293 | 27.1× | 26.2–28.0 |
| yach-fal17 | 417 | 4.61 | 6 / 2 750 | 8.73 / 204 | 24.4× | 22.9–25.9 |

Exploratory Spearman correlation of the speedup with rooms per class: ρ = -0.88; with the class count: ρ = -0.32. Smaller sizes (1, 10, 50, 200 classes) are in `results/summary/itc2019-summary.json`.

### Consistency under concurrent changes (article Table 5)

11 of 11 schedules pass for the prototype (Read Committed; `results/consistency/consistency.tsv` describes each schedule and both outcomes).

| Id | Scenario | Released implementation | Prototype | Result |
|---|---|---|---|---|
| S0 | Grants and ancestry change together while one evaluation is between its statements | ALLOWED: grants from S0 combined with ancestry from S1 | lock: swap waited, answer denied (S0); RR snapshot (advisory, no lock): swap committed during it, answer denied (S0) | PASS |
| S1 | Revocation committed before a protected operation enters synchronization | field 2 ALLOWED from cached grants; write committed after the revocation | field 2 denied (fresh evaluator inside its own unit); course unchanged=true | PASS |
| S2 | Authorized operation enters first; revocation starts while it is in progress | revocation committed while the writer was paused; write committed AFTER it, justified only by the revoked grant | revocation blocked on the domain lock until the write committed; write committed; revocation then committed=true | PASS |
| S3 | Re-parenting an ancestor while another operation checks and writes a descendant | write committed after the department left faculty 1; a fresh check now says denied | re-parent waited for the write; write committed first; in the opposite order the write is denied | PASS |
| S4 | Same request: an earlier mutation changes ancestry, a later one checks a descendant | field 1 authorized (pre=true, post=true, attach=true); field 2 delete ALLOWED from the memoised MANAGE of the department | evaluator discarded after field 1; field 2 sees level EDIT, delete denied | PASS |
| S5 | Caller removed from the group that carries the grant | before=true; after, same evaluator: ALLOWED (cached group grants) | after invalidation: denied (membership reloaded) | PASS |
| S6 | Cached descendant after its join-table edge to the granted ancestor is removed | before=true; after, same evaluator: ALLOWED (memoised) | after invalidation: denied (recomputed) | PASS |
| S7 | Cached denial, then a valid grant is added | before=false; after, same evaluator: DENIED (cached denial) | after invalidation: allowed | PASS |
| S8 | Failure after evaluation and write; retry after a revocation | failure after the write: write REMAINS committed | attempt 1 rolled back (course name 'TEST course'); retry built evaluator #2 after the revocation and was denied; epoch advanced only by the revocation (43→44) | PASS |
| S9 | Old asynchronous computation completes after invalidation | (the deployed evaluator is never replaced within a request) | old computation returned false into the discarded evaluator; current generation answers false before and false after it | PASS |
| S10 | Two application instances: protected writes vs grant revoke/restore, concurrently | 55 unjustified of 182 committed writes; 84 written in a different authorization state than the one their check began in; 220 grant toggles | 0 unjustified of 191 committed writes; 0 written in a different authorization state than the one their check began in; 169 grant toggles | PASS |

S10, two application instances, randomized: released implementation 55 unjustified of 182 committed writes (84 written in a different authorization state than their check began in); prototype 0 of 191 (0).

### Cost of the protocol (Section 8.6)

One client, protected update of one course (median over 5 runs × 300 units): released 2.75 ms, share locks 2.70 ms, exclusive lock 2.71 ms.

| Variant | Clients | Authorization changes | Updates/s (mean over runs) | Range | p50 ms |
|---|---:|---|---:|---:|---:|
| released | 1 | none | 607 | 537–674 | 1.41 |
| released | 4 | none | 1 482 | 1 250–1 635 | 2.33 |
| released | 16 | none | 2 290 | 2 170–2 447 | 6.46 |
| released | 1 | every 50 ms | 746 | 693–792 | 1.22 |
| released | 4 | every 50 ms | 1 907 | 1 724–2 115 | 2.01 |
| released | 16 | every 50 ms | 2 343 | 2 146–2 593 | 6.41 |
| share locks | 1 | none | 525 | 478–595 | 1.74 |
| share locks | 4 | none | 1 349 | 1 221–1 486 | 2.57 |
| share locks | 16 | none | 1 744 | 1 516–1 821 | 8.39 |
| share locks | 1 | every 50 ms | 599 | 539–623 | 1.49 |
| share locks | 4 | every 50 ms | 1 476 | 1 376–1 573 | 2.53 |
| share locks | 16 | every 50 ms | 1 816 | 1 770–1 856 | 8.34 |
| exclusive lock | 1 | none | 548 | 475–605 | 1.67 |
| exclusive lock | 4 | none | 611 | 542–677 | 6.37 |
| exclusive lock | 16 | none | 597 | 565–641 | 25.97 |
| exclusive lock | 1 | every 50 ms | 606 | 577–647 | 1.57 |
| exclusive lock | 4 | every 50 ms | 664 | 625–723 | 6.03 |
| exclusive lock | 16 | every 50 ms | 631 | 579–688 | 24.86 |

### Environment

2 vCPU (Intel(R) Xeon(R) Processor @ 2.10GHz), 7.8 GiB RAM; PostgreSQL 16.15; Java 25.0.2. Round trip per `SELECT 1`: 115 / 120 µs directly, 1327 / 1223 µs through the relay (`results/latency/rtt.txt`).
<!-- results:end -->

## Layout

| Path | Contents |
|---|---|
| `app/` | The application: framework, domain entities, schema (`src/main/resources/db/schema.sql`) and the project's own README. Snapshot of LNU Timetable commit `6ec3e9a` (the `timetable/` tree is identical to commit `6c2a082`, which the article cites). |
| `app/src/experiments/java/org/lnu/timetable/bench/` | `BenchEnv` (the application's metadata registry, permission graph, repositories and `PermissionService` over one r2dbc pool, with a proxy that counts every executed statement), `PerRowEvaluator` (the per-row baseline: the shape of `PermissionService.ancestryOf` at commit `205a7d1`, same grants, repositories and level rule), `LatencyBench` (§7.6), `DelayProxy` (user-space TCP relay adding a fixed one-way delay). |
| `app/src/experiments/java/org/lnu/timetable/itc/` | Annotated ITC 2019 entities (Course, Config, Subpart, Class, Room) and `ItcBench` (§7.7). |
| `app/src/experiments/java/org/lnu/timetable/cons/` | `ConsEnv` (an application instance with a statement gate for deterministic schedules), `RequestAuthzContext` (request-level invalidation), `AuthzProtocol` (domain-row lock protocol), `ConsistencyTests` (schedules S0–S10) and `ProtocolCost` (§8.6). |
| `data/lnu/` | `data-anonymised.sql`: the deployment snapshot of 2026-08-16 with personal data removed (names, e-mails and record-book numbers replaced by pseudonyms; user accounts, their grants, memberships and tokens deleted; every identifier and foreign key unchanged). `bench-caller.sql`: the benchmark caller. `permission-instance.tsv`: the row-instance graph extract. |
| `data/itc2019/` | `instances.tsv` (the ten instances, their source URLs and git blob SHA-1), `ids/` (identifiers extracted by `extract.py`), `stats.json`, `schema-itc.sql`, `grants.sql` (constructed grants). |
| `replay/` | Node.js scripts: `permedges.mjs` parses the permission cascade from the sources and checks `permission-edges.tsv`; `permgraph.mjs` the type graph; `instancegraph.mjs` replays the statement counts and the level trace on the snapshot (Table 2 and the trace of §7.5); `algocheck.mjs` checks Algorithm 1 against a reference evaluator on random graphs. |
| `scripts/` | Build, database setup, one script per experiment, `analyse.sh` and `run-all.sh`. |
| `analysis/` | Python: `analyse.py` (medians, paired speedups, 95 % t-intervals), `protocol.py`, `consistency.py`, `environment.py`, `tables.py`. |
| `results/` | `replay/`, `latency/`, `itc2019/`, `consistency/`, `protocol/` (raw CSV/TSV and logs), `environment.txt`, `summary/` (JSON summaries and `tables.md`). |

Article sections and the files behind them:

| Article | Experiment | Script | Results |
|---|---|---|---|
| Table 2, §7.5 trace | Statement counts replayed over the snapshot | `scripts/run-replay.sh` | `results/replay/` |
| §6.3 (Algorithm 1) | Differential check against a reference evaluator | `scripts/run-replay.sh` | `results/replay/algocheck.log` |
| §7.6, Table 3 | Measured latency, co-located and through the relay | `scripts/run-latency.sh` | `results/latency/` |
| §7.7, Table 4 | ITC 2019 instances | `scripts/run-itc2019.sh` | `results/itc2019/` |
| §8.6, Table 5 | Consistency schedules S0–S10 | `scripts/run-consistency.sh` | `results/consistency/` |
| §8.6 | Cost of the protocol | `scripts/run-protocol-cost.sh` | `results/protocol/` |

## Reproducing

Requirements: JDK 25 and Maven 3.9 (build), a Java 25 runtime, PostgreSQL 16 with `max_connections` of
at least 600 (the per-row baseline issues bursts of concurrent statements), `psql`, Node.js 20+, Python 3
with SciPy, and `git` and `curl` for the optional ITC download. The scripts connect with `PGHOST`, `PGPORT`,
`PGUSER` and `PGPASSWORD` (defaults `localhost`, `5432`, `postgres`, `postgres`) and run Java as `$JAVA`
(default `java`).

```bash
scripts/build.sh                 # mvn -Pexperiments -DskipTests package -> app/target/timetable-0.0.1-SNAPSHOT.jar
scripts/setup-lnu.sh             # databases lnu_timetable and lnu_cons from schema.sql + data/lnu
scripts/setup-itc2019.sh         # one database itc_<instance> per ITC 2019 instance
scripts/environment.sh           # results/environment.txt
scripts/run-replay.sh            # Table 2, trace, Algorithm 1 check (seconds)
scripts/run-latency.sh           # §7.6: 5 co-located + 5 relay JVM runs (starts and stops DelayProxy itself)
scripts/run-itc2019.sh           # §7.7: 5 runs x 10 instances, one JVM each
scripts/run-consistency.sh       # §8.6: S0-S10, S10 with 400 randomized writes
scripts/run-protocol-cost.sh     # §8.6: 5 runs
scripts/analyse.sh               # results/summary/*.json, tables.md and the summary above
# or all of it:  scripts/run-all.sh
```

The whole run took about 1.5 hours on two vCPUs; most of it is the per-row baselines. `RUNS`, `PAIRS`
and `WARM` shorten a run (for example `RUNS=1 PAIRS=3 WARM=1 scripts/run-latency.sh`).

`scripts/fetch-itc2019.sh` is optional: it downloads the ten ITC 2019 XML files from the public mirror
`github.com/DepartureZSH/ITC2019` (itc2019.org could not be reached from the evaluation environment),
verifies each file's git blob SHA-1 against `data/itc2019/instances.tsv` (each file was also checked against a
second independent mirror, wayne1512/ITC2019 or marlucioapires/itc2019_multiobjective: identical, or identical up
to whitespace for muni-fi-spr16 and yach-fal17), and regenerates `data/itc2019/ids`
and `stats.json`, which should then show no difference.

To run the application itself rather than the experiments, see [`app/README.md`](app/README.md); load
`data/lnu/data-anonymised.sql` after `schema.sql` in place of the project's `data.sql`.

## Method in brief

- **What runs.** Both strategies run outside Spring, but on the application's own classes: its
  `EntityMetadataRegistry`, `PermissionTypeGraph`, `PermissionGraphRepository`, `PermissionRepository` and
  `PermissionService`, over the R2DBC driver and an r2dbc pool configured as in `application.properties`
  (initial 10, maximum 512; initial 4 for ITC). Libraries from the built jar: Spring Boot 4.0.6, Spring
  Framework 7.0.7 (R2DBC), Spring for GraphQL 2.0.3, r2dbc-postgresql 1.1.1, r2dbc-pool 1.0.2, Reactor 3.8.5.
  The per-row baseline reconstructs the evaluator the framework replaced: each row climbs its own ancestry,
  one statement per visited vertex for its foreign-key parents and one per join-table parent, with the
  grants loaded once by the same two statements and the same level rule.
- **Latency (§7.6, §7.7).** Each JVM run first checks that both strategies return identical
  `Map<id, level>` answers and records the number of statements each issues; it then does warm-up rounds
  (10 for the snapshot, 5 for ITC) and 30 measured rounds. A round visits every request size in random
  order and times both strategies back to back in random order. The timed interval runs from the creation
  of a fresh request-scoped evaluation to the delivery of the complete map. The speedup of a run is the
  median of its paired per-row/set-at-a-time ratios; the tables give the geometric mean over runs with a
  95 % t-interval on the log scale (runs are the independent units). Medians are over all measured pairs.
- **Relay.** `DelayProxy` holds every chunk for 250 µs in each direction (the kernel `netem` qdisc was not
  available); `results/latency/rtt.txt` gives the resulting round trip per statement.
- **ITC 2019.** One instance per contributing university: the smallest of its early set, or of its middle
  set where it has none. Only identifiers are loaded. The grants are constructed (EDIT on every tenth
  course through a group, MANAGE on one room), so this is real structure with a constructed
  authorization workload, not a deployed policy.
- **Consistency (§8.6).** Read Committed. Two connection pools act as two application instances. A
  statement gate holds the first statement whose SQL contains a chosen fragment until the test releases
  it, which makes S0–S9 deterministic; S10 is randomized (400 write attempts on one instance while the
  other toggles the grant every 1–10 ms). The fixture rows (caller 2001, department 9001, courses 9101 and
  9102, group 901) are added to the scratch database `lnu_cons` only.
- **Scope of the consistency prototype.** `AuthzProtocol` protects a unit only if every writer of
  grants, memberships and permission-bearing relationships (administrative, import and background jobs
  included) takes the same `authz_domain` lock; an ordinary SQL update outside the protocol is not blocked.
  The lock row `authz_domain(id = 1)` must exist (the tests create it); the prototype does not reject a
  missing row, so it must not be used as a general safety mechanism as it stands. `run()` executes one unit;
  retrying a failed unit (resubscribing to the whole transactional unit, with a fresh evaluator) is done by
  the caller, as the tests do. `RequestAuthzContext` replaces the evaluator; it does not cancel work still
  running on the old one, whose results reach no later check. The schedules validate these mechanisms; they
  do not prove arbitrary transactional histories correct.
- **Protocol cost.** A protected update of one course; released evaluator versus the protocol with share
  locks and with an exclusive lock for every unit; one client (100 warm-up and 300 measured interleaved
  triplets per run) and 1, 4 and 16 concurrent clients for 3 s per cell, without and then with an
  authorization change every 50 ms (the cells run in that order, so block order is confounded with warm-up).
