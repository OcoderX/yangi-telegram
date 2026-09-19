package org.telegram.messenger.ayu.edithistory;

import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StrikethroughSpan;

import java.util.ArrayList;
import java.util.List;

/**
 * Word level diff used by the AyuGram edit history sheet.
 * <p>
 * Pure java (no Theme / resource dependency) so it can live next to the storage code: the caller
 * passes plain colors to {@link #buildSpannable}.
 */
public class AyuDiffUtil {

    public static final int OP_EQUAL = 0;
    public static final int OP_DELETE = 1;
    public static final int OP_INSERT = 2;

    /** biggest middle section we are willing to run the O(n*m) LCS on */
    private static final int MAX_TOKENS = 700;
    /** characters of unchanged text kept around a change when the diff is collapsed */
    private static final int CONTEXT_CHARS = 48;

    public static class Op {
        public final int type;
        public final String text;

        public Op(int type, String text) {
            this.type = type;
            this.text = text;
        }
    }

    // ------------------------------------------------------------------ diff

    public static ArrayList<Op> diffWords(String oldText, String newText) {
        final String a = oldText == null ? "" : oldText;
        final String b = newText == null ? "" : newText;
        ArrayList<Op> result = new ArrayList<>();
        if (a.equals(b)) {
            if (b.length() > 0) {
                result.add(new Op(OP_EQUAL, b));
            }
            return result;
        }
        if (a.length() == 0) {
            result.add(new Op(OP_INSERT, b));
            return result;
        }
        if (b.length() == 0) {
            result.add(new Op(OP_DELETE, a));
            return result;
        }

        final ArrayList<String> ta = tokenize(a);
        final ArrayList<String> tb = tokenize(b);

        int prefix = 0;
        final int minSize = Math.min(ta.size(), tb.size());
        while (prefix < minSize && ta.get(prefix).equals(tb.get(prefix))) {
            prefix++;
        }
        int suffix = 0;
        while (suffix < ta.size() - prefix && suffix < tb.size() - prefix
                && ta.get(ta.size() - 1 - suffix).equals(tb.get(tb.size() - 1 - suffix))) {
            suffix++;
        }

        if (prefix > 0) {
            emit(result, OP_EQUAL, join(ta, 0, prefix));
        }

        final List<String> midA = ta.subList(prefix, ta.size() - suffix);
        final List<String> midB = tb.subList(prefix, tb.size() - suffix);
        if (midA.size() > MAX_TOKENS || midB.size() > MAX_TOKENS) {
            if (!midA.isEmpty()) {
                emit(result, OP_DELETE, join(midA, 0, midA.size()));
            }
            if (!midB.isEmpty()) {
                emit(result, OP_INSERT, join(midB, 0, midB.size()));
            }
        } else {
            lcsDiff(midA, midB, result);
        }

        if (suffix > 0) {
            emit(result, OP_EQUAL, join(ta, ta.size() - suffix, ta.size()));
        }
        return result;
    }

