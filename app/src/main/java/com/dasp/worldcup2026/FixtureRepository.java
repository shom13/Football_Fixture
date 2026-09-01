package com.dasp.worldcup2026;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class FixtureRepository {

    static final long UPDATE_INTERVAL_MS = 24L * 60L * 60L * 1000L;
    private static final int CACHE_SCHEMA_VERSION = 4;

    private static final String PREFS = "fixtures";
    private static final String CACHE = "cache";
    private static final String LAST_UPDATE = "last_update";
    private static final String LAST_ERROR = "last_error";
    private static final String CACHE_SCHEMA = "cache_schema";

    /* OpenFootball's maintained 2026/27 Football.TXT datasets. */
    private static final Source[] SOURCES = {
            new Source("Premier League", "https://raw.githubusercontent.com/openfootball/england/master/2026-27/1-premierleague.txt"),
            new Source("La Liga", "https://raw.githubusercontent.com/openfootball/espana/master/2026-27/1-liga.txt"),
            new Source("Bundesliga", "https://raw.githubusercontent.com/openfootball/deutschland/master/2026-27/1-bundesliga.txt"),
            new Source("Serie A", "https://raw.githubusercontent.com/openfootball/italy/master/2026-27/1-seriea.txt"),
            new Source("FIFA World Cup", "https://raw.githubusercontent.com/openfootball/worldcup.json/master/2026/worldcup.json")
    };

    private static final Pattern DATE_PATTERN = Pattern.compile("^\\s*(?:Mon|Tue|Wed|Thu|Fri|Sat|Sun)\\s+([A-Z][a-z]{2})\\s+(\\d{1,2})(?:\\s+(\\d{4}))?\\s*$");
    private static final Pattern MATCH_PATTERN = Pattern.compile("^\\s*(\\d{1,2}:\\d{2})\\s+(.+?)\\s+v\\s+(.+?)(?:\\s+(\\d+)\\s*-\\s*(\\d+)(?:\\s*\\([^)]*\\))?)?\\s*$");
    private static final Pattern ROUND_PATTERN = Pattern.compile("^\\s*▪\\s*(.+?)\\s*$");

    private final SharedPreferences preferences;

    FixtureRepository(Context context) {
        Context app = context.getApplicationContext();
        preferences = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    List<Fixture> cachedFixtures() {
        String cached = preferences.getString(CACHE, "");
        if (cached == null || cached.trim().isEmpty()) return new ArrayList<>();
        try {
            return parseCachedArray(new JSONArray(cached));
        } catch (Exception ignored) {
            return new ArrayList<>();
        }
    }

    boolean isStale() {
        if (preferences.getInt(CACHE_SCHEMA, 0) != CACHE_SCHEMA_VERSION) return true;
        long last = preferences.getLong(LAST_UPDATE, 0L);
        return last <= 0L || System.currentTimeMillis() - last >= UPDATE_INTERVAL_MS;
    }

    List<Fixture> refreshIfStale() throws Exception {
        if (!isStale()) return cachedFixtures();

        List<Fixture> all = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        for (Source source : SOURCES) {
            try {
                String raw = download(source.url);
                List<Fixture> parsed;
                if (source.url.endsWith(".txt")) {
                    parsed = parseFootballText(raw, source.name);
                } else {
                    parsed = parseJson(raw, source.name);
                }
                all.addAll(parsed);
            } catch (Exception e) {
                String message = e.getMessage();
                if (message == null || message.trim().isEmpty()) message = e.getClass().getSimpleName();
                errors.add(source.name + ": " + message);
            }
        }

        if (all.isEmpty()) {
            saveError(joinErrors(errors));
            throw new Exception(errors.isEmpty() ? "No fixture data available" : joinErrors(errors));
        }

        all = deduplicate(all);
        sortFixtures(all);
        saveFixtures(all);
        saveError(errors.isEmpty() ? "" : joinErrors(errors));
        preferences.edit()
                .putLong(LAST_UPDATE, System.currentTimeMillis())
                .putInt(CACHE_SCHEMA, CACHE_SCHEMA_VERSION)
                .apply();
        return all;
    }

    String dataSourceLine() {
        long last = preferences.getLong(LAST_UPDATE, 0L);
        String error = preferences.getString(LAST_ERROR, "");
        if (last <= 0L) return "OpenFootball data not yet downloaded";
        SimpleDateFormat format = new SimpleDateFormat("d MMM yyyy, h:mm a 'IST'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("Asia/Kolkata"));
        String line = "OpenFootball updated " + format.format(new Date(last));
        if (error != null && !error.trim().isEmpty()) line += " • Some sources unavailable";
        return line;
    }

    void saveError(String message) {
        preferences.edit().putString(LAST_ERROR, message == null ? "" : message).apply();
    }

    private List<Fixture> parseJson(String json, String competition) throws Exception {
        List<Fixture> result = new ArrayList<>();
        JSONObject root = new JSONObject(json);
        JSONArray matches = root.optJSONArray("matches");
        if (matches == null) return result;
        for (int i = 0; i < matches.length(); i++) {
            JSONObject item = matches.optJSONObject(i);
            if (item == null) continue;
            try {
                result.add(Fixture.fromOpenFootballJson(new JSONObject(item.toString()), competition));
            } catch (Exception ignored) { }
        }
        return result;
    }

    private List<Fixture> parseFootballText(String text, String competition) throws Exception {
        List<Fixture> result = new ArrayList<>();
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                new java.io.ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8));

        String currentDate = "";
        int currentYear = 0;
        String currentRound = "";
        String line;

        while ((line = reader.readLine()) != null) {
            Matcher roundMatcher = ROUND_PATTERN.matcher(line);
            if (roundMatcher.matches()) {
                currentRound = roundMatcher.group(1).trim();
                continue;
            }

            Matcher dateMatcher = DATE_PATTERN.matcher(line);
            if (dateMatcher.matches()) {
                String month = dateMatcher.group(1);
                String day = dateMatcher.group(2);
                String year = dateMatcher.group(3);
                if (year != null) currentYear = Integer.parseInt(year);
                if (currentYear == 0) currentYear = inferYear(month, day);
                currentDate = toIsoDate(currentYear, month, day);
                continue;
            }

            Matcher matchMatcher = MATCH_PATTERN.matcher(line);
            if (!matchMatcher.matches() || currentDate.isEmpty()) continue;

            String time = matchMatcher.group(1);
            String home = cleanTeam(matchMatcher.group(2));
            String away = cleanTeam(matchMatcher.group(3));
            String hs = matchMatcher.group(4);
            String as = matchMatcher.group(5);

            int homeGoals = hs == null ? -1 : Integer.parseInt(hs);
            int awayGoals = as == null ? -1 : Integer.parseInt(as);
            boolean finished = homeGoals >= 0 && awayGoals >= 0;

            long id = stableId(currentDate, time, home, away, currentRound, competition);
            long timestamp = parseLocalTimestamp(currentDate, time);

            result.add(new Fixture(
                    id,
                    timestamp,
                    currentDate,
                    time,
                    competition,
                    currentRound,
                    "",
                    "",
                    home,
                    away,
                    homeGoals,
                    awayGoals,
                    finished ? "FT" : "NS",
                    finished ? "Match Finished" : "Not Started",
                    0,
                    new ArrayList<String>(),
                    new ArrayList<String>()
            ));
        }
        reader.close();
        return result;
    }

    private String cleanTeam(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s{2,}", " ");
    }

    private int inferYear(String month, String day) {
        int current = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC")).get(java.util.Calendar.YEAR);
        int monthNumber = monthNumber(month);
        return monthNumber >= 7 ? current : current + 1;
    }

    private String toIsoDate(int year, String month, String day) {
        return String.format(Locale.US, "%04d-%02d-%02d", year, monthNumber(month), Integer.parseInt(day));
    }

    private int monthNumber(String month) {
        try {
            return new SimpleDateFormat("MMM", Locale.US).parse(month).getMonth() + 1;
        } catch (Exception ignored) {
            return 1;
        }
    }

    private long parseLocalTimestamp(String date, String time) {
        try {
            SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
            format.setLenient(false);
            format.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date parsed = format.parse(date + " " + time);
            return parsed == null ? 0L : parsed.getTime() / 1000L;
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private List<Fixture> parseCachedArray(JSONArray array) {
        List<Fixture> fixtures = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) continue;
            try {
                fixtures.add(new Fixture(
                        item.optLong("id", 0L),
                        item.optLong("timestampSeconds", 0L),
                        item.optString("sourceDate", ""),
                        item.optString("sourceTime", ""),
                        item.optString("competition", ""),
                        item.optString("round", ""),
                        item.optString("venue", ""),
                        item.optString("city", ""),
                        item.optString("homeName", ""),
                        item.optString("awayName", ""),
                        item.has("homeGoals") ? item.optInt("homeGoals") : -1,
                        item.has("awayGoals") ? item.optInt("awayGoals") : -1,
                        item.optString("statusShort", ""),
                        item.optString("statusLong", ""),
                        item.has("elapsed") ? item.optInt("elapsed") : 0,
                        stringList(item.optJSONArray("homeScorers")),
                        stringList(item.optJSONArray("awayScorers"))
                ));
            } catch (Exception ignored) { }
        }
        sortFixtures(fixtures);
        return fixtures;
    }

    private void saveFixtures(List<Fixture> fixtures) {
        JSONArray array = new JSONArray();
        for (Fixture f : fixtures) {
            try {
                JSONObject item = new JSONObject();
                item.put("id", f.id);
                item.put("timestampSeconds", f.timestampSeconds);
                item.put("sourceDate", f.sourceDate);
                item.put("sourceTime", f.sourceTime);
                item.put("competition", f.competition);
                item.put("round", f.round);
                item.put("venue", f.venue);
                item.put("city", f.city);
                item.put("homeName", f.homeName);
                item.put("awayName", f.awayName);
                item.put("homeGoals", f.homeGoals);
                item.put("awayGoals", f.awayGoals);
                item.put("statusShort", f.statusShort);
                item.put("statusLong", f.statusLong);
                item.put("elapsed", f.elapsed);
                item.put("homeScorers", new JSONArray(f.homeScorers));
                item.put("awayScorers", new JSONArray(f.awayScorers));
                array.put(item);
            } catch (Exception ignored) { }
        }
        preferences.edit().putString(CACHE, array.toString()).apply();
    }

    private List<Fixture> deduplicate(List<Fixture> input) {
        List<Fixture> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Fixture f : input) {
            String key = f.competition + "|" + f.sourceDate + "|" + f.sourceTime + "|" + f.homeName + "|" + f.awayName;
            if (seen.add(key)) result.add(f);
        }
        return result;
    }

    private void sortFixtures(List<Fixture> fixtures) {
        Collections.sort(fixtures, new Comparator<Fixture>() {
            @Override public int compare(Fixture a, Fixture b) {
                return Long.compare(a.timestampSeconds, b.timestampSeconds);
            }
        });
    }

    private String download(String address) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(address).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(20000);
            connection.setUseCaches(false);
            connection.setRequestProperty("User-Agent", "FootballFixture/1.0");
            int response = connection.getResponseCode();
            if (response < 200 || response >= 300) throw new Exception("HTTP " + response);
            InputStream input = connection.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) builder.append(line).append('\n');
            reader.close();
            return builder.toString();
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static List<String> stringList(JSONArray array) {
        List<String> result = new ArrayList<>();
        if (array == null) return result;
        for (int i = 0; i < array.length(); i++) {
            String value = array.optString(i, "").trim();
            if (!value.isEmpty()) result.add(value);
        }
        return result;
    }

    private String joinErrors(List<String> errors) {
        StringBuilder b = new StringBuilder();
        for (String error : errors) {
            if (b.length() > 0) b.append("; ");
            b.append(error);
        }
        return b.toString();
    }

    private long stableId(String date, String time, String home, String away, String round, String competition) {
        return Math.abs((long) (date + "|" + time + "|" + home + "|" + away + "|" + round + "|" + competition).hashCode());
    }

    private static final class Source {
        final String name;
        final String url;
        Source(String name, String url) { this.name = name; this.url = url; }
    }
}
