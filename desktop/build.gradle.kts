import org.gradle.internal.os.OperatingSystem

plugins {
    application
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

// JavaFX 的 Maven 套件依作業系統分類（classifier）；Gradle 不處理 Maven profile，所以三個都明列。
// 只發佈 Windows 版，CI 的 desktop job 也在 Windows 上跑。
val javafxPlatform = when {
    OperatingSystem.current().isWindows -> "win"
    OperatingSystem.current().isMacOsX -> "mac"
    else -> "linux"
}

// JavaFX 以 System.load 載入原生程式庫；JDK 24 起不明確允許會出警告，之後的版本會直接擋下
val nativeAccess = "--enable-native-access=ALL-UNNAMED"

dependencies {
    implementation(project(":core"))
    for (fx in listOf(libs.javafx.base, libs.javafx.graphics, libs.javafx.controls)) {
        implementation(variantOf(fx) { classifier(javafxPlatform) })
    }
    // Windows 鎖定螢幕時上鎖（AC-12-1，T-18）：WTSRegisterSessionNotification
    implementation(libs.jna.platform)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.archunit.junit5)
    testImplementation(testFixtures(project(":core")))
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    mainClass = "app.svault.desktop.Launcher"
    // 不放 -Dsvault.dev=true：distZip／installDist 的啟動腳本會帶上它而開啟開發功能（資安總檢 L-7），開發模式只在 run
    applicationDefaultJvmArgs = listOf(nativeAccess)
}

// 根目錄替所有 JavaExec 加的 UTF-8 參數會取代 applicationDefaultJvmArgs 的預設值（T-02 發現：
// 原生存取參數沒傳進去），所以 run 明確加上開發模式與原生存取
tasks.named<JavaExec>("run") {
    jvmArgs("-Dsvault.dev=true", nativeAccess)
}

tasks.test {
    jvmArgs(nativeAccess)
    useJUnitPlatform()
}

// ---- 打包（STACK §9）：直接呼叫工具鏈 JDK 的 jpackage，不用第三方打包外掛 ----

val appName = "S-Vault"
val appVersion = "1.1.1"
val jpackageInput = layout.buildDirectory.dir("jpackage-input")
val jpackageOutput = layout.buildDirectory.dir("jpackage")
val windowsAppIcon = layout.projectDirectory.file("src/main/packaging/app-icon.ico")
val launcher = javaToolchains.launcherFor(java.toolchain)

val prepareJpackageInput = tasks.register<Sync>("prepareJpackageInput") {
    description = "收集 jpackage 需要的 jar（本專案＋執行期相依）"
    from(tasks.jar)
    from(configurations.runtimeClasspath)
    into(jpackageInput)
}

fun jpackageExecutable(): String {
    val home = launcher.get().metadata.installationPath.asFile
    val exe = if (OperatingSystem.current().isWindows) "jpackage.exe" else "jpackage"
    return home.resolve("bin").resolve(exe).absolutePath
}

fun jpackageArgs(type: String): List<String> = listOf(
    "--type", type,
    "--name", appName,
    "--app-version", appVersion,
    "--vendor", "S-Vault",
    "--input", jpackageInput.get().asFile.absolutePath,
    "--main-jar", tasks.jar.get().archiveFileName.get(),
    "--main-class", application.mainClass.get(),
    "--dest", jpackageOutput.get().asFile.absolutePath,
    "--java-options", "-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -Dfile.encoding=UTF-8 $nativeAccess",
    // 精簡的 Java 執行環境（I-2，T-114）：只放用得到的模組。清單來自 jdeps --print-module-deps，再加上靜態分析看不到的
    // jdk.localedata（繁體中文的日期與數字格式）、jdk.crypto.ec（外洩檢查 HTTPS 的 ECDHE）、java.logging（日誌）。
    // 新增相依後重跑 jdeps 對照；少了模組時打包版會在用到的地方丟 NoClassDefFoundError，自我檢查與冒煙測試要走過
    "--add-modules", "java.base,java.desktop,java.logging,java.naming,java.sql,jdk.jfr,jdk.unsupported," +
        "jdk.localedata,jdk.crypto.ec",
    "--jlink-options", "--strip-debug --no-header-files --no-man-pages --strip-native-commands",
) + if (OperatingSystem.current().isWindows) {
    listOf("--icon", windowsAppIcon.asFile.absolutePath)
} else {
    emptyList()
}

tasks.register<Exec>("jpackageImage") {
    group = "distribution"
    description = "產生免安裝資料夾 build/jpackage/S-Vault/（自我檢查用）"
    dependsOn(prepareJpackageInput)
    inputs.dir(jpackageInput)
    if (OperatingSystem.current().isWindows) {
        inputs.file(windowsAppIcon)
    }
    outputs.dir(jpackageOutput.map { it.dir(appName) })
    doFirst {
        // jpackage 拒絕覆寫已存在的輸出資料夾
        delete(jpackageOutput.get().dir(appName))
        executable = jpackageExecutable()
        args = jpackageArgs("app-image")
    }
}

tasks.register<Exec>("jpackageExe") {
    group = "distribution"
    description = "產生 Windows 安裝檔（需要 WiX Toolset，SETUP S-05）"
    dependsOn(prepareJpackageInput)
    doFirst {
        executable = jpackageExecutable()
        // 沒指定 --win-menu-group 時，開始功能表的資料夾會叫「Unknown」（v1.1.0 實裝發現）
        args = jpackageArgs("exe") + listOf("--win-menu", "--win-menu-group", appName, "--win-shortcut",
            "--win-dir-chooser", "--win-per-user-install")
    }
}
