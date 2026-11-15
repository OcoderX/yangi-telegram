package org.telegram.messenger.ayu.chatexport;

import java.io.IOException;
import java.util.Locale;

/** Pure boundaries shared by the streaming exporter and its focused checks. */
final class ExportRules {
    static final long DISK_LIMIT = 512L * 1024 * 1024;
    static final int TEXT_LIMIT = 262144;
    static final int PDF_PAGE_LIMIT = 500;
    private ExportRules() { }

    static boolean inRange(int date, int from, int to) {
        return (from == 0 || date >= from) && (to == 0 || date <= to);
    }

    static int nextOffset(int previous, int[] ids) throws IOException {
        int min = Integer.MAX_VALUE;
        for (int id : ids) if (id > 0 && (previous == 0 || id < previous)) min = Math.min(min, id);
        if (ids.length == 0) return -1;
        if (min == Integer.MAX_VALUE) throw new IOException("History pagination did not advance");
        return min;
    }

    static long addBytes(long used, long amount) throws IOException {
        if (amount < 0 || used < 0 || amount > DISK_LIMIT - used) throw new IOException("Export size limit");
        return used + amount;
    }

    static String escape(String value) {
        if (value == null) return "";
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&': out.append("&amp;"); break;
                case '<': out.append("&lt;"); break;
                case '>': out.append("&gt;"); break;
                case '"': out.append("&quot;"); break;
                case '\'': out.append("&#39;"); break;
                default:
                    if (c == '\n' || c == '\r' || c == '\t' || c >= 32) out.append(c);
            }
        }
        return out.toString();
    }

    static boolean safeLink(String url) {
        if (url == null || url.length() > 8192) return false;
        for (int i = 0; i < url.length(); i++) if (Character.isWhitespace(url.charAt(i)) || Character.isISOControl(url.charAt(i))) return false;
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.startsWith("https://") || lower.startsWith("http://") || lower.startsWith("mailto:");
    }

    static String assetName(long dialog, int message, int index, String extension) {
        String ext = extension != null && extension.matches("[a-z0-9]{1,8}") ? extension : "bin";
        return "assets/d" + Long.toString(dialog).replace('-', 'n') + "_m" + message + "_" + index + "." + ext;
    }
}
