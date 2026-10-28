plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.weile.liuzhou.mahjong"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.weile.liuzhou.mahjong"
        // 方案第 5 节：目标 Android 10+，重点验收 13/14/15/16。
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0-P0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            // R8 压缩 + 资源裁剪：Compose / Room / coroutines 均自带 consumer rules，
            // 无需额外 keep；未开启时 release 包体积与启动耗时都明显劣于可行状态。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
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
        compose = true
        // CaptureService 用 BuildConfig.DEBUG 关闭逐帧日志。
        buildConfig = true
    }

    composeOptions {
        // Compose 编译器版本（独立于 Kotlin 版本发布）；1.5.14 与 Kotlin 1.9.24 兼容。
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Compose BOM 统一管理 Compose 库版本。
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    // collectAsStateWithLifecycle：后台时停止收集，避免无谓重组。
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    implementation("androidx.activity:activity-compose:1.9.0")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    // 说明：已移除 material-icons-extended（未引用任何 Icons.*，属数 MB 级死依赖）
    // 与 appcompat（主题用平台 Material，Activity 为 ComponentActivity，无 AppCompat 依赖）。

    // 测试
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Room（storage 模块：规则偏好 / 事件记录 / 调试样本元数据）
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")
}
