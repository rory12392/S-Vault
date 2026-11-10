/**
 * 健檢（HLT）的平台無關邏輯：弱／重複密碼分析
 * {@link app.svault.core.feature.health.WeakReuseAnalyzer}（T-50）；外洩檢查
 * {@link app.svault.core.feature.health.BreachChecker}（T-51，整個專案唯一連網的地方）。
 * 這裡不實作 {@code FeatureModule}、不註冊：各平台的模組類別才註冊。
 */
package app.svault.core.feature.health;
