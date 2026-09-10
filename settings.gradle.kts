plugins {
    id("io.github.diskria.projektor") version "8.0.15"
}

projektor {
    version = "0.9.1"
    license { mit() }
    monorepo {
        gradlePlugin(":lapis-gradle-plugin", "lapis")
        kotlinLibrary(":lapis-ksp")
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
