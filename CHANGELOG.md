# Changelog

All notable changes to this project are written here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[Semantic Versioning](https://semver.org/).

## [1.0.0] - 2026-11-12

The first build of Module 14's code seed (33 files), run for the first time against PostgreSQL 18.6
and RocksDB 11.8.1.

### Added

- The Gradle wrapper (9.9.1, both checksums compared), lockfiles, README, ARCHITECTURE, this
  changelog and the license.
- `scripts/stack.sh` (`up`, `down`, `test`, `rocks`, `status`; a dry run unless `--run`), in the
  shape of Module 13's.
- Stack tests (`StudySchemaStackTest`, tagged `stack`, run by `scripts/stack.sh test`): the seeder's
  counts; the indexes in `pg_indexes` equal the ones the ledger reads from the schema files, on
  the same tables; no index left invalid by the concurrent builds; the seat map, basket, tickets
  page and sweeper served by their indexes; a partial index unused by a query that does not repeat
  its predicate; every `WriteStep` builds its indexes and runs its writes.
- Unit tests for `StudySettings` and `LatencySummary`, and for the new ledger rule and every
  problem's message.
- Three benchmark steps, `EVENT_CREATE`, `SECTION_CREATE` and `VENUE_CREATE`, so every table of the
  final schema has its write cost measured.
- The `rocks` service in `compose.yaml` (profile `lsm`, image `eclipse-temurin:25.0.5_8-jdk-noble`)
  and the task `lsmClasspath`, so RocksDB writes to the same Docker disk as PostgreSQL.
- `RocksHoldWritesCli` now times 10,000 point reads before and after compaction catches up, and
  waits up to 120 seconds for the compaction it owes; `WriteBenchmarkCli` reports how many updates
  were HOT.
- `src/main/resources/logback.xml` at `WARN`, so HikariCP's debug lines do not bury the output.
- JaCoCo's 0.80 line floor wired into `check` (the seed had the plugin but no rule), and
  `stackCoverage` over both test runs.

### Changed

- The study volume is a quarter of the module's (625 arena events and 625 club nights), because
  Docker's shared disk must keep 20 GB free; customers are now 2,000 per arena event, so every
  ratio of the full volume holds at any size.
- `bare-schema.sql` has Module 13's `sections.version` column and its check constraint, so the
  header's "same columns" is true.

### Fixed

- `IndexLedger` accepted any `WriteStep` as the measurement of any index, so four rows
  (`venues_pkey`, `events_pkey`, `sections_pkey`, `events_by_venue_date`) claimed a cost measured by
  `EVENT_SETUP`, which writes only `seats`. The check now reads each index's table from the SQL and
  refuses a step that benchmarks another table (`LedgerProblem.WriteCostOfAnotherTable`).
- The rejected covering index for Q3 left out `booking_id`, which Q3 returns, so it could never give
  an index-only scan; it now includes it.
- Every `WriteStep` insert on `holds`, `bookings` and `seats` failed with `function chr(bigint) does
  not exist`; the section letter is now computed as an `int`.
- The lab passwords moved from `build/secrets` to `.secrets`: `./gradlew clean` deleted them while
  the database still used them. `scripts/stack.sh down` deletes them with the database.
- `compose.yaml` sets `max_connections=200`: the benchmark's 65 connections left too little of the
  default 100. `max_wal_size` is 2 GB instead of 8 GB, so the write-ahead log cannot take 8 GB of the
  shared disk.
- `db/init/10-roles.sh` had lost its executable bit, and the container stopped at its first start
  with exit status 126.
- `RocksHoldWritesCli` threw `NumberFormatException` with a stack trace for a non-numeric argument;
  it now prints its usage message. The Gradle task and the container grant native access, which
  Java 25 asks for before `System.loadLibrary`.
