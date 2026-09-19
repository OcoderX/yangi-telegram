package org.telegram.ui.ayu.screenshot;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.core.content.FileProvider;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ChatMessageSharedResources;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.ChatMessageCell;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BulletinFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;

/**
 * AyuGram: builds one tall PNG (or a PDF) out of a range of chat messages.
 * <p>
 * Rendering notes:
 * <ul>
 *     <li>{@link ChatMessageCell} can only be measured and drawn on the UI thread, and it only
 *     loads its images while it is attached to a window. A 1x1, non-drawing host container is
 *     therefore added to the activity content view and one reusable cell lives inside it.</li>
 *     <li>All measuring/drawing runs in small time-boxed slices posted back to the UI thread so
 *     the chat stays responsive; the progress dialog can be cancelled at any point.</li>
 *     <li>A page bitmap is never taller than {@link #SLICE_PX} (except a single PNG which may go
 *     up to {@link #MAX_BITMAP_PX}); PNG compression and PDF page writing happen on
 *     {@link Utilities#globalQueue}.</li>
 * </ul>
 */
public class LongScreenshotBuilder {

    /** hard cap so that a huge multi-select cannot eat all the memory */
    public static final int MAX_MESSAGES = 500;

    private static final int MAX_BITMAP_PX = 8192;
    private static final int SLICE_PX = 4096;
    /** at most this many bytes for one destination bitmap */
    private static final long MAX_BITMAP_BYTES = 64L * 1024L * 1024L;
    /** how long a single UI-thread slice is allowed to run */
    private static final long SLICE_BUDGET_MS = 12;
    /** one retry interval while waiting for a photo to decode */
    private static final int WAIT_STEP_MS = 40;
    /** upper bound of the time the whole job may spend waiting for photos */
    private static final int MAX_TOTAL_WAIT_MS = 3000;

    private static final float A4_RATIO = 1.41421356f;

    // ---------------------------------------------------------------------------------------
    // entry points
    // ---------------------------------------------------------------------------------------

