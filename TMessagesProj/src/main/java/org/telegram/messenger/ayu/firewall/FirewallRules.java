package org.telegram.messenger.ayu.firewall;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;

/**
 * Pure, Android-free helpers behind the Personal Firewall rules: file-name / mime classification and
 * URL heuristics. Everything here is static and side-effect free so it can be called from any thread.
 */
public class FirewallRules {

    /** every extension we consider executable code on some platform */
    public static final HashSet<String> EXECUTABLE_EXTENSIONS = new HashSet<>(Arrays.asList(
            "apk", "apks", "xapk", "exe", "msi", "bat", "cmd", "scr", "pif", "com",
            "vbs", "vbe", "js", "jse", "jar", "ps1", "hta", "lnk", "reg"
    ));

    /** the hard-rule family: .exe and .apk (incl. split-apk variants) */
    public static final HashSet<String> HARD_RULE_EXTENSIONS = new HashSet<>(Arrays.asList(
            "exe", "apk", "apks", "xapk"
    ));

    public static final HashSet<String> ARCHIVE_EXTENSIONS = new HashSet<>(Arrays.asList(
            "zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "iso", "img", "dmg"
    ));

    private static final HashSet<String> EXECUTABLE_MIMES = new HashSet<>(Arrays.asList(
            "application/vnd.android.package-archive",
            "application/x-msdownload",
            "application/x-msdos-program",
            "application/x-ms-installer",
            "application/x-msi",
            "application/x-executable",
            "application/x-dosexec",
            "application/java-archive",
            "application/x-bat",
            "application/bat"
    ));

    public static final HashSet<String> URL_SHORTENERS = new HashSet<>(Arrays.asList(
            "bit.ly", "bitly.com", "tinyurl.com", "t.co", "cutt.ly", "is.gd", "rb.gy",
            "goo.gl", "ow.ly", "shorturl.at", "rebrand.ly", "s.id", "clck.ru", "vk.cc",
            "surl.li", "tiny.cc", "shorte.st", "v.gd", "qps.ru", "u.to", "gg.gg",
            "buff.ly", "lnkd.in", "trib.al", "soo.gd", "clicky.me", "shrtco.de"
    ));

    /** the official domains an attacker likes to imitate */
    private static final String[] TELEGRAM_DOMAINS = {
            "telegram.org", "t.me", "telegram.me", "telegram.dog", "telesco.pe", "fragment.com"
    };

    /** literal look-alike fragments that show up in phishing hosts */
    private static final String[] LOOKALIKE_FRAGMENTS = {
            "telegrarn", "te1egram", "tele-gram", "telegran", "telegramm", "teiegram",
            "telegrm", "telegarm", "tellegram", "telegram-", "-telegram", "telegrama",
            "tellgram", "telegran", "tlegram", "telgram", "t-me", "tme-", "telegram.app"
    };

    private static final String[] PHISHING_PATH_KEYWORDS = {
            "login", "signin", "sign-in", "verify", "verification", "confirm", "wallet",
            "seed", "mnemonic", "recover", "recovery", "airdrop", "giveaway", "gift",
            "bonus", "claim", "auth", "password", "restore", "premium-gift", "wallet-connect"
    };

    private static final HashSet<String> SECOND_LEVEL_SUFFIXES = new HashSet<>(Arrays.asList(
            "co", "com", "net", "org", "gov", "edu", "ac", "or", "ne", "go"
    ));

    private FirewallRules() {
    }

