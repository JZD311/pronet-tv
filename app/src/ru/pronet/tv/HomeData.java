package ru.pronet.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.NumberFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Weather and sunrise/sunset (MET Norway, every 30 min), cached so the screen opens with numbers.
 * Open-Meteo does not answer from this network. Only fetched while the home screen or the screensaver is visible.
 * The forecast needs no key. Location comes from config.properties.
 */
final class HomeData {
    static final String[] COINS = {"BTC", "ETH", "SOL", "USDT"};
    static final String[] COIN_NAMES = {"Bitcoin", "Ethereum", "Solana", "Dollar"};
    private static final String CURRENCY = Place.currency();
    /** Binance has no pairs in some currencies (INR...) and then refuses the whole request: prices go in dollars instead. */
    private static volatile boolean inDollars;

    /** Binance quotes dollars as USDT. */
    private static String quote() {
        return CURRENCY.equals("USD") || inDollars ? "USDT" : CURRENCY;
    }
    /** Cache fingerprint: a different source must not reuse an older forecast. */
    private static final String WEATHER_QUERY = "metno-2.0";

    private static String weatherPlace() {
        return Prefs.latitude() + "," + Prefs.longitude();
    }

    private static String weatherUrl() {
        return "https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=" + Prefs.latitude() + "&lon=" + Prefs.longitude();
    }

    private static String sunUrl() {
        SimpleDateFormat day = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        day.setTimeZone(TimeZone.getDefault());
        int off = TimeZone.getDefault().getOffset(System.currentTimeMillis());
        int mins = Math.abs(off / 60_000);
        String offset = (off >= 0 ? "%2B" : "-") + String.format(Locale.US, "%02d:%02d", mins / 60, mins % 60);
        return "https://api.met.no/weatherapi/sunrise/3.0/sun?lat=" + Prefs.latitude() + "&lon=" + Prefs.longitude()
                + "&date=" + day.format(new Date()) + "&offset=" + offset;
    }
    private static final long PRICES_EVERY = 5 * 60_000L, WEATHER_EVERY = 30 * 60_000L;
    interface Listener { void onData(); }

    /** BTC, ETH and SOL; in Brazilian reais also the dollar (USDT/BRL), which Binance doesn't list for most currencies. */
    static int coinCount() {
        return Prefs.quotes() ? active().length : 0;
    }

    private static volatile int[] activeCoins;
    private static volatile int activeVersion = -1;
    private static volatile boolean activeInDollars;

    /** Indexes in COINS of the coins the user has on (and that Binance lists in this currency). */
    static int[] active() {
        int[] a = activeCoins;
        boolean dollars = inDollars;
        if (a != null && activeVersion == Prefs.version && activeInDollars == dollars) return a;
        int n = 0;
        int[] tmp = new int[COINS.length];
        // The dollar only in reais, not in dollars: USDTUSDT isn't a pair, and Binance would refuse the whole request.
        for (int i = 0; i < COINS.length; i++) if (Prefs.coin(i) && (i < 3 || CURRENCY.equals("BRL") && !dollars)) tmp[n++] = i;
        a = java.util.Arrays.copyOf(tmp, n);
        activeCoins = a;
        activeVersion = Prefs.version;
        activeInDollars = dollars;
        return a;
    }

    /** Settings changed (city, coins): fetch again now. */
    void settingsChanged() {
        pricesAt = 0;
        weatherAt = 0;
        pricesFailedAt = 0;
        weatherFailedAt = 0;
    }

    static String coinName(int i) {
        return i == 3 ? Ui.tr("Dollar") : COIN_NAMES[i];
    }

    /** Binance.com refuses the US (HTTP 451); Binance.US answers the same call in dollars. */
    private static final String[] PRICE_HOSTS = {"https://api.binance.com", "https://api.binance.us"};

    private static String pricesPath() {
        StringBuilder symbols = new StringBuilder();
        int[] coins = active();
        for (int i = 0; i < coins.length; i++) symbols.append(i == 0 ? "" : ",").append("%22").append(COINS[coins[i]]).append(quote()).append("%22");
        return "/api/v3/ticker/24hr?type=MINI&symbols=%5B" + symbols + "%5D";
    }

