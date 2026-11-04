import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.SourcesJar
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

plugins {
    kotlin("multiplatform")
    alias(libs.plugins.kotlin.allopen)
    alias(libs.plugins.kotlinx.benchmark)
    alias(libs.plugins.maven.publish)
    signing
}

// JMH subclasses benchmark classes, so they must be open.
allOpen {
    annotation("org.openjdk.jmh.annotations.State")
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

// The built-in ABI validation DSL is experimental in Kotlin 2.4 and needs an explicit opt-in.
@OptIn(ExperimentalKotlinGradlePluginApi::class, ExperimentalWasmDsl::class, ExperimentalAbiValidation::class)
kotlin {
    explicitApi()

    // Checks the public API of the JVM jar and of every klib target against the dumps in api/.
    abiValidation()

    jvmToolchain(17)

    jvm {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
            // Check calls against the JDK 11 API even though the build runs on the 17 toolchain.
            freeCompilerArgs.add("-Xjdk-release=11")
        }
        // The JMH benchmarks, in freer/src/jvmBenchmark, see the library's internals like the tests do.
        compilations.create("benchmark") {
            associateWith(this@jvm.compilations.getByName("main"))
            compileTaskProvider.configure {
                compilerOptions.freeCompilerArgs.add("-Xexplicit-api=disable")
            }
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
            // Property-based tests: kotest's generators and shrinking, driven by runTest since
            // checkAll suspends. Both ship wasmJs and every native target this library builds.
            implementation(libs.kotest.property)
            implementation(libs.kotlinx.coroutines.test)
        }
        // kotest brings an older kotlin-reflect; the JVM test runtime needs the one matching the compiler.
        jvmTest.dependencies {
            implementation(kotlin("reflect"))
        }
        getByName("jvmBenchmark").dependencies {
            implementation(libs.kotlinx.benchmark.runtime)
        }
    }
}

// One forked JVM per benchmark, with the same warm-up and measurement as the annotations on
// the benchmark classes, so that `./gradlew :freer:jvmBenchmark` and running the jar from
// `jvmBenchmarkJar` by hand (for example with `-prof gc`) measure the same thing.
benchmark {
    configurations {
        named("main") {
            warmups = 5
            iterations = 5
            iterationTime = 1
            iterationTimeUnit = "s"
            mode = "avgt"
            outputTimeUnit = "ns"
            advanced("jvmForks", 1)
        }
        // A quick pass over every case, to check that they run rather than to measure them.
        register("smoke") {
            warmups = 1
            iterations = 1
            iterationTime = 200
            iterationTimeUnit = "ms"
            mode = "avgt"
            outputTimeUnit = "ns"
            advanced("jvmForks", 1)
        }
    }
    targets {
        register("jvmBenchmark")
    }
}

// The plugin's jar task picks the class directories to pack through a provider that checks
// whether they exist. Under the configuration cache that provider is evaluated when the
// cache entry is stored, before a fresh build has compiled anything, so the stored jar task
// leaves out the benchmark classes themselves. Running the task outside the configuration
// cache evaluates the provider at execution, which keeps the jar self-contained, as running
// it by hand with a profiler needs.
afterEvaluate {
    tasks.named("jvmBenchmarkBenchmarkJar") {
        notCompatibleWithConfigurationCache("the benchmark plugin resolves the class directories when the cache entry is stored")
    }
}

// Mutation testing of the JVM classes: `./gradlew :freer:pitest` writes build/reports/pitest.
// Pitest runs from its command-line jar through a plain JavaExec, because its Gradle plugin
// expects a Java or Kotlin/JVM project and this is a multiplatform one. The mutable code is
// the main output of the JVM target; the tests are the JVM test classes, which kotlin-test
// runs under JUnit 4 on the JVM. toString, hashCode and equals are left alone: nothing but
// a debug string depends on them, so a mutant there would only ever be junk. The run fails
// below the score threshold; the README's Semantics section explains the number.
// -PpitestMutators=ALL runs the full set, -PpitestThreshold=N overrides the bar.
val pitest: Configuration by configurations.creating

dependencies {
    pitest(libs.pitest.command.line)
}

tasks.register<JavaExec>("pitest") {
    group = "verification"
    description = "Runs Pitest over the JVM classes and writes build/reports/pitest"
    dependsOn("jvmMainClasses", "jvmTestClasses")
    val mainClasses = kotlin.jvm().compilations.getByName("main").output.classesDirs
    val testClasses = kotlin.jvm().compilations.getByName("test").output.classesDirs
    // The resolved files rather than the Configuration: the argument provider below is
    // serialized into the configuration cache, which a Configuration cannot be.
    val testRuntime = configurations.getByName("jvmTestRuntimeClasspath").incoming.files
    val reportDir = layout.buildDirectory.dir("reports/pitest")
    val sourceDirs = listOf("commonMain", "jvmMain").map { layout.projectDirectory.dir("src/$it/kotlin").asFile.path }
    val mutators = providers.gradleProperty("pitestMutators").orElse("DEFAULTS")
    val threshold = providers.gradleProperty("pitestThreshold").orElse("66")
    classpath = pitest + mainClasses + testClasses + testRuntime
    mainClass = "org.pitest.mutationtest.commandline.MutationCoverageReport"
    argumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "--classPath", (mainClasses + testClasses + testRuntime).joinToString(","),
                "--mutableCodePaths", mainClasses.joinToString(","),
                "--targetClasses", "com.benjaminearley.freer.*",
                "--targetTests", "com.benjaminearley.freer.*",
                "--sourceDirs", sourceDirs.joinToString(","),
                "--reportDir", reportDir.get().asFile.path,
                "--outputFormats", "HTML,XML,CSV",
                "--timestampedReports", "false",
                "--mutators", mutators.get(),
                "--mutationThreshold", threshold.get(),
                "--excludedMethods", "toString,hashCode,equals",
                "--threads", "8",
            )
        },
    )
}

// Lets JPMS consumers require the JVM jar under a stable module name.
tasks.named<Jar>("jvmJar") {
    manifest {
        attributes("Automatic-Module-Name" to "com.benjaminearley.freer")
    }
}
