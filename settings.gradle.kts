pluginManagement {
    repositories {
        // TODO: Remove after Projektor 8.0.16 release
        mavenLocal()

        mavenCentral()
        gradlePluginPortal()

        // TODO: Required for KSP API (KSContextParameter support). Remove after KSP API 2.3.13 release.
        maven("https://central.sonatype.com/repository/maven-snapshots")
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

        // TODO: Required for KSP API (KSContextParameter support). Remove after KSP API 2.3.13 release.
        maven("https://central.sonatype.com/repository/maven-snapshots")
    }
}
