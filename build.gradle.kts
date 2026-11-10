// 共用熱點：只在 dev 的核心任務修改（CONVENTIONS §9）
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.lint) apply false
    alias(libs.plugins.animalsniffer) apply false
}

// 依賴雜湊校驗（gradle/verification-metadata.xml；L-6 剩餘，T-118）：Gradle 下載的每個檔都要對得上檔案裡的 SHA-256。
// Android 的 aapt2 依作業系統下載不同的 jar，在 Windows 產生的校驗檔沒有 Linux 版，CI 會失敗；這個任務在任何平台都
// 把三個平台的 aapt2 都下載一次，產生校驗檔時才會一起記下。調整相依後重寫：
// .\gradlew --write-verification-metadata sha256 resolveAapt2AllPlatforms <CI 與 STACK §7 用到的任務>
tasks.register("resolveAapt2AllPlatforms") {
    group = "verification"
    description = "下載 Windows、Linux、macOS 三個平台的 aapt2，讓 verification-metadata.xml 涵蓋 CI 的 Linux"
    val agp = libs.versions.agp.get()
    val aapt2 = configurations.detachedConfiguration(
        *listOf("windows", "linux", "osx").map {
            dependencies.create("com.android.tools.build:aapt2:$agp-15978811:$it")
        }.toTypedArray(),
    )
    doLast {
        println("aapt2：" + aapt2.resolve().joinToString { it.name })
    }
}

subprojects {
    // 相依版本鎖定（資安總檢 L-6）：只鎖會打包出去的設定，每個子專案的 gradle.lockfile
    // 記下實際解析的版本，弱點掃描讀這些檔。不鎖全部：Android Lint、測試在執行時才建立的設定（*ForLint）不在
    // dependencies 任務的範圍，鎖了會讓 lint 失敗。調整相依後重寫：
    // .\gradlew :core:dependencies :desktop:dependencies :android:dependencies --write-locks
    val lockedConfigurations = setOf(
        "compileClasspath", "runtimeClasspath", "releaseCompileClasspath", "releaseRuntimeClasspath",
    )
    configurations.matching { it.name in lockedConfigurations }.configureEach {
        resolutionStrategy.activateDependencyLocking()
    }
    // 中文輸出不亂碼（STACK §13）：程式與測試一律以 UTF-8 寫 stdout／stderr
    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
    }
    tasks.withType<Test>().configureEach {
        jvmArgs("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-Dfile.encoding=UTF-8")
    }
    tasks.withType<JavaExec>().configureEach {
        jvmArgs("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-Dfile.encoding=UTF-8")
    }
}
