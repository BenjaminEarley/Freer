import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

plugins {
    kotlin("jvm")
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.dokka)
    signing
}

// Traces of programs, published as com.benjaminearley:freer-trace at the library's version. JVM
// only: the responses an effect can have are enumerated from its response type, which is in
// Kotlin's metadata and needs kotlin-reflect, a dependency the library itself must not carry.
// Publishing and signing read the same Gradle properties as freer/build.gradle.kts.
mavenPublishing {
    configure(KotlinJvm(javadocJar = JavadocJar.Empty(), sourcesJar = true))
    publishToMavenCentral()
    signAllPublications()

    pom {
        name = "Freer Trace"
        description = "Traces of freer programs: one run recorded, every path explored, replayed, stored as text and drawn as Mermaid diagrams."
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

signing {
    isRequired = providers.gradleProperty("signingInMemoryKey").isPresent
}

// Explicit API and the API dumps in api/, checked by `check`, as for the library. Compiled by
// the 17 toolchain for JVM 11, like the library's JVM jar.
@OptIn(ExperimentalAbiValidation::class)
kotlin {
    explicitApi()
    abiValidation()
    jvmToolchain(17)
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
        freeCompilerArgs.add("-Xjdk-release=11")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

dependencies {
    api(project(":freer"))
    implementation(kotlin("reflect"))
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotest.property)
    testImplementation(libs.kotlinx.coroutines.test)
    // The laws check the path explorer against the library's specification interpreter on the
    // random programs of its law tests; both are in the library's JVM test classes.
    testImplementation(project(path = ":freer", configuration = "jvmTestClasses"))
}

// Lets JPMS consumers require the jar under a stable module name.
tasks.jar {
    manifest {
        attributes("Automatic-Module-Name" to "com.benjaminearley.freer.trace")
    }
}

dokka {
    moduleName = "freer-trace"
    dokkaSourceSets.configureEach {
        sourceLink {
            localDirectory = layout.projectDirectory.dir("src")
            remoteUrl("https://github.com/BenjaminEarley/Freer/tree/main/freer-trace/src")
            remoteLineSuffix = "#L"
        }
    }
}

// Every diagram the tests generate goes to this file, which CI parses with Mermaid's own parser.
tasks.test {
    val diagramsFromTests = layout.buildDirectory.file("diagrams/from-tests.md")
    systemProperty("freer.diagramsFromTests", diagramsFromTests.get().asFile.path)
    outputs.file(diagramsFromTests)
    doFirst { diagramsFromTests.get().asFile.delete() }
}
