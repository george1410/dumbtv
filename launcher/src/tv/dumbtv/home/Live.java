package tv.dumbtv.home;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** A now-and-next guide for the channel apps that are installed. OK opens the channel. */
public final class Live extends Activity {
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final long TICK = 30 * 1000L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<RowViews> rows = new ArrayList<>();
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            bindAll();
            main.postDelayed(this, TICK);
        }
    };
    private DateFormat time;
    private TextView status;
    private Config cfg;
    private Epg epg = Epg.EMPTY;
    private boolean loading, failed;
    private volatile boolean stopped;

    private static final class RowViews {
        final Channels.Channel channel;
        TextView name, now, sub, when, nextWhen, nextTitle, nextSub;
        View done, left;

        RowViews(Channels.Channel channel) {
            this.channel = channel;
        }
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        cfg = Config.get(this);
        time = android.text.format.DateFormat.getTimeFormat(this);
        int accent = cfg.accent();

        ScrollView scroll = new ScrollView(this);
        scroll.setFocusable(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setBackgroundColor(0xFF0C0C0E);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(56), dp(40), dp(56), dp(40));
        scroll.addView(list);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.BOTTOM);
        head.setPadding(dp(16), 0, dp(16), dp(16));
        TextView title = Ui.text(this, 30, Ui.TEXT, Ui.LIGHT);
        title.setText("Live TV");
        status = Ui.text(this, 13, Ui.FAINT, Ui.MEDIUM);
        head.addView(title, new LinearLayout.LayoutParams(0, WRAP, 1f));
        head.addView(status);
        list.addView(head);

        View first = null;
        for (Channels.Channel ch : Channels.installed(this)) {
            View row = row(ch, accent);
            if (first == null) first = row;
            list.addView(row);
        }
        if (first == null) {
            TextView none = Ui.text(this, 16, Ui.MUTED, Ui.MEDIUM);
            none.setText("None of the channel apps are installed: BBC iPlayer, ITVX, Channel 4, My5 or NOW.");
            none.setPadding(dp(16), dp(24), 0, 0);
            list.addView(none);
        } else {
            first.requestFocus();
        }
        setContentView(scroll);
    }

    @Override
    protected void onStart() {
        super.onStart();
        stopped = false;
        main.post(tick);
        load();
    }

    @Override
    protected void onStop() {
        super.onStop();
        stopped = true;
        main.removeCallbacks(tick);
    }

    private void load() {
        if (loading) return;
        loading = true;
        failed = false;
        bindStatus();
        final Epg current = epg;
        new Thread(() -> {
            Epg got = current != Epg.EMPTY ? current : Epg.cached(this);
            boolean fresh = !got.stale(System.currentTimeMillis());
            deliver(got, false, fresh);
            if (fresh) return;
            try {
                deliver(Epg.fetch(this), false, true);
            } catch (Exception e) {
                deliver(null, true, true);
            }
        }, "epg").start();
    }

    private void deliver(Epg got, boolean error, boolean done) {
        main.post(() -> {
            if (got != null) epg = got;
            if (done) loading = false;
            failed = error;
            if (!stopped) bindAll();
        });
    }

    private View row(Channels.Channel ch, int accent) {
        RowViews v = new RowViews(ch);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(12), dp(16), dp(12));
        row.setFocusable(true);
        row.setClickable(true);
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[] {android.R.attr.state_focused}, Ui.round((accent & 0xFFFFFF) | 0x4D000000, dp(14)));
        bg.addState(new int[0], new ColorDrawable(0));
        row.setBackground(bg);
        row.setTag(ch);
        row.setOnClickListener(x -> open(ch));
        row.setOnFocusChangeListener((view, has) -> marquee(v, has));

        TextView number = Ui.text(this, 18, Ui.MUTED, Ui.LIGHT);
        number.setText(String.valueOf(ch.number));
        row.addView(number, new LinearLayout.LayoutParams(dp(48), WRAP));

        LinearLayout name = column();
        v.name = single(Ui.text(this, 18, Ui.TEXT, Ui.MEDIUM));
        v.name.setText(ch.name);
        TextView via = Ui.text(this, 12, Ui.FAINT, Ui.MEDIUM);
        via.setText(ch.opensLive() ? app(ch.app) : "Opens " + app(ch.app));
        via.setPadding(0, dp(5), 0, 0);
        name.addView(v.name, new LinearLayout.LayoutParams(MATCH, WRAP));
        name.addView(via);
        row.addView(name, new LinearLayout.LayoutParams(dp(210), WRAP));

        LinearLayout now = column();
        v.now = single(Ui.text(this, 18, Ui.TEXT, Ui.LIGHT));
        v.sub = single(Ui.text(this, 13, Ui.MUTED, Ui.LIGHT));
        v.sub.setPadding(0, dp(4), 0, 0);
        v.when = Ui.text(this, 12, Ui.MUTED, Ui.MEDIUM);
        v.when.setPadding(0, dp(5), 0, dp(6));
        LinearLayout bar = new LinearLayout(this);
        bar.setBackground(Ui.round(0x26FFFFFF, dp(2)));
        v.done = new View(this);
        v.done.setBackground(Ui.round(accent, dp(2)));
        v.left = new View(this);
        bar.addView(v.done, new LinearLayout.LayoutParams(0, MATCH, 0f));
        bar.addView(v.left, new LinearLayout.LayoutParams(0, MATCH, 1f));
        now.addView(v.now, new LinearLayout.LayoutParams(MATCH, WRAP));
        now.addView(v.sub, new LinearLayout.LayoutParams(MATCH, WRAP));
        now.addView(v.when);
        now.addView(bar, new LinearLayout.LayoutParams(MATCH, dp(3)));
        LinearLayout.LayoutParams nowLp = new LinearLayout.LayoutParams(0, WRAP, 1.4f);
        nowLp.rightMargin = dp(20);
        row.addView(now, nowLp);

        LinearLayout next = column();
        v.nextWhen = Ui.text(this, 12, Ui.FAINT, Ui.MEDIUM);
        v.nextTitle = single(Ui.text(this, 15, Ui.MUTED, Ui.MEDIUM));
        v.nextTitle.setPadding(0, dp(5), 0, 0);
        v.nextSub = single(Ui.text(this, 12, Ui.FAINT, Ui.LIGHT));
        v.nextSub.setPadding(0, dp(3), 0, 0);
        next.addView(v.nextWhen);
        next.addView(v.nextTitle, new LinearLayout.LayoutParams(MATCH, WRAP));
        next.addView(v.nextSub, new LinearLayout.LayoutParams(MATCH, WRAP));
        row.addView(next, new LinearLayout.LayoutParams(dp(176), WRAP));

        rows.add(v);
        bind(v, System.currentTimeMillis());
        return row;
    }

    private void bindAll() {
        long now = System.currentTimeMillis();
        for (RowViews v : rows) bind(v, now);
        bindStatus();
    }

    private void bindStatus() {
        if (status == null) return;
        if (loading && epg == Epg.EMPTY) {
            status.setText("Loading the guide…");
            status.setVisibility(View.VISIBLE);
        } else if (failed && epg == Epg.EMPTY) {
            status.setText("Couldn't load the guide");
            status.setVisibility(View.VISIBLE);
        } else {
            status.setText("");
            status.setVisibility(View.GONE);
        }
    }

    private void bind(RowViews v, long now) {
        List<Epg.Programme> l = epg.listings(v.channel);
        Epg.Programme on = null, after = null;
        for (int i = 0; i < l.size(); i++) {
            Epg.Programme p = l.get(i);
            if (p.stop <= now) continue;
            if (p.start <= now) {
                on = p;
                if (i + 1 < l.size()) after = l.get(i + 1);
            } else {
                after = p;
            }
            break;
        }
        if (on == null) {
            set(v.now, epg == Epg.EMPTY ? "" : "No listings");
            v.now.setTextColor(Ui.FAINT);
            showSub(v.sub, "");
            set(v.when, "");
            ((View) v.done.getParent()).setVisibility(View.INVISIBLE);
        } else {
            set(v.now, on.title);
            v.now.setTextColor(Ui.TEXT);
            showSub(v.sub, on.subtitle);
            long mins = Math.max(1, (on.stop - now + 59999) / 60000);
            String left = mins < 60 ? mins + " min" : mins / 60 + " h" + (mins % 60 == 0 ? "" : " " + mins % 60 + " min");
            set(v.when, span(on) + "  ·  " + left + " left");
            float frac = (now - on.start) / (float) Math.max(1, on.stop - on.start);
            ((LinearLayout.LayoutParams) v.done.getLayoutParams()).weight = frac;
            ((LinearLayout.LayoutParams) v.left.getLayoutParams()).weight = 1f - frac;
            v.done.requestLayout();
            ((View) v.done.getParent()).setVisibility(View.VISIBLE);
        }
        set(v.nextWhen, after == null ? "" : "Next  " + time.format(new Date(after.start)));
        set(v.nextTitle, after == null ? "" : after.title);
        showSub(v.nextSub, after == null ? "" : after.subtitle);
    }

    /** Marquee only the focused row, so a screen of six titles is not all crawling. */
    private static void marquee(RowViews v, boolean on) {
        crawl(v.name, on);
        crawl(v.now, on);
        crawl(v.sub, on);
        crawl(v.nextTitle, on);
        crawl(v.nextSub, on);
    }

    private static void crawl(TextView t, boolean on) {
        t.setEllipsize(on ? TextUtils.TruncateAt.MARQUEE : TextUtils.TruncateAt.END);
        t.setMarqueeRepeatLimit(on ? -1 : 0);
        t.setHorizontallyScrolling(on);
        t.setSelected(on);
    }

    private static void set(TextView v, CharSequence s) {
        if (!TextUtils.equals(v.getText(), s)) v.setText(s);
    }

    private static void showSub(TextView v, String s) {
        boolean has = !TextUtils.isEmpty(s);
        set(v, has ? s : "");
        v.setVisibility(has ? View.VISIBLE : View.GONE);
    }

    private String span(Epg.Programme p) {
        return time.format(new Date(p.start)) + " – " + time.format(new Date(p.stop));
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_MENU) {
            View focused = getCurrentFocus();
            if (focused != null && focused.getTag() instanceof Channels.Channel) {
                for (RowViews v : rows) {
                    if (v.channel == focused.getTag()) {
                        menu(v);
                        return true;
                    }
                }
            }
        }
        return super.onKeyDown(code, e);
    }

    private void menu(RowViews v) {
        boolean on = Channels.favourite(cfg, v.channel);
        String action = on ? "Remove from On Now" : "Add to On Now";
        new AlertDialog.Builder(this, Ui.DIALOG)
                .setTitle(v.channel.name)
                .setItems(new String[] {action}, (d, which) -> {
                    Channels.toggleFavourite(cfg, v.channel);
                    Toast.makeText(this, Channels.favourite(cfg, v.channel)
                            ? "Added to On Now" : "Removed from On Now", Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private void open(Channels.Channel ch) {
        Intent i = ch.intent(this);
        try {
            if (i == null) throw new ActivityNotFoundException();
            startActivity(i);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, "Couldn't open " + app(ch.app), Toast.LENGTH_SHORT).show();
        }
    }

    private static String app(String pkg) {
        switch (pkg) {
            case Channels.BBC: return "BBC iPlayer";
            case Channels.ITV: return "ITVX";
            case Channels.C4: return "Channel 4";
            case Channels.MY5: return "My5";
            case Channels.NOW: return "NOW";
            default: return pkg;
        }
    }

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private static TextView single(TextView t) {
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    private int dp(float v) {
        return Ui.dp(this, v);
    }
}
