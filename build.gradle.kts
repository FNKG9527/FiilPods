// Top-level build file for FiilPods.
buildscript {
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
    }
}

plugins {
    alias(libs.plugins.agp.app) apply false
    alias(libs.plugins.agp.lib) apply false
}

tasks.register<Delete>("clean") {
    delete(layout.buildDirectory)
}
