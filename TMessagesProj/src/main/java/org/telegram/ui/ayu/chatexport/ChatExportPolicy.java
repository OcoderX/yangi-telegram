package org.telegram.ui.ayu.chatexport;

import java.util.Calendar;

/** Pure option conversions, kept outside the chat's rendering path. */
public final class ChatExportPolicy {
    private ChatExportPolicy() {}

    public static int mediaMegabytes(float progress) {
        if (Float.isNaN(progress)) progress = 0;
        return 1 + Math.round(Math.max(0, Math.min(1, progress)) * 99);
    }

    public static int dayBoundary(Calendar selected, boolean end) {
        Calendar day = (Calendar) selected.clone();
        day.set(Calendar.HOUR_OF_DAY, 0);
        day.set(Calendar.MINUTE, 0);
        day.set(Calendar.SECOND, 0);
        day.set(Calendar.MILLISECOND, 0);
        if (end) day.add(Calendar.DAY_OF_MONTH, 1);
        long seconds = day.getTimeInMillis() / 1000L - (end ? 1 : 0);
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE, seconds));
    }

    public static boolean validRange(int from, int to) {
        return from == 0 || to == 0 || from <= to;
    }
}
