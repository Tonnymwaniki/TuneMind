// Top-level build file. Configuration here applies to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    // KSP plugin is NOT applied at the top level.
    // Explicit application causes "Cannot add extension with name 'kotlin'"
    // conflicts with AGP 9.x and KSP 2.x.
    // KSP is applied implicitly by AGP 9.x's built-in Kotlin support.
    // alias(libs.plugins.ksp) apply false
}
