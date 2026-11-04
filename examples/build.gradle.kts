import java.io.ByteArrayOutputStream
import javax.inject.Inject

plugins {
    kotlin("jvm")
    application
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":freer"))
    implementation(project(":freer-trace"))
    testImplementation(libs.kotlin.test)
}

application {
    mainClass = "com.benjaminearley.freer.examples.MainKt"
}

// The Kotlin blocks of the READMEs, the library's and freer-trace's, are compiled and run
// rather than trusted. readmeSnippets copies every fenced ```kotlin block into a generated
// source file, one package per block so that each may define its own main; the main source
// set compiles them against both modules, and runReadme runs every block's main through a
// generated entry point. check depends on it, so a README example that no longer compiles,
// or no longer runs, fails CI.
val readmeSnippets =
    tasks.register("readmeSnippets") {
        description = "Extracts the READMEs' Kotlin blocks into generated sources"
        // Locals rather than script-level values: the action below is serialized into the
        // configuration cache, which cannot hold a reference to the script.
        val readmes =
            mapOf(
                "core" to rootProject.layout.projectDirectory.file("README.md"),
                "trace" to rootProject.layout.projectDirectory.file("freer-trace/README.md"),
            )
        val outputDir = layout.buildDirectory.dir("generated/readme/kotlin")
        inputs.files(readmes.values)
        outputs.dir(outputDir)
        doLast {
            val dir = outputDir.get().asFile
            dir.deleteRecursively()
            val mains = mutableListOf<String>()
            for ((name, readme) in readmes) {
                val blocks =
                    Regex("""(?ms)^```kotlin\n(.*?)^```$""")
                        .findAll(readme.asFile.readText())
                        .map { it.groupValues[1] }
                        .toList()
                check(blocks.isNotEmpty()) { "${readme.asFile} has no fenced kotlin block" }
                blocks.forEachIndexed { index, code ->
                    val number = index + 1
                    val packageName = "readme.$name.block$number"
                    val file = dir.resolve("readme/$name/block$number/Block$number.kt")
                    file.parentFile.mkdirs()
                    file.writeText("package $packageName\n\n$code")
                    if (Regex("""\bfun main\(\)""").containsMatchIn(code)) mains += "$packageName.main()"
                }
            }
            check(mains.isNotEmpty()) { "No kotlin block of the READMEs defines main" }
            dir.resolve("readme/Run.kt").writeText(
                "package readme\n\nfun main() {\n" + mains.joinToString("") { "    $it\n" } + "}\n",
            )
        }
    }

kotlin.sourceSets.named("main") {
    kotlin.srcDir(readmeSnippets)
}

tasks.register<JavaExec>("runReadme") {
    group = "verification"
    description = "Compiles and runs the READMEs' Kotlin blocks"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "readme.RunKt"
}

tasks.named("check") {
    dependsOn("runReadme")
}

// The trace README shows the diagrams its block prints. Its main runs with the output captured,
// and every ```mermaid block of the README must appear in that output, so the shown diagrams
// cannot drift from the code. A task class rather than a JavaExec, so that the captured output
// is task-local and the task fits the configuration cache.
abstract class CheckReadmeDiagrams : DefaultTask() {
    @get:Inject
    abstract val execOperations: ExecOperations

    @get:InputFiles
    abstract val classpath: ConfigurableFileCollection

    @get:Input
    abstract val mainClass: Property<String>

    @get:InputFile
    abstract val readme: RegularFileProperty

    @TaskAction
    fun check() {
        val printed = ByteArrayOutputStream()
        execOperations.javaexec {
            classpath = this@CheckReadmeDiagrams.classpath
            mainClass = this@CheckReadmeDiagrams.mainClass
            standardOutput = printed
        }
        val output = printed.toString(Charsets.UTF_8)
        val shown = Regex("""(?ms)^```mermaid\n(.*?)^```$""").findAll(readme.get().asFile.readText()).map { it.groupValues[1].trimEnd() }.toList()
        check(shown.isNotEmpty()) { "${readme.get().asFile} shows no mermaid block" }
        for (diagram in shown) {
            check(diagram in output) { "${readme.get().asFile} shows a diagram its block does not print:\n$diagram" }
        }
    }
}

tasks.register<CheckReadmeDiagrams>("checkReadmeDiagrams") {
    group = "verification"
    description = "Checks that the trace README shows the diagrams its block prints"
    classpath.from(sourceSets.main.get().runtimeClasspath)
    mainClass = "readme.trace.block1.Block1Kt"
    readme = rootProject.layout.projectDirectory.file("freer-trace/README.md")
}

tasks.named("check") {
    dependsOn("checkReadmeDiagrams")
}

// README.md of this module shows the diagrams of the diagrams scenario, which GitHub renders.
// The page is generated so that it cannot drift from the code: `diagrams` writes it under
// build/, `checkDiagrams` compares that with the committed page as part of `check`, and
// `updateDiagrams` copies it over after a change.
val diagrams =
    tasks.register<JavaExec>("diagrams") {
        description = "Generates the diagrams page under build/"
        val page = layout.buildDirectory.file("generated/diagrams/README.md")
        classpath = sourceSets.main.get().runtimeClasspath
        mainClass = "com.benjaminearley.freer.examples.DiagramsKt"
        outputs.file(page)
        args(page.get().asFile.path)
    }

val updateDiagrams =
    tasks.register("updateDiagrams") {
        description = "Regenerates README.md from the diagrams scenario"
        val generated = diagrams.map { it.outputs.files.singleFile }
        val committed = layout.projectDirectory.file("README.md")
        inputs.file(generated)
        outputs.file(committed)
        doLast { generated.get().copyTo(committed.asFile, overwrite = true) }
    }

tasks.register("checkDiagrams") {
    group = "verification"
    description = "Checks that README.md is the generated diagrams page"
    mustRunAfter(updateDiagrams)
    val generated = diagrams.map { it.outputs.files.singleFile }
    val committed = layout.projectDirectory.file("README.md")
    inputs.file(generated)
    inputs.file(committed)
    doLast {
        check(generated.get().readText() == committed.asFile.readText()) {
            "examples/README.md is stale: run ./gradlew :examples:updateDiagrams and commit it"
        }
    }
}

tasks.named("check") {
    dependsOn("checkDiagrams")
}
