plugins {
    alias(libs.plugins.ksp)
}

projekt {
    kotlinLibrary()
    distribute {
        mavenLocal()
        mavenCentral()
    }
}

dependencies {
    implementation(project(":lapis-annotations"))
    implementation(libs.ksp.api)
    implementation(libs.kotlin.poet.ksp)

    implementation(libs.poetesse)

    implementation(libs.mixin)
    implementation(libs.mixin.extras)
    implementation(libs.asm)

    implementation(libs.kotlin.serialization.json)

    ksp(libs.auto.service.ksp)
    compileOnly(libs.auto.service.annotations)
}
