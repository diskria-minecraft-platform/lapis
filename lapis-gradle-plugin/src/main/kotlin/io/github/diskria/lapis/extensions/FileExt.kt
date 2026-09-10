package io.github.diskria.lapis.extensions

import org.gradle.api.file.ProjectLayout
import java.io.File
import kotlin.io.path.invariantSeparatorsPathString

fun File.getRootRelativePathString(layout: ProjectLayout): String =
    relativeToOrNull(layout.settingsDirectory.asFile)?.toPath()?.invariantSeparatorsPathString ?: ".../$name"
