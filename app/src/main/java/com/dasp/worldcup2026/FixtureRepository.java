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

final class FixtureRepository {

    static final long UPDATE_INTERVAL_MS =
            24L * 60L * 60L * 1000L;

    private static final int CACHE_SCHEMA_VERSION = 2;

    private static final String PREFS = "fixtures";
    private static final String CACHE = "cache";
    private static final String LAST_UPDATE = "last_update";
    private static final String LAST_ERROR = "last_error";
    private static final String CACHE_SCHEMA = "cache_schema";

    /*
     * These are verified OpenFootball JSON datasets.
     * No individual match is hardcoded anywhere.
     *
     * Current 2026 data exists for the four requested domestic
     * leagues and the 2026 FIFA World Cup.
     *
     * We do not invent current-season URLs for competitions where
     * OpenFootball does not currently provide a matching JSON dataset.
     */
    private static final Source[] SOURCES = {
            new Source(
                    "Premier League",
                    "https://raw.githubusercontent.com/openfootball/football.json/master/2026-27/en.1.json"
            ),
            new Source(
                    "La Liga",
                    "https://raw.githubusercontent.com/openfootball/football.json/master/2026-27/es.1.json"
            ),
            new Source(
                    "Bundesliga",
                    "https://raw.githubusercontent.com/openfootball/football.json/master/2026-27/de.1.json"
            ),
            new Source(
                    "Serie A",
                    "https://raw.githubusercontent.com/openfootball/football.json/master/2026-27/it.1.json"
            ),
            new Source(
                    "FIFA World Cup",
                    "https://raw.githubusercontent.com/openfootball/worldcup.json/master/2026/worldcup.json"
            )
    };

    private final Context context;
    private final SharedPreferences preferences;

    FixtureRepository(Context context) {
        this.context = context.getApplicationContext();
        this.preferences = this.context.getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE
        );
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
        int schema = preferences.getInt(CACHE_SCHEMA, 0);

        if (schema != CACHE_SCHEMA_VERSION) {
            return true;
        }

        long lastUpdate = preferences.getLong(LAST_UPDATE, 0L);

        if (lastUpdate <= 0L) {
            return true;
        }

