package org.telegram.messenger.ayu;

import android.text.SpannableStringBuilder;
import android.text.Spanned;
import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLRPC;
import java.util.ArrayList;

/** Tracks entity ranges through Android's editable buffer, without interpreting typed Markdown. */
public final class AyuForwardEditText {
    private static final class Range {
        final byte[] entity;
        final String original;
        Range(TLRPC.MessageEntity entity, String original) {
            this.entity = serialize(entity);
            this.original = original;
        }
    }

    public static byte[] serialize(TLRPC.MessageEntity entity) {
        SerializedData output = new SerializedData();
        entity.serializeToStream(output);
        byte[] bytes = output.toByteArray();
        output.cleanup();
        return bytes;
    }

    public static TLRPC.MessageEntity deserialize(byte[] bytes) {
        SerializedData input = new SerializedData(bytes);
        TLRPC.MessageEntity entity = TLRPC.MessageEntity.TLdeserialize(input, input.readInt32(true), true);
        input.cleanup();
        return entity;
    }

    public static SpannableStringBuilder editable(String text, ArrayList<TLRPC.MessageEntity> entities) {
        SpannableStringBuilder result = new SpannableStringBuilder(text);
        ArrayList<TLRPC.MessageEntity> display = new ArrayList<>();
        if (entities != null) for (TLRPC.MessageEntity entity : entities) {
            if (entity.offset < 0 || entity.length <= 0 || entity.offset > text.length() - entity.length) continue;
            result.setSpan(new Range(entity, text.substring(entity.offset, entity.offset + entity.length)),
                    entity.offset, entity.offset + entity.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            display.add(deserialize(serialize(entity)));
        }
        MessageObject.addEntitiesToText(result, display, false, false, false, false);
        return result;
    }

    public static ArrayList<TLRPC.MessageEntity> entities(Spanned text) {
        ArrayList<TLRPC.MessageEntity> result = new ArrayList<>();
        for (Range range : text.getSpans(0, text.length(), Range.class)) {
            int start = text.getSpanStart(range), end = text.getSpanEnd(range);
            if (start < 0 || end <= start || end > text.length()) continue;
            TLRPC.MessageEntity entity = deserialize(range.entity);
            boolean style = entity instanceof TLRPC.TL_messageEntityBold || entity instanceof TLRPC.TL_messageEntityItalic
                    || entity instanceof TLRPC.TL_messageEntityUnderline || entity instanceof TLRPC.TL_messageEntityStrike
                    || entity instanceof TLRPC.TL_messageEntityCode || entity instanceof TLRPC.TL_messageEntityPre
                    || entity instanceof TLRPC.TL_messageEntityBlockquote || entity instanceof TLRPC.TL_messageEntitySpoiler
                    || entity instanceof TLRPC.TL_messageEntityTextUrl;
            // A changed emoji/date/mention must not keep the old semantic identity.
            if (!style && !range.original.contentEquals(text.subSequence(start, end))) continue;
            entity.offset = start;
            entity.length = end - start;
            result.add(entity);
        }
        return result;
    }

    private AyuForwardEditText() {}
}
