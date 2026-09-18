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
    }
}

dependencyResolutionManagement {
    repositories {
        mavenLocal()
    }
}
