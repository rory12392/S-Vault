plugins {
    `java-library`
    `java-test-fixtures`
    // core 只能用 Android API 29 有的 Java API（STACK §7 第 1 點）。Android Lint 的 checkDependencies
    // 不會對純 Java 模組套用 minSdk（2026-10-17 實測：故意用 HexFormat 沒有被擋），所以用 Animal Sniffer
    alias(libs.plugins.animalsniffer)
    // 讓 android 的 lint（checkDependencies）也用一般規則掃 core（例如 DefaultLocale）
    alias(libs.plugins.android.lint)
    // 單元測試涵蓋率（AC-15-4）：門檻與排除項目見下方 jacocoTestCoverageVerification
    jacoco
}

jacoco {
    toolVersion = libs.versions.jacoco.get()
}

animalsniffer {
    // 只檢查正式程式；測試只在 JVM 上跑
    sourceSets = listOf(project.the<SourceSetContainer>()["main"])
    // record：D8 編譯時轉換，API 29 可用；javax.xml.stream：Android 上由 geronimo-stax-api_1.2_spec 提供
    ignore("java.lang.Record", "javax.xml.stream.*")
}

java {
    // core 兩平台共用：語言與類別檔都是 Java 17（Android 相容）
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 17
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

dependencies {
    // KDBX 函式庫：只有 app.svault.core.vault 能 import（LayeringTest 強制）
    implementation(libs.keepassjava2.jackson)
    // 密碼強度（StrengthEstimator）：只有 app.svault.core.security 使用，不連網
    implementation(libs.zxcvbn)

    // Android API 29 的 Java API 簽章（含 core library desugaring 補上的 API 與否見 STACK §7 第 1 點）
    signature(libs.android.api29.signature) { artifact { type = "signature" } }

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.archunit.junit5)
    testRuntimeOnly(libs.junit.platform.launcher)

    // testFixtures 不依賴任何測試框架（android 儀器測試用 JUnit 4 共用）；
    // ArchRules 用到 ArchUnit，只在編譯期可見，使用端自己加 ArchUnit
    testFixturesCompileOnly(libs.archunit)
}

tasks.test {
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    reports {
        xml.required = true
        html.required = true
    }
}

// AC-15-4：core 的單元測試涵蓋率門檻（STACK §6、§7 第 4 點）。範圍是整個 core（加密與 KDBX 讀寫在 vault、
// 合併在 sync、安全存檔在 storage；M-03 的健檢在 feature.health，也會落在逐套件的門檻裡）。
// 沒有排除任何類別：要補的是測試，不是排除清單（2026-10-21 T-20 開始時：行 88.9%、指令 89.5%、分支 78.3%）。
tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    violationRules {
        rule {
            // 整個 core
            limit {
                counter = "LINE"
                minimum = "0.80".toBigDecimal()
            }
            limit {
                counter = "INSTRUCTION"
                minimum = "0.80".toBigDecimal()
            }
            // 分支另設較低的下限，避免只靠行數（vault 的轉接層有很多只在損壞檔案才走到的防禦分支）
            limit {
                counter = "BRANCH"
                minimum = "0.75".toBigDecimal()
            }
        }
        rule {
            // 每個套件各自 80%：不讓測試很多的套件蓋過沒有測試的套件
            element = "PACKAGE"
            limit {
                counter = "LINE"
                minimum = "0.80".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}
