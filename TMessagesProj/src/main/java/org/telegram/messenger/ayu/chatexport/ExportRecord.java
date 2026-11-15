package org.telegram.messenger.ayu.chatexport;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;

/** One bounded, detached message row in the on-disk export spool. */
public final class ExportRecord {
    public long dialog;
    public int id, date;
    public String sender = "", text = "", html = "", metadata = "";
    public ArrayList<Attachment> attachments = new ArrayList<>();

    public static final class Attachment {
        public String relativePath = "", label = "", mime = "", status = "";
        public long bytes;
        public boolean preview;
    }

    public String toJson() throws IOException {
        try {
            JSONObject value = new JSONObject();
            value.put("dialog", dialog).put("id", id).put("date", date);
            value.put("sender", string(sender)).put("text", string(text));
            value.put("html", string(html)).put("metadata", string(metadata));
            JSONArray files = new JSONArray();
            for (Attachment file : attachments) {
                if (file == null) throw new IOException("Missing attachment record");
                JSONObject item = new JSONObject();
                item.put("relativePath", string(file.relativePath)).put("label", string(file.label));
                item.put("mime", string(file.mime)).put("status", string(file.status));
                item.put("bytes", file.bytes).put("preview", file.preview);
                files.put(item);
            }
            return value.put("attachments", files).toString();
        } catch (JSONException e) {
            throw new IOException("Cannot serialize export record", e);
        }
    }

    public static ExportRecord fromJson(String json) throws IOException {
        try {
            JSONObject value = new JSONObject(json);
            ExportRecord record = new ExportRecord();
            record.dialog = value.getLong("dialog");
            record.id = value.getInt("id");
            record.date = value.getInt("date");
            record.sender = value.getString("sender");
            record.text = value.getString("text");
            record.html = value.getString("html");
            record.metadata = value.getString("metadata");
            JSONArray files = value.getJSONArray("attachments");
            for (int i = 0; i < files.length(); i++) {
                JSONObject item = files.getJSONObject(i);
                Attachment file = new Attachment();
                file.relativePath = item.getString("relativePath");
                file.label = item.getString("label");
                file.mime = item.getString("mime");
                file.status = item.getString("status");
                file.bytes = item.getLong("bytes");
                file.preview = item.getBoolean("preview");
                if (file.bytes < 0) throw new IOException("Invalid attachment size");
                record.attachments.add(file);
            }
            return record;
        } catch (JSONException | IllegalArgumentException e) {
            throw new IOException("Invalid export record", e);
        }
    }

    private static String string(String value) {
        return value == null ? "" : value;
    }
}
