plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.vidcollage"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.vidcollage"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        viewBinding = true
    }

    androidResources {
        // The FaceNet weights must stay uncompressed so they can be memory-mapped from the APK.
        noCompress += "tflite"
    }

    testOptions {
        unitTests {
            // The pipeline's pure logic (tracking, clustering, scoring) is tested on the JVM; the
            // android.graphics types it touches only need to exist, not to do anything.
            isReturnDefaultValues = true
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.recyclerview)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.material)
    implementation(libs.mlkit.face.detection)
    implementation(libs.tensorflow.lite)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
