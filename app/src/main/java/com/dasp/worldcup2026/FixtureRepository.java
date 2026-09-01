package com.dasp.worldcup2026;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
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
    private static final int CACHE_SCHEMA_VERSION = 6;

    private static final String PREFS = "fixtures";
    private static final String CACHE = "cache";
    private static final String LAST_UPDATE = "last_update";
    private static final String LAST_ERROR = "last_error";
    private static final String CACHE_SCHEMA = "cache_schema";

    /*
     * Only endpoint-backed competitions are wired here.
     * No match, score, team or result is hardcoded.
     *
     * Championship and Euro 2028 are currently available from OpenFootball.
     * Champions League, League One and Copa America are deliberately not
     * pointed at an old season when a current endpoint is unavailable.
     */
    private static final Source[] SOURCES = {
            new Source("Premier League", "https://raw.githubusercontent.com/openfootball/england/master/2026-27/1-premierleague.txt", false),
            new Source("Championship", "https://raw.githubusercontent.com/openfootball/england/master/2026-27/2-championship.txt", false),
            new Source("La Liga", "https://raw.githubusercontent.com/openfootball/espana/master/2026-27/1-liga.txt", false),
            new Source("Bundesliga", "https://raw.githubusercontent.com/openfootball/deutschland/master/2026-27/1-bundesliga.txt", false),
            new Source("Serie A", "https://raw.githubusercontent.com/openfootball/italy/master/2026-27/1-seriea.txt", false),
            new Source("FIFA World Cup", "https://raw.githubusercontent.com/openfootball/worldcup.json/master/2026/worldcup.json", true),
            new Source("Euro", "https://raw.githubusercontent.com/openfootball/euro.json/master/2028/euro.json", true)
    };

    private static final Pattern DATE_PATTERN = Pattern.compile(
            "^\\s*(?:Mon|Tue|Wed|Thu|Fri|Sat|Sun)\\s+([A-Z][a-z]{2})\\s+(\\d{1,2})(?:\\s+(\\d{4}))?\\s*$"
    );

    private static final Pattern MATCH_PATTERN = Pattern.compile(
            "^\\s*(?:(\\d{1,2}:\\d{2}(?:\\s+UTC[+-]\\d+)?)\\s+)?(.+?)\\s+v\\s+(.+?)(?:\\s+(\\d+)\\s*-\\s*(\\d+)(?:\\s*\\([^)]*\\))?)?(?:\\s+@\\s+(.+?))?\\s*$"
    );

    private static final Pattern ROUND_PATTERN = Pattern.compile(
            "^\\s*▪\\s*(.+?)\\s*$"
    );

    private final SharedPreferences preferences;

    FixtureRepository(Context context) {
        Context app = context.getApplicationContext();
        preferences = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    List<Fixture> cachedFixtures() {
        String cached = preferences.getString(CACHE, "");
        if (cached == null || cached.trim().isEmpty()) {
            return new ArrayList<>();
        }

        try {
            return parseCachedArray(new JSONArray(cached));
        } catch (Exception ignored) {
            return new ArrayList<>();
        }
    }

    boolean isStale() {
        if (preferences.getInt(CACHE_SCHEMA, 0) != CACHE_SCHEMA_VERSION) {
            return true;
        }

        long last = preferences.getLong(LAST_UPDATE, 0L);
        return last <= 0L
                || System.currentTimeMillis() - last >= UPDATE_INTERVAL_MS;
    }

    List<Fixture> refreshIfStale() throws Exception {
        if (!isStale()) {
            return cachedFixtures();
        }

        List<Fixture> all = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        for (Source source : SOURCES) {
            try {
                String raw = download(source.url);
                if (source.json) {
                    all.addAll(parseJson(raw, source.name));
                } else {
                    all.addAll(parseFootballText(raw, source.name));
                }
            } catch (Exception e) {
                String message = e.getMessage();
                if (message == null || message.trim().isEmpty()) {
                    message = e.getClass().getSimpleName();
                }
                errors.add(source.name + ": " + message);
            }
        }

        if (all.isEmpty()) {
            saveError(errors.isEmpty() ? "No fixture data available" : joinErrors(errors));
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

        if (last <= 0L) {
            return "OpenFootball data not yet downloaded";
        }

        SimpleDateFormat format = new SimpleDateFormat(
                "d MMM yyyy, h:mm a 'IST'",
                Locale.US
        );
        format.setTimeZone(TimeZone.getTimeZone("Asia/Kolkata"));

        String line = "OpenFootball updated " + format.format(new Date(last));
        if (error != null && !error.trim().isEmpty()) {
            line += " • Some sources unavailable";
        }
        return line;
    }

    void saveError(String message) {
        preferences.edit()
                .putString(LAST_ERROR, message == null ? "" : message)
                .apply();
    }

    private List<Fixture> parseJson(String json, String competition) throws Exception {
        List<Fixture> result = new ArrayList<>();
        JSONObject root = new JSONObject(json);
        JSONArray matches = root.optJSONArray("matches");
        if (matches == null) {
            return result;
        }

        for (int i = 0; i < matches.length(); i++) {
            JSONObject item = matches.optJSONObject(i);
            if (item == null) {
                continue;
            }
            try {
                result.add(Fixture.fromOpenFootballJson(new JSONObject(item.toString()), competition));
            } catch (Exception ignored) {
                // Ignore only the malformed fixture.
            }
        }
        return result;
    }

    private List<Fixture> parseFootballText(String text, String competition) throws Exception {
        List<Fixture> result = new ArrayList<>();

        BufferedReader reader = new BufferedReader(
                new InputStreamReader(
                        new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)),
                        StandardCharsets.UTF_8
                )
        );

        String currentDate = "";
        int currentYear = 0;
        String currentRound = "";
        String currentTime = "";
        String line;

        while ((line = reader.readLine()) != null) {
            Matcher roundMatcher = ROUND_PATTERN.matcher(line);
            if (roundMatcher.matches()) {
                currentRound = roundMatcher.group(1).trim();
                currentTime = "";
                continue;
            }

            Matcher dateMatcher = DATE_PATTERN.matcher(line);
            if (dateMatcher.matches()) {
                String month = dateMatcher.group(1);
                String day = dateMatcher.group(2);
                String year = dateMatcher.group(3);

                if (year != null) {
                    currentYear = Integer.parseInt(year);
                }
                if (currentYear == 0) {
                    currentYear = inferYear(month);
                }

                currentDate = toIsoDate(currentYear, month, day);
                currentTime = "";
                continue;
            }

            Matcher matchMatcher = MATCH_PATTERN.matcher(line);
            if (!matchMatcher.matches() || currentDate.isEmpty()) {
                continue;
            }

            String explicitTime = matchMatcher.group(1);
            if (explicitTime != null && !explicitTime.trim().isEmpty()) {
                currentTime = explicitTime.trim();
            }

            String time = currentTime;
            String home = clean(matchMatcher.group(2));
            String away = clean(matchMatcher.group(3));
            String venue = clean(matchMatcher.group(6));

            String homeScore = matchMatcher.group(4);
            String awayScore = matchMatcher.group(5);
            int homeGoals = homeScore == null ? -1 : Integer.parseInt(homeScore);
            int awayGoals = awayScore == null ? -1 : Integer.parseInt(awayScore);
            boolean finished = homeGoals >= 0 && awayGoals >= 0;

            long id = stableId(currentDate, time, home, away, currentRound, competition);
            long timestamp = parseTimestamp(currentDate, time);

            result.add(new Fixture(
                    id,
                    timestamp,
                    currentDate,
                    time,
                    competition,
                    currentRound,
                    venue,
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

    private List<Fixture> parseCachedArray(JSONArray array) {
        List<Fixture> fixtures = new ArrayList<>();

        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) {
                continue;
            }

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
            } catch (Exception ignored) {
                // Ignore one malformed cache entry.
            }
        }

        sortFixtures(fixtures);
        return fixtures;
    }

    private void saveFixtures(List<Fixture> fixtures) {
        JSONArray array = new JSONArray();

        for (Fixture fixture : fixtures) {
            try {
                JSONObject item = new JSONObject();
                item.put("id", fixture.id);
                item.put("timestampSeconds", fixture.timestampSeconds);
                item.put("sourceDate", fixture.sourceDate);
                item.put("sourceTime", fixture.sourceTime);
                item.put("competition", fixture.competition);
                item.put("round", fixture.round);
                item.put("venue", fixture.venue);
                item.put("city", fixture.city);
                item.put("homeName", fixture.homeName);
                item.put("awayName", fixture.awayName);
                item.put("homeGoals", fixture.homeGoals);
                item.put("awayGoals", fixture.awayGoals);
                item.put("statusShort", fixture.statusShort);
                item.put("statusLong", fixture.statusLong);
                item.put("elapsed", fixture.elapsed);
                item.put("homeScorers", new JSONArray(fixture.homeScorers));
                item.put("awayScorers", new JSONArray(fixture.awayScorers));
                array.put(item);
            } catch (Exception ignored) {
                // Ignore one malformed fixture during cache serialization.
            }
        }

        preferences.edit()
                .putString(CACHE, array.toString())
                .apply();
    }

    private List<Fixture> deduplicate(List<Fixture> input) {
        List<Fixture> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (Fixture fixture : input) {
            String key = fixture.competition
                    + "|" + fixture.sourceDate
                    + "|" + fixture.sourceTime
                    + "|" + fixture.homeName
                    + "|" + fixture.awayName;

            if (seen.add(key)) {
                result.add(fixture);
            }
        }
        return result;
    }

    private void sortFixtures(List<Fixture> fixtures) {
        Collections.sort(fixtures, new Comparator<Fixture>() {
            @Override
            public int compare(Fixture a, Fixture b) {
                int date = a.dateKey().compareTo(b.dateKey());
                if (date != 0) {
                    return date;
                }
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

            int response = connection.getResponseCode();
            if (response < 200 || response >= 300) {
                throw new Exception("HTTP " + response);
            }

            InputStream input = connection.getInputStream();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(input, StandardCharsets.UTF_8)
            );

            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append('\n');
            }
            reader.close();
            return builder.toString();
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private int inferYear(String month) {
        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        int current = calendar.get(Calendar.YEAR);
        return monthNumber(month) >= 7 ? current : current + 1;
    }

    private String toIsoDate(int year, String month, String day) {
        return String.format(
                Locale.US,
                "%04d-%02d-%02d",
                year,
                monthNumber(month),
                Integer.parseInt(day)
        );
    }

    private int monthNumber(String month) {
        try {
            Date date = new SimpleDateFormat("MMM", Locale.US).parse(month);
            return date == null ? 1 : date.getMonth() + 1;
        } catch (Exception ignored) {
            return 1;
        }
    }

    private long parseTimestamp(String date, String time) {
        if (date == null || date.isEmpty() || time == null || time.isEmpty()) {
            return 0L;
        }

        try {
            String[] parts = time.trim().split("\\s+");
            String clock = parts.length > 0 ? parts[0] : "00:00";
            String zone = parts.length > 1 ? parts[1] : "UTC";

            SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
            format.setLenient(false);
            format.setTimeZone(TimeZone.getTimeZone(toGmtZone(zone)));

            Date parsed = format.parse(date + " " + clock);
            return parsed == null ? 0L : parsed.getTime() / 1000L;
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private String toGmtZone(String zone) {
        String cleanZone = clean(zone).replace("UTC", "GMT");
        if ("GMT".equals(cleanZone)) {
            return cleanZone;
        }

        int plus = cleanZone.indexOf('+');
        int minus = cleanZone.indexOf('-');
        int index;
        if (plus >= 0 && minus >= 0) {
            index = Math.min(plus, minus);
        } else {
            index = plus >= 0 ? plus : minus;
        }

        if (index < 0) {
            return "GMT";
        }

        String offset = cleanZone.substring(index);
        if (!offset.contains(":")) {
            offset += ":00";
        }
        return "GMT" + offset;
    }

    private long stableId(String date, String time, String home, String away, String round, String competition) {
        String seed = date + "|" + time + "|" + home + "|" + away + "|" + round + "|" + competition;
        return Math.abs((long) seed.hashCode());
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static List<String> stringList(JSONArray array) {
        List<String> result = new ArrayList<>();
        if (array == null) {
            return result;
        }

        for (int i = 0; i < array.length(); i++) {
            Object value = array.opt(i);
            if (value instanceof JSONObject) {
                JSONObject goal = (JSONObject) value;
                String name = goal.optString("name", "").trim();
                String minute = goal.optString("minute", "").trim();
                if (!name.isEmpty()) {
                    result.add(minute.isEmpty() ? name : name + " " + minute + "'");
                }
            } else {
                String text = array.optString(i, "").trim();
                if (!text.isEmpty()) {
                    result.add(text);
                }
            }
        }
        return result;
    }

    private String joinErrors(List<String> errors) {
        StringBuilder builder = new StringBuilder();
        for (String error : errors) {
            if (builder.length() > 0) {
                builder.append("; ");
            }
            builder.append(error);
        }
        return builder.toString();
    }

    private static final class Source {
        final String name;
        final String url;
        final boolean json;

        Source(String name, String url, boolean json) {
            this.name = name;
            this.url = url;
            this.json = json;
        }
    }
}
