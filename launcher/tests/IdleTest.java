package tv.dumbtv.home;

/** Host-side checks for the idle clock delay and burn-in wander. */
public final class IdleTest {
    private static void check(boolean ok, String name) {
        if (!ok) throw new AssertionError(name);
    }

    private static void close(float got, float want, String name) {
        if (Math.abs(got - want) > 0.01f) throw new AssertionError(name + ": " + got);
    }

    public static void main(String[] args) {
        check(Idle.delayMs("off") == 0, "off is disabled");
        check(Idle.delayMs("unknown") == 0, "unknown is disabled");
        check(Idle.delayMs("1m") == 60 * 1000L, "1m is a minute");
        check(Idle.delayMs("5m") == 5 * 60 * 1000L, "5m is five minutes");

        float[] east = Idle.drift(0f, 10, 6);
        close(east[0], 10, "start is the right apex");
        close(east[1], 0, "start has no vertical offset");
        float[] north = Idle.drift(0.25f, 10, 6);
        close(north[0], 0, "quarter turn is centred horizontally");
        close(north[1], 6, "quarter turn is the top apex");
        float[] back = Idle.drift(1f, 10, 6);
        close(back[0], east[0], "a full turn returns");
        close(back[1], east[1], "a full turn returns vertically");
        System.out.println("IdleTest ok");
    }
}
