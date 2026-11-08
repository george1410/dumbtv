package tv.dumbtv.home;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Date;
import java.util.Locale;

/** Full-screen clock shown when the home screen has been sitting unused. */
final class IdleClock extends FrameLayout {
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final int FADE_MS = 800;
    private static final int DRIFT_MS = 8 * 60 * 1000;
    private static final int PULSE_MS = 1000;

    private final LinearLayout card;
    private final TextView hours;
    private final ViewGroup colon;
    private final TextView minutes;
    private final TextView half;
    private final TextView date;
    private final LinearLayout weatherRow;
    private final ImageView weatherIcon;
    private final TextView weatherView;
    private final Handler ticks = new Handler(Looper.getMainLooper());
    private final Runnable tock = this::tock;
    private ValueAnimator drift;
    private ObjectAnimator pulse;
    private boolean shown;
    private boolean h24 = true;

    IdleClock(Context c) {
        super(c);
        setFocusable(false);
        setClickable(false);
        setVisibility(GONE);
        setAlpha(0f);
        // Sit a touch above geometric centre; a block dead in the middle looks heavy.
        setPadding(0, 0, 0, Ui.dp(c, 56));

        card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);

        LinearLayout time = new LinearLayout(c);
        time.setOrientation(LinearLayout.HORIZONTAL);
        time.setGravity(Gravity.CENTER_VERTICAL);
        hours = numeral(c);
        colon = colon(c);
        minutes = numeral(c);
        half = Ui.text(c, 22, Ui.MUTED, Ui.LIGHT);
        half.setLetterSpacing(0.16f);
        half.setPadding(Ui.dp(c, 16), 0, 0, Ui.dp(c, 10));
        half.setVisibility(GONE);
        time.addView(hours);
        time.addView(colon);
        time.addView(minutes);
        time.addView(half);
        card.addView(time, new LinearLayout.LayoutParams(WRAP, WRAP));

        date = Ui.text(c, 16, Ui.MUTED, Ui.MEDIUM);
        date.setLetterSpacing(0.22f);
        date.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams dateLp = new LinearLayout.LayoutParams(WRAP, WRAP);
        dateLp.topMargin = Ui.dp(c, 28);
        card.addView(date, dateLp);

        weatherRow = new LinearLayout(c);
        weatherRow.setOrientation(LinearLayout.HORIZONTAL);
        weatherRow.setGravity(Gravity.CENTER_VERTICAL);
        weatherRow.setVisibility(GONE);
        LinearLayout.LayoutParams weatherLp = new LinearLayout.LayoutParams(WRAP, WRAP);
        weatherLp.topMargin = Ui.dp(c, 14);
        weatherIcon = new ImageView(c);
        weatherIcon.setImageTintList(android.content.res.ColorStateList.valueOf(Ui.FAINT));
        weatherIcon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(Ui.dp(c, 16), Ui.dp(c, 16));
        iconLp.rightMargin = Ui.dp(c, 8);
        weatherRow.addView(weatherIcon, iconLp);
        weatherView = Ui.text(c, 14, Ui.FAINT, Ui.MEDIUM);
        weatherView.setLetterSpacing(0.12f);
        weatherRow.addView(weatherView);
        card.addView(weatherRow, weatherLp);

