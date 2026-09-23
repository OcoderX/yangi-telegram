package org.telegram.messenger.ayu.firewall;

import androidx.collection.LongSparseArray;

/**
 * Per-document memoization of {@link Firewall#evaluate}.
 * <p>
 * {@code DownloadController.canDownloadMedia} (and the auto-download checks around it) call
 * {@code Firewall.blocksAutoDownload(...)} once per cell bind, and a fast-scrolling chat can rebind
 * the same document several times a second. {@code evaluate()} then repeats {@code fileNameOf} +
 * {@code FirewallRules.extensionsOf} (a trim, a lower-case copy and a name scan) for nothing, since
 * the verdict for a given document under a given sender cannot change between two binds unless the
 * firewall's own settings do.
 * <p>
 * Keyed by document id and guarded by the sender id that produced the cached verdict: if the same
 * document id is ever seen from a different sender (a file re-sent by someone else, or the rare id
 * collision) the lookup simply misses instead of serving a stale, sender-specific verdict.
 * <p>
 * Invalidated wholesale whenever a {@link FirewallConfig} setter changes a rule or the whitelist -
 * those happen only on the settings screen, never on the hot path.
 */
final class FirewallVerdictCache {

    /** this is a perf cache, not a correctness structure: cap it and drop everything past that */
    private static final int MAX_ENTRIES = 512;

    private static final class Entry {
        final long senderId;
        final Firewall.Verdict verdict;

        Entry(long senderId, Firewall.Verdict verdict) {
            this.senderId = senderId;
            this.verdict = verdict;
        }
    }

    private static final LongSparseArray<Entry> cache = new LongSparseArray<>();

    private FirewallVerdictCache() {
    }

    static synchronized Firewall.Verdict get(long documentId, long senderId) {
        if (documentId == 0) {
            return null;
        }
        Entry e = cache.get(documentId);
        if (e == null || e.senderId != senderId) {
            return null;
        }
        return e.verdict;
    }

    static synchronized void put(long documentId, long senderId, Firewall.Verdict verdict) {
        if (documentId == 0 || verdict == null) {
            return;
        }
        if (cache.size() >= MAX_ENTRIES) {
            // no LRU bookkeeping on the hot path; just start over
            cache.clear();
        }
        cache.put(documentId, new Entry(senderId, verdict));
    }

    static synchronized void clear() {
        cache.clear();
    }
}
