// token-check-expect: java-color
package fixture;

class BadColor {
    Object a = Color.web("#1F5FAF");
    Object b = Color.rgb(255, 0, 0);
    Object c = Color.RED;
    String d = "#FFFFFF";
}