    /**
     * Entry point used by the multi-select action mode.
     *
     * @param fragment the chat the messages belong to
     * @param selected the selected messages, in any order
     */
    public static void start(ChatActivity fragment, ArrayList<MessageObject> selected) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        final ArrayList<MessageObject> list = normalize(selected);
        if (list.isEmpty()) {
            BulletinFactory.of(fragment)
                    .createSimpleBulletin(R.raw.error, getString(R.string.AyuScreenshotNothing))
                    .show();
            return;
        }
        final boolean trimmed = list.size() > MAX_MESSAGES;
        while (list.size() > MAX_MESSAGES) {
            list.remove(list.size() - 1);
        }
        if (trimmed) {
            BulletinFactory.of(fragment)
                    .createSimpleBulletin(R.raw.error, LocaleController.formatString(R.string.AyuScreenshotTooMany, MAX_MESSAGES))
                    .show();
        }
        final ScreenshotOptionsSheet sheet = new ScreenshotOptionsSheet(fragment, list.size(),
                () -> new LongScreenshotBuilder(fragment, list).run());
        fragment.showDialog(sheet);
    }

    /**
     * Entry point used by the per-message context menu: takes {@code from} and everything that was
     * loaded after it (i.e. the range from the tapped message down to the newest loaded message).
     */
    public static void startFromMessage(ChatActivity fragment, MessageObject from) {
        if (fragment == null || from == null) {
            return;
        }
        final ArrayList<MessageObject> range = new ArrayList<>();
        try {
            // ChatActivity.messages is ordered newest-first
            final ArrayList<MessageObject> loaded = fragment.messages;
            int index = -1;
            for (int a = 0; a < loaded.size(); a++) {
                final MessageObject object = loaded.get(a);
                if (object != null && object.getId() == from.getId()) {
                    index = a;
                    break;
                }
            }
            if (index < 0) {
                range.add(from);
            } else {
                for (int a = index; a >= 0; a--) {
                    range.add(loaded.get(a));
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
            range.clear();
            range.add(from);
        }
        start(fragment, range);
    }

    /** de-duplicates, drops service/date rows and sorts oldest-first */
    private static ArrayList<MessageObject> normalize(ArrayList<MessageObject> input) {
        final ArrayList<MessageObject> out = new ArrayList<>();
        if (input == null) {
            return out;
        }
        final HashSet<Integer> seen = new HashSet<>();
        for (int a = 0; a < input.size(); a++) {
            final MessageObject object = input.get(a);
            if (object == null || object.messageOwner == null) {
                continue;
            }
            if (object.isDateObject || object.type == MessageObject.TYPE_DATE) {
                continue;
            }
            if (object.isSponsored()) {
                continue;
            }
            if (!seen.add(object.getId())) {
                continue;
            }
            out.add(object);
        }
        Collections.sort(out, new Comparator<MessageObject>() {
            @Override
            public int compare(MessageObject o1, MessageObject o2) {
                final int d1 = o1.messageOwner.date;
                final int d2 = o2.messageOwner.date;
                if (d1 != d2) {
                    return d1 < d2 ? -1 : 1;
                }
                final int i1 = o1.getId();
                final int i2 = o2.getId();
                return Integer.compare(i1, i2);
            }
        });
        return out;
    }

    // ---------------------------------------------------------------------------------------
    // instance state
    // ---------------------------------------------------------------------------------------

    private final ChatActivity fragment;
    private final Context context;
    private final int currentAccount;
    private final Theme.ResourcesProvider resourcesProvider;
    private final ArrayList<MessageObject> messages;

    // snapshot of the options so a later settings change cannot corrupt a running render
    private final int format;
    private final int backgroundMode;
    private final int overflowMode;
    private final int pdfMode;
    private final boolean hideNames;
    private final boolean hideAvatars;
    private final boolean hideTimestamps;
    private final boolean includeHeader;
    private final boolean includeWatermark;
    private float scale;

    private HostLayout host;
    private RenderCell cell;
    private ImageReceiver avatarReceiver;
    private AvatarDrawable avatarDrawable;

    private final ArrayList<Element> elements = new ArrayList<>();
    private final ArrayList<Page> pages = new ArrayList<>();
    private final ArrayList<File> outFiles = new ArrayList<>();

    private int contentWidth;
    private int totalHeight;

    private AlertDialog progressDialog;
    private volatile boolean cancelled;
    private volatile boolean failed;
    private boolean released;

    private int measureIndex;
    private int pageIndex;
    private int drawIndex;
    /** the element the reusable cell is currently bound to, so it is not bound twice in a row */
    private MessageElement boundElement;
    /** total time already spent waiting for photos to arrive, across the whole job */
    private int totalWaitMs;
    private Bitmap pageBitmap;
    private Canvas pageCanvas;

    private PdfDocument pdfDocument;
    private File pdfFile;
    private File outDir;
    private String baseName;

    private final TextPaint datePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint subtitlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint chipPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmpRect = new RectF();

    private LongScreenshotBuilder(ChatActivity fragment, ArrayList<MessageObject> messages) {
        this.fragment = fragment;
        this.context = fragment.getParentActivity();
        this.currentAccount = fragment.getCurrentAccount();
        this.resourcesProvider = fragment.getResourceProvider();
        this.messages = messages;

        ScreenshotConfig.load();
        this.format = ScreenshotConfig.format;
        this.backgroundMode = ScreenshotConfig.background;
        this.overflowMode = ScreenshotConfig.overflowMode;
        this.pdfMode = ScreenshotConfig.pdfMode;
        this.hideNames = ScreenshotConfig.hideNames;
        this.hideAvatars = ScreenshotConfig.hideAvatars;
        this.hideTimestamps = ScreenshotConfig.hideTimestamps;
        this.includeHeader = ScreenshotConfig.includeHeader;
        this.includeWatermark = ScreenshotConfig.includeWatermark;
        this.scale = ScreenshotConfig.getScale();
    }

    // ---------------------------------------------------------------------------------------
    // pipeline
    // ---------------------------------------------------------------------------------------

    private void run() {
        if (context == null) {
            return;
        }
        try {
            contentWidth = fragment.getFragmentView() != null && fragment.getFragmentView().getWidth() > 0
                    ? fragment.getFragmentView().getWidth()
                    : AndroidUtilities.displaySize.x;
            if (contentWidth <= 0) {
                contentWidth = dp(360);
            }
            preparePaints();
            buildElements();
            if (elements.isEmpty()) {
                return;
            }
            if (!createHost()) {
                showError();
                return;
            }
            showProgress();
            AndroidUtilities.runOnUIThread(this::measureStep);
        } catch (Throwable t) {
            FileLog.e(t);
            release();
            showError();
        }
    }

    private void preparePaints() {
        final int serviceText = Theme.getColor(Theme.key_chat_serviceText, resourcesProvider);
        final int serviceBackground = Theme.getColor(Theme.key_chat_serviceBackground, resourcesProvider);

        datePaint.setTextSize(dp(13));
        datePaint.setTypeface(AndroidUtilities.bold());
        datePaint.setColor(serviceText);

        titlePaint.setTextSize(dp(16));
        titlePaint.setTypeface(AndroidUtilities.bold());
        titlePaint.setColor(serviceText);

        subtitlePaint.setTextSize(dp(13));
        subtitlePaint.setColor(serviceText);
        subtitlePaint.setAlpha(190);

        chipPaint.setColor(serviceBackground);
    }

    private void buildElements() {
        elements.clear();
        elements.add(new SpacerElement(dp(10)));
        if (includeHeader) {
            elements.add(new HeaderElement());
            elements.add(new SpacerElement(dp(4)));
        }

        String lastDateKey = null;
        for (int a = 0; a < messages.size(); a++) {
            final MessageObject message = messages.get(a);
            final String dateKey = message.dateKey;
            if (dateKey != null && !TextUtils.equals(dateKey, lastDateKey)) {
                lastDateKey = dateKey;
                elements.add(new DateElement(LocaleController.formatDateChat(message.messageOwner.date)));
            }
            final MessageObject.GroupedMessages group = fragment.getValidGroupedMessage(message);
            final MessageElement element = new MessageElement(message, group);
            element.pinnedTop = isPinned(a - 1, a);
            element.pinnedBottom = isPinned(a, a + 1);
            elements.add(element);
        }

        if (includeWatermark) {
            elements.add(new SpacerElement(dp(4)));
            elements.add(new FooterElement());
        }
        elements.add(new SpacerElement(dp(10)));
    }

    /** true when the message at {@code lower} and the message at {@code upper} form one bubble group */
    private boolean isPinned(int lower, int upper) {
        if (lower < 0 || upper < 0 || lower >= messages.size() || upper >= messages.size()) {
            return false;
        }
        final MessageObject a = messages.get(lower);
        final MessageObject b = messages.get(upper);
        if (a == null || b == null || a.messageOwner == null || b.messageOwner == null) {
            return false;
        }
        if (a.getGroupId() != 0 || b.getGroupId() != 0) {
            return false;
        }
        if (a.messageOwner.reply_markup instanceof TLRPC.TL_replyInlineMarkup
                || b.messageOwner.reply_markup instanceof TLRPC.TL_replyInlineMarkup) {
            return false;
        }
        if (a.isOutOwner() != b.isOutOwner()) {
            return false;
        }
        if (a.getSenderId() != b.getSenderId()) {
            return false;
        }
        if (!TextUtils.equals(a.dateKey, b.dateKey)) {
            return false;
        }
        return Math.abs(b.messageOwner.date - a.messageOwner.date) <= 5 * 60;
    }

    private boolean createHost() {
        try {
            final ViewGroup root = (ViewGroup) ((Activity) context).findViewById(android.R.id.content);
            if (root == null) {
                return false;
            }
            host = new HostLayout(context);
            root.addView(host, new FrameLayout.LayoutParams(1, 1));

            cell = new RenderCell(context, currentAccount, fragment.sharedResources, resourcesProvider);
            cell.hideAvatars = hideAvatars;
            cell.hideTimestamps = hideTimestamps;
            cell.setDelegate(new ChatMessageCell.ChatMessageCellDelegate() {
            });
            cell.setInvalidatesParent(false);
            cell.setFullyDraw(true);
            final TLRPC.Chat chat = fragment.getCurrentChat();
            final TLRPC.User user = fragment.getCurrentUser();
            cell.isChat = chat != null || UserObject.isUserSelf(user);
            cell.isBot = user != null && user.bot;
            cell.isMegagroup = ChatObject.isChannel(chat) && chat.megagroup;
            cell.isThreadChat = fragment.isThreadChat();
            host.addView(cell, new FrameLayout.LayoutParams(contentWidth, FrameLayout.LayoutParams.WRAP_CONTENT));
            cell.setParentViewSize(contentWidth, AndroidUtilities.displaySize.y);

            avatarDrawable = new AvatarDrawable();
            avatarReceiver = new ImageReceiver(host);
            avatarReceiver.setRoundRadius(dp(24));
            if (chat != null) {
                avatarDrawable.setInfo(currentAccount, chat);
                avatarReceiver.setForUserOrChat(chat, avatarDrawable);
            } else if (user != null) {
                avatarDrawable.setInfo(currentAccount, user);
                avatarReceiver.setForUserOrChat(user, avatarDrawable);
            }
            avatarReceiver.onAttachedToWindow();
            return true;
        } catch (Throwable t) {
            FileLog.e(t);
            return false;
        }
    }

    private void showProgress() {
        try {
            progressDialog = new AlertDialog(context, AlertDialog.ALERT_TYPE_LOADING, resourcesProvider);
            progressDialog.setMessage(getString(R.string.AyuScreenshotProgress));
            progressDialog.setCanceledOnTouchOutside(false);
            progressDialog.setCancelable(true);
            progressDialog.setOnCancelListener(dialog -> cancelled = true);
            progressDialog.setButton(AlertDialog.BUTTON_NEGATIVE, getString(R.string.Cancel), (dialog, which) -> {
                cancelled = true;
                dialog.dismiss();
            });
            progressDialog.show();
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    private void setProgress(int percent) {
        if (progressDialog == null) {
            return;
        }
        try {
            progressDialog.setProgress(Math.max(0, Math.min(100, percent)));
        } catch (Throwable ignore) {
        }
    }

    private boolean checkStopped() {
        if (cancelled) {
            release();
            return true;
        }
        if (failed) {
            release();
            showError();
            return true;
        }
        return false;
    }

    // ---------------- measure ----------------

    private void measureStep() {
        if (checkStopped()) {
            return;
        }
        try {
            final long start = SystemClock.elapsedRealtime();
            while (measureIndex < elements.size()) {
                elements.get(measureIndex).measure();
                measureIndex++;
                if (SystemClock.elapsedRealtime() - start > SLICE_BUDGET_MS) {
                    break;
                }
            }
        } catch (Throwable t) {
            FileLog.e(t);
            failed = true;
            checkStopped();
            return;
        }
        setProgress((int) (30f * measureIndex / Math.max(1, elements.size())));
        if (measureIndex < elements.size()) {
            AndroidUtilities.runOnUIThread(this::measureStep);
            return;
        }

        totalHeight = 0;
        for (int a = 0; a < elements.size(); a++) {
            final Element element = elements.get(a);
            element.y = totalHeight;
            totalHeight += element.height;
        }
        if (totalHeight <= 0) {
            failed = true;
            checkStopped();
            return;
        }
        try {
            computePages();
            prepareOutput();
        } catch (Throwable t) {
            FileLog.e(t);
            failed = true;
            checkStopped();
            return;
        }
        AndroidUtilities.runOnUIThread(this::renderStep);
    }

    private void computePages() {
        pages.clear();

        boolean fixedPageHeight = false;
        int maxPage;
        if (format == ScreenshotConfig.FORMAT_PDF) {
            if (pdfMode == ScreenshotConfig.PDF_A4) {
                maxPage = Math.max(dp(160), (int) (contentWidth * A4_RATIO));
                // an A4 page is fixed height, so lower the raster scale instead of slicing it
                scale = Math.min(scale, SLICE_PX / (float) maxPage);
                fixedPageHeight = true;
            } else {
                maxPage = (int) (SLICE_PX / scale);
            }
        } else {
            final long totalScaled = (long) Math.ceil(totalHeight * scale);
            final long bytes = (long) Math.ceil(contentWidth * scale) * totalScaled * 4L;
            if (totalScaled <= MAX_BITMAP_PX && bytes <= MAX_BITMAP_BYTES) {
                maxPage = totalHeight;
            } else if (overflowMode == ScreenshotConfig.OVERFLOW_DOWNSCALE) {
                // squeeze the whole conversation into one image by lowering the raster scale
                final float fit = MAX_BITMAP_PX / (float) totalHeight;
                scale = Math.min(scale, fit);
                final long width = (long) Math.max(1, Math.ceil(contentWidth * scale));
                if (width * (long) Math.ceil(totalHeight * scale) * 4L > MAX_BITMAP_BYTES) {
                    scale = Math.min(scale, 0.5f);
                }
                maxPage = totalHeight;
            } else {
                maxPage = (int) (SLICE_PX / scale);
            }
        }
        if (maxPage < dp(160)) {
            maxPage = dp(160);
        }

        int index = 0;
        while (index < elements.size()) {
            final Page page = new Page();
            page.from = index;
            page.yTop = elements.get(index).y;
            int used = 0;
            while (index < elements.size()) {
                final int h = elements.get(index).height;
                if (used > 0 && used + h > maxPage) {
                    break;
                }
                used += h;
                index++;
                if (used >= maxPage) {
                    break;
                }
            }
            final int cap = Math.max(1, (int) (MAX_BITMAP_PX / scale));
            page.to = index;
            page.contentHeight = used;
            page.height = fixedPageHeight ? maxPage : Math.min(used, cap);
            if (page.height <= 0) {
                page.height = 1;
            }
            pages.add(page);
        }
    }

    private void prepareOutput() throws Exception {
        File cacheDir = FileLoader.getDirectory(FileLoader.MEDIA_DIR_CACHE);
        if (cacheDir == null) {
            cacheDir = ApplicationLoader.applicationContext.getCacheDir();
        }
        outDir = new File(cacheDir, "screenshots");
        if (!outDir.exists()) {
            outDir.mkdirs();
        }
        baseName = "telegram_screenshot_" + System.currentTimeMillis();
        if (format == ScreenshotConfig.FORMAT_PDF) {
            pdfDocument = new PdfDocument();
            pdfFile = new File(outDir, baseName + ".pdf");
        }
    }

    // ---------------- render ----------------

    private void renderStep() {
        if (checkStopped()) {
            return;
        }
        try {
            if (pageIndex >= pages.size()) {
                finishOutput();
                return;
            }
            final Page page = pages.get(pageIndex);
            if (pageBitmap == null) {
                if (!allocatePage(page)) {
                    failed = true;
                    checkStopped();
                    return;
                }
                drawIndex = page.from;
            }
            final long start = SystemClock.elapsedRealtime();
            while (drawIndex < page.to) {
                final Element element = elements.get(drawIndex);
                if (element instanceof MessageElement && totalWaitMs < MAX_TOTAL_WAIT_MS) {
                    // give a photo that is still decoding a short chance to show up
                    if (!((MessageElement) element).prepare()) {
                        totalWaitMs += WAIT_STEP_MS;
                        AndroidUtilities.runOnUIThread(this::renderStep, WAIT_STEP_MS);
                        return;
                    }
                }
                pageCanvas.save();
                pageCanvas.translate(0, element.y - page.yTop);
                try {
                    element.draw(pageCanvas);
                } catch (Throwable t) {
                    FileLog.e(t);
                }
                pageCanvas.restore();
                drawIndex++;
                if (SystemClock.elapsedRealtime() - start > SLICE_BUDGET_MS) {
                    break;
                }
            }
            if (drawIndex >= page.to) {
                emitPage();
            } else {
                AndroidUtilities.runOnUIThread(this::renderStep);
            }
        } catch (Throwable t) {
            FileLog.e(t);
            failed = true;
            checkStopped();
        }
    }

    private boolean allocatePage(Page page) {
        try {
            final int width = Math.max(1, (int) Math.ceil(contentWidth * scale));
            final int height = Math.max(1, (int) Math.ceil(page.height * scale));
            pageBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            pageCanvas = new Canvas(pageBitmap);
            drawPageBackground(pageCanvas, width, height);
            pageCanvas.scale(scale, scale);
            return true;
        } catch (Throwable t) {
            FileLog.e(t);
            pageBitmap = null;
            pageCanvas = null;
            return false;
        }
    }

    private void drawPageBackground(Canvas canvas, int widthPx, int heightPx) {
        if (backgroundMode == ScreenshotConfig.BACKGROUND_LIGHT) {
            canvas.drawColor(0xFFFFFFFF);
            return;
        }
        if (backgroundMode == ScreenshotConfig.BACKGROUND_DARK) {
            canvas.drawColor(0xFF15181C);
            return;
        }
        Drawable wallpaper = null;
        try {
            wallpaper = Theme.getCachedWallpaper();
        } catch (Throwable ignore) {
        }
        if (wallpaper instanceof ColorDrawable) {
            canvas.drawColor(((ColorDrawable) wallpaper).getColor());
            return;
        }
        if (wallpaper instanceof BitmapDrawable) {
            final Bitmap bitmap = ((BitmapDrawable) wallpaper).getBitmap();
            if (bitmap != null && !bitmap.isRecycled()) {
                canvas.drawColor(Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider));
                final float ratio = Math.max(widthPx / (float) bitmap.getWidth(), heightPx / (float) bitmap.getHeight());
                final float w = bitmap.getWidth() * ratio;
                final float h = bitmap.getHeight() * ratio;
                tmpRect.set((widthPx - w) / 2f, (heightPx - h) / 2f, (widthPx + w) / 2f, (heightPx + h) / 2f);
                try {
                    canvas.drawBitmap(bitmap, null, tmpRect, null);
                    return;
                } catch (Throwable ignore) {
                }
            }
        }
        int color = 0;
        try {
            color = Theme.getColor(Theme.key_chat_wallpaper, resourcesProvider);
        } catch (Throwable ignore) {
        }
        if ((color & 0xFF000000) == 0) {
            color = Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider);
        }
        canvas.drawColor(color);
    }

    private void emitPage() {
        final Bitmap bitmap = pageBitmap;
        final int index = pageIndex;
        pageBitmap = null;
        pageCanvas = null;
        if (bitmap == null) {
            failed = true;
            checkStopped();
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            try {
                if (format == ScreenshotConfig.FORMAT_PDF) {
                    final PdfDocument.PageInfo info =
                            new PdfDocument.PageInfo.Builder(bitmap.getWidth(), bitmap.getHeight(), index + 1).create();
                    final PdfDocument.Page page = pdfDocument.startPage(info);
                    page.getCanvas().drawBitmap(bitmap, 0, 0, null);
                    pdfDocument.finishPage(page);
                } else {
                    final String name = pages.size() > 1
                            ? baseName + "_" + (index + 1) + ".png"
                            : baseName + ".png";
                    final File file = new File(outDir, name);
                    final FileOutputStream stream = new FileOutputStream(file);
                    try {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
                    } finally {
                        try {
                            stream.close();
                        } catch (Exception ignore) {
                        }
                    }
                    outFiles.add(file);
                }
            } catch (Throwable t) {
                FileLog.e(t);
                failed = true;
            }
            try {
                bitmap.recycle();
            } catch (Throwable ignore) {
            }
            AndroidUtilities.runOnUIThread(() -> {
                pageIndex++;
                setProgress(30 + (int) (65f * pageIndex / Math.max(1, pages.size())));
                renderStep();
            });
        });
    }

    private void finishOutput() {
        if (format != ScreenshotConfig.FORMAT_PDF) {
            onOutputReady();
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            try {
                final FileOutputStream stream = new FileOutputStream(pdfFile);
                try {
                    pdfDocument.writeTo(stream);
                } finally {
                    try {
                        stream.close();
                    } catch (Exception ignore) {
                    }
                }
                outFiles.add(pdfFile);
            } catch (Throwable t) {
                FileLog.e(t);
                failed = true;
            }
            try {
                pdfDocument.close();
            } catch (Throwable ignore) {
            }
            pdfDocument = null;
            AndroidUtilities.runOnUIThread(this::onOutputReady);
        });
    }

    private void onOutputReady() {
        release();
        if (cancelled) {
            return;
        }
        if (failed || outFiles.isEmpty()) {
            showError();
            return;
        }
        final boolean pdf = format == ScreenshotConfig.FORMAT_PDF;
        final String mime = pdf ? "application/pdf" : "image/png";
        for (int a = 0; a < outFiles.size(); a++) {
            final File file = outFiles.get(a);
            try {
                MediaController.saveFile(file.getAbsolutePath(), context, pdf ? 2 : 0, file.getName(), mime, null, false);
            } catch (Throwable t) {
                FileLog.e(t);
            }
        }
        final CharSequence text;
        if (pdf) {
            text = getString(R.string.AyuScreenshotSavedPdf);
        } else if (outFiles.size() > 1) {
            text = LocaleController.formatString(R.string.AyuScreenshotSavedFiles, outFiles.size());
        } else {
            text = getString(R.string.AyuScreenshotSaved);
        }
        try {
            BulletinFactory.of(fragment)
                    .createSimpleBulletin(R.raw.ic_save_to_gallery, text, getString(R.string.AyuScreenshotShare), this::share)
                    .show();
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    private void share() {
        try {
            final Activity activity = fragment.getParentActivity();
            if (activity == null || outFiles.isEmpty()) {
                return;
            }
            final boolean pdf = format == ScreenshotConfig.FORMAT_PDF;
            final String mime = pdf ? "application/pdf" : "image/png";
            final ArrayList<Uri> uris = new ArrayList<>();
            for (int a = 0; a < outFiles.size(); a++) {
                final Uri uri = uriFor(activity, outFiles.get(a));
                if (uri != null) {
                    uris.add(uri);
                }
            }
            if (uris.isEmpty()) {
                return;
            }
            final Intent intent;
            if (uris.size() == 1) {
                intent = new Intent(Intent.ACTION_SEND);
                intent.putExtra(Intent.EXTRA_STREAM, uris.get(0));
            } else {
                intent = new Intent(Intent.ACTION_SEND_MULTIPLE);
                intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
            }
            intent.setType(mime);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(Intent.createChooser(intent, getString(R.string.AyuScreenshotShare)));
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    private static Uri uriFor(Activity activity, File file) {
        try {
            if (Build.VERSION.SDK_INT >= 24) {
                return FileProvider.getUriForFile(activity, ApplicationLoader.getApplicationId() + ".provider", file);
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
        try {
            return Uri.fromFile(file);
        } catch (Throwable t) {
            FileLog.e(t);
        }
        return null;
    }

    private void showError() {
        try {
            BulletinFactory.of(fragment)
                    .createSimpleBulletin(R.raw.error, getString(R.string.AyuScreenshotError))
                    .show();
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    private void release() {
        if (released) {
            return;
        }
        released = true;
        try {
            if (progressDialog != null) {
                progressDialog.dismiss();
                progressDialog = null;
            }
        } catch (Throwable ignore) {
        }
        try {
            if (pageBitmap != null) {
                pageBitmap.recycle();
            }
        } catch (Throwable ignore) {
        }
        pageBitmap = null;
        pageCanvas = null;
        try {
            if (avatarReceiver != null) {
                avatarReceiver.onDetachedFromWindow();
                avatarReceiver = null;
            }
        } catch (Throwable ignore) {
        }
        try {
            if (host != null) {
                if (cell != null) {
                    host.removeView(cell);
                }
                if (host.getParent() instanceof ViewGroup) {
                    ((ViewGroup) host.getParent()).removeView(host);
                }
                host = null;
            }
        } catch (Throwable ignore) {
        }
        cell = null;
        boundElement = null;
        try {
            if (pdfDocument != null) {
                pdfDocument.close();
                pdfDocument = null;
            }
        } catch (Throwable ignore) {
        }
    }

    // ---------------------------------------------------------------------------------------
    // elements
    // ---------------------------------------------------------------------------------------

    private static class Page {
        int from;
        int to;
        int yTop;
        int height;
        int contentHeight;
    }

    private abstract class Element {
        int height;
        int y;

        abstract void measure();

        abstract void draw(Canvas canvas);
    }

    private class SpacerElement extends Element {
        SpacerElement(int height) {
            this.height = height;
        }

        @Override
        void measure() {
        }

        @Override
        void draw(Canvas canvas) {
        }
    }

    private class DateElement extends Element {
        private final String text;

        DateElement(String text) {
            this.text = text == null ? "" : text;
        }

        @Override
        void measure() {
            height = dp(34);
        }

        @Override
        void draw(Canvas canvas) {
            final float textWidth = datePaint.measureText(text);
            final float chipWidth = textWidth + dp(20);
            final float chipHeight = dp(22);
            final float left = (contentWidth - chipWidth) / 2f;
            final float top = (height - chipHeight) / 2f;
            tmpRect.set(left, top, left + chipWidth, top + chipHeight);
            canvas.drawRoundRect(tmpRect, chipHeight / 2f, chipHeight / 2f, chipPaint);
            final Paint.FontMetrics fm = datePaint.getFontMetrics();
            final float baseline = top + chipHeight / 2f - (fm.ascent + fm.descent) / 2f;
            canvas.drawText(text, left + dp(10), baseline, datePaint);
        }
    }

    private class FooterElement extends Element {
        @Override
        void measure() {
            height = dp(34);
        }

        @Override
        void draw(Canvas canvas) {
            final String text = getString(R.string.AyuScreenshotWatermark);
            final float textWidth = datePaint.measureText(text);
            final float chipWidth = textWidth + dp(20);
            final float chipHeight = dp(22);
            final float left = (contentWidth - chipWidth) / 2f;
            final float top = (height - chipHeight) / 2f;
            tmpRect.set(left, top, left + chipWidth, top + chipHeight);
            canvas.drawRoundRect(tmpRect, chipHeight / 2f, chipHeight / 2f, chipPaint);
            final Paint.FontMetrics fm = datePaint.getFontMetrics();
            final float baseline = top + chipHeight / 2f - (fm.ascent + fm.descent) / 2f;
            canvas.drawText(text, left + dp(10), baseline, datePaint);
        }
    }

    private class HeaderElement extends Element {
        private String title;
        private String subtitle;

        @Override
        void measure() {
            height = dp(76);
            final TLRPC.Chat chat = fragment.getCurrentChat();
            final TLRPC.User user = fragment.getCurrentUser();
            if (chat != null) {
                title = chat.title;
            } else if (user != null) {
                title = UserObject.getUserName(user);
            }
            if (title == null) {
                title = "";
            }
            final StringBuilder builder = new StringBuilder();
            builder.append(LocaleController.formatString(R.string.AyuScreenshotMessages, messages.size()));
            if (!messages.isEmpty()) {
                final String first = LocaleController.formatDateChat(messages.get(0).messageOwner.date);
                final String last = LocaleController.formatDateChat(messages.get(messages.size() - 1).messageOwner.date);
                builder.append(" · ");
                if (TextUtils.equals(first, last)) {
                    builder.append(first);
                } else {
                    builder.append(first).append(" – ").append(last);
                }
            }
            subtitle = builder.toString();
        }

        @Override
        void draw(Canvas canvas) {
            final float inset = dp(10);
            tmpRect.set(inset, dp(2), contentWidth - inset, height - dp(2));
            canvas.drawRoundRect(tmpRect, dp(14), dp(14), chipPaint);

            final float avatarSize = dp(48);
            final float avatarX = inset + dp(10);
            final float avatarY = (height - avatarSize) / 2f;
            try {
                if (avatarReceiver != null) {
                    avatarReceiver.setImageCoords(avatarX, avatarY, avatarSize, avatarSize);
                    avatarReceiver.setRoundRadius((int) (avatarSize / 2f));
                    avatarReceiver.draw(canvas);
                } else if (avatarDrawable != null) {
                    avatarDrawable.setBounds((int) avatarX, (int) avatarY, (int) (avatarX + avatarSize), (int) (avatarY + avatarSize));
                    avatarDrawable.draw(canvas);
                }
            } catch (Throwable ignore) {
            }

            final float textLeft = avatarX + avatarSize + dp(12);
            final float textRight = contentWidth - inset - dp(10);
            final float maxWidth = Math.max(dp(40), textRight - textLeft);

            final CharSequence titleText = TextUtils.ellipsize(title, titlePaint, maxWidth, TextUtils.TruncateAt.END);
            final CharSequence subtitleText = TextUtils.ellipsize(subtitle, subtitlePaint, maxWidth, TextUtils.TruncateAt.END);
            canvas.drawText(titleText, 0, titleText.length(), textLeft, height / 2f - dp(3), titlePaint);
            canvas.drawText(subtitleText, 0, subtitleText.length(), textLeft, height / 2f + dp(17), subtitlePaint);
        }
    }

    private class MessageElement extends Element {
        private final MessageObject message;
        private final MessageObject.GroupedMessages group;
        boolean pinnedTop;
        boolean pinnedBottom;

        MessageElement(MessageObject message, MessageObject.GroupedMessages group) {
            this.message = message;
            this.group = group;
        }

        private void bind() {
            if (boundElement == this) {
                return;
            }
            final String savedName = message.customName;
            if (hideNames) {
                message.customName = getString(R.string.AyuScreenshotHiddenName);
            }
            try {
                cell.setMessageObject(message, group, pinnedBottom, pinnedTop, false);
            } finally {
                if (hideNames) {
                    message.customName = savedName;
                }
            }
            // force a fresh measure pass even when the previous cell content had the same specs
            cell.requestLayout();
            cell.measure(
                    View.MeasureSpec.makeMeasureSpec(contentWidth, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            );
            cell.layout(0, 0, cell.getMeasuredWidth(), cell.getMeasuredHeight());
            boundElement = this;
        }

        /** binds the cell and reports whether its photo (if any) already has a bitmap */
        boolean prepare() {
            bind();
            try {
                final ImageReceiver photo = cell.getPhotoImage();
                return photo == null || !photo.hasImageSet() || photo.hasBitmapImage();
            } catch (Throwable t) {
                return true;
            }
        }

        @Override
        void measure() {
            bind();
            height = Math.max(1, cell.getMeasuredHeight());
        }

        @Override
        void draw(Canvas canvas) {
            bind();
            cell.drawingToBitmap = true;
            try {
                cell.draw(canvas);
                final MessageObject.GroupedMessagePosition position = cell.getCurrentPosition();
                if (position != null) {
                    // grouped media: the list normally draws these on top of the whole album
                    canvas.save();
                    canvas.translate(0, cell.getPaddingTop());
                    final boolean selectionOnly = (position.flags & MessageObject.POSITION_FLAG_LEFT) == 0;
                    if (position.last) {
                        cell.drawTime(canvas, 1f, true);
                    }
                    if (position.minX == 0 && position.minY == 0 && cell.hasNameLayout()) {
                        cell.drawNamesLayout(canvas, 1f);
                    }
                    if ((position.flags & cell.captionFlag()) != 0) {
                        cell.drawCaptionLayout(canvas, selectionOnly, 1f);
                    }
                    if (!selectionOnly
                            && (position.flags & MessageObject.POSITION_FLAG_BOTTOM) != 0
                            && (position.flags & MessageObject.POSITION_FLAG_LEFT) != 0) {
                        cell.drawReactionsLayout(canvas, 1f, null);
                        cell.drawCommentLayout(canvas, 1f);
                    }
                    canvas.restore();
                }
            } finally {
                cell.drawingToBitmap = false;
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // offscreen plumbing
    // ---------------------------------------------------------------------------------------

    /**
     * 1x1 container that never lays out or draws its children. It only exists so that the
     * {@link ChatMessageCell} inside it is really attached to a window (otherwise its
     * {@link ImageReceiver}s refuse to load anything).
     */
    private static class HostLayout extends FrameLayout {
        HostLayout(Context context) {
            super(context);
            setWillNotDraw(true);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            setMeasuredDimension(1, 1);
        }

        @Override
        protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {
        }

        /**
         * Swallowed on purpose: the cell still marks itself as needing a re-measure (which
         * {@link View#measure} requires), but the activity is never asked to re-layout.
         */
        @Override
        public void requestLayout() {
        }
    }

    private static class RenderCell extends ChatMessageCell {
        boolean hideAvatars;
        boolean hideTimestamps;

        RenderCell(Context context, int currentAccount, ChatMessageSharedResources sharedResources, Theme.ResourcesProvider resourcesProvider) {
            super(context, currentAccount, false, sharedResources, resourcesProvider);
        }

        @Override
        public boolean needDrawAvatar() {
            return !hideAvatars && super.needDrawAvatar();
        }

        @Override
        public void drawTime(Canvas canvas, float alpha, boolean fromParent) {
            if (hideTimestamps) {
                return;
            }
            super.drawTime(canvas, alpha, fromParent);
        }

        @Override
        public void invalidate() {
        }

        @Override
        public void invalidate(int l, int t, int r, int b) {
        }

        @Override
        public void invalidate(Rect dirty) {
        }
    }
}
