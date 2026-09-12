plugins {
    alias(libs.plugins.agp.app)
}

android {
    namespace = "com.dsmod.probe"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dsmod.probe"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.7.4-fix"

        // DexKit 自带 arm64-v8a / armeabi-v7a / x86 / x86_64 四套原生库。
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    // 让 .so 以传统方式打包进 APK 的 lib/ 目录，配合清单里的
    // android:extractNativeLibs="true"，DexKit 才能在运行时 dlopen 成功。
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    compileOnly(files("libs/xposed-api-stub.jar"))

    // DexKit 是 Kotlin 写的，其公开 API 暴露了 kotlin.ranges.IntRange、
    // kotlin.DeprecationLevel 等类型，Java 调用方必须有 stdlib 在编译类路径上。
    implementation(libs.kotlin.stdlib)

    // DexKit：运行时解析宿主 DEX，按行为特征定位被 R8 重命名的类。
    implementation(libs.dexkit)
}
