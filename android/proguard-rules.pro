# release 的 R8 規則（v1.0 發佈時開啟 isMinifyEnabled）
# Woodstox、Jackson、KeePassJava2 以反射建立類別，縮減時不能移除
-keep class com.ctc.wstx.** { *; }
-keep class org.codehaus.stax2.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-keep class org.linguafranca.** { *; }
-keep class org.bouncycastle.** { *; }
-dontwarn javax.xml.stream.**
# 第三方函式庫在伺服器環境才用到的選用路徑（Android 沒有這些類別，S-Vault 也不會走到）：
# Jackson 的 java.beans 支援、Bouncy Castle 的 LDAP／DANE（JNDI）、Woodstox 與 geronimo 的 OSGi 註冊
-dontwarn java.beans.**
-dontwarn javax.naming.**
-dontwarn org.osgi.**
-dontwarn org.apache.geronimo.osgi.registry.api.**
# Woodstox 的編譯期註解（bnd）與內嵌 MSV 驗證器的命令列工具
-dontwarn aQute.bnd.annotation.spi.ServiceProvider
-dontwarn com.ctc.wstx.shaded.msv_core.driver.textui.Driver

# 專案自己的類別不縮減、不改名：模組以 ServiceLoader 探索（META-INF/services 列的 AndroidFeature 實作，T-22 發現）、
# 模組頁的 Fragment 以類別名稱載入（FeatureFragments.resolve）、字表以相對路徑讀資源（Wordlist.loadResource，
# 類別換套件就找不到）。R8 只用來縮減第三方函式庫
-keep class app.svault.** { *; }
