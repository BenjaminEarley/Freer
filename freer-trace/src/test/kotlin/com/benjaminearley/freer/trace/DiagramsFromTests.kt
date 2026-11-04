package com.benjaminearley.freer.trace

import java.io.File

/** Appends a diagram to the file CI parses with Mermaid's parser, when the test task names one. */
internal fun recordDiagram(
    title: String,
    diagram: String,
) {
    val file = File(System.getProperty("freer.diagramsFromTests") ?: return)
    file.parentFile.mkdirs()
    file.appendText("## $title\n\n```mermaid\n${diagram.trimEnd()}\n```\n\n")
}