    /**
     * Removes the bidirectional control characters (U+200E, U+200F, U+202A..U+202E, U+2066..U+2069)
     * that malware uses to make "annexe.apk" render as "annexe.kpa" or worse.
     */
    public static String stripBidiControls(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == 0x200E || c == 0x200F || (c >= 0x202A && c <= 0x202E) || (c >= 0x2066 && c <= 0x2069)) {
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- file names

    /**
     * Every dot-separated token after the first one, lower-cased. "photo.jpg.exe" yields [jpg, exe],
     * so a double extension can never hide an executable.
     */
    public static String[] extensionsOf(String fileName) {
        if (fileName == null) {
            return new String[0];
        }
        String name = stripBidiControls(fileName.trim()).toLowerCase(Locale.ROOT);
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        String[] parts = name.split("\\.");
        if (parts.length <= 1) {
            return new String[0];
        }
        String[] out = new String[parts.length - 1];
        System.arraycopy(parts, 1, out, 0, parts.length - 1);
        return out;
    }

    public static String lastExtension(String fileName) {
        String[] exts = extensionsOf(fileName);
        return exts.length == 0 ? "" : exts[exts.length - 1];
    }

    /** true when any dot-separated token of the name is an executable extension */
    public static boolean hasExecutableExtension(String fileName) {
        String[] exts = extensionsOf(fileName);
        for (int i = 0; i < exts.length; i++) {
            if (EXECUTABLE_EXTENSIONS.contains(exts[i])) {
                return true;
            }
        }
        return false;
    }

    /** the executable extension that matched, or null */
    public static String matchedExecutableExtension(String fileName) {
        String[] exts = extensionsOf(fileName);
        for (int i = exts.length - 1; i >= 0; i--) {
            if (EXECUTABLE_EXTENSIONS.contains(exts[i])) {
                return exts[i];
            }
        }
        return null;
    }

    /** true when the name carries a .exe / .apk token (the hard rule family) */
    public static boolean hasHardRuleExtension(String fileName) {
        String[] exts = extensionsOf(fileName);
        for (int i = 0; i < exts.length; i++) {
            if (HARD_RULE_EXTENSIONS.contains(exts[i])) {
                return true;
            }
        }
        return false;
    }

    /** true when the name looks like "something.jpg.exe" - a real extension hidden behind a fake one */
    public static boolean hasDoubleExtension(String fileName) {
        String[] exts = extensionsOf(fileName);
        return exts.length >= 2 && EXECUTABLE_EXTENSIONS.contains(exts[exts.length - 1]);
    }

    public static boolean isExecutableMime(String mime) {
        if (mime == null) {
            return false;
        }
        return EXECUTABLE_MIMES.contains(mime.trim().toLowerCase(Locale.ROOT));
    }

    /** .apk / .exe by mime, used when the file name is missing or lies */
    public static boolean isHardRuleMime(String mime) {
        if (mime == null) {
            return false;
        }
        String m = mime.trim().toLowerCase(Locale.ROOT);
        return "application/vnd.android.package-archive".equals(m)
                || "application/x-msdownload".equals(m)
                || "application/x-msdos-program".equals(m)
                || "application/x-dosexec".equals(m);
    }

    public static boolean isArchiveName(String fileName) {
        String[] exts = extensionsOf(fileName);
        if (exts.length == 0) {
            return false;
        }
        return ARCHIVE_EXTENSIONS.contains(exts[exts.length - 1]);
    }

    public static boolean isZipLike(String fileName) {
        String ext = lastExtension(fileName);
        return "zip".equals(ext) || "apk".equals(ext) || "apks".equals(ext) || "xapk".equals(ext) || "jar".equals(ext);
    }

    // ---------------------------------------------------------------- urls

    /** scheme-less, user-info-less, port-less host, lower cased; null when the url has no host */
    public static String hostOf(String url) {
        if (url == null) {
            return null;
        }
        String s = url.trim();
        int scheme = s.indexOf("://");
        if (scheme >= 0) {
            s = s.substring(scheme + 3);
        } else {
            int colon = s.indexOf(':');
            int slash = s.indexOf('/');
            if (colon > 0 && (slash < 0 || colon < slash)) {
                // mailto:, tel:, tg: ... - no host
                return null;
            }
        }
        int cut = s.length();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '/' || c == '?' || c == '#') {
                cut = i;
                break;
            }
        }
        s = s.substring(0, cut);
        int at = s.lastIndexOf('@');
        if (at >= 0) {
            s = s.substring(at + 1);
        }
        if (s.startsWith("[")) {
            int close = s.indexOf(']');
            if (close > 0) {
                return s.substring(0, close + 1).toLowerCase(Locale.ROOT);
            }
        }
        int port = s.indexOf(':');
        if (port >= 0) {
            s = s.substring(0, port);
        }
        s = s.toLowerCase(Locale.ROOT);
        while (s.endsWith(".")) {
            s = s.substring(0, s.length() - 1);
        }
        return s.isEmpty() ? null : s;
    }

    public static String schemeOf(String url) {
        if (url == null) {
            return "";
        }
        int scheme = url.indexOf("://");
        return scheme <= 0 ? "" : url.substring(0, scheme).toLowerCase(Locale.ROOT);
    }

    public static String pathAndQueryOf(String url) {
        if (url == null) {
            return "";
        }
        String s = url.trim();
        int scheme = s.indexOf("://");
        if (scheme >= 0) {
            s = s.substring(scheme + 3);
        }
        int slash = s.indexOf('/');
        int q = s.indexOf('?');
        int start = -1;
        if (slash >= 0) {
            start = slash;
        }
        if (q >= 0 && (start < 0 || q < start)) {
            start = q;
        }
        return start < 0 ? "" : s.substring(start).toLowerCase(Locale.ROOT);
    }

    /** naive registrable domain: last two labels, three for "co.uk"-style suffixes */
    public static String registrableDomain(String host) {
        if (host == null) {
            return null;
        }
        String[] labels = host.split("\\.");
        if (labels.length <= 2) {
            return host;
        }
        String last = labels[labels.length - 1];
        String secondLast = labels[labels.length - 2];
        if (last.length() <= 3 && SECOND_LEVEL_SUFFIXES.contains(secondLast) && labels.length >= 3) {
            return labels[labels.length - 3] + "." + secondLast + "." + last;
        }
        return secondLast + "." + last;
    }

    public static boolean isPunycode(String host) {
        if (host == null) {
            return false;
        }
        return host.startsWith("xn--") || host.contains(".xn--");
    }

    /** raw IPv4 / IPv6 literal instead of a name */
    public static boolean isRawIp(String host) {
        if (host == null || host.isEmpty()) {
            return false;
        }
        if (host.startsWith("[") && host.endsWith("]")) {
            return true;
        }
        if (host.indexOf(':') >= 0) {
            return true;
        }
        String[] parts = host.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        for (int i = 0; i < 4; i++) {
            if (parts[i].isEmpty() || parts[i].length() > 3) {
                return false;
            }
            for (int j = 0; j < parts[i].length(); j++) {
                if (!Character.isDigit(parts[i].charAt(j))) {
                    return false;
                }
            }
            try {
                int v = Integer.parseInt(parts[i]);
                if (v < 0 || v > 255) {
                    return false;
                }
            } catch (Exception e) {
                return false;
            }
        }
        return true;
    }

    /** letters from more than one script inside one host - the classic homoglyph trick */
    public static boolean hasMixedScript(String host) {
        if (host == null) {
            return false;
        }
        boolean latin = false, cyrillic = false, greek = false, other = false;
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if (!Character.isLetter(c)) {
                continue;
            }
            if (c < 128) {
                latin = true;
                continue;
            }
            Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
            if (block == Character.UnicodeBlock.LATIN_1_SUPPLEMENT
                    || block == Character.UnicodeBlock.LATIN_EXTENDED_A
                    || block == Character.UnicodeBlock.LATIN_EXTENDED_B
                    || block == Character.UnicodeBlock.LATIN_EXTENDED_ADDITIONAL) {
                latin = true;
            } else if (block == Character.UnicodeBlock.CYRILLIC
                    || block == Character.UnicodeBlock.CYRILLIC_SUPPLEMENTARY) {
                cyrillic = true;
            } else if (block == Character.UnicodeBlock.GREEK
                    || block == Character.UnicodeBlock.GREEK_EXTENDED) {
                greek = true;
            } else {
                other = true;
            }
        }
        int scripts = (latin ? 1 : 0) + (cyrillic ? 1 : 0) + (greek ? 1 : 0) + (other ? 1 : 0);
        return scripts > 1;
    }

    /** folds the well known homoglyphs and leet-speak onto plain latin so look-alikes collapse */
    public static String foldHomoglyphs(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = Character.toLowerCase(s.charAt(i));
            switch (c) {
                // written as code points on purpose: this file stays pure ASCII
                case 0x0430: c = 'a'; break; // cyrillic a
                case 0x0435: case 0x03B5: c = 'e'; break; // cyrillic ie, greek epsilon
                case 0x043E: case 0x03BF: c = 'o'; break; // cyrillic o, greek omicron
                case 0x0440: case 0x03C1: c = 'p'; break; // cyrillic er, greek rho
                case 0x0441: c = 'c'; break; // cyrillic es
                case 0x0445: case 0x03C7: c = 'x'; break; // cyrillic ha, greek chi
                case 0x0443: case 0x03B3: c = 'y'; break; // cyrillic u, greek gamma
                case 0x0456: case 0x0131: c = 'i'; break; // ukrainian i, dotless i
                case 0x0458: c = 'j'; break; // cyrillic je
                case 0x04BB: c = 'h'; break; // cyrillic shha
                case 0x0501: c = 'd'; break; // cyrillic komi de
                case 0x051B: c = 'q'; break; // cyrillic qa
                case 0x0261: c = 'g'; break; // latin script g
                case 0x03BD: c = 'v'; break; // greek nu
                case 0x0442: c = 't'; break; // cyrillic te
                case 0x043C: c = 'm'; break; // cyrillic em
                case 0x043A: c = 'k'; break; // cyrillic ka
                case 0x0432: c = 'b'; break; // cyrillic ve
                case 0x043D: c = 'h'; break; // cyrillic en
                case '0': c = 'o'; break;
                case '1': case 'l': c = 'i'; break;
                case '3': c = 'e'; break;
                case '4': c = 'a'; break;
                case '5': c = 's'; break;
                case '7': c = 't'; break;
                case '@': c = 'a'; break;
                case '$': c = 's'; break;
                default: break;
            }
            if (c == '-' || c == '_') {
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    public static int levenshtein(String a, String b) {
        if (a == null || b == null) {
            return Integer.MAX_VALUE;
        }
        int n = a.length(), m = b.length();
        if (n == 0) return m;
        if (m == 0) return n;
        int[] prev = new int[m + 1];
        int[] cur = new int[m + 1];
        for (int j = 0; j <= m; j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= n; i++) {
            cur[0] = i;
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= m; j++) {
                int cost = ca == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = cur;
            cur = tmp;
        }
        return prev[m];
    }

    public static boolean isOfficialTelegramHost(String host) {
        if (host == null) {
            return false;
        }
        for (int i = 0; i < TELEGRAM_DOMAINS.length; i++) {
            String d = TELEGRAM_DOMAINS[i];
            if (host.equals(d) || host.endsWith("." + d)) {
                return true;
            }
        }
        return false;
    }

    /**
     * true when the host is not an official Telegram domain but pretends to be one: a literal
     * look-alike fragment, or a registrable domain within edit distance 2 of an official one.
     */
    public static boolean isTelegramLookalike(String host) {
        if (host == null || isOfficialTelegramHost(host)) {
            return false;
        }
        String lower = host.toLowerCase(Locale.ROOT);
        for (int i = 0; i < LOOKALIKE_FRAGMENTS.length; i++) {
            if (lower.contains(LOOKALIKE_FRAGMENTS[i])) {
                return true;
            }
        }
        String registrable = registrableDomain(lower);
        String folded = foldHomoglyphs(registrable);
        for (int i = 0; i < TELEGRAM_DOMAINS.length; i++) {
            String target = foldHomoglyphs(TELEGRAM_DOMAINS[i]);
            if (folded.equals(target)) {
                // folded onto the real thing although the raw host differs -> homoglyph / leet copy
                return true;
            }
            // short targets such as "t.me" would match almost any 4 letter domain, skip them here
            if (target.length() >= 8 && Math.abs(folded.length() - target.length()) <= 2 && levenshtein(folded, target) <= 2) {
                return true;
            }
        }
        // "telegram" anywhere in the host of a non-telegram domain, e.g. telegram.verify-login.ru
        // ("l" folds onto "i", so the folded needle is "teiegram")
        return foldHomoglyphs(lower).contains("teiegram");
    }

    public static boolean isShortener(String host) {
        if (host == null) {
            return false;
        }
        return URL_SHORTENERS.contains(registrableDomain(host)) || URL_SHORTENERS.contains(host);
    }

    /** an http (not https) url whose path smells like credential / wallet phishing */
    public static boolean isInsecurePhishyPath(String url) {
        if (url == null) {
            return false;
        }
        String scheme = schemeOf(url);
        if (!"http".equals(scheme)) {
            return false;
        }
        String path = pathAndQueryOf(url);
        if (path.isEmpty()) {
            return false;
        }
        for (int i = 0; i < PHISHING_PATH_KEYWORDS.length; i++) {
            if (path.contains(PHISHING_PATH_KEYWORDS[i])) {
                return true;
            }
        }
        return false;
    }

    /**
     * The visible text of a link claims one domain while the href points at another.
     * Only fires when the visible text really looks like a url / domain.
     */
    public static boolean visibleTextDomainMismatch(String visibleText, String url) {
        if (visibleText == null || url == null) {
            return false;
        }
        String text = visibleText.trim();
        if (text.isEmpty() || text.length() > 200) {
            return false;
        }
        String textHost = hostOf(text.contains("://") ? text : "http://" + text);
        if (textHost == null || textHost.indexOf('.') < 0) {
            return false;
        }
        // must look like a real domain, not a sentence with a dot in it
        if (textHost.contains(" ") || textHost.contains(",")) {
            return false;
        }
        String tld = textHost.substring(textHost.lastIndexOf('.') + 1);
        if (tld.length() < 2 || tld.length() > 24) {
            return false;
        }
        for (int i = 0; i < tld.length(); i++) {
            if (!Character.isLetter(tld.charAt(i))) {
                return false;
            }
        }
        String realHost = hostOf(url);
        if (realHost == null) {
            return false;
        }
        String a = registrableDomain(textHost);
        String b = registrableDomain(realHost);
        return a != null && b != null && !a.equals(b);
    }
}
