# 開源授權聲明

S-Vault 以相依套件的形式使用下列開源程式碼，**沒有修改任何原始碼**。「關於／開源授權」頁（T-23、T-24）列出同樣的內容。

> 最後更新：2026-11-11（T-131：補 jackson-core、Woodstox 內含的元件，Apache 套件的 NOTICE 原文，GPL 元件的原始碼位置，Android 漏列的相依）；2026-11-10（T-103：加入 Public Suffix List）；2026-10-31（M-03 整合：加入 T-40 的 EFF 字表）；2026-10-21（T-18 加入 JNA；T-09 加入 zxcvbn4j；T-01 起依 `.\gradlew :core:dependencies`、`:android:dependencies` 的執行期相依整理）。新增或升級相依時更新本檔，並同時複製到 `core/src/main/resources/app/svault/core/shell/NOTICES.md`（「關於」頁讀的複本，`NoticesTest` 比對兩份）。

## 兩平台共用（core）

| 套件 | 版本 | 授權 | 用途 |
|---|---|---|---|
| KeePassJava2（`KeePassJava2-jackson`、`KeePassJava2-kdbx`、`database`） | 2.2.6 | Apache License 2.0 | KDBX 4 讀寫 |
| Jackson（core、databind、annotations、dataformat-xml） | 2.22.3 | Apache License 2.0 | KeePassJava2 的 XML 資料模型 |
| FastDoubleParser（jackson-core 內含） | jackson-core 2.22.3 內附 | MIT License（內含 BSD 2-Clause、Boost Software License 1.0 的部分） | Jackson 的數字解析；其中來自 fast_float（MIT）、bigint（BSD 2-Clause）、fast_double_parser（Boost 1.0）的部分各依原授權 |
| Schubfach（jackson-core 內含） | jackson-core 2.22.3 內附 | MIT License | Jackson 的數字輸出 |
| Woodstox（`woodstox-core`） | 7.3.0 | Apache License 2.0 | StAX 實作 |
| MSV（`msv-core`、`xsdlib`，Woodstox 內含） | 2022.7 | BSD 3-Clause | Woodstox 的 XML 結構驗證（S-Vault 沒有用到） |
| RELAX NG Datatype Library（Woodstox 內含） | 20020414 | BSD 3-Clause | MSV 的相依 |
| ISO-RELAX（Woodstox 內含） | 20030108 | MIT License | MSV 的相依 |
| Stax2 API | 4.3.1 | BSD 2-Clause | Woodstox 的擴充介面 |
| Bouncy Castle（`bcprov`、`bcutil`、`bcpkix`，jdk18on） | 1.86 | Bouncy Castle License（MIT 型） | Argon2、ChaCha20 等加密演算法 |
| Guava、failureaccess | 33.7.2（Windows 用 jre 版、Android 用 android 版）、1.0.3 | Apache License 2.0 | KeePassJava2 的相依 |
| Apache Commons Codec | 1.22.1 | Apache License 2.0 | KeePassJava2 的相依 |
| zxcvbn4j（`com.nulab-inc:zxcvbn`，含 zxcvbn 的字典資料） | 1.9.0 | MIT License | 密碼強度評估（T-09 加入；沒有其他相依） |
| EFF Large Wordlist（Electronic Frontier Foundation） | 2016-07-18 | CC BY 3.0 | 通行片語的字表（T-40）。來源 https://www.eff.org/dice；`eff_large_wordlist.txt` 未修改，SHA-256 `addd35536511597a02fa0a9ff1e5284677b8883b83e986e43f15a3db996b903e` |
| Public Suffix List（Mozilla Foundation） | 2026-11-09 | Mozilla Public License 2.0 | 自動填入的上層網域比對排除公開後綴（T-103）。來源 https://publicsuffix.org/list/public_suffix_list.dat；`public_suffix_list.dat` 未修改，SHA-256 開頭 `347B147702878A99`，原始碼形式即本檔（MPL 2.0 §3.2） |
| JSpecify、Error Prone annotations、J2ObjC annotations、JSR 305 annotations | 1.0.1、2.50.0、3.1、3.0.2 | Apache License 2.0（JSR 305 為 BSD 3-Clause） | 註記用，執行期不使用 |

## Windows（desktop）

