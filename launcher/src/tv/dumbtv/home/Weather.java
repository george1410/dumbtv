package tv.dumbtv.home;

import android.content.Context;

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
import java.util.Locale;

/**
 * Current conditions from Open-Meteo. Location is a one-shot IP lookup (geojs), then only
 * the forecast is refreshed. Nothing is sent except those two GETs; no account, no key.
 */
final class Weather {
    private static final String GEO_URL = "https://get.geojs.io/v1/ip/geo.json";
    private static final String FORECAST = "https://api.open-meteo.com/v1/forecast"
            + "?latitude=%s&longitude=%s&current=temperature_2m,weather_code&temperature_unit=%s";
    private static final String GEO_FILE = "weather-geo.json";
    private static final String FILE = "weather.json";
    private static final long MAX_AGE = 30 * 60 * 1000L;
    private static final long GEO_MAX_AGE = 7 * 24 * 60 * 60 * 1000L;

    static final int SUN = 1, PARTLY = 2, CLOUD = 3, FOG = 4, DRIZZLE = 5, RAIN = 6,
            SNOW = 7, SHOWERS = 8, THUNDER = 9;

    final String line;
    final long fetched;
    final int code;

    private Weather(String line, long fetched, int code) {
        this.line = line;
        this.fetched = fetched;
        this.code = code;
    }

    static final Weather EMPTY = new Weather("", 0, -1);

    boolean stale(long now) {
        return now - fetched > MAX_AGE;
    }

    /** The cached copy, or {@link #EMPTY}. Reads a file, so keep it off the main thread. */
    static Weather cached(Context c) {
        File f = new File(c.getFilesDir(), FILE);
        if (!f.isFile()) return EMPTY;
        try (InputStream in = new FileInputStream(f)) {
            return parse(new String(read(in), StandardCharsets.UTF_8), f.lastModified());
        } catch (IOException | JSONException e) {
            return EMPTY;
        }
    }

    /** Downloads a fresh copy and caches it. Network: never on the main thread. */
    static Weather fetch(Context c) throws IOException, JSONException {
        double[] geo = geo(c);
        boolean fahrenheit = "US".equals(Locale.getDefault().getCountry());
        String url = String.format(Locale.US, FORECAST,
                geo[0], geo[1], fahrenheit ? "fahrenheit" : "celsius");
        String body = get(url);
        long now = System.currentTimeMillis();
        Weather w = parse(body, now);
        write(new File(c.getFilesDir(), FILE), body);
        return w;
    }

    static Weather parse(String json, long fetched) throws JSONException {
        JSONObject cur = new JSONObject(json).getJSONObject("current");
        int temp = (int) Math.round(cur.getDouble("temperature_2m"));
        int code = cur.optInt("weather_code", -1);
        return new Weather(line(temp, code), fetched, code);
    }

    /** "11°  Cloudy". Unknown WMO codes drop the word and keep the temperature. */
    static String line(int temp, int code) {
        String w = condition(code);
        return w.isEmpty() ? temp + "°" : temp + "°  " + w;
    }

    /** Which glyph to draw; 0 if the code is unknown. */
    static int icon(int code) {
        if (code == 0) return SUN;
        if (code == 1 || code == 2) return PARTLY;
        if (code == 3) return CLOUD;
        if (code == 45 || code == 48) return FOG;
        if (code >= 51 && code <= 57) return DRIZZLE;
        if (code >= 61 && code <= 67) return RAIN;
        if (code >= 71 && code <= 77) return SNOW;
        if (code >= 80 && code <= 82) return SHOWERS;
        if (code == 85 || code == 86) return SNOW;
        if (code >= 95 && code <= 99) return THUNDER;
        return 0;
    }

    /** Short English for a WMO weather interpretation code. */
    static String condition(int code) {
        switch (icon(code)) {
            case SUN: return "Clear";
            case PARTLY: return "Partly cloudy";
            case CLOUD: return "Cloudy";
            case FOG: return "Fog";
            case DRIZZLE: return "Drizzle";
            case RAIN: return "Rain";
            case SNOW: return "Snow";
            case SHOWERS: return "Showers";
            case THUNDER: return "Thunder";
            default: return "";
        }
    }

    private static double[] geo(Context c) throws IOException, JSONException {
        File f = new File(c.getFilesDir(), GEO_FILE);
        if (f.isFile() && System.currentTimeMillis() - f.lastModified() < GEO_MAX_AGE) {
            try (InputStream in = new FileInputStream(f)) {
                JSONObject o = new JSONObject(new String(read(in), StandardCharsets.UTF_8));
                return new double[] {o.getDouble("lat"), o.getDouble("lon")};
            } catch (IOException | JSONException e) {
                // Fall through and look up again.
            }
        }
        JSONObject o = new JSONObject(get(GEO_URL));
        double lat = num(o, "latitude");
        double lon = num(o, "longitude");
        JSONObject store = new JSONObject();
        store.put("lat", lat);
        store.put("lon", lon);
        write(f, store.toString());
        return new double[] {lat, lon};
    }

    private static double num(JSONObject o, String key) throws JSONException {
        Object v = o.get(key);
        if (v instanceof Number) return ((Number) v).doubleValue();
        try {
            return Double.parseDouble(String.valueOf(v));
        } catch (NumberFormatException e) {
            throw new JSONException(key);
        }
    }

    private static String get(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(15000);
        conn.setRequestProperty("User-Agent", "dumbtv-home");
        try {
            if (conn.getResponseCode() != 200) throw new IOException("HTTP " + conn.getResponseCode());
            try (InputStream in = conn.getInputStream()) {
                return new String(read(in), StandardCharsets.UTF_8);
            }
        } finally {
            conn.disconnect();
        }
    }

    private static void write(File f, String body) throws IOException {
        File tmp = new File(f.getAbsolutePath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(f)) tmp.delete();
    }

    private static byte[] read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(4096);
        byte[] buf = new byte[4096];
        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        return out.toByteArray();
    }
}
