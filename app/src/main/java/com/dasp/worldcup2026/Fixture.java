package com.dasp.worldcup2026;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

final class Fixture {

    final long id;
    final long timestampSeconds;

    final String competition;
    final String round;
    final String venue;
    final String city;

    final String homeName;
    final String awayName;

    final int homeGoals;
    final int awayGoals;

    final String statusShort;
    final String statusLong;
    final int elapsed;

    final List<String> homeScorers;
    final List<String> awayScorers;

    Fixture(
            long id,
            long timestampSeconds,
            String competition,
            String round,
            String venue,
            String city,
            String homeName,
            String awayName,
            int homeGoals,
            int awayGoals,
            String statusShort,
            String statusLong,
            int elapsed,
            List<String> homeScorers,
            List<String> awayScorers
    ) {
        this.id = id;
        this.timestampSeconds = timestampSeconds;

        this.competition = clean(competition);
        this.round = clean(round);
        this.venue = clean(venue);
        this.city = clean(city);

        this.homeName = clean(homeName);
        this.awayName = clean(awayName);

        this.homeGoals = homeGoals;
        this.awayGoals = awayGoals;

        this.statusShort = clean(statusShort);
        this.statusLong = clean(statusLong);
        this.elapsed = elapsed;

        this.homeScorers = copyList(homeScorers);
        this.awayScorers = copyList(awayScorers);
    }

    static Fixture fromOpenFootballJson(
            JSONObject item,
            String sourceCompetition
    ) {
        int homeGoals = -1;
        int awayGoals = -1;

        Object scoreValue = item.opt("score");

        if (scoreValue instanceof JSONObject) {
            JSONObject score = (JSONObject) scoreValue;
            JSONArray ft = score.optJSONArray("ft");

            if (ft != null && ft.length() >= 2) {
                homeGoals = ft.optInt(0, -1);
                awayGoals = ft.optInt(1, -1);
            }
        } else if (scoreValue instanceof JSONArray) {
            JSONArray score = (JSONArray) scoreValue;

            if (score.length() >= 2) {
                homeGoals = score.optInt(0, -1);
                awayGoals = score.optInt(1, -1);
            }
        }

        boolean finished =
                homeGoals >= 0 && awayGoals >= 0;

        String competition =
                item.optString("competition", "");

        if (competition.trim().isEmpty()) {
            competition = sourceCompetition;
        }

        List<String> homeScorers =
                readScorers(item, "goals1", "scorers1");

        List<String> awayScorers =
                readScorers(item, "goals2", "scorers2");

        return new Fixture(
                item.optLong("num", stableId(item)),
                parseTimestamp(
                        item.optString("date"),
                        item.optString("time")
                ),
                competition,
                item.optString("round"),
                item.optString("ground"),
                item.optString("city"),
                item.optString("team1"),
                item.optString("team2"),
                homeGoals,
                awayGoals,
                finished ? "FT" : "NS",
                finished ? "Match Finished" : "Not Started",
                0,
                homeScorers,
                awayScorers
        );
    }

