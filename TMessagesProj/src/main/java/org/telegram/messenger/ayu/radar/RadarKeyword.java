package org.telegram.messenger.ayu.radar;

import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * AyuGram Mention Radar: a single user keyword.
 * <p>
 * Keywords are stored as a JSON array inside {@link RadarConfig#keywords}. A keyword is either a
 * plain substring (optionally constrained to whole words) or a Java regular expression. Matching is
 * always case-insensitive.
 * <p>
 * Plain matching deliberately avoids {@code \b} / {@code Pattern.UNICODE_CHARACTER_CLASS} (API 24+)
 * and checks the boundaries with {@link Character#isLetterOrDigit(char)} instead, so Cyrillic and
 * Uzbek words behave the same way as ASCII ones on every supported API level.
 */
public class RadarKeyword {

    public String id;
    /** the keyword itself, or the regular expression when {@link #regex} is true */
    public String text = "";
    public boolean enabled = true;
    /** only match when the keyword is surrounded by non-word characters (ignored for regex) */
    public boolean wholeWord = true;
    /** treat {@link #text} as a Java regular expression */
    public boolean regex = false;

    /** last compilation error, null when the keyword is usable */
    public String error;

    private Pattern pattern;
    private boolean compiled;

    public RadarKeyword() {
        id = Long.toString(System.currentTimeMillis()) + "_" + Integer.toString((int) (Math.random() * 0x7fffffff), 36);
    }

    public RadarKeyword copy() {
        RadarKeyword c = new RadarKeyword();
        c.id = id;
        c.text = text;
        c.enabled = enabled;
        c.wholeWord = wholeWord;
        c.regex = regex;
        return c;
    }

    public void invalidate() {
        compiled = false;
        pattern = null;
        error = null;
    }

    public boolean isValid() {
        if (TextUtils.isEmpty(text)) {
            return false;
        }
        if (!regex) {
            return true;
        }
        getPattern();
        return pattern != null;
    }

    /** lazily compiled pattern; only used when {@link #regex} is true */
    public Pattern getPattern() {
        if (compiled) {
            return pattern;
        }
        compiled = true;
        pattern = null;
        error = null;
        if (TextUtils.isEmpty(text)) {
            error = "empty";
            return null;
        }
        try {
            pattern = Pattern.compile(text, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.MULTILINE);
        } catch (PatternSyntaxException e) {
            error = e.getDescription() != null ? e.getDescription() : e.getMessage();
        } catch (Throwable e) {
            error = String.valueOf(e.getMessage());
        }
        return pattern;
    }

    /**
     * Finds the first occurrence of this keyword inside {@code haystack}.
     *
     * @param haystack      the original text
     * @param lowerHaystack {@code haystack} lower-cased once by the caller (may be null)
     * @return {@code {start, end}} or null when there is no match
     */
    public int[] find(String haystack, String lowerHaystack) {
        if (!enabled || TextUtils.isEmpty(haystack) || TextUtils.isEmpty(text)) {
            return null;
        }
        if (regex) {
            Pattern p = getPattern();
            if (p == null) {
                return null;
            }
            try {
                Matcher m = p.matcher(haystack);
                if (m.find()) {
                    return new int[]{m.start(), m.end()};
                }
            } catch (Throwable ignore) {
            }
            return null;
        }
        String hay = lowerHaystack != null ? lowerHaystack : haystack.toLowerCase(Locale.ROOT);
        String needle = text.toLowerCase(Locale.ROOT);
        return findPlain(hay, needle, wholeWord);
    }

    /** case-insensitive substring search; both arguments must already be lower-cased */
    public static int[] findPlain(String hay, String needle, boolean wholeWord) {
        if (TextUtils.isEmpty(hay) || TextUtils.isEmpty(needle)) {
            return null;
        }
        int from = 0;
        while (from <= hay.length() - needle.length()) {
            int index = hay.indexOf(needle, from);
            if (index < 0) {
                return null;
            }
            int end = index + needle.length();
            if (!wholeWord || (isBoundary(hay, index - 1) && isBoundary(hay, end))) {
                return new int[]{index, end};
            }
            from = index + 1;
        }
        return null;
    }

    private static boolean isBoundary(String s, int index) {
        if (index < 0 || index >= s.length()) {
            return true;
        }
        char c = s.charAt(index);
        return !Character.isLetterOrDigit(c) && c != '_';
    }

    // ------------------------------------------------------------------ json

    public JSONObject toJson() throws Exception {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("text", text == null ? "" : text);
        o.put("enabled", enabled);
        o.put("wholeWord", wholeWord);
        o.put("regex", regex);
        return o;
    }

    public static RadarKeyword fromJson(JSONObject o) {
        if (o == null) {
            return null;
        }
        RadarKeyword k = new RadarKeyword();
        String id = o.optString("id", null);
        if (!TextUtils.isEmpty(id)) {
            k.id = id;
        }
        k.text = o.optString("text", "");
        k.enabled = o.optBoolean("enabled", true);
        k.wholeWord = o.optBoolean("wholeWord", true);
        k.regex = o.optBoolean("regex", false);
        if (TextUtils.isEmpty(k.text)) {
            return null;
        }
        return k;
    }

    public static String serialize(ArrayList<RadarKeyword> list) {
        JSONArray array = new JSONArray();
        if (list != null) {
            for (int i = 0; i < list.size(); i++) {
                try {
                    array.put(list.get(i).toJson());
                } catch (Throwable ignore) {
                }
            }
        }
        return array.toString();
    }

    public static ArrayList<RadarKeyword> deserialize(String json) {
        ArrayList<RadarKeyword> result = new ArrayList<>();
        if (TextUtils.isEmpty(json)) {
            return result;
        }
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                RadarKeyword k = fromJson(array.optJSONObject(i));
                if (k != null) {
                    result.add(k);
                }
            }
        } catch (Throwable ignore) {
        }
        return result;
    }
}
