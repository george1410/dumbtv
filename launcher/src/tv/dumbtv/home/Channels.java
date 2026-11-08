package tv.dumbtv.home;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The channels in the Live TV guide, and how each one is opened. None of these apps let
 * anything else read their channel list, so the links were taken from the apps and checked
 * on a Fire TV: a channel with no known link opens its app instead.
 */
final class Channels {
    static final String BBC = "uk.co.bbc.iplayer";
    static final String ITV = "air.ITVMobilePlayer";
    static final String C4 = "com.channel4.ondemand";
    static final String MY5 = "com.mobileiq.demand5";
    static final String NOW = "com.bskyb.nowtv.beta";

    static final class Channel {
        final String group;
        /** Freeview number, or the Sky number for channels on NOW. */
        final int number;
        final String name;
        final String app;
        /** Names the guide feed may use, already {@link Epg#norm normalised}. */
        final String[] feed;
        private final Intent live;

        Channel(String group, int number, String name, String app, Intent live, String... feed) {
            this.group = group;
            this.number = number;
            this.name = name;
            this.app = app;
            this.live = live;
            this.feed = feed;
        }

        /** The intent that opens the channel, or its app when no channel link is known. */
        Intent intent(Context c) {
            if (live != null) return new Intent(live);
            PackageManager pm = c.getPackageManager();
            Intent i = pm.getLeanbackLaunchIntentForPackage(app);
            return i != null ? i : pm.getLaunchIntentForPackage(app);
        }

        boolean opensLive() {
            return live != null;
        }
    }

    private Channels() {}

    /** Channels whose app is installed, in guide order. */
    static List<Channel> installed(Context c) {
        PackageManager pm = c.getPackageManager();
        List<Channel> out = new ArrayList<>();
        for (Channel ch : ALL) {
            try {
                pm.getApplicationInfo(ch.app, 0);
                out.add(ch);
            } catch (PackageManager.NameNotFoundException ignored) {
                // App not installed: leave the channel out.
            }
        }
        return out;
    }

    /** Channel names shown on On Now until the user picks their own in the guide. */
    static final List<String> DEFAULT_FAVOURITES = Collections.unmodifiableList(Arrays.asList(
            "BBC One", "ITV1", "Channel 4", "Channel 5",
            "Sky Sports F1", "Sky Sports Football", "Sky Sports Main Event"));

    static final String FAVOURITES = "live.favourites";

    /** Favourites whose app is installed, in the saved order (or {@link #DEFAULT_FAVOURITES}). */
    static List<Channel> favourites(Context c, Config cfg) {
        List<Channel> have = installed(c);
        List<Channel> out = new ArrayList<>();
        for (String want : favouriteNames(cfg)) {
            for (Channel ch : have) {
                if (ch.name.equals(want)) {
                    out.add(ch);
                    break;
                }
            }
        }
        return out;
    }

    static List<String> favouriteNames(Config cfg) {
        if (!cfg.defined(FAVOURITES)) return new ArrayList<>(DEFAULT_FAVOURITES);
        return cfg.list(FAVOURITES);
    }

    static boolean favourite(Config cfg, Channel ch) {
        return favouriteNames(cfg).contains(ch.name);
    }

    static void toggleFavourite(Config cfg, Channel ch) {
        List<String> names = favouriteNames(cfg);
        if (!names.remove(ch.name)) names.add(ch.name);
        saveFavourites(cfg, names);
    }

    static void saveFavourites(Config cfg, List<String> names) {
        cfg.setList(FAVOURITES, names);
    }

    private static Intent bbc(String service) {
        Uri uri = Uri.parse("https://www.live.bbctvapps.co.uk/tap/iplayer?deeplink=tv/playback/urn:bbc:iplayer:service:"
                + service + "/live&campaign=catalogue&medium=referral&origin=portal_live&partner=com.amazon");
        return new Intent(Intent.ACTION_VIEW, uri).setComponent(new ComponentName(BBC, "uk.co.bbc.MainActivity"));
    }

    private static Intent c4(String id) {
        return new Intent(Intent.ACTION_VIEW, Uri.parse("channel4://www.channel4.com/now/" + id)).setPackage(C4);
    }

    private static Intent my5(String id) {
        return new Intent().setComponent(new ComponentName(MY5,
                "com.channel5.my5.tv.ui.liveplayer.view.LivePlayerActivity")).putExtra("channel_id", id);
    }

    private static Intent itv(String slug) {
        return new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.itv.com/watch?channel=" + slug)).setPackage(ITV);
    }

    /**
     * NOW is a Peacock shell. It does not advertise a URL scheme, but an explicit VIEW
     * on AmazonMainActivity still delivers {@code deeplinkData}. serviceKey values are Sky's
     * HD sids; F1 (3835) was checked on a Fire Stick.
     */
    private static Intent now(String serviceKey) {
        String data = "{\"serviceKey\":\"" + serviceKey + "\",\"type\":\"LINEAR_CHANNEL\",\"action\":\"PLAY\"}";
        Uri uri = Uri.parse("https://tv.client.ott.sky.com/deeplink").buildUpon()
                .appendQueryParameter("deeplinkData", data).build();
        return new Intent(Intent.ACTION_VIEW, uri)
                .setComponent(new ComponentName(NOW, "com.peacock.peacocktv.AmazonMainActivity"));
    }

