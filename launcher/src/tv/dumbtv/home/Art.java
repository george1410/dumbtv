package tv.dumbtv.home;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

/**
 * Picks tile artwork and how to place it. Fire TV apps often ship a 16:9 splash (tiny logo on
 * black) as {@code android:banner}, or reuse a square icon as the banner so a naive stretch
 * flattens it.
 */
final class Art {
    static final float WIDE = 1.4f;
    /** Fraction of pixels that differ from the corners; splash stills sit well under this. */
    static final float SPARSE = 0.05f;
    /** Content that fills this much of the art is treated as a full-bleed card, not a logo. */
    static final float BLEED = 0.45f;
    private static final int SAMPLE = 96;
    private static final int CARD_W = 720, CARD_H = 405;
    private static final int SRC_MAX = 1024;
    private static final int CORNER_DELTA = 18;

    private Art() {}

    /**
     * The drawable that should fill the card, or null to draw the icon with a name. A wide,
     * busy banner wins; a splash falls back to the icon; a square banner is kept so the tile
     * can cover-crop it instead of stretching.
     */
    static Drawable banner(Drawable banner, Drawable icon) {
        if (usableWide(banner)) return banner;
        if (usableWide(icon)) return icon;
        if (banner != null && sparse(banner) && icon != null) return icon;
        return banner;
    }

    /**
     * Real 16:9 bitmaps are used as-is. Square vectors and logos on a flat field are rendered
     * onto a 16:9 card so {@link Tile} can draw them without stretching.
     */
    static Drawable prepare(Drawable d) {
        if (d == null) return null;
        if (d instanceof BitmapDrawable && usableWide(d)) return d;
        Bitmap card = card(d);
        if (card == null) return d;
        card.setDensity(Bitmap.DENSITY_NONE);
        return bitmap(card);
    }

    static boolean usableWide(Drawable d) {
        return d != null && wide(d.getIntrinsicWidth(), d.getIntrinsicHeight()) && !sparse(d);
    }

    static boolean wide(int w, int h) {
        return w > 0 && h > 0 && w / (float) h >= WIDE;
    }

    /**
     * Uniform scale so {@code iw×ih} covers {@code cw×ch}. Writes left, top, right, bottom into
     * {@code out}; overflow is clipped by the tile.
     */
    static void cover(int[] out, int iw, int ih, int cw, int ch) {
        if (iw <= 0 || ih <= 0) {
            out[0] = 0;
            out[1] = 0;
            out[2] = cw;
            out[3] = ch;
            return;
        }
        if ((long) iw * ch <= (long) ih * cw) {
            int dh = (int) ((long) ih * cw / iw);
            int top = (ch - dh) / 2;
            out[0] = 0;
            out[1] = top;
            out[2] = cw;
            out[3] = top + dh;
        } else {
            int dw = (int) ((long) iw * ch / ih);
            int left = (cw - dw) / 2;
            out[0] = left;
            out[1] = 0;
            out[2] = left + dw;
            out[3] = ch;
        }
    }

    static boolean sparse(Drawable d) {
        if (d == null || d.getConstantState() == null) return false;
        int iw = d.getIntrinsicWidth(), ih = d.getIntrinsicHeight();
        if (iw <= 0 || ih <= 0) return false;
        int rw, rh;
        if (iw >= ih) {
            rw = SAMPLE;
            rh = Math.max(1, SAMPLE * ih / iw);
        } else {
            rh = SAMPLE;
            rw = Math.max(1, SAMPLE * iw / ih);
        }
        Bitmap bitmap = raster(d, rw, rh);
        if (bitmap == null) return false;
        try {
            int[] pixels = new int[rw * rh];
            bitmap.getPixels(pixels, 0, rw, 0, 0, rw, rh);
            return occupancy(pixels, rw, rh) < SPARSE;
        } finally {
            bitmap.recycle();
        }
    }

