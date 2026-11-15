package org.telegram.ui.ayu.chatexport;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;
import android.app.DatePickerDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.ayu.chatexport.ChatHistoryExporter;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.CheckBoxCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.SeekBarView;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.text.DateFormat;
import java.util.Calendar;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns one chat export and its system document-picker result. No storage work runs on the UI thread. */
public final class ChatExportController {
    public static final int SAVE_REQUEST = 7091;
    private static final DispatchQueue SAVE_QUEUE = new DispatchQueue("chatExportSave");
    private final BaseFragment fragment;
    private final ChatHistoryExporter.Request request;
    private final ChatHistoryExporter.Options options = new ChatHistoryExporter.Options();
    private ChatHistoryExporter.Handle handle;
    private AlertDialog progress;
    private BottomSheet settings;
    private File artifact;
    private String mime;
    private int messages, skipped;
    private boolean destroyed, saving, awaitingPicker;
    private int generation;
    private AtomicBoolean saveCancelled;

    public ChatExportController(BaseFragment fragment, ChatHistoryExporter.Request request) {
        this.fragment = fragment;
        this.request = request;
        options.format = ChatHistoryExporter.Format.HTML;
        options.labels = new HashMap<>();
        String[] names = {"chatInfo", "date", "sender", "messages", "attachment", "unavailable", "omitted", "page", "edited", "reply", "forwarded", "service", "unsupported", "from", "to", "timezone", "scope", "exported", "sizeLimit", "downloadFailed", "notSelected", "pdfDescription", "photo"};
        int[] labels = {R.string.OxExportLabelChatInfo, R.string.OxExportLabelDate, R.string.OxExportLabelSender, R.string.OxExportLabelMessages,
                R.string.OxExportLabelAttachment, R.string.OxExportLabelUnavailable, R.string.OxExportLabelOmitted, R.string.OxExportLabelPage,
                R.string.OxExportLabelEdited, R.string.OxExportLabelReply, R.string.OxExportLabelForwarded, R.string.OxExportLabelService,
                R.string.OxExportLabelUnsupported, R.string.OxExportLabelFrom, R.string.OxExportLabelTo, R.string.OxExportLabelTimezone,
                R.string.OxExportLabelScope, R.string.OxExportLabelExported, R.string.OxExportLabelSizeLimit, R.string.OxExportLabelDownloadFailed,
                R.string.OxExportLabelNotSelected, R.string.OxExportLabelPdfDescription, R.string.OxExportPhotos};
        for (int i = 0; i < names.length; i++) options.labels.put(names[i], getString(labels[i]));
        options.labels.put("scopeChat", getString(R.string.OxExportScopeChat));
        options.labels.put("scopeSaved", getString(R.string.OxExportScopeSaved));
        options.labels.put("scopeMonoforum", getString(R.string.OxExportScopeMonoforum));
    }

    private boolean active() {
        return !destroyed && fragment.getParentActivity() != null
                && UserConfig.getInstance(request.account).getClientUserId() == request.ownerId;
    }

    public boolean matches(int account, long dialogId, long topicId, ChatHistoryExporter.Scope scope) {
        return !destroyed && request.account == account && request.dialogId == dialogId
                && request.topicId == topicId && request.scope == scope && sameOwner();
    }

