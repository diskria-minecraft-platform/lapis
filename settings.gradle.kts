pluginManagement {
    repositories {
        gradlePluginPortal()
    }
}

plugins {
    id("io.github.diskria.projektor") version "8.0.7"
    id("io.github.recrafter.recipe") version "1.2.6"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        mavenLocal()
    }
}

projekt {
    version = "0.9.1"
    license { mit() }
    kotlinLibrary()
}

recipe {
    crafter {
        mavensOnly()
    }
}
