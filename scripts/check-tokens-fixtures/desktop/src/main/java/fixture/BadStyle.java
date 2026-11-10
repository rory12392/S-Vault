// token-check-expect: java-style
package fixture;

class BadStyle {
    void apply(javafx.scene.control.Label label) {
        label.setStyle("-fx-text-fill: -sv-fg;");
        label.setFont(javafx.scene.text.Font.font(18));
        label.setFont(new Font("Arial", 12));
    }
}