    /** Share of pixels that are opaque and unlike every corner; splash logos score very low. */
    static float occupancy(int[] pixels, int w, int h) {
        if (w < 2 || h < 2 || pixels.length < w * h) return 0f;
        int[] corners = corners(pixels, w, h);
        int content = 0;
        for (int i = 0, n = w * h; i < n; i++) {
            int p = pixels[i];
            if ((p >>> 24) < 16) continue;
            if (unlikeCorners(p, corners)) content++;
        }
        return content / (float) (w * h);
    }

    /**
     * Inclusive box of pixels unlike the corners, or null when the art is empty. Used to
     * letterbox a logo onto its background instead of cropping it.
     */
    static int[] contentBox(int[] pixels, int w, int h) {
        if (w < 2 || h < 2 || pixels.length < w * h) return null;
        int[] corners = corners(pixels, w, h);
        int minx = w, miny = h, maxx = -1, maxy = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = pixels[y * w + x];
                if ((p >>> 24) < 16) continue;
                if (!unlikeCorners(p, corners)) continue;
                if (x < minx) minx = x;
                if (y < miny) miny = y;
                if (x > maxx) maxx = x;
                if (y > maxy) maxy = y;
            }
        }
        return maxx < 0 ? null : new int[] {minx, miny, maxx + 1, maxy + 1};
    }

    @SuppressWarnings("deprecation")
    private static Drawable bitmap(Bitmap card) {
        BitmapDrawable d = new BitmapDrawable(null, card);
        d.setFilterBitmap(true);
        return d;
    }

    private static Bitmap card(Drawable d) {
        int iw = d.getIntrinsicWidth(), ih = d.getIntrinsicHeight();
        int rw, rh;
        if (iw <= 0 || ih <= 0) {
            rw = CARD_W;
            rh = CARD_H;
        } else {
            rw = iw;
            rh = ih;
            if (rw > SRC_MAX || rh > SRC_MAX) {
                if (rw >= rh) {
                    rh = Math.max(1, SRC_MAX * ih / iw);
                    rw = SRC_MAX;
                } else {
                    rw = Math.max(1, SRC_MAX * iw / ih);
                    rh = SRC_MAX;
                }
            }
        }
        Bitmap src = raster(d, rw, rh);
        if (src == null) return null;
        Bitmap out = Bitmap.createBitmap(CARD_W, CARD_H, Bitmap.Config.ARGB_8888);
        try {
            int[] pixels = new int[rw * rh];
            src.getPixels(pixels, 0, rw, 0, 0, rw, rh);
            int[] box = contentBox(pixels, rw, rh);
            Canvas canvas = new Canvas(out);
            boolean bleed = box != null
                    && (box[2] - box[0]) * (box[3] - box[1]) > BLEED * rw * rh;
            Paint filter = new Paint(Paint.FILTER_BITMAP_FLAG);
            if (bleed) {
                int[] dest = new int[4];
                cover(dest, rw, rh, CARD_W, CARD_H);
                canvas.drawBitmap(src, null,
                        new Rect(dest[0], dest[1], dest[2], dest[3]), filter);
            } else {
                int fill = cornerColor(pixels, rw, rh);
                if (fill != 0) canvas.drawColor(fill);
                if (box != null) {
                    int pad = Math.round(0.08f * Math.min(CARD_W, CARD_H));
                    int roomW = CARD_W - 2 * pad, roomH = CARD_H - 2 * pad;
                    int bw = box[2] - box[0], bh = box[3] - box[1];
                    int[] dest = new int[4];
                    contain(dest, bw, bh, roomW, roomH);
                    dest[0] += pad;
                    dest[1] += pad;
                    dest[2] += pad;
                    dest[3] += pad;
                    canvas.drawBitmap(src,
                            new Rect(box[0], box[1], box[2], box[3]),
                            new Rect(dest[0], dest[1], dest[2], dest[3]), filter);
                }
            }
            return out;
        } catch (RuntimeException e) {
            out.recycle();
            return null;
        } finally {
            src.recycle();
        }
    }

    static void contain(int[] out, int iw, int ih, int cw, int ch) {
        if (iw <= 0 || ih <= 0) {
            out[0] = 0;
            out[1] = 0;
            out[2] = cw;
            out[3] = ch;
            return;
        }
        if ((long) iw * ch >= (long) ih * cw) {
            int dh = (int) ((long) ih * cw / iw);
            int top = (ch - dh) / 2;
            out[0] = 0;
            out[1] = top;
            out[2] = cw;
            out[3] = top + dh;
        } else {
            int dw = (int) ((long) iw * ch / ih);
            int left = (cw - dw) / 2;
            out[0] = left;
            out[1] = 0;
            out[2] = left + dw;
            out[3] = ch;
        }
    }

    /**
     * Draws {@code d} into a new bitmap. Adaptive icons are flattened from their background
     * and foreground layers so the extra mask inset does not leave empty padding in the tile.
     */
    private static Bitmap raster(Drawable d, int w, int h) {
        if (d.getConstantState() == null) return null;
        Bitmap bitmap = null;
        try {
            bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            Drawable[] layers = adaptiveLayers(d);
            if (layers != null) {
                drawFitted(canvas, layers[0], w, h);
                drawFitted(canvas, layers[1], w, h);
            } else {
                Drawable copy = d.getConstantState().newDrawable().mutate();
                copy.setBounds(0, 0, w, h);
                copy.draw(canvas);
            }
            return bitmap;
        } catch (RuntimeException e) {
            if (bitmap != null) bitmap.recycle();
            return null;
        }
    }

    private static void drawFitted(Canvas canvas, Drawable layer, int w, int h) {
        if (layer == null) return;
        Drawable copy = layer.getConstantState() != null
                ? layer.getConstantState().newDrawable().mutate() : layer;
        copy.setBounds(0, 0, w, h);
        copy.draw(canvas);
    }

    /** {@code AdaptiveIconDrawable} is API 26; the launcher compiles against 25. */
    private static Drawable[] adaptiveLayers(Drawable d) {
        try {
            Class<?> c = d.getClass();
            while (c != null && !"android.graphics.drawable.AdaptiveIconDrawable".equals(c.getName())) {
                c = c.getSuperclass();
            }
            if (c == null) return null;
            return new Drawable[] {
                (Drawable) c.getMethod("getBackground").invoke(d),
                (Drawable) c.getMethod("getForeground").invoke(d)
            };
        } catch (Exception ignored) {
            return null;
        }
    }

    private static int[] corners(int[] pixels, int w, int h) {
        return new int[] {pixels[0], pixels[w - 1], pixels[(h - 1) * w], pixels[h * w - 1]};
    }

    private static int cornerColor(int[] pixels, int w, int h) {
        int[] corners = corners(pixels, w, h);
        int r = 0, g = 0, b = 0, n = 0;
        for (int p : corners) {
            if ((p >>> 24) < 16) continue;
            r += p >> 16 & 255;
            g += p >> 8 & 255;
            b += p & 255;
            n++;
        }
        return n == 0 ? 0 : 0xFF000000 | (r / n) << 16 | (g / n) << 8 | b / n;
    }

    private static boolean unlikeCorners(int p, int[] corners) {
        for (int c : corners) {
            if ((c >>> 24) < 16) continue;
            if (delta(p, c) <= CORNER_DELTA) return false;
        }
        return true;
    }

    private static int delta(int a, int b) {
        int dr = Math.abs((a >> 16 & 255) - (b >> 16 & 255));
        int dg = Math.abs((a >> 8 & 255) - (b >> 8 & 255));
        int db = Math.abs((a & 255) - (b & 255));
        return Math.max(dr, Math.max(dg, db));
    }
}
