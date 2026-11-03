package tv.dumbtv.home;

/** Host-side checks for banner occupancy and cover-crop, without a device. */
public final class ArtTest {
    private static void check(boolean ok, String name) {
        if (!ok) throw new AssertionError(name);
    }

    public static void main(String[] args) {
        check(Art.wide(320, 180), "16:9 is wide");
        check(Art.wide(1280, 720), "HD still is wide");
        check(!Art.wide(108, 108), "square vector is not a banner");
        check(!Art.wide(512, 512), "square icon is not a banner");
        check(!Art.wide(0, 180), "missing size is not wide");

        int w = 80, h = 45;
        int[] splash = fill(w, h, 0xFF000000);
        blob(splash, w, h, 36, 18, 8, 6, 0xFFFF2D78);
        check(Art.occupancy(splash, w, h) < Art.SPARSE, "tiny splash logo is sparse");

        int[] card = fill(w, h, 0xFFC8E000);
        blob(card, w, h, 20, 8, 40, 28, 0xFF111111);
        check(Art.occupancy(card, w, h) > Art.SPARSE, "large logo on a colour field is a banner");

        int[] r = new int[4];
        Art.cover(r, 320, 180, 160, 90);
        check(r[0] == 0 && r[1] == 0 && r[2] == 160 && r[3] == 90, "16:9 covers 16:9");

        Art.cover(r, 108, 108, 160, 90);
        check(r[0] == 0 && r[2] == 160, "square covers the width");
        check(r[2] - r[0] == r[3] - r[1], "square keeps its aspect");
        check(r[1] < 0 && r[3] > 90, "square overflow is cropped top and bottom");

        Art.cover(r, 0, 0, 160, 90);
        check(r[0] == 0 && r[1] == 0 && r[2] == 160 && r[3] == 90, "unknown size fills");

        int[] box = Art.contentBox(splash, w, h);
        check(box != null && box[2] - box[0] < w / 2, "splash content is a small logo");
        int[] logo = Art.contentBox(card, w, h);
        check(logo != null && (logo[2] - logo[0]) * (logo[3] - logo[1]) < Art.BLEED * w * h,
                "logo on a colour field is not full-bleed");
        int[] bleedPx = fill(w, h, 0xFF101010);
        blob(bleedPx, w, h, 1, 1, w - 2, h - 2, 0xFFFF2D78);
        int[] bleed = Art.contentBox(bleedPx, w, h);
        check(bleed != null && (bleed[2] - bleed[0]) * (bleed[3] - bleed[1]) > Art.BLEED * w * h,
                "near-full art is full-bleed");

        Art.contain(r, 44, 66, 160, 90);
        check(r[1] == 0 && r[3] == 90, "tall logo fits the height");
        check(r[2] - r[0] < 160, "tall logo is not stretched");

        System.out.println("ArtTest passed");
    }

    private static int[] fill(int w, int h, int color) {
        int[] px = new int[w * h];
        for (int i = 0; i < px.length; i++) px[i] = color;
        return px;
    }

    private static void blob(int[] px, int w, int h, int x, int y, int bw, int bh, int color) {
        for (int row = y; row < y + bh; row++) {
            for (int col = x; col < x + bw; col++) {
                px[row * w + col] = color;
            }
        }
    }
}
