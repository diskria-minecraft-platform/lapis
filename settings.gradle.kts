pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
    }
}

plugins {
    id("io.github.diskria.projektor") version "8.0.12"
}

projektor {
    version = "0.9.1"
    license { mit() }
    monorepo {
        kotlinLibrary(":lapis-ksp", "lapis")
        kotlinLibrary(":lapis-annotations")
    }
}

dependencyResolutionManagement {
    repositories {
        maven("https://repo.spongepowered.org/repository/maven-public") {
            name = "SpongePublic"
            content { includeGroup("org.spongepowered") }
        }
    }
}
