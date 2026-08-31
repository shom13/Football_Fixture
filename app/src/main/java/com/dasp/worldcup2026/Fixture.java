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

    final String competition;

    final List<String> homeScorers;
    final List<String> awayScorers;

    Fixture(
            long id,
            long timestampSeconds,
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
            String competition,
            List<String> homeScorers,
            List<String> awayScorers
    ) {
        this.id = id;
        this.timestampSeconds = timestampSeconds;

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

        this.competition = clean(competition);

        this.homeScorers = homeScorers == null
                ? new ArrayList<String>()
                : new ArrayList<>(homeScorers);

        this.awayScorers = awayScorers == null
                ? new ArrayList<String>()
                : new ArrayList<>(awayScorers);
    }

    /*
     * Generic OpenFootball JSON parser.
     *
     * It only uses information that actually exists
     * in the supplied JSON. Missing fields remain empty.
     */
    static Fixture fromOpenFootballJson(JSONObject item) {

        JSONObject score = item.optJSONObject("score");

        int homeGoals = -1;
        int awayGoals = -1;

        if (score != null) {
            JSONArray fullTime = score.optJSONArray("ft");

            if (fullTime != null && fullTime.length() >= 2) {
                homeGoals = fullTime.optInt(0, -1);
                awayGoals = fullTime.optInt(1, -1);
            }
        }

        List<String> homeScorers = parseScorers(item.optJSONArray("goals1"));
        List<String> awayScorers = parseScorers(item.optJSONArray("goals2"));

        String statusShort;
        String statusLong;

        if (homeGoals >= 0 && awayGoals >= 0) {
            statusShort = "FT";
            statusLong = "Match Finished";
        } else {
            statusShort = "NS";
            statusLong = "Not Started";
        }

        return new Fixture(
                item.optLong("num", stableId(item)),
                parseTimestamp(
                        item.optString("date"),
                        item.optString("time")
                ),
                item.optString("round"),
                item.optString("ground"),
                item.optString("city"),
                item.optString("team1"),
                item.optString("team2"),
                homeGoals,
                awayGoals,
                statusShort,
                statusLong,
                0,
                item.optString("competition"),
                homeScorers,
                awayScorers
        );
    }

    private static List<String> parseScorers(JSONArray goals) {

        List<String> scorers = new ArrayList<>();

        if (goals == null) {
            return scorers;
        }

        for (int i = 0; i < goals.length(); i++) {

            Object value = goals.opt(i);

            if (value == null) {
                continue;
            }

            /*
             * Some OpenFootball JSON data represents a goal
             * as an object containing scorer information.
             */
            if (value instanceof JSONObject) {

                JSONObject goal = (JSONObject) value;

                String name = goal.optString("name");
                String minute = goal.optString("minute");

                boolean penalty = goal.optBoolean("penalty", false);
                boolean ownGoal = goal.optBoolean("owngoal", false);

                StringBuilder line = new StringBuilder();

                if (!name.isEmpty()) {
                    line.append(name);
                }

                if (!minute.isEmpty()) {
                    if (line.length() > 0) {
                        line.append(" ");
                    }

                    line.append(minute);

                    if (!minute.endsWith("'")) {
                        line.append("'");
                    }
                }

                if (penalty) {
                    line.append(" (pen.)");
                }

                if (ownGoal) {
                    line.append(" (OG)");
                }

                if (line.length() > 0) {
                    scorers.add(line.toString());
                }

            } else {

                /*
                 * If a source already supplies a formatted scorer
                 * string, preserve it instead of trying to guess
                 * its structure.
                 */
                String text = String.valueOf(value).trim();

                if (!text.isEmpty()) {
                    scorers.add(text);
                }
            }
        }

        return scorers;
    }

    String matchTitle() {

        String home = homeName.isEmpty()
                ? "TBD"
                : homeName;

        String away = awayName.isEmpty()
                ? "TBD"
                : awayName;

        return home + " vs " + away;
    }

    String scoreTitle() {

        if (homeGoals >= 0 && awayGoals >= 0) {

            String home = homeName.isEmpty()
                    ? "TBD"
                    : homeName;

            String away = awayName.isEmpty()
                    ? "TBD"
                    : awayName;

            return home
                    + " "
                    + homeGoals
                    + " - "
                    + awayGoals
                    + " "
                    + away;
        }

        return matchTitle();
    }

    String dateLine() {

        if (timestampSeconds <= 0) {
            return "Time TBD";
        }

        Date date = new Date(timestampSeconds * 1000L);

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

    boolean hasScorers() {

        return !homeScorers.isEmpty()
                || !awayScorers.isEmpty();
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

            String cleanDate = clean(date);
            String cleanTime = clean(time);

            if (cleanDate.isEmpty()) {
                return 0;
            }

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
                            cleanDate
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

    private static String toGmtZone(String zone) {

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

    private static long stableId(JSONObject item) {

        String seed =
                item.optString("date")
                        + item.optString("time")
                        + item.optString("team1")
                        + item.optString("team2")
                        + item.optString("round")
                        + item.optString("competition");

        return Math.abs((long) seed.hashCode());
    }
}
