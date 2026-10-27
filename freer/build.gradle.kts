import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
}

group = "com.benjaminearley"
version = "0.1.0-SNAPSHOT"

@OptIn(ExperimentalKotlinGradlePluginApi::class, ExperimentalWasmDsl::class)
kotlin {
    jvmToolchain(17)

    jvm {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }

    wasmJs {
        nodejs()
    }
    macosArm64()
    macosX64()
    iosArm64()
    iosSimulatorArm64()
    linuxX64()
    mingwX64()

    // Every target except the JVM shares nonJvmMain.
    applyDefaultHierarchyTemplate {
        common {
            group("nonJvm") {
                withWasmJs()
                withNative()
            }
        }
    }

    sourceSets {
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

// Runs the JVM benchmark harness, a main() in jvmTest.
listOf(
    "runBenchmark" to "com.benjaminearley.freer.BenchmarkKt",
).forEach { (taskName, harness) ->
    tasks.register<JavaExec>(taskName) {
        group = "verification"
        val testCompilation = kotlin.jvm().compilations["test"]
        classpath = testCompilation.output.allOutputs + testCompilation.runtimeDependencyFiles!!
        mainClass = harness
    }
}