    private static void lcsDiff(List<String> a, List<String> b, ArrayList<Op> out) {
        final int n = a.size();
        final int m = b.size();
        if (n == 0 && m == 0) {
            return;
        }
        if (n == 0) {
            emit(out, OP_INSERT, join(b, 0, m));
            return;
        }
        if (m == 0) {
            emit(out, OP_DELETE, join(a, 0, n));
            return;
        }
        final int[][] dp = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            final String ai = a.get(i);
            for (int j = m - 1; j >= 0; j--) {
                if (ai.equals(b.get(j))) {
                    dp[i][j] = dp[i + 1][j + 1] + 1;
                } else {
                    dp[i][j] = Math.max(dp[i + 1][j], dp[i][j + 1]);
                }
            }
        }
        int i = 0;
        int j = 0;
        while (i < n && j < m) {
            if (a.get(i).equals(b.get(j))) {
                emit(out, OP_EQUAL, a.get(i));
                i++;
                j++;
            } else if (dp[i + 1][j] >= dp[i][j + 1]) {
                emit(out, OP_DELETE, a.get(i));
                i++;
            } else {
                emit(out, OP_INSERT, b.get(j));
                j++;
            }
        }
        while (i < n) {
            emit(out, OP_DELETE, a.get(i));
            i++;
        }
        while (j < m) {
            emit(out, OP_INSERT, b.get(j));
            j++;
        }
    }

    /** appends, merging with the previous op when it has the same type */
    private static void emit(ArrayList<Op> out, int type, String text) {
        if (text == null || text.length() == 0) {
            return;
        }
        if (!out.isEmpty()) {
            final Op last = out.get(out.size() - 1);
            if (last.type == type) {
                out.set(out.size() - 1, new Op(type, last.text + text));
                return;
            }
        }
        out.add(new Op(type, text));
    }

    private static String join(List<String> tokens, int from, int to) {
        final StringBuilder sb = new StringBuilder();
        for (int a = from; a < to; a++) {
            sb.append(tokens.get(a));
        }
        return sb.toString();
    }

    /**
     * Splits into "words": runs of letters/digits, runs of whitespace and every other character on
     * its own. Concatenating every token reproduces the input exactly.
     */
    private static ArrayList<String> tokenize(String text) {
        final ArrayList<String> tokens = new ArrayList<>();
        final int length = text.length();
        int i = 0;
        while (i < length) {
            final char c = text.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                int j = i + 1;
                while (j < length && Character.isLetterOrDigit(text.charAt(j))) {
                    j++;
                }
                tokens.add(text.substring(i, j));
                i = j;
            } else if (Character.isWhitespace(c)) {
                int j = i + 1;
                while (j < length && Character.isWhitespace(text.charAt(j))) {
                    j++;
                }
                tokens.add(text.substring(i, j));
                i = j;
            } else {
                tokens.add(text.substring(i, i + 1));
                i++;
            }
        }
        return tokens;
    }

    // ------------------------------------------------------------------ rendering

    /** true when {@link #buildSpannable} with {@code full = false} would actually hide something */
    public static boolean canCollapse(List<Op> ops) {
        if (ops == null || ops.isEmpty()) {
            return false;
        }
        for (int a = 0; a < ops.size(); a++) {
            final Op op = ops.get(a);
            if (op.type != OP_EQUAL || op.text == null) {
                continue;
            }
            final int limit = a == 0 || a == ops.size() - 1 ? CONTEXT_CHARS : CONTEXT_CHARS * 2 + 5;
            if (op.text.length() > limit) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasChanges(List<Op> ops) {
        if (ops == null) {
            return false;
        }
        for (int a = 0; a < ops.size(); a++) {
            if (ops.get(a).type != OP_EQUAL) {
                return true;
            }
        }
        return false;
    }

    /**
     * Builds the colored diff.
     *
     * @param full          true renders every unchanged character, false elides long unchanged runs
     * @param deleteColor   color of removed words (also struck through)
     * @param insertColor   color of added words
     * @param insertBgColor highlight behind added words (0 = none)
     * @param equalColor    color of unchanged text
     */
    public static CharSequence buildSpannable(List<Op> ops, boolean full, int deleteColor, int insertColor, int insertBgColor, int equalColor) {
        final SpannableStringBuilder sb = new SpannableStringBuilder();
        if (ops == null || ops.isEmpty()) {
            return sb;
        }
        for (int a = 0; a < ops.size(); a++) {
            final Op op = ops.get(a);
            String text = op.text;
            if (text == null || text.length() == 0) {
                continue;
            }
            if (op.type == OP_EQUAL && !full) {
                text = collapse(text, a == 0, a == ops.size() - 1);
            }
            final int start = sb.length();
            sb.append(text);
            final int end = sb.length();
            if (end == start) {
                continue;
            }
            if (op.type == OP_DELETE) {
                sb.setSpan(new ForegroundColorSpan(deleteColor), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new StrikethroughSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (op.type == OP_INSERT) {
                sb.setSpan(new ForegroundColorSpan(insertColor), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                if (insertBgColor != 0) {
                    sb.setSpan(new BackgroundColorSpan(insertBgColor), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            } else {
                sb.setSpan(new ForegroundColorSpan(equalColor), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        return sb;
    }

    private static String collapse(String text, boolean first, boolean last) {
        final int limit = first || last ? CONTEXT_CHARS : CONTEXT_CHARS * 2 + 5;
        if (text.length() <= limit) {
            return text;
        }
        if (first) {
            return "… " + text.substring(text.length() - CONTEXT_CHARS);
        }
        if (last) {
            return text.substring(0, CONTEXT_CHARS) + " …";
        }
        return text.substring(0, CONTEXT_CHARS) + " … " + text.substring(text.length() - CONTEXT_CHARS);
    }
}
