plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val ksPath: String? = System.getenv("KEYSTORE_PATH")
val hasKeystore = ksPath != null && file(ksPath).exists() &&
    !System.getenv("KEYSTORE_PASSWORD").isNullOrEmpty() &&
    !System.getenv("KEY_ALIAS").isNullOrEmpty() &&
    !System.getenv("KEY_PASSWORD").isNullOrEmpty()

android {
    namespace = "com.kalibox.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.kalibox.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        if (hasKeystore) {
            create("release") {
                storeFile = file(ksPath!!)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasKeystore) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions { kotlinCompilerExtensionVersion = libs.versions.composeCompiler.get() }

    packaging {
        jniLibs { useLegacyPackaging = true }
        resources { excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/DEPENDENCIES", "/META-INF/LICENSE*", "/META-INF/NOTICE*") }
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        checkReleaseBuilds = false
        disable += setOf("ObsoleteSdkInt", "DataExtractionRules", "MissingTranslation", "OldTargetApi", "GradleDependency", "NewerVersionAvailable")
    }

    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.security.crypto)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.commons.compress)
    implementation(libs.tukaani.xz)
    implementation(libs.termux.terminal.view)
    implementation(libs.termux.terminal.emulator)
    testImplementation(libs.junit)
}
