import groovy.json.JsonSlurper

plugins {
    id("com.android.application")
    id("kotlin-android")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

android {
    namespace = "com.example.hs_disconnect"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = "27.3.13750724"

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

    sourceSets.named("main") {
        res.srcDir(layout.buildDirectory.dir("generated/l10n-res"))
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

// ARB → Android strings.xml. Single source of truth = lib/l10n/app_*.arb.
// Whitelist: `appTitle` becomes <string name="app_name">; everything starting
// with `notif_` is exported under the same name. Add new entries here if more
// Flutter strings need to surface as native resources.
val generateL10nResources by tasks.registering {
    val arbDir = rootProject.projectDir.parentFile.resolve("lib/l10n")
    val arbFiles = fileTree(arbDir) {
        include("app_*.arb")
    }
    val outDir = layout.buildDirectory.dir("generated/l10n-res")
    inputs.files(arbFiles).withPropertyName("arbFiles").skipWhenEmpty()
    outputs.dir(outDir)

    val keyMap = mapOf("appTitle" to "app_name")
    val exportPrefix = "notif_"

    doLast {
        val root = outDir.get().asFile
        root.deleteRecursively()
        root.mkdirs()
        for (arb in arbFiles) {
            val tag = arb.name.removePrefix("app_").removeSuffix(".arb")
            val resDir = when {
                tag == "en" -> File(root, "values")
                tag.contains("_") -> {
                    val (lang, region) = tag.split("_", limit = 2)
                    File(root, "values-$lang-r$region")
                }
                else -> File(root, "values-$tag")
            }
            resDir.mkdirs()
            @Suppress("UNCHECKED_CAST")
            val data = JsonSlurper().parse(arb) as Map<String, Any?>
            val body = StringBuilder()
            for ((key, value) in data) {
                if (key.startsWith("@")) continue
                if (value !is String) continue
                val androidName = keyMap[key]
                    ?: if (key.startsWith(exportPrefix)) key else continue
                body.append("    <string name=\"")
                    .append(androidName)
                    .append("\">")
                    .append(escapeAndroidXmlString(value))
                    .append("</string>\n")
            }
            File(resDir, "strings.xml").writeText(
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n$body</resources>\n",
                Charsets.UTF_8
            )
        }
    }
}

fun escapeAndroidXmlString(s: String): String = buildString(s.length) {
    for (c in s) when (c) {
        '&' -> append("&amp;")
        '<' -> append("&lt;")
        '>' -> append("&gt;")
        '\'' -> append("\\'")
        '"' -> append("\\\"")
        '\n' -> append("\\n")
        else -> append(c)
    }
}

tasks.named("preBuild").configure {
    dependsOn(buildHevNative, generateL10nResources)
}
