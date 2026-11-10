import com.android.build.api.artifact.SingleArtifact

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "app.svault.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.svault.android"
        minSdk = 29
        targetSdk = 37
        versionCode = 3
        versionName = "1.1.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // release 簽章從使用者層級的環境變數讀（SETUP S-12、Architecture §12）；keystore 與密碼不進 repo。
    // 沒設定時 release 不簽章（assembleRelease 產生 *-unsigned.apk，不能安裝）
    val keystorePath = System.getenv("SVAULT_KEYSTORE_PATH")
    signingConfigs {
        if (!keystorePath.isNullOrBlank()) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("SVAULT_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("SVAULT_KEY_ALIAS")
                keyPassword = System.getenv("SVAULT_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // R8：keep 規則在 proguard-rules.pro
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // core 用到的 java.time 等 API 在 API 29 上靠 desugaring 補（STACK §7 第 1 點）
        isCoreLibraryDesugaringEnabled = true
    }

    packaging {
        resources {
            // Bouncy Castle 的三個 jar 各帶一份授權檔（STACK §13）；聲明改由 NOTICES.md 與「關於」頁提供
            excludes += setOf(
                "META-INF/LICENSE.md",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE.md",
                "META-INF/NOTICE.txt",
                "META-INF/DEPENDENCIES",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
            )
        }
    }

    lint {
        // 一併掃描 core 的一般規則。注意：純 Java 模組不套用 minSdk，NewApi 擋不到 core，
        // API 29 相容由 core 的 Animal Sniffer 檢查（STACK §7 第 1 點）
        checkDependencies = true
        abortOnError = true
        warningsAsErrors = false
        // 安全類規則一律視為錯誤（STACK §7 第 2 點）
        error += setOf(
            "AllowBackup",
            "HardcodedDebugMode",
            "ExportedContentProvider",
            "ExportedReceiver",
            "ExportedService",
            "ExportedPreferenceActivity",
            "SetWorldReadable",
            "SetWorldWritable",
            "WorldReadableFiles",
            "WorldWriteableFiles",
            "SecureRandom",
            "TrustAllX509TrustManager",
            "BadHostnameVerifier",
            "AllowAllHostnameVerifier",
            "PackagedPrivateKey",
            "GetInstance",
            "InsecureBaseConfiguration",
            "UnsafeProtectedBroadcastReceiver",
            "SignatureOrSystemPermissions",
        )
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":core"))
    // Android 沒有 StAX：補 StAX 1.2 介面（1.0 版缺 newFactory()，STACK §13）
    implementation(libs.geronimo.stax.api)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    // BIO 模組的生物辨識（T-22 先加，模組分支不改相依）
    implementation(libs.androidx.biometric)
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    testImplementation(libs.junit4)
    testImplementation(libs.archunit)
    testImplementation(testFixtures(project(":core")))

    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(testFixtures(project(":core")))
}

// ManifestPermissionTest 讀合併後的 manifest：把每個 variant 的路徑交給對應的單元測試
androidComponents {
    onVariants { variant ->
        val manifest = variant.artifacts.get(SingleArtifact.MERGED_MANIFEST)
        val testTaskName = "test${variant.name.replaceFirstChar { it.uppercase() }}UnitTest"
        tasks.withType<Test>().configureEach {
            if (name == testTaskName) {
                inputs.file(manifest).withPropertyName("mergedManifest")
                jvmArgumentProviders.add(CommandLineArgumentProvider {
                    listOf("-Dsvault.mergedManifest=${manifest.get().asFile.absolutePath}")
                })
            }
        }
    }
}
