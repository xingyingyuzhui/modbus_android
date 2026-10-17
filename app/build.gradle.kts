plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.roborazzi)
    alias(libs.plugins.ksp)
}

android {
    namespace = "io.github.xingyingyuzhui.modbusmobile"
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }

    defaultConfig {
        applicationId = "io.github.xingyingyuzhui.modbusmobile"
        minSdk = 26
        targetSdk {
            version = release(37)
        }
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    lint {
        abortOnError = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":modbus-core"))
    implementation(libs.coroutines.android)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.navigation.compose)
    implementation(libs.core.ktx)
    implementation(libs.usb.serial)
    implementation(libs.datastore.preferences)
    implementation(libs.room.runtime)
    ksp(libs.room.compiler)
    implementation(libs.compose.material3.adaptive)

    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit4)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.robolectric)
}

roborazzi {
    outputDir.set(file("src/test/screenshots"))
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

tasks.withType<Test>().configureEach {
    systemProperty(
        "roborazzi.record.filePathStrategy",
        "relativePathFromRoborazziContextOutputDirectory",
    )
}
