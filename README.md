# seatwave-indexes

SeatWave's indexing study: Module 13's seven tables with their primary keys only, loaded with millions of seats, holds and bookings; five listed queries explained and timed before and after the final index set; the write cost of every index measured with 64 writers; an index ledger that fails the build when an index has no user or no measured cost; and the same hold writes against RocksDB, an LSM store. This is the project of **Module 14, "Indexes and storage engines"**, of the course System Design Fundamentals; the running case is SeatWave, a ticket-booking service.

## What it shows

- The five queries go from full scans of up to 15,000,000 rows to reads of 6 to 4,009 pages once each has one index whose leading columns match its conditions; the seat map from a median of 375 ms to 2.53 ms (timed from Java, at a quarter of the module's volume, on a laptop).
- PostgreSQL 18.6 uses a seat index in the wrong column order through a skip scan, at about 450 times the pages of the right order.
- Every index costs every write: the database's CPU per hold insert rises from 143 µs with the primary key alone to 166 µs with the final set, and a fifth index without a user cuts confirms from 22,763 to 13,741 a second.
- The index ledger reads every index and its table from the schema files; a stack test proves the database holds exactly those indexes.
- RocksDB, synced per hold, loses only 7 percent of its rate to five index keys, and at that rate never falls behind on compaction.

## Prerequisites

- JDK 25 (the build's toolchain asks for 25; it was run on Eclipse Temurin 25.0.2).
- Docker with Compose v2 for the study (run on Docker Desktop, engine 24.0.6, Compose v2.23.0-desktop.1). `./gradlew check` needs no Docker.
- `openssl`, for the generated lab passwords; about 15 GB free on Docker's disk for the quarter volume.

## Dependencies

Every version is pinned in `gradle/libs.versions.toml`, transitive versions in `gradle.lockfile`; all were checked on Maven Central on 11 and 12 November 2026, and OSV listed no vulnerability for any of them on 12 November 2026.

| Coordinates | Version | Checked | License | Why the JDK alone is not enough |
|---|---|---|---|---|
| `org.postgresql:postgresql` (runtime) | 42.7.14 | 12 Nov 2026 | BSD-2-Clause | The JDK has the JDBC interfaces, not a driver for PostgreSQL's protocol. |
| `com.zaxxer:HikariCP` | 7.1.0 | 12 Nov 2026 | Apache-2.0 | A connection pool: the benchmark's 64 writers each need an open connection. |
| `org.rocksdb:rocksdbjni` | 11.8.1 | 12 Nov 2026 | Apache-2.0 or GPLv2 | An LSM store for Lab 3; the JDK has none. |
| `org.slf4j:slf4j-api`, `ch.qos.logback:logback-classic` (runtime) | 2.0.20, 1.6.6 | 12 Nov 2026 | MIT; EPL-1.0 or LGPL-2.1 | HikariCP logs through SLF4J. |
| `org.junit:junit-bom`, `org.assertj:assertj-core` (test) | 6.1.3, 3.27.7 | 12 Nov 2026 | EPL-2.0, Apache-2.0 | Tests and assertions. |
| Spotless, google-java-format, JaCoCo (build) | 8.11.0, 2.0.0, 0.8.16 | 12 Nov 2026 | Apache-2.0, Apache-2.0, EPL-2.0 | Enforced format and a 0.80 line-coverage floor. |

Images, pinned by tag and digest in `compose.yaml`, checked on Docker Hub on 12 November 2026, each with an `arm64` variant: `postgres:18.6` (Debian trixie, the same pin as Module 13) and `eclipse-temurin:25.0.5_8-jdk-noble` (for the RocksDB runs). Gradle 9.9.1 through the wrapper; its jar and distribution checksums match services.gradle.org.

## Commands and what they printed

Run on 12 November 2026 on an Apple M2 Max (12 cores, 32 GB, macOS 27.2) with other programs running; PostgreSQL in a 4-CPU, 4 GB container in Docker Desktop's Linux virtual machine (12 CPUs, 8 GB), so every timing from Java includes the round trip into that machine. The timed runs were taken under the course's shared measurement lock, with the load average beside each. These are laptop numbers, not production's.

```bash
./gradlew clean check                       # format, compile with warnings as errors, unit tests, coverage
scripts/stack.sh test --run --yes           # PostgreSQL up, the stack tests and their coverage, down -v
scripts/stack.sh up --run --yes             # for the study below
./gradlew -q seed --args="--run --yes"      # load the study volume (a dry run without --run)
./gradlew -q plans --args=before            # EXPLAIN ANALYZE and 200 timed runs of each query
{ echo '\timing on'; cat src/main/resources/db/study/final-indexes.sql; } | \
  docker compose -p seatwave-indexes exec -T postgres sh -c \
  'PGPASSWORD="$(cat /run/secrets/owner_password)" psql -X -h 127.0.0.1 -U seatwave_owner -d seatwave -v ON_ERROR_STOP=1'
./gradlew -q plans --args=after
./gradlew -q scanTypes --args=build/study/plans-before.txt   # Lab 1
./gradlew -q columnOrder                    # Lab 2 (run it before final-indexes.sql)
./gradlew -q writeBenchmark                 # Lab 3: about 15 minutes
scripts/stack.sh rocks --run --yes          # Lab 3: RocksDB with 0, 2 and 5 index keys
scripts/stack.sh down --run --yes           # down -v, delete the passwords, check nothing is left
./gradlew -q btreeDemo lsmDemo storageEstimate writeCost indexLedger   # need no Docker
```

Every `scripts/stack.sh` action is a dry run without `--run` (a dry run needs no Docker and changes nothing), asks before it changes anything unless `--yes` is given, touches only the Compose project `seatwave-indexes`, and appends an audit line to `build/stack/audit.log`. `up` writes three throwaway passwords into `.secrets/` (ignored by git), readable by every account on the machine: on a Linux host a Compose secret is a bind mount that keeps the file's mode, and the container's `postgres` user reads it. `down` deletes them with the database. The seeder refuses a schema that already holds seats; the benchmark writes only its own `bench_` tables. `config/study.properties` loads a quarter of the module's volume (625 arena events and 625 club nights); 2,500 and 2,500 load all of it, about 28 GB with the indexes.

```text
> Task :jacocoTestReport

BUILD SUCCESSFUL in 3s
10 actionable tasks: 10 executed
Consider enabling configuration cache to speed up this build: https://docs.gradle.org/9.9.1/userguide/configuration_cache_enabling.html
exit status 0
```

30 unit test methods; Line coverage of the code that needs no database (the programs and SeedSql left out): 0.925 (358 of 387 lines). The test task also ran 40 times in a row beside 8 busy loops on the 12 cores (40 runs, 0 failed), and `./gradlew clean check` passed in a copy of the repository at a path of 378 characters and in one whose folder name holds a space.

```text
StudySchemaStackTest > holdsExactlyTheIndexesTheLedgerChecksOnTheirTables() PASSED
StudySchemaStackTest > servesTheBasketTheTicketsPageAndTheSweeperFromTheirIndexes() PASSED
StudySchemaStackTest > answersTheSeatMapFromTheCoveringIndexAndTheClaimIndex() PASSED
StudySchemaStackTest > leavesNoIndexInvalidAfterTheConcurrentBuilds() PASSED
StudySchemaStackTest > usesThePartialIndexOnlyWhenTheQueryRepeatsItsPredicate() PASSED
StudySchemaStackTest > buildsEachBenchmarkStepsIndexesAndWritesItsRows(WriteStep) > [1] HOLDS_0 PASSED
StudySchemaStackTest > buildsEachBenchmarkStepsIndexesAndWritesItsRows(WriteStep) > [2] HOLDS_2 PASSED
StudySchemaStackTest > buildsEachBenchmarkStepsIndexesAndWritesItsRows(WriteStep) > [3] HOLDS_5 PASSED
StudySchemaStackTest > buildsEachBenchmarkStepsIndexesAndWritesItsRows(WriteStep) > [4] HOLDS_FINAL PASSED
StudySchemaStackTest > buildsEachBenchmarkStepsIndexesAndWritesItsRows(WriteStep) > [5] BOOKINGS_FINAL PASSED
StudySchemaStackTest > buildsEachBenchmarkStepsIndexesAndWritesItsRows(WriteStep) > [6] EVENT_SETUP PASSED
StudySchemaStackTest > buildsEachBenchmarkStepsIndexesAndWritesItsRows(WriteStep) > [7] CUSTOMER_SIGNUP PASSED
StudySchemaStackTest > buildsEachBenchmarkStepsIndexesAndWritesItsRows(WriteStep) > [8] EVENT_CREATE PASSED
StudySchemaStackTest > buildsEachBenchmarkStepsIndexesAndWritesItsRows(WriteStep) > [9] SECTION_CREATE PASSED
StudySchemaStackTest > buildsEachBenchmarkStepsIndexesAndWritesItsRows(WriteStep) > [10] VENUE_CREATE PASSED
StudySchemaStackTest > loadsExactlyTheRowsTheSeedPlans() PASSED
 Network seatwave-indexes_default  Removed
nothing of seatwave-indexes is left
exit status 0
```

7 stack test methods, 16 runs; Line coverage of all code but the programs, over both test runs: 0.935 (362 of 387 lines). The stack test task ran 40 times in a row beside the same busy loops (40 runs, 0 failed). 18 of 18 mutants caught: each one-line change of the code, the schema files or the ledger made the test that guards it fail; the list is in `_tools/module-14-outputs/mutants.txt`.

```text
plan: create schema seatwave_study (bare-schema.sql) and load {venues=50, events=1250, sections=13125, seats=12625000, customers=1250000, holds=15000000, bookings=10000000}
  venues                            0.0 s
  arena events                      0.0 s
  club events                       0.0 s
  arena sections                    0.0 s
  club sections                     0.0 s
  arena seats 1-100                 3.8 s
  sold holds 1-100                  5.5 s
  ended holds 1-100                 3.1 s
  arena seats 101-200               3.8 s
  sold holds 101-200                7.3 s
  ended holds 101-200               3.7 s
  arena seats 201-300               4.2 s
  sold holds 201-300                6.7 s
  ended holds 201-300               3.4 s
  arena seats 301-400               3.5 s
  sold holds 301-400                6.5 s
  ended holds 301-400               4.0 s
  arena seats 401-500               3.6 s
  sold holds 401-500                6.7 s
  ended holds 401-500               4.0 s
  arena seats 501-600               3.7 s
  sold holds 501-600                7.0 s
  ended holds 501-600               3.3 s
  arena seats 601-625               0.8 s
  sold holds 601-625                1.7 s
  ended holds 601-625               0.9 s
  club seats                        0.2 s
  customers                         3.9 s
  active holds of event 1           0.1 s
  bookings                         39.7 s
  vacuum analyze venues             0.0 s
  vacuum analyze events             0.0 s
  vacuum analyze sections           0.0 s
  vacuum analyze seats              1.2 s
  vacuum analyze customers          0.4 s
  vacuum analyze holds              1.5 s
  vacuum analyze bookings           2.8 s
  check venues     expected           50, found           50 ok
  check events     expected        1,250, found        1,250 ok
  check sections   expected       13,125, found       13,125 ok
  check seats      expected   12,625,000, found   12,625,000 ok
  check customers  expected    1,250,000, found    1,250,000 ok
  check holds      expected   15,000,000, found   15,000,000 ok
  check bookings   expected   10,000,000, found   10,000,000 ok
audit: loaded schema seatwave_study as seatwave_owner
exit status 0
```

```text
Q1: Parallel Seq Scan on holds; Index Scan using seats_pkey on seats | rows removed by filters: 5012333
Q2: Parallel Seq Scan on holds | rows removed by filters: 4999999
Q3: Parallel Seq Scan on bookings | rows removed by filters: 3333330
Q4: Seq Scan on events | rows removed by filters: 1246
Q5: Parallel Seq Scan on holds | rows removed by filters: 4999333
exit status 0
Q1: Index Only Scan using one_seat_per_number on seats; Index Scan using one_claim_per_seat on holds | rows removed by filters: 0
Q2: Index Scan using active_holds_by_customer on holds | rows removed by filters: 0
Q3: Index Scan using bookings_by_customer_date on bookings | rows removed by filters: 0
Q4: Bitmap Heap Scan on events; Bitmap Index Scan on events_by_venue_date | rows removed by filters: 0
Q5: Index Scan using active_holds_by_expiry on holds | rows removed by filters: 0
exit status 0
```

The plans and timings before and after, the index sizes and heights, Lab 2's output and the RocksDB runs are printed in full in Module 14, Section 8. The write benchmark:

```text
HOLDS_0                1     22,745   p50 2.573 ms, p99 6.701 ms (1364790 runs)    24,223   p50 2.491 ms, p99 5.179 ms (1364790 runs)     98.0  1,066,741 of 1,364,790
HOLDS_2                3     23,470   p50 2.536 ms, p99 5.853 ms (1408293 runs)    24,384   p50 2.482 ms, p99 5.073 ms (1408293 runs)    251.2  0 of 1,408,293
HOLDS_5                6     23,608   p50 2.516 ms, p99 6.559 ms (1416568 runs)    13,741   p50 3.438 ms, p99 22.732 ms (824511 runs)    573.5  0 of 824,511
HOLDS_FINAL            5     22,316   p50 2.678 ms, p99 6.014 ms (1339070 runs)    22,763   p50 2.607 ms, p99 6.206 ms (1339070 runs)    447.9  0 of 1,339,070
BOOKINGS_FINAL         4     24,313   p50 2.436 ms, p99 6.033 ms (1458892 runs) -                                                    391.0  -
EVENT_SETUP            2     26,059   p50 2.340 ms, p99 4.477 ms (1563651 runs) -                                                    203.4  -
CUSTOMER_SIGNUP        2     25,217   p50 2.356 ms, p99 5.853 ms (1513117 runs) -                                                    210.0  -
EVENT_CREATE           2     26,647   p50 2.285 ms, p99 4.455 ms (1598933 runs) -                                                    134.3  -
SECTION_CREATE         1     24,308   p50 2.377 ms, p99 7.344 ms (1458586 runs) -                                                     74.1  -
VENUE_CREATE           1     26,600   p50 2.273 ms, p99 4.699 ms (1596119 runs) -                                                     55.6  -
written build/study/writes.txt
exit status 0
$ python3 -I bench-cores.py outputs
step         inserts/s  insert-phase CPU  core-us per insert  cores for 13,000/s  (samples)
HOLDS_0         22,745             324%                143               1.85  (5)
HOLDS_2         23,470             366%                156               2.03  (5)
HOLDS_5         23,608             402%                170               2.21  (5)
HOLDS_FINAL     22,316             371%                166               2.16  (5)
```

The insert rate stayed near 23,000 a second for every index set because the round trip from the Mac into the virtual machine bounded it; the database's CPU per insert is the number that moved. The load average rose from 3.42 to 18.38 during the run as other programs built beside it.

```text
plan: rocks the Compose project seatwave-indexes
  ./gradlew lsmClasspath; docker compose -p seatwave-indexes --profile lsm run --rm rocks with 0 2 5 index keys, 60 s each
 Network seatwave-indexes_default  Creating
 Network seatwave-indexes_default  Created
 Volume "seatwave-indexes_rocksdata"  Creating
 Volume "seatwave-indexes_rocksdata"  Created
 Volume "seatwave-indexes_pgdata"  Creating
 Volume "seatwave-indexes_pgdata"  Created
rocksdb, 0 extra index keys: 11,660 holds/s (699,621 holds in 60.0 s)
right after the writes: level-0 files 0, pending compaction 0 bytes, compactions running 0
  reads of 10000 random holds: p50 0.002 ms, p99 0.011 ms (10000 runs), 10000 found
compaction finished after 0.0 s: level-0 files 0, pending compaction 0 bytes, compactions running 0
  reads of 10000 random holds: p50 0.001 ms, p99 0.003 ms (10000 runs), 10000 found
rocksdb, 2 extra index keys: 11,889 holds/s (713,375 holds in 60.0 s)
right after the writes: level-0 files 1, pending compaction 0 bytes, compactions running 0
  reads of 10000 random holds: p50 0.002 ms, p99 0.013 ms (10000 runs), 10000 found
compaction finished after 0.0 s: level-0 files 1, pending compaction 0 bytes, compactions running 0
  reads of 10000 random holds: p50 0.002 ms, p99 0.006 ms (10000 runs), 10000 found
rocksdb, 5 extra index keys: 10,849 holds/s (650,936 holds in 60.0 s)
right after the writes: level-0 files 3, pending compaction 0 bytes, compactions running 0
  reads of 10000 random holds: p50 0.005 ms, p99 0.017 ms (10000 runs), 10000 found
compaction finished after 0.0 s: level-0 files 3, pending compaction 0 bytes, compactions running 0
  reads of 10000 random holds: p50 0.002 ms, p99 0.007 ms (10000 runs), 10000 found
exit status 0
```

After everything, nothing of the project is left:

```text
$ docker ps -a --filter label=com.docker.compose.project=seatwave-indexes --quiet | wc -l
0
$ docker network ls --filter label=com.docker.compose.project=seatwave-indexes --quiet | wc -l
0
$ docker volume ls --filter label=com.docker.compose.project=seatwave-indexes --quiet | wc -l
0
```

## Ports

One: PostgreSQL on `127.0.0.1:21400`, from Module 14's range 21400 to 21499. The RocksDB container publishes nothing.

## Project structure

See `ARCHITECTURE.md` for packages, the dependency rule, a data-flow diagram, walk-throughs, decisions and invariants.

```text
compose.yaml, db/init/10-roles.sh, config/study.properties, scripts/stack.sh
src/main/resources/db/study/   bare-schema.sql, final-indexes.sql, rejected-indexes.sql, index-ledger.txt
src/main/java/io/plainround/seatwave/indexes/
  catalog/  ListedQuery, WriteStep          ledger/  IndexLedger, LedgerProblem, IndexLedgerCli
  study/    settings, seeder, plan runner, write benchmark
  model/    B-tree and LSM models           estimate/ StorageEstimateCli, WriteCostCli
  lsm/      RocksHoldWritesCli               labs/     ScanTypesCli, ColumnOrderCli
src/test/java/.../             unit tests; study/StudySchemaStackTest is tagged "stack"
```

## Where things came from

Built from Module 14's code seed. The shape and guardrails of `scripts/stack.sh`, the passwords in `.secrets/` and `max_connections=200` follow Module 13's project `seatwave-transactions` at commit `d78af3a`; its Gradle wrapper files and `logback.xml` are copied from there. `CHANGELOG.md` lists what the build changed in the seed.

## What is simplified

- A quarter of the module's study volume, every ratio kept, because Docker's disk on the laptop is shared; full-scan costs scale with the volume, indexed reads hardly do (Module 14, Section 1).
- One PostgreSQL in a 4-CPU, 4 GB container on a laptop, with the programs across the virtual machine's boundary; the reference machine's 16 GB and its disk are not reproduced.
- The study schema has no foreign keys and no hold-limit trigger, on purpose: it measures indexes, and Module 13 holds the rules.
- RocksDB runs one synced batch per hold with default options; a production store would tune its memtables, compaction threads and Bloom filters.

## No CI on purpose

This repository has no `.github/` folder and no CI workflow, on purpose: the course's owner verifies every project locally, with the commands above.

## The Compose path

`compose.yaml` holds all the infrastructure under the project name `seatwave-indexes`; `scripts/stack.sh` starts it, tests it, runs the RocksDB container and brings everything down with `down -v`, then checks that no container, network or volume of the project is left.

## License

MIT, see `LICENSE`.
