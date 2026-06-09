plugins {
    id("com.android.application")
    id("kotlin-android")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

android {
    namespace = "com.example.hs_disconnect"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_11.toString()
    }

    defaultConfig {
        // TODO: Specify your own unique Application ID (https://developer.android.com/studio/build/application-id.html).
        applicationId = "com.example.hs_disconnect"
        // You can update the following values to match your application needs.
        // For more information, see: https://flutter.dev/to/review-gradle-config.
        minSdk = flutter.minSdkVersion
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }
    }

    buildTypes {
        release {
            // TODO: Add your own signing config for the release build.
            // Signing with the debug keys for now, so `flutter run --release` works.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

}

flutter {
    source = "../.."
}

val buildHevNative by tasks.registering(Exec::class) {
    val ndkBuild = File(android.ndkDirectory, if (System.getProperty("os.name").startsWith("Windows")) "ndk-build.cmd" else "ndk-build")
    workingDir(rootProject.projectDir)
    commandLine(
        ndkBuild.absolutePath,
        "NDK_PROJECT_PATH=app",
        "APP_BUILD_SCRIPT=app/src/main/jni/Android.mk",
        "NDK_APPLICATION_MK=app/src/main/jni/Application.mk",
        "NDK_LIBS_OUT=app/src/main/jniLibs",
        "NDK_OUT=../build/native-obj",
    )
}

tasks.named("preBuild").configure {
    dependsOn(buildHevNative)
}
