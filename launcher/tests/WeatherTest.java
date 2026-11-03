package tv.dumbtv.home;

/** Host-side checks for Open-Meteo WMO labels, without a device. */
public final class WeatherTest {
    private static void check(boolean ok, String name) {
        if (!ok) throw new AssertionError(name);
    }

    private static void eq(String got, String want) {
        check(want.equals(got), want + " ← " + got);
    }

    public static void main(String[] args) throws Exception {
        eq(Weather.condition(0), "Clear");
        eq(Weather.condition(2), "Partly cloudy");
        eq(Weather.condition(3), "Cloudy");
        eq(Weather.condition(45), "Fog");
        eq(Weather.condition(51), "Drizzle");
        eq(Weather.condition(61), "Rain");
        eq(Weather.condition(71), "Snow");
        eq(Weather.condition(80), "Showers");
        eq(Weather.condition(95), "Thunder");
        eq(Weather.condition(999), "");

        eq(Weather.line(11, 3), "11°  Cloudy");
        eq(Weather.line(-2, 73), "-2°  Snow");
        eq(Weather.line(18, 999), "18°");

        check(Weather.icon(0) == Weather.SUN, "sun");
        check(Weather.icon(2) == Weather.PARTLY, "partly");
        check(Weather.icon(51) == Weather.DRIZZLE, "drizzle");
        check(Weather.icon(80) == Weather.SHOWERS, "showers");
        check(Weather.icon(999) == 0, "none");

        System.out.println("WeatherTest passed");
    }
}
