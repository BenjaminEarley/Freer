plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.maven.publish) apply false
    alias(libs.plugins.kotlin.allopen) apply false
    alias(libs.plugins.kotlinx.benchmark) apply false
    alias(libs.plugins.dokka)
}

// The API reference of both published modules on one site: `./gradlew :dokkaGenerate` writes
// build/dokka/html, which CI publishes to GitHub Pages from main.
dependencies {
    dokka(project(":freer"))
    dokka(project(":freer-trace"))
}

dokka {
    moduleName = "Freer"
}
