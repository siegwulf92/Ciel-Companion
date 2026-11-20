import com.cielcompanion.service.SystemPressureWindow;
public class SystemPressureRegression {
    public static void main(String[] args) {
        SystemPressureWindow window = new SystemPressureWindow();
        if (window.observe(99, 60, 0)) throw new AssertionError("single spike");
        if (window.observe(31, 60, 45000)) throw new AssertionError("recovered spike");
        if (window.observe(99, 60, 90000)) throw new AssertionError("new spike");
        if (!window.observe(98, 60, 135000)) throw new AssertionError("sustained CPU");
        if (window.observe(31, 60, 180000)) throw new AssertionError("recovery");
        if (window.observe(Double.NaN, 99, 225000)) throw new AssertionError("first RAM sample");
        if (!window.observe(Double.NaN, 98, 270000)) throw new AssertionError("sustained RAM");
        if (window.observe(99, 99, 400000)) throw new AssertionError("stale sampling gap");
        System.out.println("System pressure regression passed");
    }
}
