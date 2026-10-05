plugins {
    alias(libs.plugins.ksp)
}

projekt {
    kotlinLibrary()
    distribute {
        mavenLocal()
    }
}

dependencies {
    implementation(project(":lapis-annotations"))
    implementation(project(":lapis-core"))
    compileOnly(libs.kotlin.compiler)
    ksp(libs.auto.service.ksp)
    compileOnly(libs.auto.service.annotations)
    implementation(libs.poetesse)
}
