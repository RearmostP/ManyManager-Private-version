import java.io.File
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}
dependencies {
    implementation(project(":shared"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    testImplementation(libs.junit)

    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.room.runtime)
    // Align app/test serialization: Room's schema test helper requires JSON/core 1.8.1.
    implementation(platform(libs.kotlinx.serialization.bom))
    implementation(libs.kotlinx.coroutines.android)
    ksp(libs.androidx.room.compiler)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.testExt.junit)
    androidTestImplementation(libs.androidx.testRunner)
    androidTestImplementation(libs.androidx.room.testing)

    implementation(libs.compose.uiToolingPreview)
    debugImplementation(libs.compose.uiTooling)
}

room {
    schemaDirectory("$projectDir/schemas")
}

// Credentials are supplied only to the local Gradle process; never put values in source control.
val releaseSigningEnvironment = listOf(
    "MANYMANAGER_RELEASE_STORE_FILE", "MANYMANAGER_RELEASE_STORE_PASSWORD",
    "MANYMANAGER_RELEASE_KEY_ALIAS", "MANYMANAGER_RELEASE_KEY_PASSWORD",
).associateWith { providers.environmentVariable(it).orNull }

val validatePrivateReleaseSigning = tasks.register("validatePrivateReleaseSigning") {
    doLast {
        val missing = listOf(
            "MANYMANAGER_RELEASE_STORE_FILE", "MANYMANAGER_RELEASE_STORE_PASSWORD",
            "MANYMANAGER_RELEASE_KEY_ALIAS", "MANYMANAGER_RELEASE_KEY_PASSWORD",
        ).filter { System.getenv(it).isNullOrBlank() }
        check(missing.isEmpty()) {
            "Release signing credentials are missing: ${missing.joinToString()}. Use scripts/release.ps1; see docs/release.md."
        }
        check(File(System.getenv("MANYMANAGER_RELEASE_STORE_FILE")).isFile) {
            "Release keystore does not exist. See docs/release.md."
        }
    }
}
tasks.configureEach {
    if (name == "preReleaseBuild") dependsOn(validatePrivateReleaseSigning)
}

android {
    signingConfigs {
        if (releaseSigningEnvironment.values.all { !it.isNullOrBlank() }) {
            create("privateRelease") {
                storeFile = file(releaseSigningEnvironment.getValue("MANYMANAGER_RELEASE_STORE_FILE")!!)
                storePassword = releaseSigningEnvironment.getValue("MANYMANAGER_RELEASE_STORE_PASSWORD")
                keyAlias = releaseSigningEnvironment.getValue("MANYMANAGER_RELEASE_KEY_ALIAS")
                keyPassword = releaseSigningEnvironment.getValue("MANYMANAGER_RELEASE_KEY_PASSWORD")
            }
        }
    }
    sourceSets.getByName("androidTest").assets.directories.add("$projectDir/schemas")
    namespace = "com.example.simple.manymanager"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.manymanager.privateversion"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 3
        versionName = "0.3.0"
        testInstrumentationRunner = "com.example.simple.manymanager.DatabaseTestRunner"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("privateRelease")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}