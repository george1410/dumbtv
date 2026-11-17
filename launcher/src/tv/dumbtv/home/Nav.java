package tv.dumbtv.home;

/**
 * Left and right along a grid of tiles: wrap to the next line instead of stopping
 * at the edge of the current one.
 */
final class Nav {
    private Nav() {}

    /** Not at a row edge: leave left/right to the default focus search. */
    static final int DEFAULT = Integer.MIN_VALUE;
    /** First or last tile of this group: the caller steps to the next section. */
    static final int SECTION = -1;

    /**
     * Child index to focus, or {@link #DEFAULT} / {@link #SECTION}.
     * {@code cols} is the grid column count, or {@code count} for a single strip.
     */
    static int wrap(int idx, int count, int cols, boolean right) {
        if (count <= 0) return SECTION;
        if (cols < 1) cols = 1;
        if (right) {
            if (idx < count - 1) {
                if ((idx + 1) % cols == 0) return idx + 1;
                return DEFAULT;
            }
            return SECTION;
        }
        if (idx > 0) {
            if (idx % cols == 0) return idx - 1;
            return DEFAULT;
        }
        return SECTION;
    }
}
