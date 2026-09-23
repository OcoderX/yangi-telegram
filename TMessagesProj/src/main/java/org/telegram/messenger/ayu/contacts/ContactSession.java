package org.telegram.messenger.ayu.contacts;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;

/**
 * Ox-gram contact tracker: one continuous "was online" interval, rebuilt from the recorded
 * {@link ContactStatusEntry} transitions.
 * <p>
 * Pure data crunching, no Android dependency — the log fragment calls
 * {@link #build(ArrayList, long)} on the list it loaded from {@link ContactTrackerStorage} and
 * renders the result.
 */
public class ContactSession {

    /** start of the online interval, wall clock milliseconds */
    public long start;
    /** end of the interval; for an ongoing session this is "now" */
    public long end;
    /** true when the contact is still online (no offline transition recorded yet) */
    public boolean ongoing;

    public ContactSession(long start, long end, boolean ongoing) {
        this.start = start;
        this.end = end;
        this.ongoing = ongoing;
    }

    public long getDuration() {
        return Math.max(0, end - start);
    }

    /**
     * Folds transitions into intervals.
     *
     * @param entries transitions in any order (they are sorted here, oldest first)
     * @param now     current time, used to close an ongoing session
     * @return sessions ordered newest first
     */
    public static ArrayList<ContactSession> build(ArrayList<ContactStatusEntry> entries, long now) {
        ArrayList<ContactSession> sessions = new ArrayList<>();
        if (entries == null || entries.isEmpty()) {
            return sessions;
        }
        ArrayList<ContactStatusEntry> sorted = new ArrayList<>(entries);
        Collections.sort(sorted, new Comparator<ContactStatusEntry>() {
            @Override
            public int compare(ContactStatusEntry a, ContactStatusEntry b) {
                return Long.compare(a.timestamp, b.timestamp);
            }
        });

        long openedAt = -1;
        for (int i = 0; i < sorted.size(); i++) {
            ContactStatusEntry entry = sorted.get(i);
            if (entry == null) {
                continue;
            }
            if (entry.online) {
                if (openedAt < 0) {
                    openedAt = entry.timestamp;
                }
            } else {
                if (openedAt >= 0) {
                    sessions.add(new ContactSession(openedAt, Math.max(openedAt, entry.timestamp), false));
                    openedAt = -1;
                }
            }
        }
        if (openedAt >= 0) {
            sessions.add(new ContactSession(openedAt, Math.max(openedAt, now), true));
        }

        Collections.reverse(sessions);
        return sessions;
    }

    /** sum of the parts of {@code sessions} that fall into [from, to) */
    public static long totalBetween(ArrayList<ContactSession> sessions, long from, long to) {
        long total = 0;
        if (sessions == null) {
            return 0;
        }
        for (int i = 0; i < sessions.size(); i++) {
            ContactSession session = sessions.get(i);
            if (session == null) {
                continue;
            }
            long start = Math.max(session.start, from);
            long end = Math.min(session.end, to);
            if (end > start) {
                total += end - start;
            }
        }
        return total;
    }

    /** midnight of the day {@code time} belongs to */
    public static long startOfDay(long time) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(time);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }

    /** start of the week (locale first day of week) {@code time} belongs to */
    public static long startOfWeek(long time) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(startOfDay(time));
        int firstDay = calendar.getFirstDayOfWeek();
        int diff = calendar.get(Calendar.DAY_OF_WEEK) - firstDay;
        if (diff < 0) {
            diff += 7;
        }
        calendar.add(Calendar.DAY_OF_MONTH, -diff);
        return calendar.getTimeInMillis();
    }
}
