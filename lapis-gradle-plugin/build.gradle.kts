projekt {
    gradlePlugin {
        supportsIsolatedProjects = true
    }
    distribute {
        mavenLocal()
        gradlePluginPortal()
    }
}

dependencies {
    implementation(libs.ksp.plugin)
    implementation(libs.kotlin.serialization.json)
}
