plugins {
    alias(libs.plugins.ksp)
}

projekt {
    kotlinLibrary()
    distribute {
        mavenLocal()
//        mavenCentral() TODO: Uncomment upon Lapis 1.0.0 release
    }
}

dependencies {
    implementation(project(":lapis-annotations"))
    implementation(project(":lapis-core"))
    compileOnly(kotlin("compiler-embeddable"))
    compileOnly(kotlin("compiler"))
    ksp(libs.auto.service.ksp)
    compileOnly(libs.auto.service.annotations)
    implementation(libs.poetesse)
}
