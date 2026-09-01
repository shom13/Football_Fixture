package com.dasp.worldcup2026;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

public final class MainActivity extends Activity {

    private static final String FILTER_TODAY = "today";
    private static final String FILTER_UPCOMING = "upcoming";
    private static final String FILTER_RESULTS = "results";

    private FixtureRepository repository;
    private LinearLayout list;
    private TextView status;
    private Button todayButton;
    private Button upcomingButton;
    private Button resultsButton;

    private String filter = FILTER_TODAY;
    private List<Fixture> fixtures = new ArrayList<>();

    private final SimpleDateFormat dayFormat = createDayFormat();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        repository = new FixtureRepository(this);
        AppUpdateScheduler.schedule(this);

        setContentView(createContent());

        fixtures = repository.cachedFixtures();
        render();
        refreshIfNeeded();
    }

    private View createContent() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(color(R.color.surface));
        root.setPadding(dp(18), dp(18), dp(18), dp(12));

        TextView title = text(
                "Football Fixtures",
                25,
                R.color.ink,
                true
        );
        root.addView(title, matchWrap());

        TextView subtitle = text(
                "OpenFootball • updated once daily",
                14,
                R.color.muted,
                false
        );
        subtitle.setPadding(0, dp(4), 0, dp(14));
        root.addView(subtitle, matchWrap());

        status = text(
                repository.dataSourceLine(),
                12,
                R.color.muted,
                false
        );
        status.setSingleLine(false);
        root.addView(status, matchWrap());

        LinearLayout filterRow = new LinearLayout(this);
        filterRow.setOrientation(LinearLayout.HORIZONTAL);
        filterRow.setPadding(0, dp(14), 0, dp(12));

        todayButton = filterButton("Today", FILTER_TODAY);
        upcomingButton = filterButton("Upcoming", FILTER_UPCOMING);
        resultsButton = filterButton("Results", FILTER_RESULTS);

        filterRow.addView(todayButton, buttonWeight());
        filterRow.addView(upcomingButton, buttonWeight());
        filterRow.addView(resultsButton, buttonWeight());

        root.addView(filterRow, matchWrap());

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);

        scroll.addView(list, matchWrap());

        root.addView(
                scroll,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        0,
                        1
                )
        );

        return root;
    }

    private void refreshIfNeeded() {
        if (!repository.isStale()) {
            return;
        }

        status.setText("Checking OpenFootball for the daily update...");

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final List<Fixture> updated =
                            repository.refreshIfStale();

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            fixtures = updated;
                            render();
                        }
                    });
                } catch (Exception exception) {
                    repository.saveError(exception.getMessage());

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            fixtures = repository.cachedFixtures();
                            render();
                        }
                    });
                }
            }
        }).start();
    }

    private Button filterButton(
            String label,
            final String value
    ) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(12);
        button.setAllCaps(false);
        button.setSingleLine(true);
        button.setEllipsize(TextUtils.TruncateAt.END);

        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                filter = value;
                render();
            }
        });

        return button;
    }

    private void render() {
        list.removeAllViews();
        updateFilterButtons();

        List<Fixture> visible = filtered();

        if (visible.isEmpty()) {
            addEmptyState();
        } else {
            for (Fixture fixture : visible) {
                list.addView(card(fixture), matchWrap());
            }
        }

        status.setText(repository.dataSourceLine());
    }

    /**
     * The three views are calculated entirely from the downloaded fixtures.
     * Nothing about individual matches or dates is hardcoded.
     *
     * Today:
     *   Every fixture whose source date falls on today's date in India.
     *
     * Upcoming:
     *   Every fixture on the nearest future date for which at least one
     *   fixture exists. This deliberately skips empty calendar days.
     *
     * Results:
     *   Every completed fixture on the most recent past fixture date.
     *   This is the previous matchday represented by the downloaded data.
     */
    private List<Fixture> filtered() {
        if (FILTER_TODAY.equals(filter)) {
            return fixturesForDate(todayKey());
        }

        if (FILTER_UPCOMING.equals(filter)) {
            String nextDate = nearestFutureDate();
            return nextDate == null
                    ? new ArrayList<Fixture>()
                    : fixturesForDate(nextDate);
        }

        if (FILTER_RESULTS.equals(filter)) {
            String previousDate = previousCompletedDate();
            return previousDate == null
                    ? new ArrayList<Fixture>()
                    : completedFixturesForDate(previousDate);
        }

        return new ArrayList<>(fixtures);
    }

    private List<Fixture> fixturesForDate(String wantedDate) {
        List<Fixture> result = new ArrayList<>();

        if (wantedDate == null) {
            return result;
        }

        for (Fixture fixture : fixtures) {
            if (fixture.timestampSeconds <= 0L) {
                continue;
            }

            if (wantedDate.equals(dateKey(fixture))) {
                result.add(fixture);
            }
        }

        sortByTime(result);
        return result;
    }

    private List<Fixture> completedFixturesForDate(String wantedDate) {
        List<Fixture> result = new ArrayList<>();

        if (wantedDate == null) {
            return result;
        }

        for (Fixture fixture : fixtures) {
            if (fixture.timestampSeconds <= 0L) {
                continue;
            }

            if (wantedDate.equals(dateKey(fixture))
                    && fixture.isFinished()) {
                result.add(fixture);
            }
        }

        sortByTime(result);
        return result;
    }

    private String nearestFutureDate() {
        String today = todayKey();
        String nearest = null;

        for (Fixture fixture : fixtures) {
            if (fixture.timestampSeconds <= 0L) {
                continue;
            }

            String date = dateKey(fixture);

            if (date.compareTo(today) <= 0) {
                continue;
            }

            if (nearest == null || date.compareTo(nearest) < 0) {
                nearest = date;
            }
        }

        return nearest;
    }

    private String previousCompletedDate() {
        String today = todayKey();
        String previous = null;

        for (Fixture fixture : fixtures) {
            if (fixture.timestampSeconds <= 0L
                    || !fixture.isFinished()) {
                continue;
            }

            String date = dateKey(fixture);

            if (date.compareTo(today) >= 0) {
                continue;
            }

            if (previous == null || date.compareTo(previous) > 0) {
                previous = date;
            }
        }

        return previous;
    }

    private String todayKey() {
        synchronized (dayFormat) {
            return dayFormat.format(new Date());
        }
    }

    private String dateKey(Fixture fixture) {
        synchronized (dayFormat) {
            return dayFormat.format(
                    new Date(fixture.timestampSeconds * 1000L)
            );
        }
    }

    private SimpleDateFormat createDayFormat() {
        SimpleDateFormat format =
                new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("Asia/Kolkata"));
        format.setLenient(false);
        return format;
    }

    private void sortByTime(List<Fixture> items) {
        Collections.sort(
                items,
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

    private View card(Fixture fixture) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.panel_background);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));

        if (!fixture.competition.isEmpty()) {
            TextView competition = text(
                    fixture.competition,
                    12,
                    R.color.gold,
                    true
            );
            card.addView(competition, matchWrap());
        }

        TextView date = text(
                fixture.statusLine(),
                13,
                fixture.isLive()
                        ? R.color.accent_dark
                        : R.color.muted,
                false
        );
        card.addView(date, matchWrap());

        TextView match = text(
                fixture.scoreTitle(),
                18,
                R.color.ink,
                true
        );
        match.setPadding(0, dp(4), 0, dp(4));
        card.addView(match, matchWrap());

        String locationLine = fixture.locationLine();
        if (!locationLine.isEmpty()) {
            TextView location = text(
                    locationLine,
                    13,
                    R.color.muted,
                    false
            );
            card.addView(location, matchWrap());
        }

        if (!fixture.round.isEmpty()) {
            TextView round = text(
                    fixture.round,
                    12,
                    R.color.gold,
                    true
            );
            round.setPadding(0, dp(6), 0, 0);
            card.addView(round, matchWrap());
        }

        LinearLayout.LayoutParams params = matchWrap();
        params.setMargins(0, 0, 0, dp(10));
        card.setLayoutParams(params);

        return card;
    }

    private void addEmptyState() {
        String message;

        if (FILTER_TODAY.equals(filter)) {
            message = "No matches today.";
        } else if (FILTER_UPCOMING.equals(filter)) {
            message = "No upcoming matches in the downloaded data.";
        } else if (FILTER_RESULTS.equals(filter)) {
            message = "No previous results in the downloaded data.";
        } else {
            message = "No fixtures available.";
        }

        TextView empty = text(
                message,
                16,
                R.color.muted,
                false
        );
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(20), dp(40), dp(20), dp(40));
        list.addView(empty, matchWrap());
    }

    private void updateFilterButtons() {
        styleFilter(
                todayButton,
                FILTER_TODAY.equals(filter)
        );
        styleFilter(
                upcomingButton,
                FILTER_UPCOMING.equals(filter)
        );
        styleFilter(
                resultsButton,
                FILTER_RESULTS.equals(filter)
        );
    }

    private void styleFilter(
            Button button,
            boolean selected
    ) {
        button.setTextColor(
                selected
                        ? Color.WHITE
                        : color(R.color.ink)
        );
        button.setBackgroundColor(
                selected
                        ? color(R.color.accent)
                        : color(R.color.panel)
        );
    }

    private TextView text(
            String value,
            int sp,
            int colorRes,
            boolean bold
    ) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(sp);
        text.setTextColor(color(colorRes));
        text.setIncludeFontPadding(true);

        if (bold) {
            text.setTypeface(
                    android.graphics.Typeface.DEFAULT_BOLD
            );
        }

        return text;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
    }

    private LinearLayout.LayoutParams buttonWeight() {
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(
                        0,
                        dp(38),
                        1
                );
        params.setMargins(dp(3), 0, dp(3), 0);
        return params;
    }

    private int dp(int value) {
        return Math.round(
                value * getResources().getDisplayMetrics().density
        );
    }

    private int color(int resId) {
        return getResources().getColor(resId);
    }
}
