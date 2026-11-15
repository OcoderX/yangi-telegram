package org.telegram.messenger.ayu.chatexport;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Worker-thread writers. Neither format needs the complete history in memory. */
public final class ExportWriters {
    private ExportWriters() { }

    public interface Writer extends AutoCloseable {
        void add(ExportRecord record) throws IOException;
        File finish() throws IOException;
        @Override void close();
    }

    public static final class SizeLimitException extends IOException {
        public SizeLimitException(String message) { super(message); }
    }

    public static Writer create(ChatHistoryExporter.Format format, File workDir, String title,
                                String metadata, Map<String, String> labels, Runnable check) throws IOException {
        if (format == null) throw new IOException("Missing export format");
        if (format == ChatHistoryExporter.Format.PDF) return new Pdf(workDir, title, metadata, labels, check);
        return new Html(workDir, title, metadata, labels, check);
    }

    private abstract static class Base implements Writer {
        final File root, assets;
        final Runnable check;
        final Map<String, String> labels;
        final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.ROOT);
        long diskBytes;
        boolean closed, finished;

        Base(File workDir, Map<String, String> labels, Runnable check) throws IOException {
            this.check = check == null ? () -> { } : check;
            this.labels = labels == null ? new HashMap<>() : new HashMap<>(labels);
            this.check.run();
            root = workDir.getCanonicalFile();
            if (!root.isDirectory()) throw new IOException("Export directory is missing");
            assets = new File(root, "assets").getCanonicalFile();
            if (!assets.getParentFile().equals(root)) throw new IOException("Unsafe asset directory");
            dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
            diskBytes = scan(root, 0);
        }

        long scan(File directory, int depth) throws IOException {
            check.run();
            if (depth > 16) throw new IOException("Export directory nesting limit");
            File[] children = directory.listFiles();
            if (children == null) throw new IOException("Cannot read export directory");
            long total = 0;
            for (File child : children) {
                check.run();
                File canonical = child.getCanonicalFile();
                if (!canonical.getPath().startsWith(root.getPath() + File.separator)) throw new IOException("Unsafe export file");
                total = budget(total, canonical.isDirectory() ? scan(canonical, depth + 1) : canonical.length());
            }
            return total;
        }

        long budget(long used, long amount) throws SizeLimitException {
            if (amount < 0 || used < 0 || amount > ExportRules.DISK_LIMIT - used) {
                throw new SizeLimitException("Export disk limit exceeded");
            }
            return used + amount;
        }

