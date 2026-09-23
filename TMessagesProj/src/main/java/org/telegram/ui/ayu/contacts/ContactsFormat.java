package org.telegram.ui.ayu.contacts;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.ayu.contacts.ContactSession;
import org.telegram.messenger.ayu.contacts.ContactTracker;
import org.telegram.tgnet.TLRPC;

import java.util.Calendar;
import java.util.Date;

/**
 * Ox-gram contacts: small formatting helpers shared by the online / special / tracker screens.
 */
public class ContactsFormat {

    /** "Online" (localized) when the user is online right now, otherwise the usual last seen text */
    public static CharSequence statusText(int account, TLRPC.User user) {
        if (user == null) {
            return "";
        }
        if (ContactTracker.isOnline(account, user)) {
            return LocaleController.getString(R.string.OxContactsOnline);
        }
        return LocaleController.formatUserStatus(account, user);
    }

    /** "5 s" / "35 min" / "2 h" / "2 h 15 min" */
    public static String formatDuration(long millis) {
        long totalSeconds = Math.max(0, millis) / 1000L;
        if (totalSeconds < 60) {
            return LocaleController.formatString(R.string.OxContactsDurationSec, (int) totalSeconds);
        }
        long minutes = totalSeconds / 60L;
        if (minutes < 60) {
            return LocaleController.formatString(R.string.OxContactsDurationMin, (int) minutes);
        }
        long hours = minutes / 60L;
        long restMinutes = minutes % 60L;
        if (restMinutes == 0) {
            return LocaleController.formatString(R.string.OxContactsDurationHour, (int) hours);
        }
        return LocaleController.formatString(R.string.OxContactsDurationHourMin, (int) hours, (int) restMinutes);
    }

    /** "14:02" */
    public static String formatTime(long millis) {
        try {
            return LocaleController.getInstance().getFormatterDay().format(new Date(millis));
        } catch (Throwable ignore) {
            return "";
        }
    }

    /** "Today" / "Yesterday" / "12 Mar" / "12 Mar 2024" */
    public static String formatDayHeader(long millis) {
        try {
            long day = ContactSession.startOfDay(millis);
            long today = ContactSession.startOfDay(System.currentTimeMillis());
            if (day == today) {
                return LocaleController.getString(R.string.OxContactsToday);
            }
            Calendar calendar = Calendar.getInstance();
            calendar.setTimeInMillis(today);
            calendar.add(Calendar.DAY_OF_MONTH, -1);
            if (day == calendar.getTimeInMillis()) {
                return LocaleController.getString(R.string.OxContactsYesterday);
            }
            Calendar dayCalendar = Calendar.getInstance();
            dayCalendar.setTimeInMillis(day);
            Calendar nowCalendar = Calendar.getInstance();
            nowCalendar.setTimeInMillis(today);
            if (dayCalendar.get(Calendar.YEAR) == nowCalendar.get(Calendar.YEAR)) {
                return LocaleController.getInstance().getFormatterDayMonth().format(new Date(day));
            }
            return LocaleController.getInstance().getFormatterYear().format(new Date(day));
        } catch (Throwable ignore) {
            return "";
        }
    }

    /** "Online 14:02 – 14:37 (35 min)" or "Online since 14:02 (35 min)" for an ongoing session */
    public static String formatSession(ContactSession session) {
        if (session == null) {
            return "";
        }
        String duration = formatDuration(session.getDuration());
        if (session.ongoing) {
            return LocaleController.formatString(R.string.OxContactsSessionOngoing, formatTime(session.start), duration);
        }
        return LocaleController.formatString(R.string.OxContactsSessionRange, formatTime(session.start), formatTime(session.end), duration);
    }
}
