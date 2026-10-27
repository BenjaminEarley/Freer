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
