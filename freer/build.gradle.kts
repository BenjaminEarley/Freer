import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.SourcesJar
import kotlinx.validation.ExperimentalBCVApi
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    alias(libs.plugins.binary.compatibility.validator)
    alias(libs.plugins.maven.publish)
    signing
}

// The group and version come from the root gradle.properties and become the Maven
// coordinates com.benjaminearley:freer:<version>.
//
// Publishing to Maven Central reads credentials and the signing key from Gradle properties,
// which belong in ~/.gradle/gradle.properties or in ORG_GRADLE_PROJECT_<name> environment
// variables, never in this repository:
//   mavenCentralUsername, mavenCentralPassword  Central Portal user token
//   signingInMemoryKey                          ASCII-armored PGP private key
//   signingInMemoryKeyId                        short key id (optional)
//   signingInMemoryKeyPassword                  passphrase of the key
// Without a signing key the publications stay unsigned, so publishToMavenLocal works on any
// machine; Maven Central rejects unsigned uploads.
mavenPublishing {
    configure(
        KotlinMultiplatform(
            javadocJar = JavadocJar.Empty(),
            sourcesJar = SourcesJar.Sources(),
        ),
    )
    publishToMavenCentral()
    signAllPublications()

    pom {
        name = "Freer"
        description = "Freer monads and extensible effects for Kotlin Multiplatform."
        inceptionYear = "2025"
        url = "https://github.com/BenjaminEarley/Freer"
        licenses {
            license {
                name = "MIT License"
                url = "https://opensource.org/licenses/MIT"
                distribution = "repo"
            }
        }
        developers {
            developer {
                id = "BenjaminEarley"
                name = "Benjamin Earley"
                url = "https://github.com/BenjaminEarley"
            }
        }
        scm {
            url = "https://github.com/BenjaminEarley/Freer"
            connection = "scm:git:https://github.com/BenjaminEarley/Freer.git"
            developerConnection = "scm:git:ssh://git@github.com/BenjaminEarley/Freer.git"
        }
    }
}

// Sign whenever a key is configured, and skip signing rather than fail when none is.
signing {
    isRequired = providers.gradleProperty("signingInMemoryKey").isPresent
}

apiValidation {
    @OptIn(ExperimentalBCVApi::class)
    klib {
        enabled = true
    }
}

@OptIn(ExperimentalKotlinGradlePluginApi::class, ExperimentalWasmDsl::class)
kotlin {
    explicitApi()
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
    iosArm64()
    iosSimulatorArm64()
    linuxX64()
    linuxArm64()
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
