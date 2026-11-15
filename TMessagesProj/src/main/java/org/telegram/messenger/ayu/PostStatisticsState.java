package org.telegram.messenger.ayu;

import java.util.Locale;

/** Display states shared by post graph requests and their explicit retry controls. */
public final class PostStatisticsState {
    public enum Graph { LOADING, READY, EMPTY, LIMITED, UNAVAILABLE, ACCESS_REQUIRED, UNSUPPORTED, NETWORK_ERROR, INVALID_DATA }

    private PostStatisticsState() { }

    public static Graph error(String message, boolean graphResponse) {
        String value = message == null ? "" : message.trim().toUpperCase(Locale.ROOT);
        if (value.contains("NOT_ENOUGH") || value.contains("NOT ENOUGH") || value.contains("TOO_SMALL") || value.contains("INSUFFICIENT")) return Graph.LIMITED;
        if (value.equals("CHAT_ADMIN_REQUIRED") || value.equals("CHANNEL_PRIVATE") || value.equals("CHAT_WRITE_FORBIDDEN") || value.startsWith("AUTH_KEY_") || value.equals("SESSION_REVOKED")) return Graph.ACCESS_REQUIRED;
        if (value.equals("BOT_METHOD_INVALID") || value.equals("METHOD_INVALID")) return Graph.UNSUPPORTED;
        if (value.equals("GRAPH_EMPTY") || value.equals("STATS_EMPTY")) return Graph.EMPTY;
        return graphResponse ? Graph.UNAVAILABLE : Graph.NETWORK_ERROR;
    }

    public static boolean canRetry(Graph state) {
        return state == Graph.NETWORK_ERROR || state == Graph.INVALID_DATA || state == Graph.UNAVAILABLE;
    }

    public static boolean isCurrent(boolean destroyed, long owner, long currentOwner, int generation, int currentGeneration) {
        return !destroyed && owner != 0 && owner == currentOwner && generation == currentGeneration;
    }
}