        LayoutParams lp = new LayoutParams(WRAP, WRAP, Gravity.CENTER);
        addView(card, lp);
    }

    private static TextView numeral(Context c) {
        TextView v = Ui.text(c, 152, Ui.TEXT, Ui.THIN);
        v.setLetterSpacing(0.02f);
        v.setGravity(Gravity.CENTER);
        return v;
    }

    /** Two dots, optically in the middle of the numerals. A 152sp colon sits too far apart. */
    private static ViewGroup colon(Context c) {
        LinearLayout dots = new LinearLayout(c);
        dots.setOrientation(LinearLayout.VERTICAL);
        dots.setGravity(Gravity.CENTER);
        int size = Ui.dp(c, 8);
        int gap = Ui.dp(c, 22);
        dots.addView(dot(c, size), new LinearLayout.LayoutParams(size, size));
        View space = new View(c);
        dots.addView(space, new LinearLayout.LayoutParams(size, gap));
        dots.addView(dot(c, size), new LinearLayout.LayoutParams(size, size));
        dots.setPadding(Ui.dp(c, 22), 0, Ui.dp(c, 22), 0);
        return dots;
    }

    private static View dot(Context c, int size) {
        View v = new View(c);
        v.setBackground(Ui.round(0xFFF2F2F3, size / 2f));
        return v;
    }

    void format(boolean twentyFour) {
        h24 = twentyFour;
        half.setVisibility(h24 ? GONE : VISIBLE);
    }

    void bind(CharSequence day, CharSequence weather, Drawable icon) {
        date.setText(day == null ? "" : day.toString().toUpperCase(Locale.getDefault()));
        boolean has = weather != null && weather.length() > 0;
        weatherRow.setVisibility(has ? VISIBLE : GONE);
        if (has) {
            weatherView.setText(weather);
            Drawable copy = icon == null || icon.getConstantState() == null
                    ? icon
                    : icon.getConstantState().newDrawable(getResources()).mutate();
            weatherIcon.setImageDrawable(copy);
        }
        tock();
    }

    boolean shown() {
        return shown;
    }

    void appear() {
        if (shown) return;
        shown = true;
        tock();
        animate().cancel();
        setVisibility(VISIBLE);
        setAlpha(0f);
        animate().alpha(1f).setDuration(FADE_MS).start();
        startPulse();
        startDrift();
        ticks.removeCallbacks(tock);
        ticks.postDelayed(tock, PULSE_MS);
    }

    void dismiss() {
        if (!shown) return;
        shown = false;
        stopMotion();
        animate().cancel();
        animate().alpha(0f).setDuration(FADE_MS).withEndAction(() -> {
            if (!shown) setVisibility(GONE);
        }).start();
    }

    void hideNow() {
        shown = false;
        stopMotion();
        animate().cancel();
        setAlpha(0f);
        setVisibility(GONE);
        card.setTranslationX(0f);
        card.setTranslationY(0f);
    }

    private void tock() {
        Date now = new Date();
        hours.setText(DateFormat.format(h24 ? "HH" : "hh", now));
        minutes.setText(DateFormat.format("mm", now));
        if (!h24) half.setText(DateFormat.format("a", now));
        if (shown) {
            ticks.removeCallbacks(tock);
            ticks.postDelayed(tock, PULSE_MS);
        }
    }

    private void startPulse() {
        if (pulse != null) pulse.cancel();
        pulse = ObjectAnimator.ofFloat(colon, "alpha", 0.22f, 0.9f);
        pulse.setDuration(PULSE_MS);
        pulse.setRepeatCount(ValueAnimator.INFINITE);
        pulse.setRepeatMode(ValueAnimator.REVERSE);
        pulse.start();
    }

    private void startDrift() {
        if (drift != null) drift.cancel();
        final float ampX = Ui.dp(getContext(), 10);
        final float ampY = Ui.dp(getContext(), 6);
        drift = ValueAnimator.ofFloat(0f, 1f);
        drift.setDuration(DRIFT_MS);
        drift.setInterpolator(new LinearInterpolator());
        drift.setRepeatCount(ValueAnimator.INFINITE);
        drift.addUpdateListener(a -> {
            float[] at = Idle.drift((Float) a.getAnimatedValue(), ampX, ampY);
            card.setTranslationX(at[0]);
            card.setTranslationY(at[1]);
        });
        drift.start();
    }

    private void stopMotion() {
        ticks.removeCallbacks(tock);
        if (pulse != null) {
            pulse.cancel();
            pulse = null;
            colon.setAlpha(1f);
        }
        if (drift != null) {
            drift.cancel();
            drift = null;
        }
    }
}