    private static String getPrices() throws IOException {
        IOException failed = null;
        for (String host : PRICE_HOSTS) {
            try {
                return get(host + pricesPath());
            } catch (IOException e) {
                // Binance.US has only dollar pairs: its error counts only where Binance.com refuses the US (HTTP 451).
                // After a timeout or a 5xx there, its HTTP 400 would put the prices in dollars for good.
                if (failed == null || "HTTP 451".equals(failed.getMessage())) failed = e;
                // A pair Binance doesn't list (HTTP 400) isn't a refusal: the caller falls back to dollars instead.
                if ("HTTP 400".equals(e.getMessage())) break;
            }
        }
        throw failed;
    }

    static final class Weather {
        double temp = Double.NaN, max = Double.NaN, min = Double.NaN;
        int code = -1;
        boolean isDay = true;
        long sunrise, sunset;   // today's, in epoch ms
        // Next hours (starting at the next full hour): time, temperature, code and day/night.
        final List<Long> hours = new ArrayList<>();
        final List<Double> hourTemp = new ArrayList<>();
        final List<Integer> hourCode = new ArrayList<>();
        final List<Boolean> hourDay = new ArrayList<>();
        // The next 5 days (from tomorrow): noon of each day on the TV's clock, high, low and code.
        final List<Long> days = new ArrayList<>();
        final List<Double> dayMax = new ArrayList<>();
        final List<Double> dayMin = new ArrayList<>();
        final List<Integer> dayCode = new ArrayList<>();
    }

    private static HomeData instance;

    static synchronized HomeData get(Context c) {
        if (instance == null) instance = new HomeData(c.getApplicationContext());
        return instance;
    }

    final double[] prices = {Double.NaN, Double.NaN, Double.NaN, Double.NaN};
    /** 24 h change (%), in the same order. */
    final double[] changes = {Double.NaN, Double.NaN, Double.NaN, Double.NaN};
    volatile long pricesAt, weatherAt;
    volatile Weather weather = new Weather();

    private final SharedPreferences cache;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private volatile boolean fetchingPrices, fetchingWeather;
    private volatile long pricesFailedAt, weatherFailedAt;
    /** The city of the forecast in memory: another process (ScreensaverAlone) can see the city change under it. */
    private volatile String weatherFor = "";

