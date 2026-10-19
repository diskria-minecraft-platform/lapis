pluginManagement {
    repositories {
        // TODO: Remove after Projektor 8.0.16 release
        mavenLocal()

        mavenCentral()
        gradlePluginPortal()
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
        kotlinLibrary(":lapis-annotations")
        kotlinLibrary(":lapis-core")
        kotlinLibrary(":lapis-kcp")
        kotlinLibrary(":lapis-ksp")
    }
}

dependencyResolutionManagement {
    repositories {
        // TODO: Remove after Poetesse 0.1.5 release
        mavenLocal()
    }
}