    public void show() {
        if (!active()) return;
        if (UserConfig.getInstance(request.account).getCurrentUser() != null
                && UserConfig.getInstance(request.account).getCurrentUser().bot) {
            alert(getString(R.string.OxExportBotUnsupported));
            return;
        }
        if (artifact != null) { showCompleted(); return; }
        if (handle != null || saving || awaitingPicker) return;
        Context context = fragment.getParentActivity();
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.addView(label(getString(R.string.OxExportInfo), false), row(0, 8));
        content.addView(label(getString(R.string.OxExportFormat), true), row(0, 8));
        CheckBoxCell html = check(getString(R.string.OxExportHtml), options.format == ChatHistoryExporter.Format.HTML);
        CheckBoxCell pdf = check(getString(R.string.OxExportPdf), options.format == ChatHistoryExporter.Format.PDF);
        html.setOnClickListener(v -> { options.format = ChatHistoryExporter.Format.HTML; html.setChecked(true, true); pdf.setChecked(false, true); });
        pdf.setOnClickListener(v -> { options.format = ChatHistoryExporter.Format.PDF; pdf.setChecked(true, true); html.setChecked(false, true); });
        content.addView(html, row(48, 0));
        content.addView(pdf, row(48, 0));
        content.addView(label(getString(R.string.OxExportFormatInfo), false), row(0, 8));
        content.addView(label(getString(R.string.OxExportMedia), true), row(0, 8));
        addMedia(content, R.string.OxExportPhotos, 0, options.photos);
        addMedia(content, R.string.OxExportVideos, 1, options.videos);
        addMedia(content, R.string.OxExportVoice, 2, options.voiceMessages);
        addMedia(content, R.string.OxExportVideoMessages, 3, options.videoMessages);
        addMedia(content, R.string.OxExportStickers, 4, options.stickers);
        addMedia(content, R.string.OxExportGifs, 5, options.gifs);
        addMedia(content, R.string.OxExportFiles, 6, options.files);
        TextView limit = label(sizeText(), true);
        content.addView(limit, row(0, 8));
        SeekBarView seek = new SeekBarView(context, true, fragment.getResourceProvider());
        seek.setReportChanges(true);
        seek.setProgress((options.maxMediaBytes / (1024f * 1024f) - 1) / 99f);
        seek.setDelegate(new SeekBarView.SeekBarViewDelegate() {
            @Override public void onSeekBarDrag(boolean stop, float value) {
                options.maxMediaBytes = ChatExportPolicy.mediaMegabytes(value) * 1024L * 1024L;
                limit.setText(sizeText());
            }
            @Override public CharSequence getContentDescription() { return sizeText(); }
            @Override public int getStepsCount() { return 100; }
        });
        content.addView(seek, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44, 20, 0, 20, 0));
        content.addView(label(getString(R.string.OxExportSizeInfo), false), row(0, 8));
        content.addView(label(getString(R.string.OxExportDates), true), row(0, 8));
        TextView from = dateRow(true), to = dateRow(false);
        content.addView(from, row(48, 0));
        content.addView(to, row(48, 0));
        TextView reset = button(getString(R.string.OxExportAllDates));
        reset.setOnClickListener(v -> { options.fromDate = options.toDate = 0; updateDate(from, true); updateDate(to, false); });
        content.addView(reset, row(48, 0));
        content.addView(label(getString(R.string.OxExportDateInfo), false), row(0, 8));
        TextView start = button(getString(R.string.OxExportStart));
        start.setTypeface(AndroidUtilities.bold());
        start.setOnClickListener(v -> {
            if (!ChatExportPolicy.validRange(options.fromDate, options.toDate)) { alert(getString(R.string.OxExportInvalidDates)); return; }
            settings.dismiss();
            startExport();
        });
        content.addView(start, row(52, 8));
        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(false);
        scroll.addView(content);
        settings = new BottomSheet.Builder(context, false, fragment.getResourceProvider())
                .setTitle(getString(R.string.OxExportTitle), true).setCustomView(scroll).create();
        fragment.showDialog(settings);
    }

    private LinearLayout.LayoutParams row(int height, int bottom) {
        return LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, height == 0 ? LayoutHelper.WRAP_CONTENT : height, 0, 0, 0, bottom);
    }

    private TextView label(String value, boolean header) {
        TextView view = new TextView(fragment.getParentActivity());
        view.setText(value);
        view.setTextSize(header ? 15 : 13);
        view.setTextColor(Theme.getColor(header ? Theme.key_windowBackgroundWhiteBlueHeader : Theme.key_windowBackgroundWhiteGrayText, fragment.getResourceProvider()));
        view.setPadding(dp(22), dp(8), dp(22), dp(4));
        if (header) view.setTypeface(AndroidUtilities.bold());
        return view;
    }

    private TextView button(String value) {
        TextView view = label(value, false);
        view.setTextSize(15);
        view.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText, fragment.getResourceProvider()));
        view.setGravity(Gravity.CENTER_VERTICAL | (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT));
        view.setBackground(Theme.getSelectorDrawable(false));
        view.setFocusable(true);
        return view;
    }

    private CheckBoxCell check(String value, boolean checked) {
        CheckBoxCell cell = new CheckBoxCell(fragment.getParentActivity(), CheckBoxCell.TYPE_CHECK_BOX_ROUND, 21, fragment.getResourceProvider());
        cell.setText(value, "", checked, false);
        cell.setBackground(Theme.getSelectorDrawable(false));
        return cell;
    }

    private void addMedia(LinearLayout content, int label, int type, boolean value) {
        CheckBoxCell cell = check(getString(label), value);
        cell.setOnClickListener(v -> {
            boolean selected = !cell.isChecked();
            cell.setChecked(selected, true);
            switch (type) {
                case 0: options.photos = selected; break;
                case 1: options.videos = selected; break;
                case 2: options.voiceMessages = selected; break;
                case 3: options.videoMessages = selected; break;
                case 4: options.stickers = selected; break;
                case 5: options.gifs = selected; break;
                case 6: options.files = selected; break;
            }
        });
        content.addView(cell, row(48, 0));
    }

    private String sizeText() {
        return LocaleController.formatString(R.string.OxExportSizeLimit, options.maxMediaBytes / (1024 * 1024));
    }

    private TextView dateRow(boolean start) {
        TextView view = button("");
        updateDate(view, start);
        view.setOnClickListener(v -> {
            if (!active()) return;
            Calendar initial = Calendar.getInstance();
            int seconds = start ? options.fromDate : options.toDate;
            if (seconds != 0) initial.setTimeInMillis(seconds * 1000L);
            DatePickerDialog picker = new DatePickerDialog(fragment.getParentActivity(), (date, year, month, day) -> {
                Calendar selected = Calendar.getInstance();
                selected.set(year, month, day);
                if (start) options.fromDate = ChatExportPolicy.dayBoundary(selected, false);
                else options.toDate = ChatExportPolicy.dayBoundary(selected, true);
                updateDate(view, start);
            }, initial.get(Calendar.YEAR), initial.get(Calendar.MONTH), initial.get(Calendar.DAY_OF_MONTH));
            picker.getDatePicker().setMinDate(1);
            picker.getDatePicker().setMaxDate(Math.min(System.currentTimeMillis(), Integer.MAX_VALUE * 1000L));
            picker.show();
        });
        return view;
    }

    private void updateDate(TextView view, boolean start) {
        int seconds = start ? options.fromDate : options.toDate;
        String date = seconds == 0 ? getString(R.string.OxExportAnyDate) : DateFormat.getDateInstance(DateFormat.MEDIUM).format(new java.util.Date(seconds * 1000L));
        view.setText(LocaleController.formatString(start ? R.string.OxExportFromDate : R.string.OxExportToDate, date));
    }

    private void startExport() {
        if (!active()) return;
        int token = ++generation;
        showProgress(getString(R.string.OxExportPreparing), this::cancelExport);
        handle = ChatHistoryExporter.start(request, options, new ChatHistoryExporter.Listener() {
            @Override public void onProgress(int count, int media) {
                if (active() && token == generation && progress != null) progress.setMessage(LocaleController.formatString(R.string.OxExportProgress, count, media));
            }
            @Override public void onComplete(File file, String type, int count, int omitted) {
                if (token != generation) { delete(file); return; }
                handle = null;
                dismissProgress();
                if (!active()) { delete(file); return; }
                artifact = file; mime = type; messages = count; skipped = omitted;
                showCompleted();
            }
            @Override public void onError(ChatHistoryExporter.Error error) {
                if (token != generation) return;
                handle = null;
                dismissProgress();
                if (!active()) return;
                if (error == ChatHistoryExporter.Error.CANCELLED) return;
                AlertDialog.Builder dialog = new AlertDialog.Builder(fragment.getParentActivity(), fragment.getResourceProvider())
                        .setTitle(getString(R.string.OxExportTitle)).setMessage(getString(errorText(error)));
                if (error == ChatHistoryExporter.Error.SIZE_LIMIT || error == ChatHistoryExporter.Error.INVALID_OPTIONS) {
                    dialog.setPositiveButton(getString(R.string.OxExportChangeOptions), (d, w) -> show());
                    dialog.setNegativeButton(getString(R.string.Close), null);
                } else if (error == ChatHistoryExporter.Error.UNSUPPORTED_CHAT || error == ChatHistoryExporter.Error.ACCOUNT_CHANGED) {
                    dialog.setPositiveButton(getString(R.string.OK), null);
                } else {
                    dialog.setPositiveButton(getString(R.string.Retry), (d, w) -> startExport());
                    dialog.setNegativeButton(getString(R.string.Close), null);
                }
                fragment.showDialog(dialog.create());
            }
        });
    }

    private int errorText(ChatHistoryExporter.Error error) {
        switch (error) {
            case ACCOUNT_CHANGED: return R.string.OxExportAccountChanged;
            case UNSUPPORTED_CHAT: return UserConfig.getInstance(request.account).getCurrentUser() != null
                    && UserConfig.getInstance(request.account).getCurrentUser().bot ? R.string.OxExportBotUnsupported : R.string.OxExportUnsupported;
            case BUSY: return R.string.OxExportBusy;
            case INVALID_OPTIONS: return R.string.OxExportInvalidDates;
            case RATE_LIMIT: return R.string.OxExportRateLimit;
            case TIMEOUT: return R.string.OxExportTimeout;
            case STORAGE: return R.string.OxExportStorageError;
            case SIZE_LIMIT: return R.string.OxExportTooLarge;
            default: return R.string.OxExportNetworkError;
        }
    }

    private void showProgress(String message, Runnable cancel) {
        progress = new AlertDialog(fragment.getParentActivity(), AlertDialog.ALERT_TYPE_LOADING, fragment.getResourceProvider());
        progress.setTitle(getString(R.string.OxExportTitle));
        progress.setMessage(message);
        progress.setCanceledOnTouchOutside(false);
        progress.setCancelable(true);
        progress.setOnCancelListener(d -> cancel.run());
        progress.setButton(AlertDialog.BUTTON_NEGATIVE, getString(R.string.Cancel), (d, w) -> cancel.run());
        fragment.showDialog(progress);
    }

    private void dismissProgress() {
        if (progress != null) { progress.dismiss(); progress = null; }
    }

    private void cancelExport() {
        generation++;
        if (handle != null) { handle.cancel(); handle = null; }
        dismissProgress();
    }

    private void showCompleted() {
        if (!active() || artifact == null || awaitingPicker || saving) return;
        fragment.showDialog(new AlertDialog.Builder(fragment.getParentActivity(), fragment.getResourceProvider())
                .setTitle(getString(R.string.OxExportReady))
                .setMessage(LocaleController.formatString(R.string.OxExportSummary, messages, skipped))
                .setPositiveButton(getString(R.string.OxExportSaveAs), (d, w) -> chooseDestination())
                .setNegativeButton(getString(R.string.Close), null).create());
    }

    private void chooseDestination() {
        if (!active() || artifact == null) return;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(mime);
        intent.putExtra(Intent.EXTRA_TITLE, artifact.getName());
        try {
            awaitingPicker = true;
            fragment.startActivityForResult(intent, SAVE_REQUEST);
        } catch (Exception e) {
            awaitingPicker = false;
            alert(getString(R.string.OxExportPickerError));
        }
    }

    public boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != SAVE_REQUEST) return false;
        if (!awaitingPicker) return true;
        awaitingPicker = false;
        if (!active()) return true;
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) { showCompleted(); return true; }
        if (artifact != null && !saving) save(data.getData());
        return true;
    }

    private void save(Uri destination) {
        final File source = artifact;
        final AtomicBoolean cancelled = saveCancelled = new AtomicBoolean();
        saving = true;
        showProgress(getString(R.string.OxExportSaving), () -> cancelled.set(true));
        SAVE_QUEUE.postRunnable(() -> {
            boolean success = false;
            try (FileInputStream input = new FileInputStream(source);
                 OutputStream output = ApplicationLoader.applicationContext.getContentResolver().openOutputStream(destination, "w")) {
                if (output == null) throw new java.io.IOException("No output stream");
                byte[] buffer = new byte[65536];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (cancelled.get() || !sameOwner()) throw new java.io.InterruptedIOException();
                    output.write(buffer, 0, read);
                }
                output.flush();
                success = !cancelled.get() && sameOwner();
            } catch (Exception ignored) { success = false; }
            final boolean saved = success && !cancelled.get() && sameOwner();
            if (!saved) {
                try { DocumentsContract.deleteDocument(ApplicationLoader.applicationContext.getContentResolver(), destination); } catch (Exception ignored) { }
            }
            AndroidUtilities.runOnUIThread(() -> {
                saving = false;
                saveCancelled = null;
                dismissProgress();
                boolean committed = saved && !cancelled.get() && sameOwner();
                if (saved && !committed) SAVE_QUEUE.postRunnable(() -> {
                    try { DocumentsContract.deleteDocument(ApplicationLoader.applicationContext.getContentResolver(), destination); } catch (Exception ignored) { }
                });
                if (!active()) { delete(source); return; }
                if (committed) {
                    artifact = null;
                    delete(source);
                    alert(getString(R.string.OxExportSaved));
                } else if (cancelled.get()) showCompleted();
                else fragment.showDialog(new AlertDialog.Builder(fragment.getParentActivity(), fragment.getResourceProvider())
                        .setTitle(getString(R.string.OxExportTitle)).setMessage(getString(R.string.OxExportSaveError))
                        .setPositiveButton(getString(R.string.Retry), (d, w) -> chooseDestination())
                        .setNegativeButton(getString(R.string.Close), null).create());
            });
        });
    }

    private boolean sameOwner() { return UserConfig.getInstance(request.account).getClientUserId() == request.ownerId; }

    private void alert(String message) {
        if (active()) fragment.showDialog(new AlertDialog.Builder(fragment.getParentActivity(), fragment.getResourceProvider())
                .setTitle(getString(R.string.OxExportTitle)).setMessage(message).setPositiveButton(getString(R.string.OK), null).create());
    }

    private static void delete(File file) { if (file != null) SAVE_QUEUE.postRunnable(file::delete); }

    public void destroy() {
        destroyed = true;
        cancelExport();
        if (settings != null) { settings.dismiss(); settings = null; }
        if (saveCancelled != null) saveCancelled.set(true);
        if (!saving) delete(artifact);
        artifact = null;
    }
}
