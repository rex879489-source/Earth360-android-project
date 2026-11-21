plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Every CI run gets a higher versionCode so new builds install over old ones.
val runNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

// If you add your own keystore as GitHub secrets, the workflow exports these variables.
val envKeystore = System.getenv("RELEASE_KEYSTORE_PATH")

android {
    namespace = "com.earth360.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.earth360.app"
        minSdk = 29
        targetSdk = 34
        versionCode = runNumber
        versionName = "1.0.$runNumber"
    }

    signingConfigs {
        create("shared") {
            if (envKeystore != null && file(envKeystore).exists()) {
                storeFile = file(envKeystore)
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            } else {
                // Fallback key committed to the repo so every build has the same signature
                storeFile = file("earth360.keystore")
                storePassword = "earth360"
                keyAlias = "earth360"
                keyPassword = "earth360"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.webkit:webkit:1.11.0")
}
