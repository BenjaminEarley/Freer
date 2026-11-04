plugins {
    kotlin("jvm")
    application
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":freer"))
}

application {
    mainClass = "com.benjaminearley.freer.examples.MainKt"
}

// The README's Kotlin blocks are compiled and run rather than trusted. readmeSnippets copies
// every fenced ```kotlin block of README.md into a generated source file, one package per
// block so that each may define its own main; the main source set compiles them against the
// library, and runReadme runs every block's main through a generated entry point. check
// depends on it, so a README example that no longer compiles, or no longer runs, fails CI.
val readmeSnippets =
    tasks.register("readmeSnippets") {
        description = "Extracts the README's Kotlin blocks into generated sources"
        // Locals rather than script-level values: the action below is serialized into the
        // configuration cache, which cannot hold a reference to the script.
        val readme = rootProject.layout.projectDirectory.file("README.md")
        val outputDir = layout.buildDirectory.dir("generated/readme/kotlin")
        inputs.file(readme)
        outputs.dir(outputDir)
        doLast {
            val dir = outputDir.get().asFile
            dir.deleteRecursively()
            val blocks =
                Regex("""(?ms)^```kotlin\n(.*?)^```$""")
                    .findAll(readme.asFile.readText())
                    .map { it.groupValues[1] }
                    .toList()
            check(blocks.isNotEmpty()) { "README.md has no fenced kotlin block" }
            val mains = mutableListOf<String>()
            blocks.forEachIndexed { index, code ->
                val number = index + 1
                val packageName = "readme.block$number"
                val file = dir.resolve("readme/block$number/Block$number.kt")
                file.parentFile.mkdirs()
                file.writeText("package $packageName\n\n$code")
                if (Regex("""\bfun main\(\)""").containsMatchIn(code)) mains += "$packageName.main()"
            }
            check(mains.isNotEmpty()) { "No kotlin block of README.md defines main" }
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
    description = "Compiles and runs the README's Kotlin blocks"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "readme.RunKt"
}

tasks.named("check") {
    dependsOn("runReadme")
}
