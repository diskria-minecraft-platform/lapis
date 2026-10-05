projekt {
    kotlinLibrary()
    distribute {
        mavenLocal()
    }
}

dependencies {
    implementation(project(":lapis-annotations"))
    implementation(libs.kotlin.serialization.json)
    implementation(libs.poetesse)
}
