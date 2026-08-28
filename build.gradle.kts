import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(convention.plugins.projektor)
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
    implementation(libs.ksp.api)
    implementation(libs.kotlin.poet.ksp)

    implementation(libs.lapis.annotations)
    implementation(libs.poetesse)

    implementation(libs.mixin)
    implementation(libs.mixin.extras)
    implementation(libs.asm)

    implementation(libs.kotlin.serialization.json)

    ksp(libs.auto.service)
    implementation(libs.auto.service.annotations)
}

tasks {
    withType<KotlinCompile>().configureEach {
        compilerOptions.freeCompilerArgs.add("-Xcontext-parameters")
    }
}
