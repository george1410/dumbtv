package tv.dumbtv.home;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.text.style.RelativeSizeSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class Home extends Activity {
    /** How long Menu is held to open Options while the child lock is on. */
    static final long UNLOCK_MS = 3000;
    /** Focus-follow scroll; short enough for the remote, long enough not to jump. */
    private static final int SLIDE_MS = 180;
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final String APPSTORE = "com.amazon.venezia";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService colorIo = Executors.newSingleThreadExecutor();
    private final List<View> chips = new ArrayList<>();
    private final List<NowCard> nowCards = new ArrayList<>();
    private Config cfg;
    private TvInputManager tv;
    private int applied = -1, appsGeneration;
    private boolean resumed, landed, unlockOpened;
    private String lastFocusedKind;
    private final Map<String, String> lastIds = new HashMap<>();
    private FrameLayout root;
    private LinearLayout column;
    private ImageView wall;
    private View shade;
    private View tint;
    private LinearLayout head, nowStrip, liveCard;
    private ImageView smartHome;
    private View nowBlock;
    private TextView clock, date, nowHeading;
    private Meter meter;
    private Epg epg = Epg.EMPTY;
    private boolean epgLoading;
    private Row apps, allApps, inputs;
    private Hero hero;
    private int libraryGeneration;
    private Map<String, Long> recent = new HashMap<>();
    private Tile moving;
    private String wallKey;
    private String backgroundMode;
    private ValueAnimator colorAnimation;
    private int displayedColor, targetColor;
    private boolean tintWallpaper;
    /** Options › Icon pack and the picked icons as the tiles' art was last loaded for. UI thread. */
    private String artState = "";
    /** Icon packs read so far by package, null for one that would not load; io thread only. */
    private final Map<String, IconPack> packs = new HashMap<>();
    /** The installed icon packs, package -> name, read on io; UI thread. */
    private Map<String, String> iconPacks = new HashMap<>();

    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            Uri data = i.getData();
            String changed = data == null ? null : data.getSchemeSpecificPart();
            if (changed != null) {
                // Forget what was read from that package, a pack that would not load included:
                // a pack installed again while another was chosen must load when chosen.
                io.execute(() -> packs.remove(changed));
                loadIconPacks();
                if (artState.contains(changed)) {
                    for (Item it : apps.all) it.artRequested = false;
                }
            }
            loadApps(changed);
        }
    };

    private final BroadcastReceiver time = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            tick();
        }
    };

    private final TvInputManager.TvInputCallback inputWatch = new TvInputManager.TvInputCallback() {
        @Override
        public void onInputStateChanged(String id, int state) {
            loadInputs();
        }

        @Override
        public void onInputAdded(String id) {
            loadInputs();
        }

        @Override
        public void onInputRemoved(String id) {
            loadInputs();
        }

        @Override
        public void onInputUpdated(String id) {
            loadInputs();
        }
    };

    private final class Row {
        final String kind;
        final TextView title;
        final HorizontalScrollView scroll;
        final LinearLayout strip;
        final Grid grid;
        List<Item> all = new ArrayList<>();

        Row(String kind, String name, LinearLayout parent) {
            this.kind = kind;
            int edge = dp(56), bleed = dp(14);
            title = Ui.text(Home.this, 12, Ui.FAINT, Ui.MEDIUM);
            title.setText(name.toUpperCase(Locale.ROOT));
            title.setLetterSpacing(0.14f);
            title.setPadding(edge, dp(20), edge, dp(2));
            strip = new LinearLayout(Home.this);
            strip.setClipChildren(false);
            strip.setClipToPadding(false);
            strip.setPadding(bleed, dp(12), bleed, dp(8));
            scroll = new StripScroll(false);
            scroll.setPadding(edge - bleed, 0, edge - bleed, 0);
            scroll.addView(strip, new FrameLayout.LayoutParams(WRAP, WRAP));
            grid = new Grid(Home.this);
            grid.setPadding(edge, dp(12), edge, dp(8));
            parent.addView(title, new LinearLayout.LayoutParams(MATCH, WRAP));
            parent.addView(scroll, new LinearLayout.LayoutParams(MATCH, WRAP));
            parent.addView(grid, new LinearLayout.LayoutParams(MATCH, WRAP));
        }

        /** Whichever container currently holds the tiles, following Options › Layout. */
        ViewGroup tiles() {
            return "grid".equals(cfg.get(Config.LAYOUT)) ? grid : strip;
        }

        void setVisible(boolean on) {
            boolean gridMode = "grid".equals(cfg.get(Config.LAYOUT));
            title.setVisibility(on ? View.VISIBLE : View.GONE);
            scroll.setVisibility(on && !gridMode ? View.VISIBLE : View.GONE);
            grid.setVisibility(on && gridMode ? View.VISIBLE : View.GONE);
        }
    }

    /** A compact on-now card: channel name and what's playing. */
    private final class NowCard extends LinearLayout {
        final Channels.Channel channel;
        final TextView name, prog;

        NowCard(Channels.Channel ch) {
            super(Home.this);
            this.channel = ch;
            setOrientation(VERTICAL);
            setPadding(dp(16), dp(14), dp(16), dp(14));
            setFocusable(true);
            setClickable(true);
            setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
            setOnClickListener(v -> openChannel(ch));
            setOnFocusChangeListener((view, has) -> {
                view.animate().scaleX(has ? 1.05f : 1f).scaleY(has ? 1.05f : 1f).setDuration(110).start();
                paint();
                if (has) focusBackground(null);
            });
            TextView via = Ui.text(Home.this, 11, Ui.FAINT, Ui.MEDIUM);
            via.setText(ch.name.toUpperCase(Locale.ROOT));
            via.setLetterSpacing(0.06f);
            via.setSingleLine(true);
            name = Ui.text(Home.this, 16, Ui.TEXT, Ui.MEDIUM);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setPadding(0, dp(6), 0, 0);
            prog = Ui.text(Home.this, 13, Ui.MUTED, Ui.LIGHT);
            prog.setSingleLine(true);
            prog.setEllipsize(TextUtils.TruncateAt.END);
            prog.setPadding(0, dp(4), 0, 0);
            addView(via);
            addView(name, new LayoutParams(MATCH, WRAP));
            addView(prog, new LayoutParams(MATCH, WRAP));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(210), WRAP);
            lp.rightMargin = dp(10);
            setLayoutParams(lp);
        }

        void paint() {
            int accent = cfg.accent();
            boolean has = isFocused();
            setBackground(has
                    ? Ui.round((accent & 0x00FFFFFF) | 0x73000000, dp(14))
                    : Ui.round(0xFF16171C, dp(14)));
            name.setTextColor(Ui.TEXT);
            prog.setTextColor(has ? Ui.TEXT : Ui.MUTED);
        }

        void bind(long now) {
            Epg.Programme on = playing(channel, now);
            name.setText(on == null ? chEmpty() : on.title);
            prog.setText(on == null ? "" : left(on, now));
        }

        private String chEmpty() {
            return epg == Epg.EMPTY ? "" : "No listings";
        }
    }

    /** Opens the full guide; sits at the start of On Now so Live TV is not a header control. */
    private LinearLayout makeLiveCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setFocusable(true);
        card.setClickable(true);
        card.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        card.setOnClickListener(v -> startActivity(new Intent(this, Live.class)));
        card.setOnFocusChangeListener((view, has) -> {
            view.animate().scaleX(has ? 1.05f : 1f).scaleY(has ? 1.05f : 1f).setDuration(110).start();
            paintLive();
            if (has) focusBackground(null);
        });
        TextView eyebrow = Ui.text(this, 11, Ui.FAINT, Ui.MEDIUM);
        eyebrow.setText("LIVE TV");
        eyebrow.setLetterSpacing(0.06f);
        TextView title = Ui.text(this, 16, Ui.TEXT, Ui.MEDIUM);
        title.setText("Programme guide");
        title.setPadding(0, dp(6), 0, 0);
        TextView sub = Ui.text(this, 13, Ui.MUTED, Ui.LIGHT);
        sub.setText("All channels");
        sub.setPadding(0, dp(4), 0, 0);
        card.addView(eyebrow);
        card.addView(title, new LinearLayout.LayoutParams(MATCH, WRAP));
        card.addView(sub, new LinearLayout.LayoutParams(MATCH, WRAP));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(210), WRAP);
        lp.rightMargin = dp(10);
        card.setLayoutParams(lp);
        return card;
    }

    private void paintLive() {
        if (liveCard == null) return;
        int accent = cfg.accent();
        boolean has = liveCard.isFocused();
        liveCard.setBackground(has
                ? Ui.round((accent & 0x00FFFFFF) | 0x73000000, dp(14))
                : Ui.round((accent & 0x00FFFFFF) | 0x3D000000, dp(14)));
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Future<List<Item>> catalog = io.submit(() -> Catalog.apps(this));
        cfg = Config.get(this);
        Future<Map<String, Long>> times = recentFor(Catalog.APPS) == null ? null
                : io.submit(() -> Recent.times(this));
        tv = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
        setContentView(build());

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(packages, f);
        Keeper.start(this);
        if (tv != null) tv.registerCallback(inputWatch, ui);
        firstApps(catalog, times);
        loadInputs();
        loadIconPacks();
    }

    private void loadIconPacks() {
        io.execute(() -> {
            Map<String, String> found = IconPack.installed(getPackageManager());
            ui.post(() -> iconPacks = found);
        });
    }

    /**
     * Waits for the app list and loads the art of the tiles that fit on screen, so the first
     * frame is complete. After a cold start that frame is what people see first: loading in the
     * background drew the Inputs row alone, focused HDMI 1 for ~0.3 s, then popped the apps and
     * their banners in.
     */
    private void firstApps(Future<List<Item>> catalog, Future<Map<String, Long>> times) {
        artState = artState();
        try {
            apps.all = catalog.get();
            if (times != null) recent = times.get();
        } catch (ExecutionException e) {
            loadApps(null);
            return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            loadApps(null);
            return;
        }
        int fit = getResources().getDisplayMetrics().widthPixels / (cardSize()[0] + dp(18)) + 1;
        boolean cornerIcon = "afterglow".equals(cfg.get(Config.TILE_STYLE));
        List<Item> first = new ArrayList<>();
        List<Runnable> jobs = new ArrayList<>();
        // A chosen icon pack is read by parsing its whole appfilter; the first frame does not wait
        // for that, and its tiles load their art as they are drawn, like every later tile.
        if (!IconPack.OFF.equals(cfg.get(IconPack.KEY, IconPack.OFF))) return;
        for (Item it : Catalog.visible(cfg, Catalog.APPS, apps.all, recentFor(Catalog.APPS))) {
            if (fit-- <= 0) break;
            first.add(it);
            jobs.add(artJob(it, cornerIcon));
        }
        try {
            io.submit(() -> {
                for (Runnable job : jobs) job.run();
            }).get();
        } catch (ExecutionException e) {
            return; // the tiles load their art as they are drawn
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        for (Item it : first) it.artRequested = true;
    }

    /** What the tiles' art depends on besides the apps themselves. */
    private String artState() {
        return cfg.get(IconPack.KEY, IconPack.OFF) + "|" + cfg.joined(IconPack.CHOSEN);
    }

    /** Loads one tile's art: the settings are read here, on the UI thread; run it on io. */
    private Runnable artJob(Item it, boolean cornerIcon) {
        String pack = cfg.get(IconPack.KEY, IconPack.OFF);
        String chosen = cfg.get(IconPack.CHOSEN + it.id, null);
        return () -> Catalog.art(getPackageManager(), it, cornerIcon, replacementArt(it, chosen, pack));
    }

    /**
     * The picture that replaces an app's own art: the icon picked for it from the chosen pack,
     * then the chosen pack's match. Null keeps the app's own. io thread only.
     */
    private Drawable replacementArt(Item it, String chosen, String pack) {
        if (IconPack.OWN.equals(chosen)) return null;
        IconPack p = iconPack(pack);
        if (p == null) return null;
        // A picked icon belongs to the pack it came from and only counts while that pack is the
        // chosen one: switching packs needs no tile fixed by hand, switching back restores it.
        String picked = IconMatch.picked(chosen, pack);
        Drawable d = picked == null ? null : p.picture(picked);
        return d != null ? d : p.drawable(it);
    }

    /** An icon pack by package, read once. io thread only. */
    private IconPack iconPack(String pkg) {
        if (!packs.containsKey(pkg)) {
            IconPack p = IconPack.load(getPackageManager(), pkg);
            packs.put(pkg, p);
            if (p != null) Keeper.note("icon pack " + pkg + ": " + p.size() + " apps");
        }
        return packs.get(pkg);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        Guard.arrived(this);
        Filter.wake(this);
        cfg.refresh();
        cfg.visited();
        if (cfg.version() != applied) apply();
        else background();
        refreshRecent();
        loadLibrary();
        hero.start();
        IntentFilter f = new IntentFilter(Intent.ACTION_TIME_TICK);
        f.addAction(Intent.ACTION_TIME_CHANGED);
        f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(time, f);
        tick();
        loadEpg();
    }

    @Override
    public void onEnterAnimationComplete() {
        Guard.shown();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        hero.stop();
        stopColorAnimation();
        targetColor = 0;
        unregisterReceiver(time);
        if (moving != null) stopMoving();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packages);
        if (tv != null) tv.unregisterCallback(inputWatch);
        io.shutdownNow();
        colorIo.shutdownNow();
        super.onDestroy();
    }

    /**
     * Android 9 keeps a hidden window's GPU context (about 35 MB on this TV) unless memory is
     * critical. Dropping it while an app plays costs one context rebuild on the way back.
     */
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level < TRIM_MEMORY_UI_HIDDEN || level >= TRIM_MEMORY_COMPLETE) return;
        try {
            Class<?> wmg = Class.forName("android.view.WindowManagerGlobal");
            Object global = wmg.getMethod("getInstance").invoke(null);
            wmg.getMethod("trimMemory", int.class).invoke(global, TRIM_MEMORY_COMPLETE);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // not reachable on this build: the context simply stays
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (moving != null) stopMoving();
        if (resumed) focusFirst();
    }

    @Override
    public void onBackPressed() {
        focusFirst();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (moving == null) return super.dispatchKeyEvent(e);
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    shift(-1);
                    break;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    shift(1);
                    break;
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    // In a grid, up and down move the tile a whole line; a strip has no lines.
                    if (moving.getParent() instanceof Grid) {
                        int cols = ((Grid) moving.getParent()).columns();
                        shift(e.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP ? -cols : cols);
                    }
                    break;
                default:
                    stopMoving();
            }
        }
        return true;
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_MENU && locked()) {
            // Repeats keep arriving while the button is held; the first one past the hold opens it.
            if (e.getRepeatCount() == 0) {
                unlockOpened = false;
            } else if (!unlockOpened && e.getEventTime() - e.getDownTime() >= UNLOCK_MS) {
                unlockOpened = true;
                startActivity(new Intent(this, Options.class));
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_MENU) {
            View f = getCurrentFocus();
            if (f instanceof Tile) menu((Tile) f);
            else startActivity(new Intent(this, Options.class));
            return true;
        }
        return super.onKeyDown(code, e);
    }

    /**
     * The scroll position that keeps the focused row fully on screen, moving as little as
     * possible. The row is not snapped to the top.
     */
    private int rowTarget(ScrollView page, Rect r) {
        View content = page.getChildAt(0);
        List<int[]> rows = rowBlocks(content);
        int[] at = null;
        for (int[] b : rows) {
            if (r.centerY() >= b[0]) at = b;
        }
        if (at == null) return 0;
        int height = page.getHeight();
        int range = Math.max(0, content.getHeight() - height);
        int y = page.getScrollY();
        if (at[1] > y + height) y = at[1] - height;
        if (at[0] < y) y = at[0];
        return Math.max(0, Math.min(range, y));
    }

    /**
     * Title top and strip bottom of each visible row, in {@code content}'s coordinates. In grid
     * mode every line of tiles is its own block (the first one starting at the row title), so
     * a grid taller than the screen still keeps one line fully visible at a time.
     */
    private List<int[]> rowBlocks(View content) {
        List<int[]> rows = new ArrayList<>();
        for (Row row : rows()) {
            if (row.scroll.getVisibility() == View.VISIBLE) {
                rows.add(new int[] {topIn(row.title, content), topIn(row.scroll, content) + row.scroll.getHeight()});
            } else if (row.grid.getVisibility() == View.VISIBLE) {
                int gridTop = topIn(row.grid, content), lastTop = Integer.MIN_VALUE;
                for (int i = 0; i < row.grid.getChildCount(); i++) {
                    View t = row.grid.getChildAt(i);
                    if (t.getTop() == lastTop) continue;
                    int start = lastTop == Integer.MIN_VALUE ? topIn(row.title, content) : gridTop + t.getTop() - dp(12);
                    lastTop = t.getTop();
                    rows.add(new int[] {start, gridTop + t.getBottom() + dp(8)});
                }
            }
        }
        return rows;
    }

    /** {@code v}'s top edge in {@code content}'s coordinates. */
    private static int topIn(View v, View content) {
        int y = 0;
        while (v != null && v != content) {
            y += v.getTop();
            v = (View) v.getParent();
        }
        return y;
    }

    /** Vertical page that follows focus with a short slide, instead of jumping a row to the top. */
    private final class PageScroll extends ScrollView {
        private ValueAnimator slide;

        PageScroll() {
            super(Home.this);
            setFocusable(false);
            setFillViewport(true);
            setVerticalScrollBarEnabled(false);
            setOverScrollMode(OVER_SCROLL_NEVER);
            setClipChildren(false);
        }

        @Override
        protected int computeScrollDeltaToGetChildRectOnScreen(Rect r) {
            return rowTarget(this, r) - getScrollY();
        }

        @Override
        public void requestChildFocus(View child, View focused) {
            int from = getScrollY();
            int to = from;
            if (focused != null) {
                Rect r = new Rect();
                focused.getDrawingRect(r);
                offsetDescendantRectToMyCoords(focused, r);
                to = from + computeScrollDeltaToGetChildRectOnScreen(r);
            }
            super.requestChildFocus(child, focused);
            scrollTo(0, from);
            slideY(to);
        }

        @Override
        public boolean requestChildRectangleOnScreen(View child, Rect rectangle, boolean immediate) {
            rectangle.offset(child.getLeft() - child.getScrollX(), child.getTop() - child.getScrollY());
            int delta = computeScrollDeltaToGetChildRectOnScreen(rectangle);
            if (delta == 0) return false;
            if (immediate) scrollBy(0, delta);
            else slideY(getScrollY() + delta);
            return true;
        }

        /**
         * Up and down only move the focus; scrolling follows it. Left to ScrollView, a press
         * with nothing focusable that way scrolls half a screen and leaves rows cut.
         */
        @Override
        public boolean executeKeyEvent(KeyEvent e) {
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_PAGE_UP:
                case KeyEvent.KEYCODE_PAGE_DOWN:
                case KeyEvent.KEYCODE_SPACE:
                    return false;
                default:
                    return super.executeKeyEvent(e);
            }
        }

        private void slideY(int to) {
            if (slide != null) slide.cancel();
            int from = getScrollY();
            if (from == to) return;
            slide = ValueAnimator.ofInt(from, to);
            slide.setDuration(SLIDE_MS);
            slide.setInterpolator(new DecelerateInterpolator());
            slide.addUpdateListener(a -> scrollTo(0, (int) a.getAnimatedValue()));
            slide.start();
        }
    }

    /** Horizontal strip that keeps the focused child centered, with a short slide instead of a jump. */
    private final class StripScroll extends HorizontalScrollView {
        private final boolean arrowsMoveFocusOnly;
        private ValueAnimator slide;

        StripScroll(boolean arrowsMoveFocusOnly) {
            super(Home.this);
            this.arrowsMoveFocusOnly = arrowsMoveFocusOnly;
            setFocusable(false);
            setHorizontalScrollBarEnabled(false);
            setOverScrollMode(OVER_SCROLL_NEVER);
            setClipChildren(false);
            setClipToPadding(false);
        }

        @Override
        protected int computeScrollDeltaToGetChildRectOnScreen(Rect r) {
            if (getChildCount() == 0) return 0;
            int range = Math.max(0, getChildAt(0).getWidth()
                    - (getWidth() - getPaddingLeft() - getPaddingRight()));
            int target = Math.max(0, Math.min(range, r.centerX() - getWidth() / 2));
            return target - getScrollX();
        }

        @Override
        public void requestChildFocus(View child, View focused) {
            int from = getScrollX();
            int to = from;
            if (focused != null) {
                Rect r = new Rect();
                focused.getDrawingRect(r);
                offsetDescendantRectToMyCoords(focused, r);
                to = from + computeScrollDeltaToGetChildRectOnScreen(r);
            }
            super.requestChildFocus(child, focused);
            scrollTo(from, 0);
            slideX(to);
        }

        @Override
        public boolean requestChildRectangleOnScreen(View child, Rect rectangle, boolean immediate) {
            rectangle.offset(child.getLeft() - child.getScrollX(), child.getTop() - child.getScrollY());
            int delta = computeScrollDeltaToGetChildRectOnScreen(rectangle);
            if (delta == 0) return false;
            if (immediate) scrollBy(delta, 0);
            else slideX(getScrollX() + delta);
            return true;
        }

        @Override
        public boolean executeKeyEvent(KeyEvent e) {
            if (arrowsMoveFocusOnly) {
                switch (e.getKeyCode()) {
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        return false;
                    default:
                        break;
                }
            }
            return super.executeKeyEvent(e);
        }

        private void slideX(int to) {
            if (slide != null) slide.cancel();
            int from = getScrollX();
            if (from == to) return;
            slide = ValueAnimator.ofInt(from, to);
            slide.setDuration(SLIDE_MS);
            slide.setInterpolator(new DecelerateInterpolator());
            slide.addUpdateListener(a -> scrollTo((int) a.getAnimatedValue(), 0));
            slide.start();
        }
    }

    private View build() {
        root = new FrameLayout(this);
        wall = new ImageView(this);
        wall.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(wall, new FrameLayout.LayoutParams(MATCH, MATCH));
        shade = new View(this);
        root.addView(shade, new FrameLayout.LayoutParams(MATCH, MATCH));
        tint = new View(this);
        root.addView(tint, new FrameLayout.LayoutParams(MATCH, MATCH));

        ScrollView scroll = new PageScroll();
        root.addView(scroll, new FrameLayout.LayoutParams(MATCH, MATCH));

        LinearLayout column = new LinearLayout(this) {
            @Override
            public View focusSearch(View focused, int direction) {
                // Library banner, then On Now, then the app rows.
                if (focused == hero && direction == View.FOCUS_UP) {
                    for (View c : chips) if (c.getVisibility() == View.VISIBLE) return c;
                }
                if (focused == hero && direction != View.FOCUS_DOWN) return focused;
                if (direction == View.FOCUS_DOWN && chips.contains(focused) && heroShown()) {
                    return hero;
                }
                if (direction == View.FOCUS_DOWN && (chips.contains(focused) || focused == hero)) {
                    if (nowStrip != null && nowStrip.getVisibility() == View.VISIBLE
                            && nowStrip.getChildCount() > 0) {
                        View now = nearest(nowStrip, focused);
                        if (now != null) return now;
                    }
                    View remembered = rememberedTile(lastFocusedKind);
                    if (remembered != null) return remembered;
                    for (Row row : rows()) {
                        View below = nearest(row.tiles(), focused);
                        if (below != null) return below;
                    }
                }
                if (focused instanceof NowCard && nowStrip != null) {
                    int idx = nowStrip.indexOfChild(focused);
                    if (direction == View.FOCUS_LEFT && idx == 0) return focused;
                    if (direction == View.FOCUS_RIGHT && idx == nowStrip.getChildCount() - 1) {
                        return focused;
                    }
                }
                if (direction == View.FOCUS_DOWN && focused instanceof NowCard) {
                    View remembered = rememberedTile(lastFocusedKind);
                    if (remembered != null) return remembered;
                    View below = nearest(apps.tiles(), focused);
                    if (below != null) return below;
                }
                if (direction == View.FOCUS_UP && focused instanceof Tile
                        && Catalog.APPS.equals(((Tile) focused).kind)
                        && nowStrip != null && nowStrip.getVisibility() == View.VISIBLE
                        && nowStrip.getChildCount() > 0) {
                    ViewGroup parent = (ViewGroup) focused.getParent();
                    boolean firstLine = !(parent instanceof Grid)
                            || !aboveInGrid(parent, (Tile) focused);
                    if (firstLine) {
                        View now = nearest(nowStrip, focused);
                        if (now != null) return now;
                    }
                }
                if (focused instanceof Tile
                        && focused.getParent() instanceof ViewGroup) {
                    Tile tile = (Tile) focused;
                    ViewGroup parent = (ViewGroup) tile.getParent();
                    int idx = parent.indexOfChild(tile);
                    int count = parent.getChildCount();
                    boolean grid = parent instanceof Grid;
                    int cols = grid ? ((Grid) parent).columns() : 1;

                    // Left and right stop at the edge of a row instead of jumping away
                    // from it: no tile of another row takes the focus.
                    if (direction == View.FOCUS_LEFT) {
                        boolean edge = grid ? idx % cols == 0 : idx == 0;
                        if (edge) return focused;
                    }
                    if (direction == View.FOCUS_RIGHT) {
                        boolean edge = grid
                                ? (idx + 1) % cols == 0 || idx == count - 1
                                : idx == count - 1;
                        if (edge) return focused;
                    }

                    // Up from the first line of the apps row goes to the library banner.
                    if (direction == View.FOCUS_UP && Catalog.APPS.equals(tile.kind)
                            && heroShown() && !(grid && aboveInGrid(parent, tile))) {
                        return hero;
                    }

                    // Stay in a grid until its last/first line. Crossing a section restores
                    // that section's own last tile, even if the same app is also pinned above.
                    if (direction == View.FOCUS_DOWN) {
                        int target = grid ? belowInGrid(parent, tile) : -1;
                        if (target >= 0) return parent.getChildAt(target);
                        View below = adjacentTile(tile.kind, 1, focused);
                        return below != null ? below : focused;
                    }
                    if (direction == View.FOCUS_UP && !(grid && aboveInGrid(parent, tile))) {
                        View above = adjacentTile(tile.kind, -1, focused);
                        if (above != null) return above;
                        if (locked()) return focused;
                    }
                }
                View next = super.focusSearch(focused, direction);
                if (next == null && direction == View.FOCUS_UP && !chips.isEmpty()) {
                    int[] at = new int[2];
                    focused.getLocationOnScreen(at);
                    int x = at[0] + focused.getWidth() / 2;
                    View best = null;
                    int bestGap = Integer.MAX_VALUE;
                    View fallback = null;
                    for (View c : chips) {
                        if (c.getVisibility() != View.VISIBLE) continue;
                        if (fallback == null) fallback = c;
                        c.getLocationOnScreen(at);
                        int gap = Math.abs(at[0] + c.getWidth() / 2 - x);
                        if (gap < bestGap) {
                            bestGap = gap;
                            best = c;
                        }
                    }
                    return best != null ? best : fallback;
                }
                return next;
            }
        };
        column.setOrientation(LinearLayout.VERTICAL);
        column.setClipChildren(false);
        column.setClipToPadding(false);
        column.setPadding(0, dp(40), 0, dp(32));
        scroll.addView(column, new FrameLayout.LayoutParams(MATCH, WRAP));

        head = new LinearLayout(this);
        head.setClipChildren(false);
        head.setClipToPadding(false);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(56), 0, dp(56), dp(12));
        LinearLayout when = new LinearLayout(this);
        when.setOrientation(LinearLayout.VERTICAL);
        clock = Ui.text(this, 56, Ui.TEXT, Ui.LIGHT);
        date = Ui.text(this, 14, Ui.MUTED, Ui.MEDIUM);
        date.setLetterSpacing(0.08f);
        date.setPadding(dp(2), dp(10), 0, 0);
        when.addView(clock);
        when.addView(date);
        meter = new Meter(this);
        meter.setWords(getString(R.string.meter_title), getString(R.string.meter_free),
                getString(R.string.meter_low));
        meter.setVisibility(View.GONE);
        LinearLayout.LayoutParams under = new LinearLayout.LayoutParams(WRAP, WRAP);
        under.topMargin = dp(12);
        under.leftMargin = dp(2);
        when.addView(meter, under);
        head.addView(when, new LinearLayout.LayoutParams(0, WRAP, 1f));
        smartHome = headerIcon(R.drawable.house, "Alexa Smart Home", v -> openSmartHome());
        chips.add(smartHome);
        head.addView(smartHome);
        head.addView(chip(getString(R.string.options), R.drawable.chip_options, R.drawable.chip_options_mark,
                v -> startActivity(new Intent(this, Options.class))));
        column.addView(head, new LinearLayout.LayoutParams(MATCH, WRAP));

        hero = new Hero(this, ui, io);
        hero.setOnClickListener(v -> hero.openCurrent());
        hero.setOnFocusChangeListener((v, has) -> {
            if (has) focusBackground(null);
        });
        LinearLayout.LayoutParams heroLp = new LinearLayout.LayoutParams(MATCH, dp(220));
        heroLp.leftMargin = dp(56);
        heroLp.rightMargin = dp(56);
        heroLp.topMargin = dp(2);
        column.addView(hero, heroLp);
        this.column = column;

        nowBlock = nowRow(column);

        apps = new Row(Catalog.APPS, getString(R.string.row_apps), column);
        allApps = new Row(Catalog.ALL_APPS, getString(R.string.row_all_apps), column);
        allApps.setVisible(false);
        inputs = new Row(Catalog.INPUTS, getString(R.string.row_inputs), column);
        return root;
    }

    /**
     * The index of the tile below {@code tile}, or -1 when it is in the last line. Only the
     * last line of a grid can be shorter than the others: a tile with no tile directly under
     * it takes the last tile of the line below, the nearest one in that line.
     */
    private static int belowInGrid(ViewGroup parent, Tile tile) {
        int cols = ((Grid) parent).columns();
        int index = parent.indexOfChild(tile), count = parent.getChildCount();
        if (index / cols >= (count - 1) / cols) return -1;
        return Math.min(index + cols, count - 1);
    }

    /** True when the grid has another line above {@code tile}. */
    private static boolean aboveInGrid(ViewGroup parent, Tile tile) {
        int cols = ((Grid) parent).columns();
        return parent.indexOfChild(tile) - cols >= 0;
    }

    /**
     * The child of {@code strip} horizontally closest to {@code from}, restricted to its top
     * visual row so a wrapped grid does not match a tile several rows further down.
     */
    private static View nearest(ViewGroup strip, View from) {
        int n = strip.getChildCount();
        if (n == 0) return null;
        int[] at = new int[2];
        from.getLocationOnScreen(at);
        int x = at[0] + from.getWidth() / 2;
        int[] ys = new int[n];
        int topY = Integer.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            strip.getChildAt(i).getLocationOnScreen(at);
            ys[i] = at[1];
            topY = Math.min(topY, at[1]);
        }
        View best = null;
        int bestGap = Integer.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            if (ys[i] != topY) continue;
            View c = strip.getChildAt(i);
            c.getLocationOnScreen(at);
            int gap = Math.abs(at[0] + c.getWidth() / 2 - x);
            if (gap < bestGap) {
                bestGap = gap;
                best = c;
            }
        }
        return best;
    }

    private boolean locked() {
        return "on".equals(cfg.get(Config.LOCK));
    }

    /**
     * A chip in the corner. Options › Chip style picks its look: the text chip (the default), or
     * a round icon button whose name shows next to the icon only while it has the focus, so the
     * corner stays quiet and the remote still says where OK goes. {@code shape} and
     * {@code mark} are the two layers {@link Ui#chipIcon} tints; {@link #apply} draws them.
     */
    private TextView chip(String label, int shape, int mark, View.OnClickListener click) {
        TextView v = Ui.text(this, 14, Ui.TEXT, Ui.MEDIUM);
        v.setTag(new int[] {shape, mark});
        v.setContentDescription(label);
        v.setGravity(Gravity.CENTER);
        v.setFocusable(true);
        v.setClickable(true);
        v.setOnClickListener(click);
        v.setOnFocusChangeListener((view, has) -> {
            chipLook(v, has);
            view.animate().scaleX(has ? 1.06f : 1f).scaleY(has ? 1.06f : 1f).setDuration(110).start();
            if (has) focusBackground(null);
        });
        chipLook(v, false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, WRAP);
        lp.leftMargin = dp(10);
        lp.topMargin = dp(6);
        v.setLayoutParams(lp);
        chips.add(v);
        return v;
    }

    private boolean iconChips() {
        return "icons".equals(cfg.get(Config.CHIPS));
    }

    /** Text and padding for the chip style: the name always for text, on focus only for icons. */
    private void chipLook(TextView c, boolean focused) {
        if (!iconChips()) {
            c.setText(c.getContentDescription());
            c.setCompoundDrawablePadding(0);
            c.setPadding(dp(18), dp(9), dp(18), dp(9));
            return;
        }
        c.setText(focused ? c.getContentDescription() : "");
        c.setCompoundDrawablePadding(focused ? dp(8) : 0);
        c.setPadding(dp(10), dp(10), dp(focused ? 16 : 10), dp(10));
    }

    private ImageView headerIcon(int drawable, String label, View.OnClickListener click) {
        ImageView v = new ImageView(this);
        v.setImageResource(drawable);
        v.setContentDescription(label);
        v.setPadding(dp(4), dp(4), dp(4), dp(4));
        v.setFocusable(true);
        v.setClickable(true);
        v.setOnClickListener(click);
        v.setOnFocusChangeListener((view, has) -> {
            view.animate().scaleX(has ? 1.12f : 1f).scaleY(has ? 1.12f : 1f).setDuration(110).start();
            if (has) focusBackground(null);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(28), dp(28));
        lp.gravity = Gravity.TOP;
        v.setLayoutParams(lp);
        return v;
    }

    private Intent smartHomeIntent() {
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(Catalog.SMART_HOME);
        return i != null ? i : getPackageManager().getLaunchIntentForPackage(Catalog.SMART_HOME);
    }

    private void openSmartHome() {
        Intent i = smartHomeIntent();
        if (i == null) {
            Toast.makeText(this, R.string.could_not_open, Toast.LENGTH_SHORT).show();
            return;
        }
        launch(i, Catalog.SMART_HOME);
    }

    private View nowRow(LinearLayout parent) {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setClipChildren(false);
        int edge = dp(56), bleed = dp(14);
        nowHeading = Ui.text(this, 12, Ui.FAINT, Ui.MEDIUM);
        nowHeading.setText("ON NOW");
        nowHeading.setLetterSpacing(0.14f);
        nowHeading.setPadding(edge, dp(8), edge, dp(2));
        nowStrip = new LinearLayout(this);
        nowStrip.setClipChildren(false);
        nowStrip.setClipToPadding(false);
        nowStrip.setPadding(bleed, dp(10), bleed, dp(8));
        HorizontalScrollView scroll = new StripScroll(true);
        scroll.setPadding(edge - bleed, 0, edge - bleed, 0);
        scroll.addView(nowStrip, new FrameLayout.LayoutParams(WRAP, WRAP));
        block.addView(nowHeading);
        block.addView(scroll, new LinearLayout.LayoutParams(MATCH, WRAP));
        parent.addView(block, new LinearLayout.LayoutParams(MATCH, WRAP));
        return block;
    }

    private void apply() {
        applied = cfg.version();
        int accent = cfg.accent();
        boolean hide = locked();
        boolean icons = iconChips();
        for (View c : chips) {
            if (c instanceof TextView) {
                TextView t = (TextView) c;
                Ui.focusStyle(t, accent, 0x14FFFFFF, dp(icons ? 21 : 18));
                int[] art = (int[]) t.getTag();
                t.setCompoundDrawablesRelative(icons ? Ui.chipIcon(getDrawable(art[0]), getDrawable(art[1]),
                        accent, dp(22)) : null, null, null, null);
                chipLook(t, t.isFocused());
            } else if (c instanceof ImageView) {
                Ui.ghostIcon((ImageView) c, accent, dp(14));
            }
            c.setVisibility(hide ? View.GONE : View.VISIBLE);
        }
        if (smartHome != null && (hide || smartHomeIntent() == null)) {
            smartHome.setVisibility(View.GONE);
        }
        if (nowBlock != null) nowBlock.setVisibility(hide ? View.GONE : View.VISIBLE);
        meter.setVisibility("on".equals(cfg.get(Config.MEMORY)) ? View.VISIBLE : View.GONE);
        meter.setAccent(accent);
        hero.setAccent(accent);
        apps.title.setText(getString("on".equals(cfg.get(Config.ALL_APPS))
                ? R.string.row_pinned_apps : R.string.row_apps).toUpperCase(Locale.ROOT));
        String state = artState();
        if (!state.equals(artState)) {
            artState = state;
            for (Item it : apps.all) it.artRequested = false;
        }
        fillNow();
        paintLive();
        render(apps);
        render(allApps);
        render(inputs);
        background();
        tick();
        loadEpg();
    }

    private void fillNow() {
        if (nowStrip == null) return;
        nowStrip.removeAllViews();
        nowCards.clear();
        if (locked()) {
            nowBlock.setVisibility(View.GONE);
            return;
        }
        if (liveCard == null) liveCard = makeLiveCard();
        nowStrip.addView(liveCard);
        for (Channels.Channel ch : Channels.favourites(this, cfg)) {
            NowCard card = new NowCard(ch);
            nowCards.add(card);
            nowStrip.addView(card);
        }
        nowBlock.setVisibility(View.VISIBLE);
        bindNow();
    }

    private void bindNow() {
        long now = System.currentTimeMillis();
        for (NowCard card : nowCards) {
            card.paint();
            card.bind(now);
        }
    }

    private void loadEpg() {
        if (epgLoading) return;
        epgLoading = true;
        io.execute(() -> {
            Epg got = epg != Epg.EMPTY ? epg : Epg.cached(this);
            final Epg cached = got;
            ui.post(() -> {
                epg = cached;
                bindNow();
            });
            if (!got.stale(System.currentTimeMillis())) {
                ui.post(() -> epgLoading = false);
                return;
            }
            try {
                final Epg fresh = Epg.fetch(this);
                ui.post(() -> {
                    epg = fresh;
                    epgLoading = false;
                    bindNow();
                });
            } catch (Exception e) {
                ui.post(() -> epgLoading = false);
            }
        });
    }

    private Epg.Programme playing(Channels.Channel ch, long now) {
        for (Epg.Programme p : epg.listings(ch)) {
            if (p.start <= now && p.stop > now) return p;
        }
        return null;
    }

    private String left(Epg.Programme p, long now) {
        long mins = Math.max(1, (p.stop - now + 59999) / 60000);
        if (mins < 60) return mins + " min left";
        return mins / 60 + " h" + (mins % 60 == 0 ? "" : " " + mins % 60 + " min") + " left";
    }

    private void openChannel(Channels.Channel ch) {
        Intent i = ch.intent(this);
        try {
            if (i == null) throw new ActivityNotFoundException();
            startActivity(i);
        } catch (ActivityNotFoundException | SecurityException e) {
            startActivity(new Intent(this, Live.class));
        }
    }

    /** Rebuilds the app list, keeping loaded art for every package except {@code changed}. */
    private void loadApps(String changed) {
        int generation = ++appsGeneration;
        io.execute(() -> {
            List<Item> list = Catalog.apps(this);
            ui.post(() -> {
                if (generation != appsGeneration || isDestroyed()) return;
                for (Item fresh : list) {
                    for (Item old : apps.all) {
                        if (old.id.equals(fresh.id) && !old.id.equals(changed) && old.artRequested) {
                            fresh.banner = old.banner;
                            fresh.icon = old.icon;
                            fresh.artRequested = true;
                            fresh.backgroundColor = old.backgroundColor;
                        }
                    }
                }
                apps.all = list;
                render(apps);
                render(allApps);
            });
        });
    }

    private boolean heroShown() {
        return hero.getVisibility() == View.VISIBLE && hero.hasEntries();
    }

    /** Reads the library folder off the UI thread; the banner hides while it has no titles. */
    private void loadLibrary() {
        int generation = ++libraryGeneration;
        boolean on = "on".equals(cfg.get(Config.LIBRARY));
        io.execute(() -> {
            List<Library.Entry> list = on ? Library.load(this, cfg) : new ArrayList<>();
            ui.post(() -> {
                if (generation != libraryGeneration || isDestroyed()) return;
                boolean was = heroShown();
                hero.setEntries(list);
                if (was && !heroShown()) focusFirst();
                // A shorter top keeps the clock, banner and apps row on one screen.
                column.setPadding(0, heroShown() ? dp(26) : dp(40), 0, column.getPaddingBottom());
                adjustAppRowPosition();
            });
        });
    }

    private void loadInputs() {
        inputs.all = Catalog.inputs(this, tv);
        render(inputs);
    }

    /** The last-used times to sort {@code kind} by, or null when it keeps the user's order. */
    private Map<String, Long> recentFor(String kind) {
        return Catalog.APPS.equals(kind) && "recent".equals(cfg.get(Config.ORDER)) ? recent : null;
    }

    /** With App order › Recently used, re-sorts the apps row on the way back home. */
    private void refreshRecent() {
        if (recentFor(Catalog.APPS) == null) return;
        io.execute(() -> {
            Map<String, Long> times = Recent.times(this);
            ui.post(() -> {
                if (isDestroyed() || recentFor(Catalog.APPS) == null) return;
                List<Item> before = Catalog.visible(cfg, Catalog.APPS, apps.all, recent);
                recent = times;
                if (!before.equals(Catalog.visible(cfg, Catalog.APPS, apps.all, recent))) render(apps);
            });
        });
    }

    private void render(Row row) {
        if (moving != null && moving.kind.equals(row.kind)) stopMoving();
        View focused = getCurrentFocus();
        String keep = focused instanceof Tile && ((Tile) focused).kind.equals(row.kind)
                ? ((Tile) focused).item.id : null;
        boolean gridMode = "grid".equals(cfg.get(Config.LAYOUT));
        ViewGroup target = gridMode ? row.grid : row.strip;
        row.strip.removeAllViews();
        row.grid.removeAllViews();

        boolean enabled = !Catalog.INPUTS.equals(row.kind) || "on".equals(cfg.get(Config.INPUTS));
        List<Item> shown = enabled ? Catalog.visible(cfg, row.kind,
                row == allApps ? apps.all : row.all, recentFor(row.kind))
                : new ArrayList<>();
        int[] size = cardSize();
        int w = size[0], h = Catalog.isApp(row.kind) ? size[1] : Math.round(size[1] * 0.56f);
        String labels = cfg.get(Config.LABELS);
        int accent = cfg.accent();
        int index = 0, restore = -1;
        for (Item it : shown) {
            Tile t = new Tile(this, it, row.kind, Catalog.label(cfg, it), w, h, labels, accent);
            t.setOnClickListener(v -> open((Tile) v));
            t.setOnFocusChangeListener((v, has) -> {
                if (has) {
                    Tile tile = (Tile) v;
                    lastFocusedKind = tile.kind;
                    lastIds.put(tile.kind, tile.item.id);
                    focusBackground(tile.item);
                }
            });
            t.setOnLongClickListener(v -> {
                if (!locked()) menu((Tile) v);
                return true;
            });
            if (gridMode) {
                target.addView(t, new ViewGroup.LayoutParams(WRAP, WRAP));
            } else {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, WRAP);
                lp.rightMargin = dp(18);
                target.addView(t, lp);
            }
            if (it.id.equals(keep)) restore = index;
            index++;
            if (Catalog.isApp(row.kind) && (!it.artRequested
                    || "afterglow".equals(cfg.get(Config.TILE_STYLE)) && it.icon == null)) loadArt(it);
        }
        row.setVisible(!shown.isEmpty());
        adjustAppRowPosition();
        if (shown.isEmpty()) {
            // Nothing is left to come back to: forget the tile that used to be here.
            lastIds.remove(row.kind);
        }
        if (restore >= 0) {
            target.getChildAt(restore).requestFocus();
        } else if (keep != null && target.getChildCount() > 0) {
            target.getChildAt(0).requestFocus();
        } else if (getCurrentFocus() == null || !landed) {
            focusFirst();
        }
    }

    private boolean isInputsHidden() {
        if (inputs == null || !"on".equals(cfg.get(Config.INPUTS))) return true;
        return inputs.title.getVisibility() != View.VISIBLE;
    }

    private int appRowShift() {
        int titleH = apps.title.getHeight();
        if (titleH <= 0) {
            apps.title.measure(
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            titleH = apps.title.getMeasuredHeight();
        }
        if (titleH <= 0) titleH = dp(38);
        int scrollH = cardSize()[1] + dp(46);
        int desired = Math.round((titleH + scrollH) * 0.70f);

        int screenH = getResources().getDisplayMetrics().heightPixels;
        int headH = head != null && head.getHeight() > 0 ? head.getHeight() : 0;
        if (headH <= 0 && head != null) {
            head.measure(
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            headH = head.getMeasuredHeight();
        }
        if (headH <= 0) headH = dp(96);
        int nowH = 0;
        if (nowBlock != null && nowBlock.getVisibility() == View.VISIBLE) {
            nowH = nowBlock.getHeight();
            if (nowH <= 0) nowH = dp(128);
        }
        int maxShift = Math.max(0, screenH - dp(40) - headH - nowH - titleH - scrollH - dp(32));
        return Math.min(desired, maxShift);
    }

    private void adjustAppRowPosition() {
        if (apps == null || apps.title == null) return;
        ViewGroup.LayoutParams params = apps.title.getLayoutParams();
        if (!(params instanceof LinearLayout.LayoutParams)) return;
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) params;
        // The library banner takes that space when it shows, and so does All apps.
        int targetMargin = (!"grid".equals(cfg.get(Config.LAYOUT)) && isInputsHidden()
                && !heroShown() && allApps.title.getVisibility() != View.VISIBLE) ? appRowShift() : 0;
        if (lp.topMargin != targetMargin) {
            lp.topMargin = targetMargin;
            apps.title.setLayoutParams(lp);
        }
    }

    private void loadArt(Item it) {
        it.artRequested = true;
        Runnable job = artJob(it, "afterglow".equals(cfg.get(Config.TILE_STYLE)));
        io.execute(() -> {
            job.run();
            ui.post(() -> {
                for (Row row : new Row[] {apps, allApps}) {
                    ViewGroup tiles = row.tiles();
                    for (int i = 0; i < tiles.getChildCount(); i++) tiles.getChildAt(i).invalidate();
                }
                if (focusedItem() == it) focusBackground(it);
            });
        });
    }

    private Item focusedItem() {
        View focused = getCurrentFocus();
        return focused instanceof Tile ? ((Tile) focused).item : null;
    }

    private void focusBackground(Item it) {
        String mode = cfg.get(Config.BACKGROUND);
        if (!resumed || !"adaptive".equals(mode) && !"adaptive-tint".equals(mode)) return;
        if ("adaptive-tint".equals(mode) && !tintWallpaper) return;
        if (it != null && it.info != null && it.backgroundColor == 0 && !it.colorRequested
                && (it.banner != null || it.icon != null)) {
            it.colorRequested = true;
            colorIo.execute(() -> {
                int color = FocusColor.from(it.banner != null ? it.banner : it.icon);
                ui.post(() -> {
                    it.backgroundColor = color;
                    if (focusedItem() == it) focusBackground(it);
                });
            });
        }
        int color = it == null || it.backgroundColor == 0 ? FocusColor.FALLBACK : it.backgroundColor;
        if ("adaptive-tint".equals(mode)) color = FocusColor.tint(color);
        if (color == targetColor) return;
        targetColor = color;
        stopColorAnimation();
        if (Settings.Global.getFloat(getContentResolver(), "animator_duration_scale", 1f) == 0f) {
            setAdaptiveColor(color);
            return;
        }
        colorAnimation = ValueAnimator.ofArgb(displayedColor, color);
        colorAnimation.setDuration(250);
        colorAnimation.addUpdateListener(a -> setAdaptiveColor((Integer) a.getAnimatedValue()));
        colorAnimation.start();
    }

    private void setAdaptiveColor(int color) {
        displayedColor = color;
        if ("adaptive-tint".equals(backgroundMode)) tint.setBackgroundColor(color);
        else root.setBackgroundColor(color);
    }

    private void stopColorAnimation() {
        if (colorAnimation != null) {
            colorAnimation.cancel();
            colorAnimation = null;
        }
    }

    /**
     * Tile size that packs as many columns as the named size would, then grows each tile so
     * the row fills the screen between the side paddings.
     */
    private int[] cardSize() {
        int prefer;
        switch (cfg.get(Config.TILES)) {
            case "small":
                prefer = dp(144);
                break;
            case "large":
                prefer = dp(224);
                break;
            default:
                prefer = dp(180);
                break;
        }
        int gap = dp(18);
        int avail = getResources().getDisplayMetrics().widthPixels - 2 * dp(56);
        int cols = Math.max(1, (avail + gap) / (prefer + gap));
        int w = Math.max(1, (avail - (cols - 1) * gap) / cols);
        return new int[] {w, Math.round(w * 9 / 16f)};
    }

    /**
     * The tile of {@code kind} that was focused last, or null when there is none left.
     */
    private View rememberedTile(String kind) {
        if (kind == null) return null;
        String id = lastIds.get(kind);
        if (id == null) return null;
        View v = findTile(kind, id);
        if (v == null) {
            // The tile is gone: the row was rebuilt without it.
            lastIds.remove(kind);
        }
        return v;
    }

    /** The tile with this id in the row of {@code kind}, or null when there is none. */
    private View findTile(String kind, String id) {
        if (kind == null || id == null) return null;
        Row row = row(kind);
        if (row == null || row.title.getVisibility() != View.VISIBLE) return null;
        ViewGroup tiles = row.tiles();
        for (int i = 0; i < tiles.getChildCount(); i++) {
            View v = tiles.getChildAt(i);
            if (v instanceof Tile
                    && ((Tile) v).kind.equals(kind)
                    && ((Tile) v).item.id.equals(id)) {
                return v;
            }
        }
        return null;
    }

    private Row[] rows() {
        return new Row[] {apps, allApps, inputs};
    }

    private Row row(String kind) {
        for (Row row : rows()) if (row.kind.equals(kind)) return row;
        return null;
    }

    /** The next nonempty section in this direction, skipping hidden sections. */
    private View adjacentTile(String kind, int step, View from) {
        Row[] sections = rows();
        for (int i = 0; i < sections.length; i++) {
            if (!sections[i].kind.equals(kind)) continue;
            for (int j = i + step; j >= 0 && j < sections.length; j += step) {
                Row next = sections[j];
                if (next.title.getVisibility() != View.VISIBLE) continue;
                View remembered = rememberedTile(next.kind);
                if (remembered != null) return remembered;
                View nearest = nearest(next.tiles(), from);
                if (nearest != null) return nearest;
            }
            break;
        }
        return null;
    }

    private void focusFirst() {
        View first = null;
        for (Row row : rows()) {
            row.scroll.scrollTo(0, 0);
            if (first == null) first = row.tiles().getChildAt(0);
        }
        landed = apps.tiles().getChildCount() > 0 || allApps.tiles().getChildCount() > 0;
        if (first == null) first = chips.get(0);
        first.requestFocus();
    }

    private void tick() {
        String mode = cfg.get(Config.CLOCK);
        boolean h24 = "24h".equals(mode) || "auto".equals(mode) && DateFormat.is24HourFormat(this);
        Date now = new Date();
        if (h24) {
            clock.setText(DateFormat.format("HH:mm", now));
        } else {
            SpannableString half = new SpannableString(" " + DateFormat.format("a", now));
            half.setSpan(new RelativeSizeSpan(0.36f), 0, half.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            clock.setText(DateFormat.format("h:mm", now));
            clock.append(half);
        }
        Locale locale = Locale.getDefault();
        date.setText(DateFormat.format(DateFormat.getBestDateTimePattern(locale, "EEEEdMMMM"), now));
        if (meter.getVisibility() == View.VISIBLE) {
            meter.update((android.app.ActivityManager) getSystemService(Context.ACTIVITY_SERVICE));
        }
        // hourly and time-of-day rotations change while the screen stays open; a no-op otherwise
        background();
        bindNow();
    }

    private void background() {
        String mode = cfg.get(Config.BACKGROUND);
        boolean image = "image".equals(mode) || "adaptive-tint".equals(mode);
        File file = image ? cfg.wallpaper() : null;
        tintWallpaper = "adaptive-tint".equals(mode) && file != null;
        if ("adaptive".equals(mode)) {
            if (!mode.equals(backgroundMode)) {
                stopColorAnimation();
                targetColor = 0;
                root.setBackgroundColor(FocusColor.FALLBACK);
                displayedColor = FocusColor.FALLBACK;
                tint.setBackgroundColor(Color.TRANSPARENT);
            }
            backgroundMode = mode;
            focusBackground(focusedItem());
        } else if ("adaptive-tint".equals(mode)) {
            root.setBackgroundColor(file == null ? cfg.background() : Color.BLACK);
            if (!mode.equals(backgroundMode) || file == null) {
                stopColorAnimation();
                targetColor = 0;
                tint.setBackgroundColor(Color.TRANSPARENT);
                displayedColor = Color.TRANSPARENT;
            }
            backgroundMode = mode;
            if (tintWallpaper) focusBackground(focusedItem());
        } else {
            stopColorAnimation();
            targetColor = 0;
            backgroundMode = mode;
            tint.setBackgroundColor(Color.TRANSPARENT);
            root.setBackgroundColor(cfg.background());
        }
        int dim = Integer.parseInt(cfg.get(Config.DIM));
        shade.setBackgroundColor(file == null ? Color.TRANSPARENT : Color.argb(dim * 255 / 100, 0, 0, 0));
        String key = file == null ? null : file.getPath() + ":" + file.lastModified();
        if (key == null ? wallKey == null : key.equals(wallKey)) return;
        wallKey = key;
        if (file == null) {
            wall.setImageDrawable(null);
            return;
        }
        io.execute(() -> {
            Bitmap b = decode(file, getResources().getDisplayMetrics().widthPixels,
                    getResources().getDisplayMetrics().heightPixels);
            ui.post(() -> {
                if (key.equals(wallKey)) wall.setImageBitmap(b);
            });
        });
    }

    private static Bitmap decode(File f, int w, int h) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        o.inSampleSize = 1;
        while (o.outWidth / (o.inSampleSize * 2) >= w && o.outHeight / (o.inSampleSize * 2) >= h) {
            o.inSampleSize *= 2;
        }
        o.inJustDecodeBounds = false;
        return BitmapFactory.decodeFile(f.getPath(), o);
    }

    /**
     * On Fire OS 8 the Appstore's launcher entry opens Amazon's home with its apps grid, which
     * Guard then replaces with ours, so the tile seemed to do nothing (#133). Its store screen is
     * opened instead. On Fire OS 7 the entry opens the store itself.
     */
    private Intent appIntent(Item it) {
        if (Build.VERSION.SDK_INT >= 30 && APPSTORE.equals(it.id)) {
            Intent store = new Intent(Intent.ACTION_MAIN)
                    .setClassName(APPSTORE, APPSTORE + ".ade.ADEHomeActivity");
            if (store.resolveActivity(getPackageManager()) != null) return store;
        }
        return new Intent(it.intent);
    }

    private void open(Tile t) {
        if (Catalog.isApp(t.kind)) {
            launch(appIntent(t.item), t.item.id);
            return;
        }
        Intent own = Input.intent(this, cfg, t.item, Catalog.label(cfg, t.item));
        // The built-in screen goes on top of this one in the same task, so BACK returns here and
        // the HOME takeover's singleTask relaunch clears it. NEW_TASK | RESET_TASK_IF_NEEDED on an
        // activity of the task that is already in front only brings that task forward and starts
        // nothing (ActivityStarter.complyActivityFlags), so it is started plainly.
        if (own != null) startActivity(own);
        else launch(new Intent(t.item.intent), null);
    }

    private void launch(Intent i, String pkg) {
        if (Guard.AMAZON.equals(pkg)) Guard.pause();
        try {
            // No transition: on a warm start of an app with a trampoline activity, such as
            // Prime Video, the animation showed home again between the two (#169).
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
                    ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle());
            if (pkg != null) Recent.opened(this, pkg);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.could_not_open, Toast.LENGTH_SHORT).show();
        }
    }

    private void menu(Tile t) {
        if (locked()) return;
        if (Catalog.ALL_APPS.equals(t.kind)) {
            boolean pinned = Catalog.shown(cfg, Catalog.APPS, t.item);
            new AlertDialog.Builder(this, Ui.DIALOG)
                    .setTitle(Catalog.label(cfg, t.item))
                    .setItems(new String[] {getString(pinned ? R.string.tile_unpin : R.string.tile_pin),
                            getString(R.string.tile_rename), getString(R.string.tile_app_info)}, (d, which) -> {
                        if (which == 0) {
                            Catalog.setShown(cfg, Catalog.APPS, t.item, !pinned);
                            render(apps);
                            render(allApps);
                        } else if (which == 1) {
                            rename(t);
                        } else {
                            appInfo(t);
                        }
                    })
                    .show();
            return;
        }
        boolean app = Catalog.APPS.equals(t.kind);
        boolean pinning = app && "on".equals(cfg.get(Config.ALL_APPS));
        // Icon picks from the chosen pack, so it is only offered while that pack is installed.
        boolean icon = app && iconPacks.containsKey(cfg.get(IconPack.KEY, IconPack.OFF));
        List<Integer> menu = new ArrayList<>();
        menu.add(R.string.tile_move);
        menu.add(R.string.tile_rename);
        if (icon) menu.add(R.string.tile_icon);
        menu.add(pinning ? R.string.tile_unpin : R.string.tile_hide);
        if (app) menu.add(R.string.tile_app_info);
        String[] actions = new String[menu.size()];
        for (int i = 0; i < actions.length; i++) actions[i] = getString(menu.get(i));
        new AlertDialog.Builder(this, Ui.DIALOG)
                .setTitle(Catalog.label(cfg, t.item))
                .setItems(actions, (d, which) -> {
                    switch (menu.get(which)) {
                        case R.string.tile_move:
                            if (t.getParent() == null) break;
                            if (app && recentFor(t.kind) != null) {
                                Toast.makeText(this, R.string.move_needs_order, Toast.LENGTH_LONG).show();
                                break;
                            }
                            moving = t;
                            t.setMoving(true);
                            break;
                        case R.string.tile_rename:
                            rename(t);
                            break;
                        case R.string.tile_icon:
                            startActivity(new Intent(this, IconPicker.class)
                                    .putExtra(IconPicker.APP, t.item.id)
                                    .putExtra(IconPicker.LABEL, Catalog.label(cfg, t.item).toString()));
                            break;
                        case R.string.tile_hide:
                        case R.string.tile_unpin:
                            Catalog.setShown(cfg, t.kind, t.item, false);
                            render(app ? apps : inputs);
                            Toast.makeText(this, pinning ? R.string.unpinned_toast : R.string.hidden_toast,
                                    Toast.LENGTH_SHORT).show();
                            break;
                        default:
                            appInfo(t);
                    }
                })
                .show();
    }

    private void appInfo(Tile t) {
        launch(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", t.item.id, null)), null);
    }

    private void rename(Tile t) {
        EditText field = new EditText(this);
        field.setSingleLine();
        field.setText(Catalog.label(cfg, t.item));
        field.setSelectAllOnFocus(true);
        FrameLayout box = new FrameLayout(this);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        box.addView(field);
        String key = "name." + t.item.id;
        AlertDialog d = new AlertDialog.Builder(this, Ui.DIALOG)
                .setTitle(R.string.rename_title)
                .setView(box)
                .setPositiveButton(R.string.save, (dialog, w) -> {
                    String name = field.getText().toString().trim();
                    cfg.set(key, name.equals(t.item.label.toString()) ? null : name);
                    apply();
                })
                .setNeutralButton(R.string.original, (dialog, w) -> {
                    cfg.set(key, null);
                    apply();
                })
                .setNegativeButton(R.string.cancel, null)
                .create();
        d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        d.show();
    }

    private void shift(int by) {
        ViewGroup strip = (ViewGroup) moving.getParent();
        int from = strip.indexOfChild(moving), to = from + by;
        if (to < 0 || to >= strip.getChildCount()) return;
        ViewGroup.LayoutParams lp = moving.getLayoutParams();
        strip.removeViewAt(from);
        strip.addView(moving, to, lp);
        moving.requestFocus();
    }

    private void stopMoving() {
        Tile t = moving;
        moving = null;
        t.setMoving(false);
        ViewGroup strip = (ViewGroup) t.getParent();
        if (strip == null) return;
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < strip.getChildCount(); i++) ids.add(((Tile) strip.getChildAt(i)).item.id);
        Catalog.saveOrder(cfg, t.kind, ids);
        applied = cfg.version();
    }

    private int dp(float v) {
        return Ui.dp(this, v);
    }
}
