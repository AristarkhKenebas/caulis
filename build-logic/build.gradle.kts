plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.dokka.gradle.plugin)
    implementation(libs.compose.compiler.gradle.plugin)
}
