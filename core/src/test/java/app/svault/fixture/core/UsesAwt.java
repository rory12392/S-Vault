package app.svault.fixture.core;

import java.awt.Point;

/**
 * LayeringTest 的違規範例：假的 core 類別使用平台 API（java.awt）。
 */
public final class UsesAwt {

    private UsesAwt() {
    }

    /** 違規：core 不得依賴平台 API。 */
    static Object point() {
        return new Point(1, 2);
    }
}
