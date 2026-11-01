import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}

/** 版本号集中在这里，defaultConfig 和 mapping 归档都用它，避免两处不一致。 */
val appVersionCode = 11
val appVersionName = "1.1.0"

android {
    namespace = "com.example.videoplayer"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.videoplayer"
        minSdk = 24
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("release") {
            if (keystorePropsFile.exists()) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (keystorePropsFile.exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
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
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

ksp {
    // 导出 Room schema，方便后续版本做 migration 检查
    arg("room.schemaLocation", "$projectDir/schemas")
}

/**
 * 归档 R8 的 mapping.txt。
 *
 * 开了 minifyEnabled 之后，线上崩溃栈全是 `a.b.c()`，没有对应版本的 mapping.txt 就完全还原不出来。
 * 每次 assembleRelease 结束顺手拷一份到根目录 mapping/ 下，按版本名区分，方便追溯。
 */
tasks.register<Copy>("archiveMapping") {
    val mappingFile = layout.buildDirectory.file("outputs/mapping/release/mapping.txt")
    from(mappingFile)
    into(rootProject.layout.projectDirectory.dir("mapping"))
    rename { "mapping-$appVersionName.txt" }
    onlyIf { mappingFile.get().asFile.exists() }
}

tasks.matching { it.name == "assembleRelease" }.configureEach {
    finalizedBy("archiveMapping")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.core)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)

    // 后台播放：MediaSessionService 承载媒体通知 / 锁屏 / 耳机按键
    implementation(libs.androidx.media3.session)

    // 相册缩略图：coil-video 提供 VideoFrameDecoder，从视频里抽一帧当封面
    implementation(libs.coil.compose)
    implementation(libs.coil.video)

    // 媒体入库：Room 存元数据，WorkManager 做可中断的后台导入
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.androidx.work.runtime.ktx)

    debugImplementation(libs.androidx.ui.tooling)

    // 纯逻辑单元测试（只在 testImplementation，不会打进 APK）
    testImplementation(libs.junit)
}
