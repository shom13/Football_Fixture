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

        this.homeScorers =
                homeScorers == null
                        ? new ArrayList<String>()
                        : new ArrayList<>(homeScorers);

        this.awayScorers =
                awayScorers == null
                        ? new ArrayList<String>()
                        : new ArrayList<>(awayScorers);
    }

    static Fixture fromOpenFootballJson(JSONObject item) {

        JSONObject scoreObject = item.optJSONObject("score");

        int homeGoals = -1;
        int awayGoals = -1;

        if (scoreObject != null) {
            JSONArray fullTime = scoreObject.optJSONArray("ft");

            if (fullTime != null && fullTime.length() >= 2) {
                homeGoals = fullTime.optInt(0, -1);
                awayGoals = fullTime.optInt(1, -1);
            }
        } else {
            /*
             * Some OpenFootball datasets may represent score
             * directly as an array.
             */
            JSONArray directScore = item.optJSONArray("score");

            if (directScore != null && directScore.length() >= 2) {
                homeGoals = directScore.optInt(0, -1);
                awayGoals = directScore.optInt(1, -1);
            }
        }

        boolean finished =
                homeGoals >= 0 && awayGoals >= 0;

        List<String> homeScorers =
                readScorers(item, "scorers1");

        List<String> awayScorers =
                readScorers(item, "scorers2");

        return new Fixture(
                item.optLong("num", stableId(item)),
                parseTimestamp(
                        item.optString("date"),
                        item.optString("time")
                ),
                item.optString("competition"),
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
            String key
    ) {
        List<String> scorers = new ArrayList<>();

        JSONArray array = item.optJSONArray(key);

        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                String value = array.optString(i, "");

                if (!value.trim().isEmpty()) {
                    scorers.add(value.trim());
                }
            }
        }

        return scorers;
    }

    String matchTitle() {
        String home =
                homeName.isEmpty() ? "TBD" : homeName;

        String away =
                awayName.isEmpty() ? "TBD" : awayName;

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

    String competitionLine() {
        return competition;
    }

    String roundLine() {
        return round;
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

    boolean hasScorers() {
        return !homeScorers.isEmpty()
                || !awayScorers.isEmpty();
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
                    cleanTime.split(" ");

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
            return cleanZone;
        }

        int signIndex =
                Math.max(
                        cleanZone.indexOf('+'),
                        cleanZone.indexOf('-')
                );

        if (signIndex < 0) {
            return "GMT";
        }

        String offset =
                cleanZone.substring(signIndex);

        if (!offset.contains(":")) {
            offset = offset + ":00";
        }

        return "GMT" + offset;
    }

    private static long stableId(
            JSONObject item
    ) {
        String seed =
                item.optString("date")
                        + item.optString("time")
                        + item.optString("team1")
                        + item.optString("team2")
                        + item.optString("round");

        return Math.abs(seed.hashCode());
    }
}
