// The concurrency stress tests: Program values shared between threads and interpreted at
// the same time, run under JCStress, the OpenJDK memory-model stress harness. The test
// classes are Java, because the harness generates a runner for each of them with a javac
// annotation processor; the programs they share are built in Kotlin, in SharedPrograms.kt.
plugins {
    kotlin("jvm")
    application
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":freer"))
    implementation(libs.jcstress.core)
    annotationProcessor(libs.jcstress.core)
}

application {
    mainClass = "org.openjdk.jcstress.Main"
}

// `./gradlew :jcstress:run` runs every test in quick mode and writes the report to
// jcstress/build/jcstress/results/index.html. `--args` replaces the arguments, for example
// `--args="-m default"` for a longer run or `--args="-t Racy"` for the tests matching a
// pattern; `-PjcstressJdk=21` runs the harness and its forked JVMs on another JDK.
tasks.named<JavaExec>("run") {
    // A local rather than a script property: the doFirst action is serialized into the
    // configuration cache, and a script property would drag the script object in with it.
    val resultsDir = layout.buildDirectory.dir("jcstress").get().asFile
    val jcstressJdk = providers.gradleProperty("jcstressJdk").map { JavaLanguageVersion.of(it) }
    workingDir = resultsDir
    args("-m", "quick")
    if (jcstressJdk.isPresent) {
        javaLauncher = javaToolchains.launcherFor { languageVersion = jcstressJdk.get() }
    }
    doFirst { resultsDir.mkdirs() }
}
