buildscript {
    dependencies {
        // AGP 9 provides built-in Kotlin. Pin a newer KGP runtime so it matches
        // the Compose compiler plugin version used by this project.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.21")
    }
}

plugins {
    id("com.android.application") version "9.2.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
}
