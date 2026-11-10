package app.svault.core.module;

/**
 * 某個功能模組提供的一項擴充（頁面、按鈕、設定段落…），記下來源模組，
 * 讓外殼之後呼叫它時能以 {@link ModuleGuard} 包住並指名出錯的模組。
 *
 * @param moduleId 提供者的模組代號
 * @param value    擴充內容
 * @param <E>      擴充的型別
 */
public record Contribution<E>(String moduleId, E value) { }
