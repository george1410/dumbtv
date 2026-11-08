package tv.dumbtv.home;

/**
 * When to fade the home screen into a large clock, and a slow ellipse so the
 * digits never sit on the same pixels long enough to burn in.
 */
final class Idle {
    private Idle() {}

    /** 0 means the idle clock is off. */
    static long delayMs(String value) {
        if ("1m".equals(value)) return 60 * 1000L;
        if ("5m".equals(value)) return 5 * 60 * 1000L;
        return 0;
    }

    /**
     * {@code turn} is 0..1 around the ellipse. Amplitude is in pixels; Y is
     * smaller so the motion reads as a hover, not a bounce.
     */
    static float[] drift(float turn, float ampX, float ampY) {
        double a = turn * 2 * Math.PI;
        return new float[] {
            (float) (Math.cos(a) * ampX),
            (float) (Math.sin(a) * ampY)
        };
    }
}
