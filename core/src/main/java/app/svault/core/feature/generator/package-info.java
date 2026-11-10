/**
 * 密碼產生器（GEN，US-04）的平台無關邏輯：
 * {@link app.svault.core.feature.generator.PasswordGenerator} 產生隨機密碼與通行片語，
 * {@link app.svault.core.feature.generator.GeneratorSettings} 讀寫上次的選項。強度評估用核心的
 * {@code app.svault.core.security.StrengthEstimator}（AC-04-4），不在這裡另做。
 *
 * <p>這裡不實作 {@code FeatureModule}、不註冊：各平台的模組類別（T-41、T-42）才註冊擴充點。
 */
package app.svault.core.feature.generator;
