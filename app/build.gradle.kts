import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/*
 * Read local.properties manually.
 *
 * This file stays on YOUR computer and should not be uploaded to GitHub.
 */
val localProperties =
    Properties().apply {

        val localPropertiesFile =
            rootProject.file("local.properties")

        if (localPropertiesFile.exists()) {

            localPropertiesFile
                .inputStream()
                .use {
                    load(it)
                }
        }
    }

/*
 * Read our Gemini key from:
 *
 * GEMINI_API_KEY=xxxxxxxx
 */
val geminiApiKey =
    localProperties
        .getProperty(
            "GEMINI_API_KEY",
            ""
        )
        .trim()


android {
    namespace = "com.example.chessai"

    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.chessai"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner =
            "androidx.test.runner.AndroidJUnitRunner"

        /*
         * Put the local key into BuildConfig for THIS build.
         *
         * Kotlin can then use:
         *
         * BuildConfig.GEMINI_API_KEY
         */
        buildConfigField(
            "String",
            "GEMINI_API_KEY",
            "\"$geminiApiKey\""
        )
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }

    compileOptions {
        sourceCompatibility =
            JavaVersion.VERSION_11

        targetCompatibility =
            JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}


dependencies {
    implementation(
        platform(
            libs.androidx.compose.bom
        )
    )

    implementation(
        libs.androidx.activity.compose
    )

    implementation(
        libs.androidx.compose.material3
    )

    implementation(
        libs.androidx.compose.ui
    )

    implementation(
        libs.androidx.compose.ui.graphics
    )

    implementation(
        libs.androidx.compose.ui.tooling.preview
    )

    implementation(
        libs.androidx.core.ktx
    )

    implementation(
        libs.androidx.lifecycle.runtime.ktx
    )


    implementation(
        "androidx.compose.material:material-icons-extended"
    )

    implementation(
        "com.rmtheis:tess-two:9.1.0"
    )

    implementation(
        "org.opencv:opencv:4.9.0"
    )


    testImplementation(
        libs.junit
    )


    androidTestImplementation(
        platform(
            libs.androidx.compose.bom
        )
    )

    androidTestImplementation(
        libs.androidx.compose.ui.test.junit4
    )

    androidTestImplementation(
        libs.androidx.espresso.core
    )

    androidTestImplementation(
        libs.androidx.junit
    )


    debugImplementation(
        libs.androidx.compose.ui.test.manifest
    )

    debugImplementation(
        libs.androidx.compose.ui.tooling
    )
}