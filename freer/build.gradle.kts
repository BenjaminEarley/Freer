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
    alias(libs.plugins.dokka)
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

    // Checks the public API of the JVM jar and of every klib target against the dumps in api/,
    // as part of `check` (task checkKotlinAbi). The build is in explicit API mode, so every
    // public declaration carries its visibility and return type. After an intentional API
    // change, run `./gradlew updateKotlinAbi` and commit the updated dumps with the change.
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

// The API reference: `./gradlew :freer:dokkaGenerate` writes build/dokka/html, which CI
// publishes to GitHub Pages from main. Every declaration links to its source on GitHub.
dokka {
    moduleName = "freer"
    dokkaSourceSets.configureEach {
        sourceLink {
            localDirectory = layout.projectDirectory.dir("src")
            remoteUrl("https://github.com/BenjaminEarley/Freer/tree/main/freer/src")
            remoteLineSuffix = "#L"
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
// below the score threshold. -PpitestMutators=ALL runs the full set, -PpitestThreshold=N
// overrides the bar.
//
// The bar is set just under the score the tests reach, so that a test that stops detecting
// a mutant fails the build. Of the 239 mutants the default operators make in FFree.kt,
// ProgramDsl.kt and the built-in handlers (at v0.2.0), the tests detect 164. Each of the 75
// that survive is one of: a Kotlin compiler artifact (null-check intrinsics on casts,
// coroutine state machines, the never-executed originals of inline functions); a fast path
// equivalent to the path it skips (among them the identity checks by which the loops
// recognise a returned continuation); a null branch or a defensive branch the interpreter's
// invariants make unreachable; an unused accessor; one of three guards that only a data
// race can observe, covered by the JCStress tests instead; or a Pitest artefact whose
// hand-applied mutation does fail its test. None is a behaviour the tests leave open. The
// full operator set makes 1162 mutants, of which 881 are detected.
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

// The README names the public API in backticks, and every such name must exist in the dumps
// in api/: a rename or a removal then fails `check` instead of leaving a stale name in the
// front door. Only identifier-shaped spans are checked (a dotted name, optionally with type
// arguments, empty parentheses or a trailing block); paths, coordinates, commands and file
// names are left alone, as are the fenced code blocks, which the examples module compiles
// and runs. `proceed` is the one name the dumps cannot carry: it is a parameter of the
// intercept rule, and the dumps record parameters by type only.
tasks.register("checkReadmeIdentifiers") {
    group = "verification"
    description = "Checks that every backticked identifier in README.md is declared in api/"
    // Locals rather than script-level values: the action below is serialized into the
    // configuration cache, which cannot hold a reference to the script.
    val readme = rootProject.layout.projectDirectory.file("README.md")
    val apiDumps = layout.projectDirectory.dir("api")
    inputs.file(readme)
    inputs.dir(apiDumps)
    doLast {
        val declared = mutableSetOf("proceed")
        val packageName = Regex("""com[./]benjaminearley[./]freer(?:[./]effects)?""")
        val qualified = Regex("""com[./]benjaminearley[./]freer(?:[./]effects)?[/.$]([A-Za-z_][\w$.]*)""")
        val jvmFunction = Regex("""\bfun ([A-Za-z_]\w*) \(""")
        val accessor = Regex("""^(?:get|is)([A-Z])(\w*)$""")
        apiDumps.asFileTree.forEach { dump ->
            val text = dump.readText()
            packageName.findAll(text).forEach { match -> declared += match.value.split('.', '/') }
            qualified.findAll(text).forEach { match -> declared += match.groupValues[1].split('$', '.') }
            jvmFunction.findAll(text).forEach { match ->
                val name = match.groupValues[1]
                declared += name
                // A JVM accessor stands for a Kotlin property: getValue for value.
                accessor.find(name)?.let { declared += it.groupValues[1].lowercase() + it.groupValues[2] }
            }
        }
        val prose = readme.asFile.readText().replace(Regex("""(?s)```.*?```"""), "")
        val identifier = Regex("""^\.?([A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*)(?:<.*>)?(?:\(\))?(?: \{ \})?$""")
        val fileName = Regex("""\.(kt|kts|hs|md|api|yml)$""")
        val missing =
            Regex("`([^`]+)`")
                .findAll(prose)
                .map { it.groupValues[1] }
                .filterNot { span -> fileName.containsMatchIn(span) }
                .mapNotNull { span -> identifier.find(span)?.groupValues?.get(1) }
                .flatMap { name -> name.split('.') }
                .filterNot { name -> name in declared }
                .distinct()
                .toList()
        check(missing.isEmpty()) { "README.md names identifiers the API dumps in api/ do not declare: $missing" }
    }
}

tasks.named("check") {
    dependsOn("checkReadmeIdentifiers")
}
