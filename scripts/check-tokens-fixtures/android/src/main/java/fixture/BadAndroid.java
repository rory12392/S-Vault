// token-check-expect: android-java
package fixture;

class BadAndroid {
    int a = android.graphics.Color.parseColor("#1F5FAF");
    int b = Color.argb(255, 0, 0, 0);
    int c = 0xFF1F5FAF;

    void size(android.widget.TextView view) {
        view.setTextSize(18);
    }
}
