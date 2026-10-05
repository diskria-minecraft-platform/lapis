pluginManagement {
    repositories {
        mavenLocal()
        mavenCentral()
        gradlePluginPortal()
        maven("https://central.sonatype.com/repository/maven-snapshots") // TODO: Required for Context Parameters support. Switch to stable once out.
    }
}

plugins {
    id("io.github.diskria.projektor") version "8.0.15"
}

projektor {
    version = "0.10.0-SNAPSHOT"
    license { mit() }
    monorepo {
        gradlePlugin(":lapis-gradle-plugin", "lapis")
        kotlinLibrary(":lapis-ksp")
        kotlinLibrary(":lapis-annotations")
        kotlinLibrary(":lapis-core")
    }
}

dependencyResolutionManagement {
    repositories {
        mavenLocal()
        maven("https://central.sonatype.com/repository/maven-snapshots") // TODO: Required for Context Parameters support. Switch to stable once out.
    }
}
