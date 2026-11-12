import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Ключ release-подписи вынесен в keystore.properties (не коммитить)
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "ru.mqtt.panel"
    compileSdk = 36

    defaultConfig {
        applicationId = "ru.mqtt.panel"
        minSdk = 24
        targetSdk = 36
        versionCode = 68
        versionName = "0.2.56"
        vectorDrawables.useSupportLibrary = true
    }

    // Release-подпись своим ключом: debuggable=true и сертификат Android Debug
    // вызывают ложные срабатывания сканера MIUI/HyperOS («обнаружен опасный вирус»)
    signingConfigs {
        create("release") {
            storeFile = keystoreProps.getProperty("storeFile")?.let { rootProject.file(it) }
            storePassword = keystoreProps.getProperty("storePassword")
            keyAlias = keystoreProps.getProperty("keyAlias")
            keyPassword = keystoreProps.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.coordinatorlayout:coordinatorlayout:1.2.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.google.code.gson:gson:2.11.0")
    // MQTT-клиент: TCP, SSL и WebSocket (ws://, wss://) поддерживаются самой библиотекой
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")
    testImplementation("junit:junit:4.13.2")
}
