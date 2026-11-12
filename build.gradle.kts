plugins {
    java
    jacoco
    alias(libs.plugins.spotless)
}

group = "io.plainround"
version = "1.0.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    // The benchmark's writers each need their own open connection; the JDK has no pool.
    implementation(libs.hikari)
    // Lab 3's LSM store; the JDK has none.
    implementation(libs.rocksdb)
    // The JDK has the JDBC interfaces, not a driver that speaks PostgreSQL's protocol.
    runtimeOnly(libs.postgresql)
    // HikariCP logs through SLF4J and needs a backend.
    runtimeOnly(libs.logback.classic)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// Transitive versions are written to gradle.lockfile, so two builds can never resolve different jars.
dependencyLocking {
    lockAllConfigurations()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 25
    // Every warning is an error: a warning that is allowed to stay is a defect nobody owns.
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

spotless {
    java {
        googleJavaFormat(libs.versions.googleJavaFormat.get())
        removeUnusedImports()
        endWithNewline()
    }
}

jacoco {
    toolVersion = libs.versions.jacoco.get()
}

// ./gradlew check needs no Docker: the unit tests, among them IndexLedgerTest, which fails the
// build when an index in the final schema has no listed user or no measured write cost. The tests
// tagged "stack" need the study database; scripts/stack.sh test starts it, runs them, stops it.
tasks.test {
    useJUnitPlatform {
        excludeTags("stack")
    }
    testLogging {
        events("passed", "failed", "skipped")
    }
    finalizedBy(tasks.jacocoTestReport)
}

tasks.register<Test>("stackTest") {
    group = "verification"
    description = "Runs the tests against the study database; scripts/stack.sh test starts and stops it."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("stack")
    }
    // The database is outside Gradle's view, so these tests are never up to date.
    outputs.upToDateWhen { false }
    testLogging {
        events("passed", "failed", "skipped")
    }
}

// Code that only runs against PostgreSQL or RocksDB is measured by the stack run, not by check.
// The programs (*Cli) are run by hand, and their output is printed in the README and the module.
val databaseOnly = listOf("**/*Cli*", "**/study/SeedSql*")
val programs = listOf("**/*Cli*")

tasks.jacocoTestReport {
    reports {
        xml.required = true
    }
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    classDirectories.setFrom(sourceSets.main.get().output.asFileTree.matching { exclude(databaseOnly) })
    violationRules {
        rule {
            limit {
                counter = "LINE"
                minimum = "0.80".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}

tasks.register<JacocoReport>("stackCoverageReport") {
    group = "verification"
    description = "Writes the line coverage of the unit and stack test runs together."
    dependsOn(tasks.test, tasks.named("stackTest"))
    executionData(fileTree(layout.buildDirectory.dir("jacoco")).include("*.exec"))
    sourceDirectories.setFrom(sourceSets.main.get().allSource.srcDirs)
    classDirectories.setFrom(sourceSets.main.get().output.asFileTree.matching { exclude(programs) })
    reports {
        xml.required = true
    }
}

tasks.register<JacocoCoverageVerification>("stackCoverage") {
    group = "verification"
    description = "Checks line coverage of all main code but the programs over both test runs."
    dependsOn(tasks.named("stackCoverageReport"))
    executionData(fileTree(layout.buildDirectory.dir("jacoco")).include("*.exec"))
    classDirectories.setFrom(sourceSets.main.get().output.asFileTree.matching { exclude(programs) })
    violationRules {
        rule {
            limit {
                counter = "LINE"
                minimum = "0.80".toBigDecimal()
            }
        }
    }
}

// The program and its libraries in one folder, for the profile-lsm container of compose.yaml
// (scripts/stack.sh rocks), so RocksDB runs on the same Docker disk as PostgreSQL.
tasks.register<Sync>("lsmClasspath") {
    group = "seatwave"
    description = "Copies the classes and runtime libraries to build/lsm for the RocksDB container."
    from(tasks.jar)
    from(configurations.runtimeClasspath)
    into(layout.buildDirectory.dir("lsm"))
}

// One task per program, so a reader runs one command per example. The working folder is the
// project, where config/ and .secrets/ are.
fun registerProgram(taskName: String, mainClassName: String, text: String) =
    tasks.register<JavaExec>(taskName) {
        group = "seatwave"
        description = text
        classpath = sourceSets.main.get().runtimeClasspath
        mainClass = mainClassName
        workingDir = projectDir
    }

registerProgram("btreeDemo", "io.plainround.seatwave.indexes.model.BTreeDemoCli", "What a B-tree lookup and insert touch")
registerProgram("lsmDemo", "io.plainround.seatwave.indexes.model.LsmDemoCli", "Memtable, SSTables, compaction, falling behind")
registerProgram("storageEstimate", "io.plainround.seatwave.indexes.estimate.StorageEstimateCli", "Pages, sizes, heights, query estimates")
registerProgram("writeCost", "io.plainround.seatwave.indexes.estimate.WriteCostCli", "Index entries per hold write")
registerProgram("indexLedger", "io.plainround.seatwave.indexes.ledger.IndexLedgerCli", "The index ledger, as a report").configure {
    args(
        "src/main/resources/db/study/index-ledger.txt",
        "src/main/resources/db/study/bare-schema.sql",
        "src/main/resources/db/study/final-indexes.sql",
    )
}
registerProgram("seed", "io.plainround.seatwave.indexes.study.SeedCli", "Load the study volume (dry run unless --run)")
registerProgram("plans", "io.plainround.seatwave.indexes.study.PlanRunnerCli", "EXPLAIN ANALYZE and time the five queries")
registerProgram("writeBenchmark", "io.plainround.seatwave.indexes.study.WriteBenchmarkCli", "Writes per second as indexes are added")
registerProgram("rocksHolds", "io.plainround.seatwave.indexes.lsm.RocksHoldWritesCli", "The hold writes against RocksDB").configure {
    // rocksdbjni loads its native library through System.loadLibrary, which Java 25 allows
    // without a warning only when native access is granted.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
registerProgram("scanTypes", "io.plainround.seatwave.indexes.labs.ScanTypesCli", "Lab 1: the scan nodes of each query")
registerProgram("columnOrder", "io.plainround.seatwave.indexes.labs.ColumnOrderCli", "Lab 2: column order decides index use")