        OutputStream output(String name) throws IOException {
            check.run();
            File target = new File(root, name);
            if (!target.getCanonicalFile().getParentFile().equals(root) || !target.createNewFile()) {
                throw new IOException("Export output already exists or is unsafe");
            }
            return new BufferedOutputStream(new FilterOutputStream(new FileOutputStream(target)) {
                @Override public void write(int value) throws IOException {
                    check.run();
                    diskBytes = budget(diskBytes, 1);
                    out.write(value);
                }
                @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                    check.run();
                    diskBytes = budget(diskBytes, length);
                    out.write(bytes, offset, length);
                }
            }, 32768);
        }

        File asset(String relativePath) throws IOException {
            check.run();
            if (relativePath == null || !relativePath.matches("assets/[A-Za-z0-9_.-]+")) {
                throw new IOException("Unsafe attachment path");
            }
            File file = new File(root, relativePath).getCanonicalFile();
            if (!file.getParentFile().equals(assets) || !file.getPath().startsWith(assets.getPath() + File.separator)) {
                throw new IOException("Attachment escaped export assets");
            }
            if (!file.isFile()) throw new IOException("Export attachment is missing");
            return file;
        }

        void active() throws IOException {
            check.run();
            if (closed || finished) throw new IOException("Export writer is closed");
        }

        void validate(ExportRecord record) throws IOException {
            active();
            if (record == null || record.attachments == null) throw new IOException("Missing export message");
            if (str(record.text).length() > ExportRules.TEXT_LIMIT || str(record.html).length() > ExportRules.TEXT_LIMIT * 8
                    || str(record.sender).length() > ExportRules.TEXT_LIMIT || str(record.metadata).length() > ExportRules.TEXT_LIMIT
                    || record.attachments.size() > 1024) throw new SizeLimitException("Export message limit exceeded");
            for (ExportRecord.Attachment attachment : record.attachments) {
                check.run();
                if (attachment == null || attachment.bytes < 0 || str(attachment.label).length() > ExportRules.TEXT_LIMIT
                        || str(attachment.status).length() > ExportRules.TEXT_LIMIT) throw new IOException("Invalid export attachment");
                if (!str(attachment.relativePath).isEmpty()) asset(attachment.relativePath);
            }
        }

        String label(String key, String fallback) {
            String value = labels.get(key);
            return value == null || value.isEmpty() ? fallback : value;
        }

        String date(int seconds) { return dateFormat.format(new Date(seconds * 1000L)); }
    }

    private static String str(String text) { return text == null ? "" : text; }
    private static String esc(String text) { return ExportRules.escape(text); }

    private static final class Html extends Base {
        private OutputStreamWriter html;

        Html(File workDir, String title, String metadata, Map<String, String> labels, Runnable check) throws IOException {
            super(workDir, labels, check);
            try {
                try (OutputStreamWriter css = new OutputStreamWriter(output("styles.css"), StandardCharsets.UTF_8)) {
                    css.write("html{color-scheme:light}body{font:16px/1.55 system-ui,sans-serif;background:#f3f5f7;color:#17212b;margin:0;padding:24px}"
                            + "main{max-width:900px;margin:auto}header,article{background:#fff;border:1px solid #dce3e9;border-radius:12px;padding:20px;margin-bottom:16px}"
                            + "h1{font-size:26px;margin:0 0 8px}h2{font-size:16px;margin:0}time,.metadata,.status{color:#536575;font-size:13px;white-space:pre-wrap;overflow-wrap:anywhere}"
                            + ".text{white-space:pre-wrap;overflow-wrap:anywhere;margin-top:12px}a{color:#1769a6}img,video{max-width:100%;max-height:600px;height:auto}"
                            + "audio{max-width:100%}.attachment{margin-top:12px;padding-top:10px;border-top:1px solid #e5e9ed}pre{white-space:pre-wrap}"
                            + "blockquote{border-inline-start:3px solid #a9becd;margin-inline:0;padding-inline-start:12px}.spoiler{background:#dce3e9}code{font-family:monospace}");
                }
                html = new OutputStreamWriter(output("index.html"), StandardCharsets.UTF_8);
                html.write("<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                        + "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; img-src 'self'; media-src 'self'; style-src 'self'; base-uri 'none'; form-action 'none'\">"
                        + "<title>" + esc(title) + "</title><link rel=\"stylesheet\" href=\"styles.css\"></head><body><main><header><h1>"
                        + esc(title) + "</h1><div class=\"metadata\">" + esc(metadata) + "</div></header>\n");
            } catch (IOException | RuntimeException e) {
                close();
                throw e;
            }
        }

        @Override public void add(ExportRecord record) throws IOException {
            validate(record);
            html.write("<article id=\"d" + record.dialog + "m" + record.id + "\"><h2>" + esc(record.sender)
                    + "</h2><time>" + esc(date(record.date)) + "</time><div class=\"metadata\">" + esc(record.metadata) + "</div><div class=\"text\">");
            html.write(str(record.html).isEmpty() ? esc(record.text) : safeMarkup(record.html));
            html.write("</div>");
            for (ExportRecord.Attachment file : record.attachments) {
                check.run();
                html.write("<div class=\"attachment\">");
                String caption = str(file.label).isEmpty() ? label("attachment", "Attachment") : file.label;
                if (!str(file.relativePath).isEmpty()) {
                    asset(file.relativePath);
                    String path = esc(file.relativePath);
                    html.write("<a download href=\"" + path + "\">" + esc(caption) + "</a>");
                    String mime = str(file.mime).toLowerCase(Locale.ROOT);
                    if (file.preview && (mime.equals("image/jpeg") || mime.equals("image/png") || mime.equals("image/webp") || mime.equals("image/gif"))) {
                        html.write("<div><img loading=\"lazy\" src=\"" + path + "\" alt=\"" + esc(caption) + "\"></div>");
                    } else if (mime.equals("audio/mpeg") || mime.equals("audio/ogg") || mime.equals("audio/mp4") || mime.equals("audio/wav")) {
                        html.write("<audio controls preload=\"none\" src=\"" + path + "\"></audio>");
                    } else if (mime.equals("video/mp4") || mime.equals("video/webm")) {
                        html.write("<video controls preload=\"none\" src=\"" + path + "\"></video>");
                    }
                } else {
                    html.write(esc(caption));
                }
                html.write("<div class=\"status\">" + esc(file.status) + (file.bytes > 0 ? " (" + file.bytes + " B)" : "") + "</div></div>");
            }
            html.write("</article>\n");
        }

        @Override public File finish() throws IOException {
            active();
            html.write("</main></body></html>\n");
            html.close();
            html = null;
            try (ZipOutputStream zip = new ZipOutputStream(output("result.zip"))) {
                zip(zip, new File(root, "index.html"), "index.html");
                zip(zip, new File(root, "styles.css"), "styles.css");
                if (assets.exists()) {
                    File[] files = assets.listFiles();
                    if (files == null) throw new IOException("Cannot list export attachments");
                    for (File file : files) {
                        check.run();
                        String path = "assets/" + file.getName();
                        zip(zip, asset(path), path);
                    }
                }
            }
            finished = true;
            return new File(root, "result.zip");
        }

        void zip(ZipOutputStream zip, File source, String path) throws IOException {
            check.run();
            File canonical = source.getCanonicalFile();
            if (!canonical.getPath().startsWith(root.getPath() + File.separator)) throw new IOException("Unsafe ZIP input");
            zip.putNextEntry(new ZipEntry(path));
            byte[] buffer = new byte[32768];
            try (FileInputStream input = new FileInputStream(canonical)) {
                int read;
                while ((read = input.read(buffer)) != -1) {
                    check.run();
                    zip.write(buffer, 0, read);
                }
            }
            zip.closeEntry();
        }

        @Override public void close() {
            closed = true;
            if (html != null) {
                try { html.close(); } catch (IOException | RuntimeException ignore) { }
                html = null;
            }
        }
    }

    /** Only the entity renderer's inert formatting vocabulary is accepted. */
    static String safeMarkup(String source) {
        StringBuilder result = new StringBuilder(source.length());
        for (int i = 0; i < source.length();) {
            if (source.charAt(i) != '<') {
                result.append(source.charAt(i++));
                continue;
            }
            int end = source.indexOf('>', i + 1);
            if (end < 0) { result.append("&lt;"); i++; continue; }
            String tag = source.substring(i + 1, end);
            if (tag.matches("/?(?:b|strong|i|em|u|s|del|code|pre|blockquote)") || tag.equals("br") || tag.equals("br/") || tag.equals("br /")) {
                result.append('<').append(tag).append('>');
            } else if (tag.equals("span class=\"spoiler\"") || tag.equals("/span") || tag.equals("/a")) {
                result.append('<').append(tag).append('>');
            } else if (tag.startsWith("a href=\"") && tag.endsWith("\"")) {
                String href = tag.substring(8, tag.length() - 1);
                if (href.indexOf('"') < 0 && href.indexOf('<') < 0 && href.indexOf('>') < 0
                        && ExportRules.safeLink(href) && !href.contains("&#") && !href.contains("&colon;")) {
                    result.append("<a rel=\"noreferrer noopener\" href=\"").append(href).append("\">");
                }
            }
            i = end + 1;
        }
        return result.toString();
    }

    private static final class Pdf extends Base {
        static final int WIDTH = 595, HEIGHT = 842, MARGIN = 36, CONTENT_WIDTH = WIDTH - MARGIN * 2;
        static final int BOTTOM = HEIGHT - 42;
        static final long IMAGE_PIXELS = 1024L * 1024, TOTAL_IMAGE_PIXELS = 8L * 1024 * 1024;
        final PdfDocument document = new PdfDocument();
        final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        final TextPaint footerPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        final Paint imagePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        PdfDocument.Page page;
        Canvas canvas;
        int pages, y;
        long imagePixels;

        Pdf(File workDir, String title, String metadata, Map<String, String> labels, Runnable check) throws IOException {
            super(workDir, labels, check);
            try {
                textPaint.setColor(Color.BLACK);
                textPaint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
                footerPaint.setColor(Color.BLACK);
                footerPaint.setTextSize(9);
                footerPaint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
                text(title, 19, true);
                text(metadata, 10, false);
                space(14);
            } catch (IOException | RuntimeException e) {
                close();
                throw e;
            }
        }

        void newPage() throws IOException {
            check.run();
            endPage();
            if (pages >= ExportRules.PDF_PAGE_LIMIT) throw new SizeLimitException("PDF page limit exceeded");
            page = document.startPage(new PdfDocument.PageInfo.Builder(WIDTH, HEIGHT, ++pages).create());
            canvas = page.getCanvas();
            canvas.drawColor(Color.WHITE);
            y = MARGIN;
        }

        void endPage() {
            if (page == null) return;
            check.run();
            // The footer also uses Android shaping for localized page labels.
            @SuppressWarnings("deprecation")
            StaticLayout footer = new StaticLayout(label("page", "Page") + " " + pages, footerPaint,
                    CONTENT_WIDTH, Layout.Alignment.ALIGN_NORMAL, 1.12f, 0f, false);
            canvas.save();
            canvas.translate(MARGIN, HEIGHT - 29);
            footer.draw(canvas);
            canvas.restore();
            document.finishPage(page);
            page = null;
            canvas = null;
        }

        @SuppressWarnings("deprecation")
        StaticLayout layout(String value, int width) {
            return new StaticLayout(value, textPaint, width, Layout.Alignment.ALIGN_NORMAL, 1.12f, 0f, false);
        }

        void text(String value, float size, boolean bold) throws IOException {
            check.run();
            if (str(value).isEmpty()) return;
            if (value.length() > ExportRules.TEXT_LIMIT) throw new SizeLimitException("PDF text limit exceeded");
            textPaint.setTextSize(size);
            textPaint.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
            StaticLayout layout = layout(value, CONTENT_WIDTH);
            int first = 0;
            while (first < layout.getLineCount()) {
                check.run();
                if (page == null) newPage();
                int top = layout.getLineTop(first), last = first;
                while (last < layout.getLineCount() && layout.getLineBottom(last) - top <= BOTTOM - y) last++;
                if (last == first) {
                    if (y == MARGIN) throw new SizeLimitException("PDF line exceeds page height");
                    newPage();
                    continue;
                }
                int height = layout.getLineBottom(last - 1) - top;
                canvas.save();
                canvas.clipRect(MARGIN, y, WIDTH - MARGIN, y + height);
                canvas.translate(MARGIN, y - top);
                layout.draw(canvas);
                canvas.restore();
                y += height;
                first = last;
                if (first < layout.getLineCount()) newPage();
            }
        }

        void space(int pixels) throws IOException {
            check.run();
            if (page == null) newPage();
            y = Math.min(BOTTOM, y + pixels);
        }

        @Override public void add(ExportRecord record) throws IOException {
            validate(record);
            text(str(record.sender) + " · " + date(record.date), 11, true);
            text(record.metadata, 9, false);
            space(4);
            text(record.text, 11, false);
            for (ExportRecord.Attachment attachment : record.attachments) {
                check.run();
                space(6);
                text(str(attachment.label).isEmpty() ? label("attachment", "Attachment") : attachment.label, 10, true);
                text(str(attachment.status) + (attachment.bytes > 0 ? " (" + attachment.bytes + " B)" : ""), 9, false);
                if (attachment.preview && !str(attachment.relativePath).isEmpty()) {
                    if (!preview(attachment)) text(label("omitted", "Preview unavailable"), 9, false);
                }
            }
            space(14);
        }

        boolean preview(ExportRecord.Attachment attachment) throws IOException {
            File file = asset(attachment.relativePath);
            String mime = str(attachment.mime).toLowerCase(Locale.ROOT);
            if (!mime.equals("image/jpeg") && !mime.equals("image/png") && !mime.equals("image/webp") && !mime.equals("image/gif")) return false;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), options);
            check.run();
            if (options.outWidth <= 0 || options.outHeight <= 0) return false;
            options.inSampleSize = 1;
            while ((long) ((options.outWidth - 1) / options.inSampleSize + 1) * ((options.outHeight - 1) / options.inSampleSize + 1) > IMAGE_PIXELS) {
                if (options.inSampleSize >= 1 << 29) return false;
                options.inSampleSize *= 2;
            }
            long expected = (long) ((options.outWidth - 1) / options.inSampleSize + 1) * ((options.outHeight - 1) / options.inSampleSize + 1);
            if (expected > TOTAL_IMAGE_PIXELS - imagePixels) throw new SizeLimitException("PDF image budget exceeded");
            options.inJustDecodeBounds = false;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap bitmap = BitmapFactory.decodeFile(file.getPath(), options);
            if (bitmap == null) return false;
            try {
                check.run();
                long pixels = (long) bitmap.getWidth() * bitmap.getHeight();
                if (pixels > IMAGE_PIXELS || pixels > TOTAL_IMAGE_PIXELS - imagePixels) throw new SizeLimitException("PDF image budget exceeded");
                imagePixels += pixels;
                float scale = Math.min(1f, Math.min(CONTENT_WIDTH / (float) bitmap.getWidth(), 350f / bitmap.getHeight()));
                float width = bitmap.getWidth() * scale, height = bitmap.getHeight() * scale;
                if (page == null || height + 8 > BOTTOM - y) newPage();
                canvas.drawBitmap(bitmap, null, new RectF(MARGIN, y + 4, MARGIN + width, y + 4 + height), imagePaint);
                y += (int) Math.ceil(height) + 8;
                check.run();
                return true;
            } finally {
                bitmap.recycle();
            }
        }

        @Override public File finish() throws IOException {
            active();
            if (page == null && pages == 0) newPage();
            endPage();
            try (OutputStream output = output("result.pdf")) {
                check.run();
                document.writeTo(output);
                check.run();
            }
            finished = true;
            return new File(root, "result.pdf");
        }

        @Override public void close() {
            if (closed) return;
            closed = true;
            if (page != null) {
                try { document.finishPage(page); } catch (RuntimeException ignore) { }
                page = null;
            }
            try { document.close(); } catch (RuntimeException ignore) { }
        }
    }
}
