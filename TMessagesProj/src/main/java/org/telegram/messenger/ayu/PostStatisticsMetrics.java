package org.telegram.messenger.ayu;

/** Aggregate counters only. A view is not a unique viewer, and an action is not a person. */
public final class PostStatisticsMetrics {
    public static final long UNKNOWN = -1;

    public final long views, shares, reactions, comments, publicShares, privateShares;

    public PostStatisticsMetrics(long views, long shares, long reactions, long comments, long publicShares) {
        this.views = normalize(views);
        this.shares = normalize(shares);
        this.reactions = normalize(reactions);
        this.comments = normalize(comments);
        this.publicShares = normalize(publicShares);
        // Counts arrive from separate requests. Do not invent a zero when their snapshots disagree.
        this.privateShares = this.shares >= 0 && this.publicShares >= 0 && this.shares >= this.publicShares
                ? this.shares - this.publicShares : UNKNOWN;
    }

    private static long normalize(long value) {
        return value < 0 ? UNKNOWN : value;
    }

    public static long add(long first, long second) {
        if (first < 0 || second < 0) return UNKNOWN;
        return Long.MAX_VALUE - first < second ? Long.MAX_VALUE : first + second;
    }

    public static double rate(long actions, long views) {
        return actions < 0 || views <= 0 ? Double.NaN : (double) actions / views * 100.0;
    }

    public double engagementRate() {
        return rate(add(add(reactions, shares), comments), views);
    }
}