    private HomeData(Context c) {
        cache = c.getSharedPreferences("homedata", Context.MODE_PRIVATE);
        inDollars = CURRENCY.equals(cache.getString("dollarsFor", ""));
        try {
            if (Prefs.quotes()) {
                String p = cache.getString("prices", null);
                if (p != null) parsePrices(p);
                pricesAt = cache.getLong("pricesAt", 0);
            }
            String w = cache.getString("weather", null);
            // The cached forecast is for the city it was fetched for; another city means fetch again.
            if (w != null && weatherPlace().equals(cache.getString("weatherPlace", ""))) {
                weather = parseWeather(w);
                weather.sunrise = cache.getLong("sunrise", 0);
                weather.sunset = cache.getLong("sunset", 0);
                // Fetched with another query (an older version): shown, and fetched again now.
                weatherAt = WEATHER_QUERY.equals(cache.getString("weatherQuery", "")) ? cache.getLong("weatherAt", 0) : 0;
                weatherFor = weatherPlace();
            }
        } catch (Exception ignored) {}
        // As for the picks: a fetch made before the Wi-Fi is up (the TV just started) is tried again when it comes.
        try {
            ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
            if (cm != null) cm.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) {
                    if (pricesFailedAt == 0 && weatherFailedAt == 0) return;
                    pricesFailedAt = 0;
                    weatherFailedAt = 0;
                    main.postDelayed(HomeData.this::refreshIfStale, 2000);
                }
            });
        } catch (RuntimeException e) {
            AppLog.w("weather: no network callback: " + e.getMessage());
        }
    }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    /** Fetches whatever is stale. The screen calls this when it appears and every minute while it's visible. */
    void refreshIfStale() {
        long now = System.currentTimeMillis();
        // A negative age (the TV's clock went back) counts as stale: otherwise the data would freeze.
        if (!fetchingPrices && coinCount() > 0 && (now - pricesAt > PRICES_EVERY || now < pricesAt) && !failedRecently(pricesFailedAt, now)) {
            fetchingPrices = true;
            io.execute(() -> {
                try {
                    String body;
                    try {
                        body = getPrices();
                    } catch (IOException e) {
                        if (inDollars || CURRENCY.equals("USD") || !"HTTP 400".equals(e.getMessage())) throw e;
                        inDollars = true;
                        cache.edit().putString("dollarsFor", CURRENCY).apply();
                        AppLog.w("prices: Binance has no " + CURRENCY + " pairs, using dollars");
                        body = getPrices();
                    }
                    if (!inDollars && !CURRENCY.equals("USD") && halted(body, System.currentTimeMillis())) {
                        // Not saved, unlike a currency Binance doesn't list: the market may open again (next start).
                        inDollars = true;
                        AppLog.w("prices: Binance has halted its " + CURRENCY + " pairs, using dollars");
                        body = getPrices();
                    }
                    parsePrices(body);
                    pricesAt = System.currentTimeMillis();
                    cache.edit().putString("prices", body).putLong("pricesAt", pricesAt).apply();
                    notifyListeners();
                } catch (Exception e) {
                    pricesFailedAt = System.currentTimeMillis();
                    AppLog.w("prices: " + e.getMessage());
                } finally {
                    fetchingPrices = false;
                }
            });
        }
        boolean moved = !weatherPlace().equals(weatherFor);
        if (!fetchingWeather && (moved || now - weatherAt > WEATHER_EVERY || now < weatherAt) && !failedRecently(weatherFailedAt, now)) {
            fetchingWeather = true;
            io.execute(() -> {
                try {
                    String place = weatherPlace();
                    String body = get(weatherUrl());
                    Weather parsed = parseWeather(body);
                    try {
                        applySun(parsed, get(sunUrl()));
                    } catch (Exception sun) {
                        AppLog.w("sunrise: " + sun.getMessage());
                    }
                    weather = parsed;
                    weatherAt = System.currentTimeMillis();
                    weatherFor = place;
                    cache.edit().putString("weather", body).putLong("weatherAt", weatherAt).putString("weatherPlace", place)
                            .putString("weatherQuery", WEATHER_QUERY)
                            .putLong("sunrise", parsed.sunrise).putLong("sunset", parsed.sunset).apply();
                    notifyListeners();
                } catch (Exception e) {
                    weatherFailedAt = System.currentTimeMillis();
                    AppLog.w("weather: " + e.getMessage());
                } finally {
                    fetchingWeather = false;
                }
            });
        }
    }

    private static boolean failedRecently(long at, long now) {
        return at != 0 && now >= at && now - at < 2 * 60_000L;
    }

    private void notifyListeners() {
        main.post(() -> { for (Listener l : new ArrayList<>(listeners)) l.onData(); });
    }

    /**
     * A pair with no trade for a day: Binance halted it and keeps answering with the frozen last price (its AUD pairs
     * since 2026-10-06, BTC at AUD 37,000 for AUD 115,000).
     */
    static boolean halted(String body, long now) throws Exception {
        JSONArray list = new JSONArray(body);
        for (int i = 0; i < list.length(); i++) {
            long close = list.getJSONObject(i).optLong("closeTime", 0);
            if (close > 0 && now - close > 24 * 60 * 60_000L) return true;
        }
        return false;
    }

    private void parsePrices(String body) throws Exception {
        JSONArray list = new JSONArray(body);
        String quote = quote();
        int found = 0;
        for (int i = 0; i < list.length(); i++) {
            JSONObject o = list.getJSONObject(i);
            String symbol = o.getString("symbol");
            for (int k = 0; k < COINS.length; k++) {
                if (!symbol.equals(COINS[k] + quote)) continue;
                found++;
                double last = Double.parseDouble(o.optString("lastPrice", o.optString("price", "NaN")));
                double open = Double.parseDouble(o.optString("openPrice", "NaN"));
                prices[k] = last;
                changes[k] = open > 0 ? (last - open) / open * 100 : Double.NaN;
            }
        }
        if (found == 0) pricesAt = 0;   // cached in another currency (config changed): fetch again
    }

    private static Weather parseWeather(String body) throws Exception {
        JSONArray series = new JSONObject(body).getJSONObject("properties").getJSONArray("timeseries");
        if (series.length() == 0) throw new IOException("empty forecast");
        Weather w = new Weather();
        long now = System.currentTimeMillis();
        String today = localDay(now);
        int current = 0;
        Map<String, double[]> span = new LinkedHashMap<>();
        Map<String, Integer> dayCode = new LinkedHashMap<>();
        Map<String, Long> dayWhen = new LinkedHashMap<>();
        for (int i = 0; i < series.length(); i++) {
            JSONObject point = series.getJSONObject(i);
            long at = isoTime(point.getString("time"));
            if (at <= now) current = i;
            JSONObject details = point.getJSONObject("data").getJSONObject("instant").getJSONObject("details");
            if (!details.has("air_temperature")) continue;
            double temp = details.getDouble("air_temperature");
            String symbol = symbolOf(point.getJSONObject("data"));
            String day = localDay(at);
            double[] range = span.get(day);
            if (range == null) span.put(day, range = new double[] {temp, temp});
            range[0] = Math.min(range[0], temp);
            range[1] = Math.max(range[1], temp);
            Long chosen = dayWhen.get(day);
            if (chosen == null || Math.abs(at - noon(day)) < Math.abs(chosen - noon(day))) {
                dayCode.put(day, symbolCode(symbol));
                dayWhen.put(day, at);
            }
            if (at > now && w.hours.size() < 6) {
                w.hours.add(at);
                w.hourTemp.add(temp);
                w.hourCode.add(symbolCode(symbol));
                w.hourDay.add(symbolDay(symbol, at));
            }
        }
        JSONObject nowPoint = series.getJSONObject(current).getJSONObject("data");
        JSONObject nowDetails = nowPoint.getJSONObject("instant").getJSONObject("details");
        w.temp = nowDetails.getDouble("air_temperature");
        String nowSymbol = symbolOf(nowPoint);
        w.code = symbolCode(nowSymbol);
        w.isDay = symbolDay(nowSymbol, isoTime(series.getJSONObject(current).getString("time")));
        double[] todayRange = span.get(today);
        if (todayRange != null) {
            w.min = todayRange[0];
            w.max = todayRange[1];
        }
        for (Map.Entry<String, double[]> e : span.entrySet()) {
            if (w.days.size() >= 5 || e.getKey().compareTo(today) <= 0) continue;
            w.days.add(localTime(e.getKey() + "T12:00", TimeZone.getDefault()));
            w.dayMin.add(e.getValue()[0]);
            w.dayMax.add(e.getValue()[1]);
            Integer code = dayCode.get(e.getKey());
            w.dayCode.add(code == null ? -1 : code);
        }
        return w;
    }

    private static long noon(String day) throws Exception {
        return localTime(day + "T12:00", TimeZone.getDefault());
    }

    private static void applySun(Weather w, String body) throws Exception {
        JSONObject p = new JSONObject(body).getJSONObject("properties");
        w.sunrise = isoTime(p.getJSONObject("sunrise").getString("time"));
        w.sunset = isoTime(p.getJSONObject("sunset").getString("time"));
    }

    private static String symbolOf(JSONObject data) {
        for (String key : new String[] {"next_1_hours", "next_6_hours", "next_12_hours"}) {
            JSONObject block = data.optJSONObject(key);
            if (block == null) continue;
            JSONObject summary = block.optJSONObject("summary");
            String code = summary == null ? "" : summary.optString("symbol_code", "");
            if (!code.isEmpty()) return code;
        }
        return "";
    }

    /** MET Norway symbol codes, mapped onto the WMO codes the icons and words already use. */
    private static int symbolCode(String symbol) {
        String s = symbol == null ? "" : symbol.toLowerCase(Locale.US);
        if (s.contains("thunder")) return 95;
        if (s.contains("snow") || s.contains("sleet")) return 71;
        if (s.contains("fog")) return 45;
        if (s.contains("heavyrain")) return 65;
        if (s.contains("lightrain") || s.contains("drizzle") || s.contains("rainshower")) return 51;
        if (s.contains("rain")) return 61;
        if (s.contains("partlycloudy")) return 2;
        if (s.contains("cloudy")) return 3;
        if (s.contains("fair")) return 1;
        if (s.contains("clearsky") || s.contains("clear")) return 0;
        return s.isEmpty() ? -1 : 3;
    }

    private static boolean symbolDay(String symbol, long at) {
        if (symbol != null && symbol.endsWith("_night")) return false;
        if (symbol != null && symbol.endsWith("_day")) return true;
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(at);
        int hour = c.get(Calendar.HOUR_OF_DAY);
        return hour >= 7 && hour < 18;
    }

    private static String localDay(long ms) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        f.setTimeZone(TimeZone.getDefault());
        return f.format(new Date(ms));
    }

    /** "2026-10-27T06:00:00Z" and "2026-10-27T06:55+03:00". */
    private static long isoTime(String iso) throws Exception {
        boolean zulu = iso.endsWith("Z");
        int cut = zulu ? iso.length() - 1 : Math.max(iso.lastIndexOf('+'), iso.lastIndexOf('-', 11) == -1 ? -1 : lastOffset(iso));
        if (!zulu && cut < 0) cut = iso.length();
        String base = iso.substring(0, Math.min(16, cut));
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        long ms = f.parse(base).getTime();
        if (zulu || cut >= iso.length()) return ms;
        String off = iso.substring(cut);
        int sign = off.startsWith("-") ? -1 : 1;
        String[] hm = off.substring(1).split(":");
        int minutes = Integer.parseInt(hm[0]) * 60 + (hm.length > 1 ? Integer.parseInt(hm[1]) : 0);
        return ms - sign * minutes * 60_000L;
    }

    private static int lastOffset(String iso) {
        int plus = iso.lastIndexOf('+');
        int minus = iso.lastIndexOf('-');
        return Math.max(plus, minus > 10 ? minus : -1);
    }

    private static long localTime(String iso, TimeZone zone) throws ParseException {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US);
        f.setTimeZone(zone);
        return f.parse(iso).getTime();
    }

    static List<String[]> findCity(String name) throws Exception {
        String lang = Ui.language.replaceAll("-.*", "");   // Open-Meteo takes the language without the country
        JSONObject o = new JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?count=10&format=json&language=" + lang
                + "&name=" + URLEncoder.encode(name.trim(), "UTF-8")));
        JSONArray results = o.optJSONArray("results");
        List<String[]> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();   // places with the same name, state and country read the same
        for (int i = 0; results != null && i < results.length(); i++) {
            JSONObject r = results.getJSONObject(i);
            String city = r.optString("name", "");
            if (city.isEmpty() || !r.has("latitude") || !r.has("longitude")) continue;
            StringBuilder label = new StringBuilder(city);
            for (String part : new String[] {r.optString("admin1", ""), r.optString("country", "")}) {
                if (!part.isEmpty() && !part.equals(city)) label.append(", ").append(part);
            }
            if (!seen.add(label.toString())) continue;
            out.add(new String[] {label.toString(), city,
                    String.format(Locale.US, "%.4f", r.getDouble("latitude")), String.format(Locale.US, "%.4f", r.getDouble("longitude"))});
        }
        return out;
    }

    private static String get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(6000);
        c.setRequestProperty("User-Agent", "FreeMyTV/1.4 (Android TV)");
        c.setRequestProperty("Accept", "application/json");
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                return out.toString(StandardCharsets.UTF_8.name());
            }
        } finally {
            c.disconnect();
        }
    }

    /**
     * Night at your location: from sunset to sunrise (like Apple's "Automatic" appearance: the light design darkens).
     * Uses the last weather fetched (it moves ~1 min a day); without data, 6:00 and 18:00.
     */
    boolean isNight(long now) {
        return isNight(Prefs.theme(), now, weather.sunrise, weather.sunset);
    }

    /** The same from what it depends on (SleepOverlay gets these in its Intent). */
    static boolean isNight(String theme, long now, long sunrise, long sunset) {
        if (theme.equals("dark")) return true;
        if (theme.equals("light")) return false;
        long t = Shaders.timeOfDay(now, 0);
        long rise = Shaders.timeOfDay(sunrise, 6 * 60 * 60_000L);
        long set = Shaders.timeOfDay(sunset, 18 * 60 * 60_000L);
        return t < rise || t >= set;
    }

    // MARK: - Formatting

    private static Locale locale() {
        return Ui.locale();
    }

    /** 4.15M · 580.4k · 21.0k · 1.04k · 5.61 (as short as possible; the dollar always with cents). */
    static String price(double v) {
        if (Double.isNaN(v)) return "—";
        if (v >= 1_000_000) return String.format(locale(), "%.2fM", v / 1_000_000);
        if (v >= 10_000) return String.format(locale(), "%.1fk", v / 1000);
        if (v >= 1000) return String.format(locale(), "%.2fk", v / 1000);
        return String.format(locale(), "%.2f", v);
    }

    /** 4.15M · 416.3k · 12.2k · 1.04k · 531 · 5.05: abbreviated, for the price row on the home screen. */
    static String compact(double v) {
        if (Double.isNaN(v)) return "—";
        if (v >= 1_000_000) return String.format(locale(), "%.2fM", v / 1_000_000);   // Bitcoin in rubles…
        if (v >= 10_000) return String.format(locale(), "%.1fk", v / 1000);
        if (v >= 1000) return String.format(locale(), "%.2fk", v / 1000);
        if (v >= 100) return String.format(locale(), "%.0f", v);
        return String.format(locale(), "%.2f", v);
    }

    /**
     * $108,234 · $185.20 · R$ 581.234 · 74 264 € (cents only below a thousand), in the configured currency, with the
     * symbol and spaces where the language puts them.
     */
    static String brl(double v) {
        String symbol = currencySymbol();
        if (Double.isNaN(v)) return symbol + "—";
        return Ui.once("brl", v, () -> {
            DecimalFormat f = (DecimalFormat) NumberFormat.getCurrencyInstance(locale());
            DecimalFormatSymbols s = f.getDecimalFormatSymbols();
            s.setCurrencySymbol(symbol.trim());   // our symbols: ICU would write "US$" outside the US
            f.setDecimalFormatSymbols(s);
            f.setMinimumFractionDigits(v >= 1000 ? 0 : 2);
            f.setMaximumFractionDigits(v >= 1000 ? 0 : 2);
            return f.format(v);
        });
    }

    /** The weather in words, from Open-Meteo's WMO code. */
    static String condition(int code, boolean day) {
        if (code < 0) return "";
        if (code == 0) return day ? Ui.tr("Clear") : Ui.tr("night|Clear");
        if (code == 1) return day ? Ui.tr("Mostly Sunny") : Ui.tr("Mostly Clear");
        if (code == 2) return Ui.tr("Partly Cloudy");
        if (code == 3) return Ui.tr("Cloudy");
        if (code == 45 || code == 48) return Ui.tr("Fog");
        if (code >= 51 && code <= 57) return Ui.tr("Drizzle");
        if (code == 65 || code == 67 || code == 82) return Ui.tr("Heavy Rain");
        if (code >= 71 && code <= 77 || code == 85 || code == 86) return Ui.tr("Snow");
        if (code >= 95) return Ui.tr("Thunderstorms");
        return Ui.tr("Rain");
    }

    /** "H:24°  L:15°" (in Portuguese, "Máx. 24°  Mín. 15°"), with the given space between the two. */
    static String highLow(Weather wx, String space) {
        return Ui.trf("H:{max}", "max", degrees(wx.max)) + space + Ui.trf("L:{min}", "min", degrees(wx.min));
    }

    private static String currencySymbol() {
        switch (inDollars ? "USD" : CURRENCY) {
            case "USD": return "$";
            case "BRL": return "R$ ";
            case "EUR": return "€";
            case "GBP": return "£";
            case "JPY": return "¥";
            default: return CURRENCY + " ";
        }
    }

    /** Celsius or Fahrenheit (config.properties "temperature": C, F, or auto = Fahrenheit in the US). */
    static String degrees(double v) {
        if (Double.isNaN(v)) return "—";
        return Math.round(fahrenheit() ? v * 9 / 5 + 32 : v) + "°";
    }

    private static boolean fahrenheit() {
        String unit = Prefs.temperature();
        if (unit.equals("F")) return true;
        if (unit.equals("C")) return false;
        return "US".equals(Locale.getDefault().getCountry());
    }

    /** "+1.2%" / "−0.8%" (a real minus sign); "+1,2 %" in the languages that space the percent sign. */
    static String change(double v) {
        if (Double.isNaN(v)) return "";
        return Ui.once("change", v, () -> {
            NumberFormat f = NumberFormat.getPercentInstance(locale());
            f.setMinimumFractionDigits(1);
            f.setMaximumFractionDigits(1);
            return (v >= 0 ? "+" : "−") + f.format(Math.abs(v) / 100);
        });
    }

    static String hour(long at) {
        return Ui.once(Prefs.clock24() ? "hour24" : "hour", at, () -> Ui.datePattern("format|h a").format(at));
    }

    /** "Seg", "Mon", "Lun", "Mo", "Пн": the short day of the week, capitalized and without the abbreviation's dot. */
    static String weekday(long at) {
        return Ui.once("weekday", at, () -> {
            String s = Ui.datePattern("format|EEE").format(at);
            if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
            return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(Ui.locale()) + s.substring(1);
        });
    }

    /** "Sunday", "domingo", "Sonntag": a day's full name, for TalkBack. */
    static String weekdayName(long at) {
        return Ui.once("weekdayName", at, () -> new SimpleDateFormat("EEEE", Ui.locale()).format(new java.util.Date(at)));
    }

    /** How long ago the data was fetched, if it's stale (no internet): "12 min ago". */
    static String staleness(long at, long every) {
        if (at == 0) return "";
        long age = System.currentTimeMillis() - at;
        if (age < every * 2) return "";
        long minutes = age / 60_000;
        return minutes < 60 ? Ui.trf("{n} min ago", "n", minutes) : Ui.trf("{n} h ago", "n", minutes / 60);
    }
}