| 套件 | 版本 | 授權 | 用途 |
|---|---|---|---|
| OpenJFX（`javafx-base`、`javafx-graphics`、`javafx-controls`） | 27 | GPL v2 with Classpath Exception | 介面 |
| Eclipse Temurin JDK 執行環境（由 `jpackage` 打包進安裝檔） | 25 | GPL v2 with Classpath Exception | Java 執行環境 |
| JNA（`jna`、`jna-platform`） | 5.19.1 | Apache License 2.0 | Windows 鎖定螢幕時上鎖（T-18） |

JNA 以 Apache License 2.0 與 LGPL 2.1 雙授權發佈，S-Vault 選用 Apache License 2.0。

## Android

| 套件 | 版本 | 授權 | 用途 |
|---|---|---|---|
| Apache Geronimo StAX 1.2 API（`geronimo-stax-api_1.2_spec`） | 1.2 | Apache License 2.0 | Android 沒有內建 StAX 介面 |
| AndroidX（AppCompat 及其相依） | 1.8.0 | Apache License 2.0 | 介面相容層 |
| AndroidX Biometric | 1.1.0 | Apache License 2.0 | 生物辨識解鎖 |
| Material Components for Android | 1.14.0 | Apache License 2.0 | Material Design 3 元件 |
| Kotlin 標準函式庫（AndroidX 的相依） | 2.2.10 | Apache License 2.0 | AndroidX 內部使用 |
| kotlinx.coroutines（AndroidX 的相依） | 1.6.4 | Apache License 2.0 | AndroidX 內部使用 |
| JetBrains annotations（Kotlin 的相依） | 13.0 | Apache License 2.0 | 註記用，執行期不使用 |
| desugar_jdk_libs（core library desugaring） | 2.1.5 | GPL v2 with Classpath Exception | 在 API 29 上補 Java API |

Bouncy Castle 等套件 jar 內的 `META-INF/LICENSE*`、`NOTICE*` 在 APK 打包時排除（重複檔名衝突，STACK §13），授權聲明改由本檔與 App 的「關於」頁提供。

## 聲明原文與原始碼位置

- **Apache 套件的 NOTICE**：Jackson（四個套件）、Apache Commons Codec、Apache Geronimo StAX 1.2 API 附有 NOTICE 檔，依 Apache License 2.0 第 4(d) 條原文轉載在「關於」頁的 Apache License 2.0 全文前面（`licenses/apache-2.0.txt`）。Windows 版另外原封保留各 jar 內的 NOTICE。
- **GPL v2 with Classpath Exception 元件的原始碼**（GPL v2 第 3 條；S-Vault 沒有修改）：Eclipse Temurin JDK 25.0.4.1+1 https://github.com/adoptium/jdk25u/tree/jdk-25.0.4.1+1 ；OpenJFX 27 https://github.com/openjdk/jfx/tree/27-ga ；desugar_jdk_libs 2.1.5 https://github.com/google/desugar_jdk_libs/tree/73170c345e6a762fc6a1f0301bb15218850023ef 。「關於」頁的 GPL 全文前面列出同樣的位置。Windows 版的 Java 執行環境另附 JDK 自己的授權聲明（安裝資料夾的 `runtime/legal/`）。

## 圖示與字型

| 素材 | 授權 | 用途 |
|---|---|---|
| Material Symbols（Outlined）：`lock` | Apache License 2.0 | 暫用的 App 圖示（Android 啟動圖示） |
| Material Symbols（Outlined，24px）：`add`、`check_circle`、`chevron_left`、`chevron_right`、`close`、`content_copy`、`dark_mode`、`delete`、`edit`、`error`、`folder`、`info`、`key`、`light_mode`、`lock`、`lock_open`、`menu`、`more_vert`、`open_in_new`、`search`、`settings`、`sync`、`visibility`、`visibility_off`、`warning` | Apache License 2.0 | desktop 介面圖示（`icons.css` 的 `-fx-shape`，只取 SVG 路徑字串，來源 `google/material-design-icons` 的 `symbols/web/`，2026-10-17 取得；路徑前加了讓縮放等比例的零長度線段，圖形本身未修改） |
| Material Symbols（Outlined，24px）：上一列的 25 個，加 `language`、`arrow_back` | Apache License 2.0 | android 介面圖示（`res/drawable/sv_ic_*.xml` 的 vector drawable，路徑與 desktop 相同，`language`、`arrow_back` 於 2026-10-17 從同一來源取得；只改成 vector 格式與主題色，圖形本身未修改） |

## 開發與測試工具（不隨程式發佈）

JUnit 6、JUnit 4、ArchUnit、AndroidX Test（EPL 2.0／Apache 2.0）；Animal Sniffer 與 gummy-bears API 簽章（MIT／Apache 2.0）。
