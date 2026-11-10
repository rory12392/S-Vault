package app.svault.core.module;

/**
 * 功能模組出錯時的通知對象（平台外殼用來在該區塊顯示錯誤狀態）。
 */
@FunctionalInterface
public interface ModuleFailureListener {

    /** 不做任何事（只記日誌）。 */
    ModuleFailureListener NONE = (moduleId, action, error) -> { };

    /**
     * 模組的進入點丟出例外時呼叫。
     *
     * @param moduleId 出錯的模組代號；探索階段無法得知時為 {@code "?"}
     * @param action   出錯的動作名稱，例如 {@code menuPages}
     * @param error    被接住的例外
     */
    void onFailure(String moduleId, String action, Throwable error);
}
