plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// O SDK da Samsung não está no Maven e não é versionado (licença).
// Baixe em developer.samsung.com/health/sensor e coloque o .aar em app/libs/.
val samsungAars = fileTree("libs") { include("samsung-health-sensor-api*.aar") }
if (samsungAars.isEmpty) {
    throw GradleException(
        "Samsung Health Sensor SDK ausente: coloque samsung-health-sensor-api-*.aar em app/libs/ (ver README)."
    )
}

android {
    namespace = "net.extrasensors.watch"
    compileSdk {
        version = release(37) { minorApiLevel = 1 }
    }

    defaultConfig {
        applicationId = "net.extrasensors.watch"
        minSdk = 34
        targetSdk = 37
        versionCode = 6
        versionName = "1.5"
    }

    signingConfigs {
        // Chave fixa do projeto: todas as compilações têm a mesma assinatura,
        // então "adb install -r" atualiza o app sem apagar os dados.
        create("project") {
            storeFile = rootProject.file("keystore/project-debug.jks")
            storePassword = "android"
            keyAlias = "extrasensors"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("project")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("project")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    jvmToolchain(21)
}

ksp {
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(samsungAars)
    implementation(project(":shared"))
    implementation(libs.play.services.wearable)
    implementation(libs.kotlinx.coroutines.play.services)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.wear.compose.material3)
    implementation(libs.wear.compose.foundation)
    implementation(libs.wear.compose.navigation)

    implementation(libs.wear.ongoing)
    implementation(libs.wear.complications.data.source.ktx)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // Só para renderizar as telas em testes (verificação de layout); não entra no APK de release.
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
