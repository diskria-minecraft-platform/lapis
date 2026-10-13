projekt {
    gradlePlugin {
        supportsIsolatedProjects = true
    }
    distribute {
        mavenLocal()
//        gradlePluginPortal() TODO: Uncomment upon Lapis 1.0.0 release
    }
}

dependencies {
    implementation(project(":lapis-core"))
    implementation(libs.ksp.plugin)
    implementation(libs.kotlin.serialization.json)
}
