package app.svault.core.sync;

/**
 * 三方合併的兩邊：這台裝置（本機）與另一台裝置（遠端檔案目前的內容）。衝突解決時用來選邊（AC-08-2）。
 */
public enum Side {
    /** 這台裝置的版本。 */
    LOCAL,
    /** 另一台裝置的版本（遠端檔案）。 */
    REMOTE
}
