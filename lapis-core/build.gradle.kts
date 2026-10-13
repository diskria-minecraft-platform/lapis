projekt {
    kotlinLibrary()
    distribute {
        mavenLocal()
//        mavenCentral() TODO: Uncomment upon Lapis 1.0.0 release
    }
}

dependencies {
    implementation(project(":lapis-annotations"))
    implementation(libs.kotlin.serialization.json)
    implementation(libs.poetesse)
}
