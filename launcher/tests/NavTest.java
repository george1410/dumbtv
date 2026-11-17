package tv.dumbtv.home;

/** Host-side checks for wrapping left/right at the edge of a tile row. */
public final class NavTest {
    private static void check(boolean ok, String name) {
        if (!ok) throw new AssertionError(name);
    }

    public static void main(String[] args) {
        // 5 columns, 12 tiles: last line is short.
        check(Nav.wrap(3, 12, 5, true) == Nav.DEFAULT, "mid-row right stays on the line");
        check(Nav.wrap(4, 12, 5, true) == 5, "end of a line goes to the start of the next");
        check(Nav.wrap(5, 12, 5, false) == 4, "start of a line goes to the end of the one above");
        check(Nav.wrap(11, 12, 5, true) == Nav.SECTION, "last tile leaves the group");
        check(Nav.wrap(0, 12, 5, false) == Nav.SECTION, "first tile leaves the group");
        check(Nav.wrap(9, 12, 5, true) == 10, "end of a full line still wraps onto a short last line");
        check(Nav.wrap(10, 12, 5, true) == Nav.DEFAULT, "short last line, not the last tile");

        // One horizontal strip: wrap only at the ends, into the next section.
        check(Nav.wrap(0, 8, 8, false) == Nav.SECTION, "strip: left of the first");
        check(Nav.wrap(7, 8, 8, true) == Nav.SECTION, "strip: right of the last");
        check(Nav.wrap(2, 8, 8, true) == Nav.DEFAULT, "strip: right mid-row");
        check(Nav.wrap(2, 8, 8, false) == Nav.DEFAULT, "strip: left mid-row");
        System.out.println("NavTest ok");
    }
}
