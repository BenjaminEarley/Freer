import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
}

group = "com.benjaminearley"
version = "0.1.0-SNAPSHOT"

kotlin {
    jvmToolchain(17)

    jvm {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
        // The tests inline the examples' reified handlers, which are built for the toolchain's target.
        compilations["test"].compileTaskProvider.configure {
            compilerOptions.jvmTarget = JvmTarget.JVM_17
        }
    }

    sourceSets {
        jvmTest.dependencies {
            // SemanticsTest exercises the KVStore handler from the accounting example.
            implementation(project(":examples"))
        }
    }
}

// Temporary: runners for the main()-based harnesses in jvmTest, until they become kotlin-test suites.
listOf(
    "runTrampolineTest" to "com.benjaminearley.freer.TrampolineTestKt",
    "runSemanticsTest" to "com.benjaminearley.freer.SemanticsTestKt",
    "runBenchmark" to "com.benjaminearley.freer.BenchmarkKt",
).forEach { (taskName, harness) ->
    tasks.register<JavaExec>(taskName) {
        group = "verification"
        val testCompilation = kotlin.jvm().compilations["test"]
        classpath = testCompilation.output.allOutputs + testCompilation.runtimeDependencyFiles!!
        mainClass = harness
    }
}