    private static List<String> readScorers(
            JSONObject item,
            String primaryKey,
            String fallbackKey
    ) {
        List<String> result = new ArrayList<>();

        JSONArray array = item.optJSONArray(primaryKey);

        if (array == null) {
            array = item.optJSONArray(fallbackKey);
        }

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
                    if (!minute.isEmpty()) {
                        result.add(name + " " + minute + "'");
                    } else {
                        result.add(name);
                    }
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

    String matchTitle() {
        String home =
                homeName.isEmpty()
                        ? "TBD"
                        : homeName;

        String away =
                awayName.isEmpty()
                        ? "TBD"
                        : awayName;

        return home + " vs " + away;
    }

    String scoreTitle() {
        if (homeGoals >= 0 && awayGoals >= 0) {
            return homeName
                    + " "
                    + homeGoals
                    + " - "
                    + awayGoals
                    + " "
                    + awayName;
        }

        return matchTitle();
    }

    String dateLine() {
        if (timestampSeconds <= 0) {
            return "Time TBD";
        }

        Date date =
                new Date(timestampSeconds * 1000L);

        DateFormat format =
                new SimpleDateFormat(
                        "d MMM yyyy, h:mm a 'IST'",
                        Locale.US
                );

        format.setTimeZone(
                TimeZone.getTimeZone("Asia/Kolkata")
        );

        return format.format(date);
    }

    String locationLine() {
        if (venue.isEmpty() && city.isEmpty()) {
            return "";
        }

        if (venue.isEmpty()) {
            return city;
        }

        if (city.isEmpty()) {
            return venue;
        }

        return venue + ", " + city;
    }

    String statusLine() {
        if (isLive()) {
            String clock =
                    elapsed > 0
                            ? " - " + elapsed + "'"
                            : "";

            return "Live" + clock;
        }

        if (isFinished()) {
            return statusLong.isEmpty()
                    ? "Finished"
                    : statusLong;
        }

        return dateLine();
    }

    boolean isLive() {
        return "1H".equals(statusShort)
                || "HT".equals(statusShort)
                || "2H".equals(statusShort)
                || "ET".equals(statusShort)
                || "BT".equals(statusShort)
                || "P".equals(statusShort)
                || "LIVE".equals(statusShort)
                || "INT".equals(statusShort);
    }

    boolean isFinished() {
        return "FT".equals(statusShort)
                || "AET".equals(statusShort)
                || "PEN".equals(statusShort);
    }

    boolean isUpcoming() {
        return !isLive() && !isFinished();
    }

    private static List<String> copyList(
            List<String> source
    ) {
        if (source == null) {
            return new ArrayList<>();
        }

        return new ArrayList<>(source);
    }

    private static String clean(String value) {
        if (value == null
                || "null".equalsIgnoreCase(value)) {
            return "";
        }

        return value.trim();
    }

    private static long parseTimestamp(
            String date,
            String time
    ) {
        try {
            String cleanTime = clean(time);

            String[] parts =
                    cleanTime.split("\\s+");

            String clock =
                    parts.length > 0
                            ? parts[0]
                            : "00:00";

            String zone =
                    parts.length > 1
                            ? parts[1]
                            : "UTC";

            SimpleDateFormat format =
                    new SimpleDateFormat(
                            "yyyy-MM-dd HH:mm",
                            Locale.US
                    );

            format.setLenient(false);

            format.setTimeZone(
                    TimeZone.getTimeZone(
                            toGmtZone(zone)
                    )
            );

            Date parsed =
                    format.parse(
                            clean(date)
                                    + " "
                                    + clock
                    );

            return parsed == null
                    ? 0
                    : parsed.getTime() / 1000L;

        } catch (Exception ignored) {
            return 0;
        }
    }

    private static String toGmtZone(
            String zone
    ) {
        String cleanZone =
                clean(zone)
                        .replace("UTC", "GMT");

        if ("GMT".equals(cleanZone)) {
            return "GMT";
        }

        int plus =
                cleanZone.indexOf('+');

        int minus =
                cleanZone.indexOf('-');

        int signIndex;

        if (plus >= 0 && minus >= 0) {
            signIndex = Math.min(plus, minus);
        } else if (plus >= 0) {
            signIndex = plus;
        } else {
            signIndex = minus;
        }

        if (signIndex < 0) {
            return "GMT";
        }

        String offset =
                cleanZone.substring(signIndex);

        if (!offset.contains(":")) {
            offset += ":00";
        }

        return "GMT" + offset;
    }

    private static long stableId(
            JSONObject item
    ) {
        String seed =
                item.optString("date")
                        + "|"
                        + item.optString("time")
                        + "|"
                        + item.optString("team1")
                        + "|"
                        + item.optString("team2")
                        + "|"
                        + item.optString("round");

        return Math.abs((long) seed.hashCode());
    }
}
