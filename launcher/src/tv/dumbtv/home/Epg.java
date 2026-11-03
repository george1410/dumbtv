package tv.dumbtv.home;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Now-and-next listings from EPGTalk's free UK feed: about eleven hours of programmes for
 * every channel, refreshed by them every few hours. The feed lists some channels more than
 * once under different names, so the copy with the most programmes wins.
 */
final class Epg {
    static final class Programme {
        final long start, stop;
        final String title, subtitle;

        Programme(long start, long stop, String title, String subtitle) {
            this.start = start;
            this.stop = stop;
            this.title = title;
            this.subtitle = subtitle;
        }
    }

    private static final String URL_UK = "https://epgtalk.com/api/now/uk.json";
    private static final String FILE = "epg-uk.json";
    private static final Pattern TAGS = Pattern.compile("\\[[^\\[\\]]*\\]");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final Pattern EDGE = Pattern.compile("^[\\s:,·-]+|[\\s:,·-]+$");
    private static final long MAX_AGE = 90 * 60 * 1000L;
    /** Refetch early when less than this much of the feed is still ahead. */
    private static final long MIN_AHEAD = 3 * 60 * 60 * 1000L;

    /** Programmes by normalised channel name, sorted by start time. */
    final Map<String, List<Programme>> byName;
    /** When the data was fetched, in milliseconds. */
    final long fetched;
    private final long until;

    private Epg(Map<String, List<Programme>> byName, long fetched, long until) {
        this.byName = byName;
        this.fetched = fetched;
        this.until = until;
    }

    static final Epg EMPTY = new Epg(Collections.<String, List<Programme>>emptyMap(), 0, 0);

    /** The cached copy, or {@link #EMPTY}. Reads a file, so keep it off the main thread. */
    static Epg cached(Context c) {
        File f = new File(c.getFilesDir(), FILE);
        if (!f.isFile()) return EMPTY;
        try (InputStream in = new FileInputStream(f)) {
            return parse(new String(read(in), StandardCharsets.UTF_8), f.lastModified());
        } catch (IOException | JSONException e) {
            return EMPTY;
        }
    }

    boolean stale(long now) {
        return now - fetched > MAX_AGE || until - now < MIN_AHEAD;
    }

    /** Downloads a fresh copy and caches it. Network: never on the main thread. */
    static Epg fetch(Context c) throws IOException, JSONException {
        HttpURLConnection conn = (HttpURLConnection) new URL(URL_UK).openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(20000);
        conn.setRequestProperty("User-Agent", "dumbtv-home");
        try {
            if (conn.getResponseCode() != 200) throw new IOException("HTTP " + conn.getResponseCode());
            byte[] body;
            try (InputStream in = conn.getInputStream()) {
                body = read(in);
            }
            long now = System.currentTimeMillis();
            Epg epg = parse(new String(body, StandardCharsets.UTF_8), now);
            File tmp = new File(c.getFilesDir(), FILE + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(body);
            }
            if (!tmp.renameTo(new File(c.getFilesDir(), FILE))) tmp.delete();
            return epg;
        } finally {
            conn.disconnect();
        }
    }

    private static Epg parse(String json, long fetched) throws JSONException {
        JSONObject root = new JSONObject(json);
        JSONArray channels = root.getJSONArray("channels");
        Map<String, List<Programme>> out = new HashMap<>();
        for (int i = 0; i < channels.length(); i++) {
            JSONObject ch = channels.getJSONObject(i);
            JSONArray progs = ch.optJSONArray("programmes");
            if (progs == null) continue;
            String name = norm(ch.optString("name"));
            List<Programme> have = out.get(name);
            if (have != null && have.size() >= progs.length()) continue;
            List<Programme> list = new ArrayList<>(progs.length());
            for (int j = 0; j < progs.length(); j++) {
                JSONObject p = progs.getJSONObject(j);
                list.add(new Programme(p.getLong("start") * 1000L, p.getLong("stop") * 1000L,
                        plain(p.optString("title")), plain(p.optString("subtitle"))));
            }
            Collections.sort(list, (a, b) -> Long.compare(a.start, b.start));
            out.put(name, list);
        }
        return new Epg(out, fetched, root.optLong("until") * 1000L);
    }

    /**
     * Drops EPGTalk's sport stamps: emojis, [EPL]-style tags, and leftover punctuation.
     * Real titles such as {@code S.W.A.T.} are left alone.
     */
    static String plain(String s) {
        if (s == null) return "";
        s = s.trim();
        if (s.isEmpty()) return s;
        StringBuilder b = new StringBuilder(s.length());
        boolean junk = false;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (stamp(cp)) junk = true;
            else b.appendCodePoint(cp);
        }
        String t = TAGS.matcher(b).replaceAll("");
        if (t.length() != b.length()) junk = true;
        if (!junk) return s;
        t = SPACES.matcher(t).replaceAll(" ").trim();
        return EDGE.matcher(t).replaceAll("").trim();
    }

    private static boolean stamp(int cp) {
        if (cp == 0x200D || (cp >= 0xFE00 && cp <= 0xFE0F)) return true;
        if (cp >= 0x1F3FB && cp <= 0x1F3FF) return true;
        if (cp >= 0x1F1E6 && cp <= 0x1F1FF) return true;
        if (cp >= 0x20D0 && cp <= 0x20FF) return true;
        int type = Character.getType(cp);
        return type == Character.OTHER_SYMBOL
                || type == Character.ENCLOSING_MARK
                || type == Character.PRIVATE_USE;
    }

    /** "UK - BBC ONE HD" and "BBC One" both become "bbc one". */
    static String norm(String name) {
        String s = name.toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " ");
        s = s.replaceFirst("^uk ?- ?", "");
        s = s.replaceFirst(" hd$", "");
        return s.trim();
    }

    /** The best-covered listings for a channel, or an empty list. */
    List<Programme> listings(Channels.Channel ch) {
        List<Programme> best = Collections.emptyList();
        for (String name : ch.feed) {
            List<Programme> l = byName.get(name);
            if (l != null && l.size() > best.size()) best = l;
        }
        return best;
    }

    private static byte[] read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 18);
        byte[] buf = new byte[16384];
        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        return out.toByteArray();
    }
}