        return System.currentTimeMillis() - lastUpdate >= UPDATE_INTERVAL_MS;
    }

    List<Fixture> refreshIfStale() throws Exception {
        if (!isStale()) {
            return cachedFixtures();
        }

        List<Fixture> all = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        for (Source source : SOURCES) {
            try {
                String json = download(source.url);
                List<Fixture> parsed = parseSource(json, source.name);
                all.addAll(parsed);
            } catch (Exception exception) {
                String message = exception.getMessage();

                if (message == null || message.trim().isEmpty()) {
                    message = exception.getClass().getSimpleName();
                }

                errors.add(source.name + ": " + message);
            }
        }

        if (all.isEmpty()) {
            saveError(joinErrors(errors));

            throw new Exception(
                    errors.isEmpty()
                            ? "No fixture data available"
                            : joinErrors(errors)
            );
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
        long lastUpdate = preferences.getLong(LAST_UPDATE, 0L);
        String error = preferences.getString(LAST_ERROR, "");

        if (lastUpdate <= 0L) {
            return "OpenFootball data not yet downloaded";
        }

        SimpleDateFormat format = new SimpleDateFormat(
                "d MMM yyyy, h:mm a 'IST'",
                Locale.US
        );
        format.setTimeZone(TimeZone.getTimeZone("Asia/Kolkata"));

        String line = "OpenFootball updated " +
                format.format(new Date(lastUpdate));

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

    private List<Fixture> parseSource(
            String json,
            String competition
    ) throws Exception {

        List<Fixture> result = new ArrayList<>();

        if (json == null || json.trim().isEmpty()) {
            return result;
        }

        JSONObject root;

        try {
            root = new JSONObject(json);
        } catch (Exception exception) {
            throw new Exception("Invalid JSON");
        }

        JSONArray matches = root.optJSONArray("matches");

        if (matches == null) {
            return result;
        }

        for (int i = 0; i < matches.length(); i++) {
            JSONObject item = matches.optJSONObject(i);

            if (item == null) {
                continue;
            }

            Fixture fixture = parseFixture(item, competition);

            if (fixture != null) {
                result.add(fixture);
            }
        }

        return result;
    }

    private Fixture parseFixture(
            JSONObject item,
            String competition
    ) {
        try {
            return Fixture.fromOpenFootballJson(
                    new JSONObject(item.toString()),
                    competition
            );
        } catch (Exception ignored) {
            return null;
        }
    }

    private List<Fixture> parseCachedArray(JSONArray array) {
        List<Fixture> fixtures = new ArrayList<>();

        if (array == null) {
            return fixtures;
        }

        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);

            if (item == null) {
                continue;
            }

            try {
                long id = item.optLong("id", 0L);
                long timestampSeconds = item.optLong("timestampSeconds", 0L);

                String sourceDate = item.optString("sourceDate", "");
                String sourceTime = item.optString("sourceTime", "");
                String competition = item.optString("competition", "");
                String round = item.optString("round", "");
                String venue = item.optString("venue", "");
                String city = item.optString("city", "");
                String homeName = item.optString("homeName", "");
                String awayName = item.optString("awayName", "");

                int homeGoals = item.has("homeGoals")
                        ? item.optInt("homeGoals")
                        : -1;

                int awayGoals = item.has("awayGoals")
                        ? item.optInt("awayGoals")
                        : -1;

                String statusShort = item.optString("statusShort", "");
                String statusLong = item.optString("statusLong", "");
                int elapsed = item.has("elapsed")
                        ? item.optInt("elapsed")
                        : 0;

                List<String> homeScorers = stringList(
                        item.optJSONArray("homeScorers")
                );

                List<String> awayScorers = stringList(
                        item.optJSONArray("awayScorers")
                );

                fixtures.add(new Fixture(
                        id,
                        timestampSeconds,
                        sourceDate,
                        sourceTime,
                        competition,
                        round,
                        venue,
                        city,
                        homeName,
                        awayName,
                        homeGoals,
                        awayGoals,
                        statusShort,
                        statusLong,
                        elapsed,
                        homeScorers,
                        awayScorers
                ));
            } catch (Exception ignored) {
                // Ignore only the malformed cached item.
            }
        }

        sortFixtures(fixtures);
        return fixtures;
    }

    private void saveFixtures(List<Fixture> fixtures) {
        JSONArray array = new JSONArray();

        for (Fixture fixture : fixtures) {
            JSONObject item = new JSONObject();

            try {
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
                // Ignore only the fixture that could not be serialized.
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
                    + "|"
                    + fixture.homeName
                    + "|"
                    + fixture.awayName
                    + "|"
                    + fixture.timestampSeconds;

            if (seen.add(key)) {
                result.add(fixture);
            }
        }

        return result;
    }

    private void sortFixtures(List<Fixture> fixtures) {
        Collections.sort(
                fixtures,
                new Comparator<Fixture>() {
                    @Override
                    public int compare(Fixture a, Fixture b) {
                        return Long.compare(
                                a.timestampSeconds,
                                b.timestampSeconds
                        );
                    }
                }
        );
    }

    private String download(String address) throws Exception {
        HttpURLConnection connection = null;

        try {
            URL url = new URL(address);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(20000);
            connection.setUseCaches(false);
            connection.setRequestProperty(
                    "User-Agent",
                    "FootballFixture/1.0"
            );

            int response = connection.getResponseCode();

            if (response < 200 || response >= 300) {
                throw new Exception("HTTP " + response);
            }

            InputStream input = connection.getInputStream();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(
                            input,
                            StandardCharsets.UTF_8
                    )
            );

            StringBuilder builder = new StringBuilder();
            String line;

            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }

            reader.close();
            return builder.toString();
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static List<String> stringList(JSONArray array) {
        List<String> result = new ArrayList<>();

        if (array == null) {
            return result;
        }

        for (int i = 0; i < array.length(); i++) {
            String value = array.optString(i, "").trim();

            if (!value.isEmpty()) {
                result.add(value);
            }
        }

        return result;
    }

    private String joinErrors(List<String> errors) {
        if (errors == null || errors.isEmpty()) {
            return "";
        }

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

        Source(String name, String url) {
            this.name = name;
            this.url = url;
        }
    }
}
