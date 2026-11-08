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