    private static Channel free(int number, String name, String app, Intent live, String... feed) {
        return new Channel("Freeview", number, name, app, live, feed);
    }

    private static Channel sky(int number, String name, String key, String... feed) {
        return new Channel("Sky on NOW", number, name, NOW, now(key), feed);
    }

    /**
     * In guide order: Freeview numbers (England, with the Scottish and Welsh numbers for
     * channels only those nations get), then Sky Glass/Stream numbers for the NOW channels.
     */
    static final List<Channel> ALL = Collections.unmodifiableList(Arrays.asList(
            free(1, "BBC One", BBC, bbc("bbc_one"), "bbc one"),
            free(2, "BBC Two", BBC, bbc("bbc_two"), "bbc two"),
            free(3, "ITV1", ITV, itv("itv"), "itv1", "itv 1"),
            free(4, "Channel 4", C4, c4("C4"), "channel 4"),
            free(4, "S4C", BBC, bbc("s4cpbs"), "s4c"),
            free(5, "Channel 5", MY5, my5("C5"), "5", "channel 5"),
            free(6, "ITV2", ITV, itv("itv2"), "itv 2", "itv2"),
            free(7, "BBC Alba", BBC, bbc("bbc_alba"), "bbc alba"),
            free(9, "BBC Four", BBC, bbc("bbc_four"), "bbc four"),
            free(9, "BBC Scotland", BBC, bbc("bbc_scotland"), "bbc scotland"),
            free(10, "ITV3", ITV, itv("itv3"), "itv 3", "itv3"),
            free(13, "E4", C4, c4("E4"), "e4"),
            free(14, "Film4", C4, c4("F4"), "film4"),
            free(18, "More4", C4, c4("M4"), "more4"),
            free(21, "5USA", MY5, null, "5 usa", "5usa"),
            free(23, "BBC Three", BBC, bbc("bbc_three"), "bbc three"),
            free(26, "ITV4", ITV, itv("itv4"), "itv4", "itv 4"),
            free(28, "ITV Quiz", ITV, itv("itvbe"), "itv quiz", "itvquiz", "itvbe"),
            free(32, "5Star", MY5, null, "5 star", "5star"),
            free(46, "5Action", MY5, null, "5 action", "5action"),
            free(49, "4seven", C4, c4("4S"), "4seven"),
            free(201, "CBBC", BBC, bbc("cbbc"), "cbbc"),
            free(202, "CBeebies", BBC, bbc("cbeebies"), "cbeebies"),
            free(231, "BBC News", BBC, bbc("bbc_news"), "bbc news"),
            free(232, "BBC Parliament", BBC, bbc("bbc_parliament"), "bbc parliament"),

            sky(107, "Sky Witness", "4066", "sky witness"),
            sky(108, "Sky Atlantic", "4053", "sky atlantic"),
            sky(109, "Sky Comedy", "1143", "sky comedy"),
            sky(110, "Sky Documentaries", "1136", "sky documentaries"),
            sky(111, "Sky Crime", "1212", "sky crime & thriller", "sky crime"),
            sky(112, "Sky Arts", "4063", "sky arts"),
            sky(113, "Sky Nature", "1165", "sky nature"),
            sky(114, "Sky Sci-Fi", "4074", "sky sci-fi"),
            sky(115, "Sky History", "4086", "sky history"),
            sky(158, "Sky Mix", "1831", "sky mix"),
            sky(203, "Sky Kids", "1147", "sky kids"),
            sky(301, "Sky Cinema Premiere", "4021", "sky cinema premiere"),
            sky(304, "Sky Cinema Family", "4018", "sky cinema family"),
            sky(306, "Sky Cinema Action", "4014", "sky cinema action"),
            sky(307, "Sky Cinema Greats", "4015", "sky cinema greats"),
            sky(308, "Sky Cinema Comedy", "4019", "sky cinema comedy"),
            sky(309, "Sky Cinema Thriller", "4062", "sky cinema thriller"),
            sky(310, "Sky Cinema Drama", "4016", "sky cinema drama"),
            sky(311, "Sky Cinema Sci-Fi & Horror", "4017", "sky cinema sci fi & horror", "sky cinema scifi & horror"),
            sky(401, "Sky Sports Main Event", "4002", "sky sports main event"),
            sky(402, "Sky Sports Premier League", "4010", "sky sports premier league"),
            sky(403, "Sky Sports Football", "3939", "sky sports football"),
            sky(404, "Sky Sports+", "3940", "sky sports+"),
            sky(405, "Sky Sports Cricket", "4081", "sky sports cricket"),
            sky(406, "Sky Sports Golf", "4026", "sky sports golf"),
            sky(407, "Sky Sports F1", "3835", "sky sports f1"),
            sky(408, "Sky Sports Tennis", "1284", "sky sports tennis"),
            sky(409, "Sky Sports News", "4049", "sky sports news", "sky sport news"),
            sky(410, "Sky Sports NFL", "4022", "sky sports nfl", "sky sports action"),
            sky(411, "Sky Sports Racing", "4032", "sky sports racing"),
            sky(412, "Sky Sports Mix", "4090", "sky sports mix"),
            sky(501, "Sky News", "4050", "sky news")));
}
