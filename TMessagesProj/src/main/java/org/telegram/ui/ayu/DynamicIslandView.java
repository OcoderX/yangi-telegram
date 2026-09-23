package org.telegram.ui.ayu;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import androidx.core.content.ContextCompat;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.DownloadController;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotchInfoUtils;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.ayu.AyuConfig;
import org.telegram.messenger.ayu.netdiag.NetDiagConfig;
import org.telegram.messenger.ayu.netdiag.NetworkDiagnostics;
import org.telegram.messenger.voip.VoIPService;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.AnimatedFloat;
import org.telegram.ui.Components.AudioPlayerAlert;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.GroupCallActivity;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.ProxyListActivity;
import org.telegram.ui.ayu.netdiag.NetworkDiagnosticsActivity;

import java.util.ArrayList;

/**
 * AyuGram: iPhone-style "Dynamic Island" drawn on top of the whole app window.
 * <p>
 * A black pill sits in the status-bar area (around the camera cut-out) and shows the
 * current live activity: an ongoing call, a voice recording, the audio player,
 * active downloads or the ghost-mode indicator. Tapping the pill expands it into a
 * card with quick actions; tapping outside (or waiting) collapses it again.
 * <p>
 * The view is a single custom-drawn {@link View}: it only consumes touches that land
 * inside the pill, so everything else keeps working underneath it.
 */
public class DynamicIslandView extends View implements NotificationCenter.NotificationCenterDelegate, VoIPService.StateListener, NetworkDiagnostics.Listener {

    public static final int MODE_NONE = 0;
    public static final int MODE_CALL = 1;
    public static final int MODE_RECORDING = 2;
    public static final int MODE_PLAYER = 3;
    public static final int MODE_DOWNLOAD = 4;
    public static final int MODE_GHOST = 5;
    public static final int MODE_NETWORK = 6;

    private static final int COLOR_BG = 0xFF000000;
    private static final int COLOR_STROKE = 0x1FFFFFFF;
    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_SUBTEXT = 0xFF9B9BA3;
    private static final int COLOR_GREEN = 0xFF30D158;
    private static final int COLOR_AMBER = 0xFFFF9F0A;
    private static final int COLOR_RED = 0xFFFF453A;
    private static final int COLOR_BLUE = 0xFF0A84FF;
    private static final int COLOR_BUTTON = 0x2EFFFFFF;
    private static final int COLOR_BUTTON_RED = 0x33FF453A;

    private static final int BTN_MUTE = 1;
    private static final int BTN_SPEAKER = 2;
    private static final int BTN_HANGUP = 3;
    private static final int BTN_PREV = 4;
    private static final int BTN_PLAY = 5;
    private static final int BTN_NEXT = 6;
    private static final int BTN_CLOSE = 7;
    private static final int BTN_CANCEL_ALL = 8;
    private static final int BTN_GHOST_OFF = 9;
    private static final int BTN_NETDIAG = 10;
    private static final int BTN_PROXY = 11;

    private static final long AUTO_COLLAPSE_MS = 5000;
    private static final int MAX_DOWNLOAD_ROWS = 3;

    private static DynamicIslandView instance;

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint subPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint compactPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint rightPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint buttonTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    private final AnimatedFloat showT = new AnimatedFloat(this, 0, 380, CubicBezierInterpolator.EASE_OUT_QUINT);
    private final AnimatedFloat expandT = new AnimatedFloat(this, 0, 420, CubicBezierInterpolator.EASE_OUT_QUINT);
    private final AnimatedFloat contentT = new AnimatedFloat(this, 0, 180, CubicBezierInterpolator.EASE_OUT);
    private final AnimatedFloat widthT = new AnimatedFloat(this, 0, 320, CubicBezierInterpolator.EASE_OUT_QUINT);
    private final AnimatedFloat heightT = new AnimatedFloat(this, 0, 320, CubicBezierInterpolator.EASE_OUT_QUINT);
    private final AnimatedFloat pressT = new AnimatedFloat(this, 0, 160, CubicBezierInterpolator.EASE_OUT);
    private final AnimatedFloat progressT = new AnimatedFloat(this, 0, 260, CubicBezierInterpolator.EASE_OUT);

    private final RectF rect = new RectF();
    private final RectF compactRect = new RectF();
    /** hit area of the play/pause glyph on the collapsed music pill; empty when not drawn */
    private final RectF compactPlayRect = new RectF();
    private final RectF expandedRect = new RectF();
    private final RectF tmpRect = new RectF();
    private final ArrayList<IslandButton> buttons = new ArrayList<>();

    private final ImageReceiver imageReceiver;
    private final AvatarDrawable avatarDrawable = new AvatarDrawable();
    private final Drawable ghostDrawable;
    private final Drawable playDrawable;
    private final Drawable pauseDrawable;
    private final Drawable nextDrawable;
    private final Drawable prevDrawable;
    private final Drawable muteDrawable;
    private final Drawable unmuteDrawable;
    private final Drawable hangupDrawable;
    private final Drawable soundDrawable;

    private int mode = MODE_NONE;
    private int targetMode = MODE_NONE;
    private boolean expanded;
    private boolean idle;
    /** network pill: stays opaque this long after the last sample that showed a real transfer */
    private static final long NET_HOLD_MS = 2500;
    /** whole-app throughput above which the pill counts as "transferring" even without file-loader jobs */
    private static final long NET_SPEED_THRESHOLD = 48 * 1024;
    private long netActiveUntil;
    private boolean pressed;
    private float pressX, pressY;
    private int pressedButton;
    private final int touchSlop;

    private String title = "";
    private String subtitle = "";
    private String rightText = "";
    private int rightTextColor = COLOR_TEXT;
    private boolean hasImage;

    private int callAccount = -1;
    private boolean callIsGroup;
    private boolean callEstablished;
    private VoIPService listeningService;

    private int recordingAccount = -1;
    private long recordingStart;
    private boolean recordingPaused;
    private long recordingPausedAt;

    private MessageObject playingMessage;
    private boolean playerPaused;

    private final ArrayList<MessageObject> downloading = new ArrayList<>();
    private final ArrayList<Float> downloadProgresses = new ArrayList<>();
    private float downloadProgress;

    /** last sample delivered by the network sampler; null until the first tick */
    private NetworkDiagnostics.Sample netSample;
    /** true while this view holds a reference on {@link NetworkDiagnostics} */
    private boolean netSamplingActive;
    /** true while this view asks the sampler for ping / packet-loss probes */
    private boolean netProbesActive;
    private final float[] netPingBuf = new float[NetworkDiagnostics.HISTORY_SIZE];

    //perf: fields of the last sample that was actually rendered, to skip no-op updates
    private long lastNetDown = -1, lastNetUp = -1, lastNetProxyPing = Long.MIN_VALUE;
    private int lastNetPing = Integer.MIN_VALUE, lastNetState = Integer.MIN_VALUE;
    private int lastNetDc = Integer.MIN_VALUE, lastNetReason = Integer.MIN_VALUE;
    private float lastNetLoss = -1f;

    //perf: measureCompactWidth() runs on every frame; Paint.measureText() is not free
    private String measuredCompactText;
    private float measuredCompactWidth;
    private String measuredRightText;
    private float measuredRightWidth;

    private float micAmplitude;
    private float speakerAmplitude;
    private NotchInfoUtils.NotchInfo notchInfo;
    private boolean notchChecked;

    private final Runnable tickRunnable = this::onTick;
    private final Runnable collapseRunnable = () -> setExpanded(false);

    //perf: file notifications fire for every thumbnail, sticker and avatar while scrolling;
    //      they are all folded into one delayed update instead of one update each
    private static final long FILE_UPDATE_DELAY = 200;
    private boolean fileUpdateScheduled;
    private final Runnable fileUpdateRunnable = () -> {
        fileUpdateScheduled = false;
        update();
    };

    private static class IslandButton {
        final int id;
        final RectF bounds = new RectF();

        IslandButton(int id) {
            this.id = id;
        }
    }

    public DynamicIslandView(Context context) {
        super(context);
        instance = this;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();

        bgPaint.setColor(COLOR_BG);
        strokePaint.setColor(COLOR_STROKE);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(Math.max(1, dp(0.66f)));
        barPaint.setColor(COLOR_TEXT);
        barPaint.setStrokeCap(Paint.Cap.ROUND);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeCap(Paint.Cap.ROUND);
        ringPaint.setStrokeWidth(dp(2.2f));

        titlePaint.setTypeface(AndroidUtilities.bold());
        titlePaint.setTextSize(dp(15));
        titlePaint.setColor(COLOR_TEXT);
        subPaint.setTypeface(Typeface.DEFAULT);
        subPaint.setTextSize(dp(13));
        subPaint.setColor(COLOR_SUBTEXT);
        compactPaint.setTypeface(AndroidUtilities.bold());
        compactPaint.setTextSize(dp(11.5f));
        compactPaint.setColor(COLOR_TEXT);
        rightPaint.setTypeface(AndroidUtilities.bold());
        rightPaint.setTextSize(dp(11.5f));
        rightPaint.setColor(COLOR_TEXT);
        buttonTextPaint.setTypeface(AndroidUtilities.bold());
        buttonTextPaint.setTextSize(dp(14));
        buttonTextPaint.setColor(COLOR_TEXT);

        imageReceiver = new ImageReceiver(this);
        imageReceiver.setRoundRadius(dp(6));

        ghostDrawable = tinted(context, R.drawable.msg_ghost);
        playDrawable = tinted(context, R.drawable.ic_action_play);
        pauseDrawable = tinted(context, R.drawable.ic_action_pause);
        nextDrawable = tinted(context, R.drawable.ic_action_next);
        prevDrawable = tinted(context, R.drawable.ic_action_previous);
        muteDrawable = tinted(context, R.drawable.filled_profile_mute_24);
        unmuteDrawable = tinted(context, R.drawable.filled_profile_unmute_24);
        hangupDrawable = tinted(context, R.drawable.ic_call_notification_decline);
        soundDrawable = tinted(context, R.drawable.filled_sound_on);

        setWillNotDraw(false);
    }

    private static Drawable tinted(Context context, int res) {
        Drawable d = ContextCompat.getDrawable(context, res);
        if (d != null) {
            d = d.mutate();
            d.setColorFilter(new PorterDuffColorFilter(COLOR_TEXT, PorterDuff.Mode.SRC_IN));
        }
        return d;
    }

    public static DynamicIslandView getInstance() {
        return instance;
    }

    /** Re-evaluates the live sources and animates the pill to the right state. */
    public static void updateIfExists() {
        if (instance != null) {
            instance.update();
        }
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        imageReceiver.onAttachedToWindow();
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            NotificationCenter nc = NotificationCenter.getInstance(a);
            nc.addObserver(this, NotificationCenter.messagePlayingDidStart);
            nc.addObserver(this, NotificationCenter.messagePlayingPlayStateChanged);
            nc.addObserver(this, NotificationCenter.messagePlayingDidReset);
            nc.addObserver(this, NotificationCenter.messagePlayingProgressDidChanged);
            nc.addObserver(this, NotificationCenter.groupCallUpdated);
            nc.addObserver(this, NotificationCenter.recordStarted);
            nc.addObserver(this, NotificationCenter.recordStopped);
            nc.addObserver(this, NotificationCenter.recordStartError);
            nc.addObserver(this, NotificationCenter.recordPaused);
            nc.addObserver(this, NotificationCenter.recordResumed);
            nc.addObserver(this, NotificationCenter.onDownloadingFilesChanged);
            nc.addObserver(this, NotificationCenter.fileLoadProgressChanged);
            nc.addObserver(this, NotificationCenter.fileLoaded);
            nc.addObserver(this, NotificationCenter.fileLoadFailed);
        }
        NotificationCenter global = NotificationCenter.getGlobalInstance();
        global.addObserver(this, NotificationCenter.didStartedCall);
        global.addObserver(this, NotificationCenter.didEndCall);
        global.addObserver(this, NotificationCenter.groupCallVisibilityChanged);
        global.addObserver(this, NotificationCenter.webRtcMicAmplitudeEvent);
        global.addObserver(this, NotificationCenter.webRtcSpeakerAmplitudeEvent);
        global.addObserver(this, NotificationCenter.ayuGhostModeChanged);
        global.addObserver(this, NotificationCenter.ayuConfigChanged);
        NetDiagConfig.load();
        NetworkDiagnostics.getInstance().addListener(this);
        update();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        imageReceiver.onDetachedFromWindow();
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            NotificationCenter nc = NotificationCenter.getInstance(a);
            nc.removeObserver(this, NotificationCenter.messagePlayingDidStart);
            nc.removeObserver(this, NotificationCenter.messagePlayingPlayStateChanged);
            nc.removeObserver(this, NotificationCenter.messagePlayingDidReset);
            nc.removeObserver(this, NotificationCenter.messagePlayingProgressDidChanged);
            nc.removeObserver(this, NotificationCenter.groupCallUpdated);
            nc.removeObserver(this, NotificationCenter.recordStarted);
            nc.removeObserver(this, NotificationCenter.recordStopped);
            nc.removeObserver(this, NotificationCenter.recordStartError);
            nc.removeObserver(this, NotificationCenter.recordPaused);
            nc.removeObserver(this, NotificationCenter.recordResumed);
            nc.removeObserver(this, NotificationCenter.onDownloadingFilesChanged);
            nc.removeObserver(this, NotificationCenter.fileLoadProgressChanged);
            nc.removeObserver(this, NotificationCenter.fileLoaded);
            nc.removeObserver(this, NotificationCenter.fileLoadFailed);
        }
        NotificationCenter global = NotificationCenter.getGlobalInstance();
        global.removeObserver(this, NotificationCenter.didStartedCall);
        global.removeObserver(this, NotificationCenter.didEndCall);
        global.removeObserver(this, NotificationCenter.groupCallVisibilityChanged);
        global.removeObserver(this, NotificationCenter.webRtcMicAmplitudeEvent);
        global.removeObserver(this, NotificationCenter.webRtcSpeakerAmplitudeEvent);
        global.removeObserver(this, NotificationCenter.ayuGhostModeChanged);
        global.removeObserver(this, NotificationCenter.ayuConfigChanged);
        NetworkDiagnostics.getInstance().removeListener(this);
        setNetSampling(false);
        unregisterCallListener();
        //perf: drop the coalesced file update so a detached island stops doing work
        AndroidUtilities.cancelRunOnUIThread(fileUpdateRunnable);
        fileUpdateScheduled = false;
        removeCallbacks(tickRunnable);
        removeCallbacks(collapseRunnable);
        removeCallbacks(netQuietRunnable);
        if (instance == this) {
            instance = null;
        }
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        // pauses / resumes the network sampler together with the window
        update();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.recordStarted) {
            recordingAccount = account;
            recordingStart = System.currentTimeMillis();
            recordingPaused = false;
            update();
        } else if (id == NotificationCenter.recordStopped || id == NotificationCenter.recordStartError) {
            recordingAccount = -1;
            recordingPaused = false;
            update();
        } else if (id == NotificationCenter.recordPaused) {
            if (!recordingPaused) {
                recordingPaused = true;
                recordingPausedAt = System.currentTimeMillis();
            }
            update();
        } else if (id == NotificationCenter.recordResumed) {
            if (recordingPaused) {
                recordingStart += System.currentTimeMillis() - recordingPausedAt;
                recordingPaused = false;
            }
            update();
        } else if (id == NotificationCenter.messagePlayingProgressDidChanged) {
            if (mode == MODE_PLAYER) {
                invalidate();
            }
        } else if (id == NotificationCenter.fileLoadProgressChanged) {
            //perf: progress ticks only matter while the download row is (about to be) on screen
            if (mode == MODE_DOWNLOAD || targetMode == MODE_DOWNLOAD) {
                scheduleFileUpdate();
            }
        } else if (id == NotificationCenter.fileLoaded || id == NotificationCenter.fileLoadFailed) {
            //perf: one of these fires per thumbnail / sticker / avatar while scrolling a chat
            if (isDownloadStateRelevant()) {
                scheduleFileUpdate();
            }
        } else if (id == NotificationCenter.onDownloadingFilesChanged) {
            scheduleFileUpdate();
        } else if (id == NotificationCenter.webRtcMicAmplitudeEvent) {
            micAmplitude = Math.min(1f, Math.max(0f, ((float) args[0]) * 4000f / 1500f));
            if (mode == MODE_CALL) {
                invalidate();
            }
        } else if (id == NotificationCenter.webRtcSpeakerAmplitudeEvent) {
            speakerAmplitude = Math.min(1f, Math.max(0f, ((float) args[0]) * 15f / 80f));
            if (mode == MODE_CALL) {
                invalidate();
            }
        } else {
            update();
        }
    }

    /** Coalesces a burst of file-loader notifications into a single {@link #update()}. */
    //perf: one update per FILE_UPDATE_DELAY instead of one per loaded file
    private void scheduleFileUpdate() {
        if (fileUpdateScheduled) {
            return;
        }
        fileUpdateScheduled = true;
        AndroidUtilities.cancelRunOnUIThread(fileUpdateRunnable);
        AndroidUtilities.runOnUIThread(fileUpdateRunnable, FILE_UPDATE_DELAY);
    }

    /** true when a finished / failed file could change what the island shows */
    private boolean isDownloadStateRelevant() {
        if (mode == MODE_DOWNLOAD || targetMode == MODE_DOWNLOAD) {
            return true;
        }
        return AyuConfig.dynamicIsland && AyuConfig.islandDownloads && hasActiveDownloads();
    }

    /** Cheap "is anything downloading at all" probe: no strings, no ImageLoader lookups. */
    private static boolean hasActiveDownloads() {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (!UserConfig.getInstance(a).isClientActivated()) {
                continue;
            }
            if (!DownloadController.getInstance(a).downloadingFiles.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    // NetworkDiagnostics.Listener

    @Override
    public void onNetworkSample(NetworkDiagnostics.Sample sample) {
        netSample = sample;
        if (sample != null && isTransferring(sample)) {
            netActiveUntil = System.currentTimeMillis() + NET_HOLD_MS;
            removeCallbacks(netQuietRunnable);
            postDelayed(netQuietRunnable, NET_HOLD_MS + 32);
        }
        if (targetMode != MODE_NETWORK && targetMode != MODE_DOWNLOAD) {
            return;
        }
        //perf: while the pill is folded away nothing of this sample is drawn
        if (targetMode == MODE_NETWORK && isNetworkQuiet()) {
            return;
        }
        //perf: identical numbers would produce an identical pill - skip the rebuild + invalidate
        if (!netSampleChanged(sample)) {
            return;
        }
        rememberNetSample(sample);
        update();
    }

    /** true when {@code s} would render differently from the sample the pill currently shows */
    private boolean netSampleChanged(NetworkDiagnostics.Sample s) {
        if (s == null) {
            return lastNetState != Integer.MIN_VALUE;
        }
        if (s.connectionState != lastNetState || s.datacenterId != lastNetDc || s.reason != lastNetReason) {
            return true;
        }
        if (targetMode == MODE_DOWNLOAD) {
            return s.downSpeed != lastNetDown;
        }
        if (NetDiagConfig.islandShowDown && s.downSpeed != lastNetDown) {
            return true;
        }
        if (NetDiagConfig.islandShowUp && s.upSpeed != lastNetUp) {
            return true;
        }
        if (NetDiagConfig.islandShowPing && s.ping != lastNetPing) {
            return true;
        }
        // the expanded card shows everything, so it has to follow every field it renders
        return expanded && (s.ping != lastNetPing || s.loss != lastNetLoss
                || s.proxyPing != lastNetProxyPing || s.downSpeed != lastNetDown || s.upSpeed != lastNetUp);
    }

    private void rememberNetSample(NetworkDiagnostics.Sample s) {
        if (s == null) {
            lastNetState = Integer.MIN_VALUE;
            return;
        }
        lastNetDown = s.downSpeed;
        lastNetUp = s.upSpeed;
        lastNetPing = s.ping;
        lastNetLoss = s.loss;
        lastNetProxyPing = s.proxyPing;
        lastNetState = s.connectionState;
        lastNetDc = s.datacenterId;
        lastNetReason = s.reason;
    }

    private final Runnable netQuietRunnable = this::update;

    /** true while media, files or any noticeable traffic is actually moving */
    private static boolean isTransferring(NetworkDiagnostics.Sample s) {
        return s.activeDownloads > 0 || s.activeUploads > 0
                || s.activeDownSpeed > 0 || s.activeUpSpeed > 0
                || s.downSpeed >= NET_SPEED_THRESHOLD || s.upSpeed >= NET_SPEED_THRESHOLD;
    }

    /** the network pill hides itself (fully transparent) while nothing is being transferred */
    private boolean isNetworkQuiet() {
        return !expanded && System.currentTimeMillis() > netActiveUntil;
    }

    /** what the island should currently show once a quiet network pill is folded away */
    private int effectiveTargetMode() {
        return targetMode == MODE_NETWORK && isNetworkQuiet() ? MODE_NONE : targetMode;
    }

    /** Keeps the sampler's reference count balanced with what the island is actually showing. */
    private void setNetSampling(boolean value) {
        if (netSamplingActive == value) {
            return;
        }
        netSamplingActive = value;
        if (value) {
            // the island is a passive consumer: probes are requested separately, only when shown
            NetworkDiagnostics.getInstance().start(false);
        } else {
            setNetProbes(false);
            netSample = null;
            rememberNetSample(null);
            NetworkDiagnostics.getInstance().stop(false);
        }
    }

    /**
     * Ping / packet loss cost a real {@code help.getNearestDc} round trip, so they are only
     * requested while the pill really shows them.
     */
    //perf: no probes while the network pill is quiet, folded away or off screen
    private void setNetProbes(boolean value) {
        if (netProbesActive == value) {
            return;
        }
        netProbesActive = value;
        if (value) {
            NetworkDiagnostics.getInstance().addProbeConsumer();
        } else {
            NetworkDiagnostics.getInstance().removeProbeConsumer();
        }
    }

    // VoIPService.StateListener

    @Override
    public void onStateChanged(int state) {
        update();
    }

    @Override
    public void onAudioSettingsChanged() {
        invalidate();
    }

    private void registerCallListener(VoIPService service) {
        if (listeningService == service) {
            return;
        }
        unregisterCallListener();
        if (service != null) {
            service.registerStateListener(this);
            listeningService = service;
        }
    }

    private void unregisterCallListener() {
        if (listeningService != null) {
            try {
                listeningService.unregisterStateListener(this);
            } catch (Exception ignore) {
            }
            listeningService = null;
        }
    }

    // ------------------------------------------------------------------ state

    private void onTick() {
        if (mode == MODE_CALL || mode == MODE_RECORDING || mode == MODE_DOWNLOAD || targetMode == MODE_CALL || targetMode == MODE_RECORDING) {
            update();
        }
    }

    private void scheduleTick() {
        removeCallbacks(tickRunnable);
        if (targetMode == MODE_CALL || targetMode == MODE_RECORDING || targetMode == MODE_DOWNLOAD) {
            postDelayed(tickRunnable, 500);
        }
    }

    private int computeMode() {
        if (!AyuConfig.dynamicIsland) {
            return MODE_NONE;
        }
        if (GroupCallActivity.groupCallUiVisible) {
            return MODE_NONE;
        }
        VoIPService service = VoIPService.getSharedInstance();
        if (AyuConfig.islandCalls && service != null && !service.isHangingUp()
                && service.getCallState() != VoIPService.STATE_WAITING_INCOMING
                && service.getCallState() != VoIPService.STATE_ENDED) {
            return MODE_CALL;
        }
        if (AyuConfig.islandRecording && recordingAccount >= 0) {
            return MODE_RECORDING;
        }
        MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
        if (AyuConfig.islandMusic && playing != null && playing.getId() != 0 && !playing.isVideo()) {
            return MODE_PLAYER;
        }
        //perf: collectDownloads() allocates file names and hits ImageLoader for every job,
        //      so the cheap "is anything downloading" check runs first
        if (AyuConfig.islandDownloads) {
            if (hasActiveDownloads()) {
                if (collectDownloads() > 0) {
                    return MODE_DOWNLOAD;
                }
            } else if (!downloading.isEmpty()) {
                downloading.clear();
                downloadProgresses.clear();
                downloadProgress = 0f;
            }
        }
        if (NetDiagConfig.islandNetwork) {
            return MODE_NETWORK;
        }
        if (AyuConfig.islandGhost && AyuConfig.isGhostModeActive()) {
            return MODE_GHOST;
        }
        return MODE_NONE;
    }

    private int collectDownloads() {
        downloading.clear();
        downloadProgresses.clear();
        float sum = 0;
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (!UserConfig.getInstance(a).isClientActivated()) {
                continue;
            }
            ArrayList<MessageObject> list = DownloadController.getInstance(a).downloadingFiles;
            for (int i = 0; i < list.size(); i++) {
                MessageObject mo = list.get(i);
                if (mo == null || mo.getDocument() == null) {
                    continue;
                }
                String name = FileLoader.getAttachFileName(mo.getDocument());
                Float p = ImageLoader.getInstance().getFileProgress(name);
                float progress = p == null ? 0f : Math.max(0f, Math.min(1f, p));
                downloading.add(mo);
                downloadProgresses.add(progress);
                sum += progress;
            }
        }
        downloadProgress = downloading.isEmpty() ? 0f : sum / downloading.size();
        return downloading.size();
    }

    public void update() {
        //perf: a disabled or detached island must not walk the download lists or sample the network
        if (!AyuConfig.dynamicIsland || !isAttachedToWindow()) {
            setNetSampling(false);
            if (targetMode != MODE_NONE || idle) {
                targetMode = MODE_NONE;
                idle = false;
                unregisterCallListener();
                if (expanded) {
                    setExpanded(false);
                }
                downloading.clear();
                downloadProgresses.clear();
                downloadProgress = 0f;
                removeCallbacks(tickRunnable);
                invalidate();
            }
            return;
        }
        int newMode = computeMode();
        boolean wasNone = targetMode == MODE_NONE;
        targetMode = newMode;
        idle = (newMode == MODE_NONE || newMode == MODE_NETWORK) && AyuConfig.dynamicIsland && AyuConfig.islandIdle;

        if (newMode != MODE_CALL) {
            unregisterCallListener();
        }
        // The sampler only runs while the island is on screen and actually shows network data.
        setNetSampling(getWindowVisibility() == VISIBLE
                && NetDiagConfig.islandNetwork
                && (newMode == MODE_NETWORK || (newMode == MODE_DOWNLOAD && NetDiagConfig.islandNetworkWithDownloads)));
        // Probes only while the network pill is actually visible (the download pill shows no ping).
        setNetProbes(netSamplingActive && newMode == MODE_NETWORK && !isNetworkQuiet());
        switch (newMode) {
            case MODE_CALL:
                fillCall();
                break;
            case MODE_RECORDING:
                fillRecording();
                break;
            case MODE_PLAYER:
                fillPlayer();
                break;
            case MODE_DOWNLOAD:
                fillDownload();
                break;
            case MODE_GHOST:
                fillGhost();
                break;
            case MODE_NETWORK:
                //perf: a quiet pill is transparent, so its strings are never drawn - do not build them
                if (isNetworkQuiet()) {
                    hasImage = false;
                } else {
                    fillNetwork();
                }
                break;
            default:
                hasImage = false;
                break;
        }
        if (newMode == MODE_NONE && expanded) {
            setExpanded(false);
        }
        if (newMode != MODE_NONE && mode == MODE_NONE && wasNone) {
            // Snap to the new content immediately; the pill itself animates in.
            mode = newMode;
            contentT.set(1f, true);
            widthT.set(measureCompactWidth(), true);
            heightT.set(measureExpandedHeight(), true);
        }
        if (!isExpandable(newMode) && expanded) {
            setExpanded(false);
        }
        scheduleTick();
        invalidate();
    }

    private void fillCall() {
        VoIPService service = VoIPService.getSharedInstance();
        if (service == null) {
            return;
        }
        registerCallListener(service);
        callAccount = service.getAccount();
        callIsGroup = service.groupCall != null;
        int state = service.getCallState();
        callEstablished = state == VoIPService.STATE_ESTABLISHED || state == VoIPService.STATE_RECONNECTING;

        TLRPC.User user = service.getUser();
        TLRPC.Chat chat = service.getChat();
        TLObject peer = null;
        if (callIsGroup && chat != null) {
            title = service.groupCall.call != null && !TextUtils.isEmpty(service.groupCall.call.title) ? service.groupCall.call.title : chat.title;
            peer = chat;
        } else if (user != null) {
            title = UserObject.getUserName(user);
            peer = user;
        } else if (chat != null) {
            title = chat.title;
            peer = chat;
        } else {
            title = getString(R.string.VoipInCallBranding);
        }
        if (peer != null) {
            avatarDrawable.setInfo(callAccount, peer);
            imageReceiver.setRoundRadius(dp(24));
            imageReceiver.setForUserOrChat(peer, avatarDrawable);
            hasImage = true;
        } else {
            hasImage = false;
        }
        String kind = callIsGroup ? getString(R.string.VoipGroupVoiceChat) : getString(R.string.VoipInCallBranding);
        if (callEstablished) {
            rightText = AndroidUtilities.formatLongDuration((int) (service.getCallDuration() / 1000));
            rightTextColor = COLOR_GREEN;
        } else {
            rightText = getString(R.string.VoipConnecting);
            rightTextColor = COLOR_SUBTEXT;
        }
        subtitle = kind + " · " + rightText;
    }

    private void fillRecording() {
        long elapsed = recordingPaused ? recordingPausedAt - recordingStart : System.currentTimeMillis() - recordingStart;
        title = getString(R.string.AyuIslandRecordingText);
        subtitle = getString(R.string.AttachAudio);
        rightText = AndroidUtilities.formatLongDuration((int) (Math.max(0, elapsed) / 1000));
        rightTextColor = COLOR_RED;
        hasImage = false;
    }

    private void fillPlayer() {
        MessageObject mo = MediaController.getInstance().getPlayingMessageObject();
        playingMessage = mo;
        playerPaused = MediaController.getInstance().isMessagePaused();
        if (mo == null) {
            return;
        }
        if (mo.isMusic()) {
            title = safe(mo.getMusicTitle());
            subtitle = safe(mo.getMusicAuthor());
            ImageLocation cover = getArtworkLocation(mo);
            if (cover != null) {
                imageReceiver.setRoundRadius(dp(6));
                imageReceiver.setImage(cover, "48_48", null, null, mo, 0);
                hasImage = true;
            } else {
                hasImage = false;
            }
        } else {
            title = safe(mo.getMusicAuthor());
            subtitle = mo.isRoundVideo() ? getString(R.string.AttachRound) : getString(R.string.AttachAudio);
            hasImage = false;
        }
        rightText = "";
        rightTextColor = COLOR_TEXT;
    }

    private void fillDownload() {
        int count = downloading.size();
        if (count == 1) {
            MessageObject mo = downloading.get(0);
            title = safe(FileLoader.getDocumentFileName(mo.getDocument()));
            if (TextUtils.isEmpty(title)) {
                title = LocaleController.formatPluralString("Files", 1);
            }
        } else {
            title = LocaleController.formatPluralString("Files", count);
        }
        subtitle = LocaleController.formatPluralString("AyuIslandDownloadingFiles", count);
        if (SharedConfig.turboDownloadEnabled) {
            subtitle = subtitle + " · " + getString(R.string.AyuTurboIslandBadge);
        }
        if (NetDiagConfig.islandNetwork && NetDiagConfig.islandNetworkWithDownloads && netSample != null) {
            subtitle = subtitle + " · ▼ " + NetworkDiagnostics.formatSpeed(netSample.downSpeed);
        }
        rightText = (int) (downloadProgress * 100) + "%";
        rightTextColor = COLOR_BLUE;
        hasImage = false;
    }

    private void fillGhost() {
        title = getString(R.string.AyuIslandGhostOn);
        subtitle = getString(R.string.AyuIslandGhostInfo);
        rightText = "";
        rightTextColor = COLOR_TEXT;
        hasImage = false;
    }

    private void fillNetwork() {
        NetworkDiagnostics.Sample s = netSample;
        hasImage = false;
        if (s == null) {
            title = getString(R.string.AyuNetDiagIslandTitle);
            subtitle = getString(R.string.AyuNetDiagGraphEmpty);
            rightText = "";
            rightTextColor = COLOR_SUBTEXT;
            return;
        }
        StringBuilder sb = new StringBuilder();
        if (NetDiagConfig.islandShowDown) {
            sb.append("▼ ").append(NetworkDiagnostics.formatSpeed(s.downSpeed));
        }
        if (NetDiagConfig.islandShowUp) {
            if (sb.length() > 0) {
                sb.append("  ");
            }
            sb.append("▲ ").append(NetworkDiagnostics.formatSpeed(s.upSpeed));
        }
        if (sb.length() == 0 && !NetDiagConfig.islandShowPing) {
            sb.append(getString(R.string.AyuNetDiagIslandTitle));
        }
        title = sb.toString();
        subtitle = LocaleController.formatString(R.string.AyuNetDiagDc, s.datacenterId)
                + " · " + NetworkDiagnostics.getStateText(s.connectionState);
        rightText = NetDiagConfig.islandShowPing ? NetworkDiagnostics.formatPing(s.ping) : "";
        rightTextColor = netColor(s);
    }

    /** the compact network pill reserves a left icon slot only when the status dot is on */
    private static boolean hasLeftIcon(int m) {
        return m != MODE_NETWORK || NetDiagConfig.islandShowDot;
    }

    /** green / amber / red by the sample's severity */
    private static int netColor(NetworkDiagnostics.Sample s) {
        if (s == null) {
            return COLOR_SUBTEXT;
        }
        switch (s.severity()) {
            case NetworkDiagnostics.SEVERITY_BAD:
                return COLOR_RED;
            case NetworkDiagnostics.SEVERITY_WARN:
                return COLOR_AMBER;
            default:
                return COLOR_GREEN;
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static ImageLocation getArtworkLocation(MessageObject messageObject) {
        final TLRPC.Document document = messageObject.getDocument();
        TLRPC.PhotoSize thumb = document != null ? FileLoader.getClosestPhotoSizeWithSize(document.thumbs, 360) : null;
        if (!(thumb instanceof TLRPC.TL_photoSize) && !(thumb instanceof TLRPC.TL_photoSizeProgressive)) {
            thumb = null;
        }
        if (thumb != null) {
            return ImageLocation.getForDocument(thumb, document);
        }
        final String smallArtworkUrl = messageObject.getArtworkUrl(true);
        if (smallArtworkUrl != null) {
            return ImageLocation.getForPath(smallArtworkUrl);
        }
        return null;
    }

    private boolean isExpandable(int m) {
        return m == MODE_CALL || m == MODE_PLAYER || m == MODE_DOWNLOAD || m == MODE_GHOST || m == MODE_NETWORK;
    }

    private void setExpanded(boolean value) {
        removeCallbacks(collapseRunnable);
        if (expanded == value) {
            return;
        }
        expanded = value;
        if (expanded) {
            postDelayed(collapseRunnable, AUTO_COLLAPSE_MS);
        }
        invalidate();
    }

    private void bumpAutoCollapse() {
        removeCallbacks(collapseRunnable);
        if (expanded) {
            postDelayed(collapseRunnable, AUTO_COLLAPSE_MS);
        }
    }

    // ------------------------------------------------------------------ geometry

    private int statusBarHeight() {
        int h = AndroidUtilities.statusBarHeight;
        return h > 0 ? h : dp(24);
    }

    private float compactHeight() {
        return dp(26);
    }

    private float compactTop() {
        return Math.max(dp(4), (statusBarHeight() - compactHeight()) / 2f);
    }

    private float leftIconSize() {
        return dp(20);
    }

    /** {@link Paint#measureText} for the right label, cached per string (text size never changes) */
    //perf: called from onDraw on every frame
    private float rightTextWidth() {
        if (!TextUtils.equals(measuredRightText, rightText)) {
            measuredRightText = rightText;
            measuredRightWidth = rightPaint.measureText(rightText);
        }
        return measuredRightWidth;
    }

    private float measureRightWidth(int m) {
        switch (m) {
            case MODE_CALL:
                return dp(22) + rightTextWidth();
            case MODE_RECORDING:
            case MODE_DOWNLOAD:
            case MODE_NETWORK:
                return rightTextWidth();
            case MODE_PLAYER:
                return dp(18);
            default:
                return 0;
        }
    }

    private float measureCompactWidth() {
        int m = effectiveTargetMode();
        if (m == MODE_NONE) {
            return idleRectWidth();
        }
        float textMax = m == MODE_NETWORK ? dp(200) : dp(150);
        String text = compactText(m);
        //perf: same cache-per-string trick for the pill's own label
        if (!TextUtils.equals(measuredCompactText, text)) {
            measuredCompactText = text;
            measuredCompactWidth = compactPaint.measureText(text);
        }
        float textW = Math.min(textMax, measuredCompactWidth);
        float rightW = measureRightWidth(m);
        float iconW = hasLeftIcon(m) ? leftIconSize() + dp(8) : 0;
        float w = dp(12) + iconW + textW + (rightW > 0 && textW > 0 ? dp(8) : 0) + rightW + dp(12);
        int screen = getMeasuredWidth() > 0 ? getMeasuredWidth() : AndroidUtilities.displaySize.x;
        return Math.min(w, screen - dp(40));
    }

    private String compactText(int m) {
        if (m == MODE_GHOST) {
            return getString(R.string.AyuGhostMode);
        }
        return title;
    }

    private float measureExpandedHeight() {
        switch (targetMode) {
            case MODE_CALL:
                return dp(16 + 44 + 18 + 48 + 16);
            case MODE_PLAYER:
                return dp(16 + 48 + 16 + 4 + 6 + 14 + 10 + 48 + 16);
            case MODE_DOWNLOAD: {
                int rows = Math.min(MAX_DOWNLOAD_ROWS, Math.max(1, downloading.size()));
                return dp(16 + 24 + 6 + rows * 26 + 8 + 40 + 16);
            }
            case MODE_GHOST:
                return dp(16 + 44 + 16);
            case MODE_NETWORK:
                return dp(16 + 40 + 20 + 20 + 4 + 26 + 8 + 18 + 8 + 38 + 16);
            default:
                return compactHeight();
        }
    }

    private void checkNotch() {
        if (notchChecked) {
            return;
        }
        notchChecked = true;
        try {
            notchInfo = NotchInfoUtils.getInfo(getContext());
            if (notchInfo != null && notchInfo.gravity != Gravity.CENTER) {
                notchInfo = null;
            }
        } catch (Throwable t) {
            notchInfo = null;
        }
    }

    private float idleRectWidth() {
        checkNotch();
        if (notchInfo != null && notchInfo.bounds != null) {
            return notchInfo.bounds.width() + dp(20);
        }
        return dp(84);
    }

    private void computeIdleRect(RectF out, int width) {
        checkNotch();
        if (notchInfo != null && notchInfo.bounds != null) {
            float cx = notchInfo.bounds.centerX();
            float h = Math.max(dp(26), notchInfo.bounds.height() + dp(8));
            float top = Math.max(dp(2), notchInfo.bounds.top - dp(4));
            float w = notchInfo.bounds.width() + dp(20);
            out.set(cx - w / 2f, top, cx + w / 2f, top + h);
        } else {
            float w = dp(84), h = dp(26);
            float top = Math.max(dp(4), (statusBarHeight() - h) / 2f);
            out.set((width - w) / 2f, top, (width + w) / 2f, top + h);
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        setMeasuredDimension(width, dp(260));
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void onDraw(Canvas canvas) {
        final int width = getWidth();
        if (width <= 0) {
            return;
        }

        // Content cross-fade when the mode changes.
        final int target = effectiveTargetMode();
        if (target != mode) {
            float c = contentT.set(0f);
            if (c <= 0.02f || mode == MODE_NONE) {
                mode = target;
                contentT.set(1f, mode == MODE_NONE);
            }
        } else {
            contentT.set(1f);
        }

        boolean visible = target != MODE_NONE || idle;
        float show = showT.set(visible);
        if (show <= 0f && !visible) {
            if (mode != MODE_NONE) {
                mode = MODE_NONE;
            }
            return;
        }

        float expand = expandT.set(expanded && isExpandable(mode) ? 1f : 0f);
        float press = pressT.set(pressed && !expanded && pressedButton == 0 ? 1f : 0f);

        // Compact rect.
        float cw = widthT.set(measureCompactWidth());
        float ch = compactHeight();
        float ctop = compactTop();
        if (target == MODE_NONE && idle) {
            computeIdleRect(compactRect, width);
        } else {
            compactRect.set((width - cw) / 2f, ctop, (width + cw) / 2f, ctop + ch);
        }
        // Expanded rect.
        float eh = heightT.set(measureExpandedHeight());
        expandedRect.set(dp(10), ctop, width - dp(10), ctop + eh);

        rect.left = AndroidUtilities.lerp(compactRect.left, expandedRect.left, expand);
        rect.top = AndroidUtilities.lerp(compactRect.top, expandedRect.top, expand);
        rect.right = AndroidUtilities.lerp(compactRect.right, expandedRect.right, expand);
        rect.bottom = AndroidUtilities.lerp(compactRect.bottom, expandedRect.bottom, expand);
        float radius = AndroidUtilities.lerp(compactRect.height() / 2f, dp(30), expand);

        canvas.save();
        float scale = AndroidUtilities.lerp(0.72f, 1f, show) * AndroidUtilities.lerp(1f, 0.96f, press);
        canvas.scale(scale, scale, rect.centerX(), rect.top);
        int alpha = (int) (255 * show);
        bgPaint.setAlpha(alpha);
        strokePaint.setAlpha((int) (0x1F * show));
        canvas.drawRoundRect(rect, radius, radius, bgPaint);
        canvas.drawRoundRect(rect, radius, radius, strokePaint);

        if (mode != MODE_NONE) {
            float content = contentT.get() * show;
            canvas.save();
            path.rewind();
            path.addRoundRect(rect, radius, radius, Path.Direction.CW);
            canvas.clipPath(path);
            buttons.clear();
            compactPlayRect.setEmpty();
            if (expand < 1f) {
                drawCompact(canvas, content * (1f - expand));
            }
            if (expand > 0f) {
                drawExpanded(canvas, content * expand);
            }
            canvas.restore();
        }
        canvas.restore();

        if (mode == MODE_PLAYER && !playerPaused && show > 0f) {
            postInvalidateDelayed(60);
        } else if (mode == MODE_RECORDING && show > 0f) {
            postInvalidateDelayed(40);
        }
    }

    private void drawCompact(Canvas canvas, float alpha) {
        if (alpha <= 0f) {
            return;
        }
        int a = (int) (255 * alpha);
        float l = compactRect.left;
        float cy = compactRect.centerY();
        float x = l + dp(12);
        float iconSize = leftIconSize();

        // Left element.
        switch (mode) {
            case MODE_CALL:
                if (hasImage) {
                    imageReceiver.setAlpha(alpha);
                    imageReceiver.setRoundRadius((int) (iconSize / 2));
                    imageReceiver.setImageCoords(x, cy - iconSize / 2f, iconSize, iconSize);
                    imageReceiver.draw(canvas);
                } else {
                    drawDrawable(canvas, hangupDrawable, x + iconSize / 2f, cy, dp(18), a, COLOR_GREEN);
                }
                break;
            case MODE_RECORDING: {
                float pulse = recordingPaused ? 0.8f : 0.7f + 0.3f * (float) Math.abs(Math.sin(System.currentTimeMillis() / 350.0));
                fillPaint.setColor(COLOR_RED);
                fillPaint.setAlpha((int) (a * pulse));
                canvas.drawCircle(x + iconSize / 2f, cy, dp(5), fillPaint);
                break;
            }
            case MODE_PLAYER:
                if (hasImage) {
                    imageReceiver.setAlpha(alpha);
                    imageReceiver.setRoundRadius(dp(5));
                    imageReceiver.setImageCoords(x, cy - iconSize / 2f, iconSize, iconSize);
                    imageReceiver.draw(canvas);
                } else {
                    drawNote(canvas, x + iconSize / 2f, cy, dp(8), a);
                }
                break;
            case MODE_DOWNLOAD:
                drawDownloadRing(canvas, x + iconSize / 2f, cy, dp(9), downloadProgress, a);
                break;
            case MODE_GHOST:
                drawDrawable(canvas, ghostDrawable, x + iconSize / 2f, cy, dp(18), a, COLOR_TEXT);
                break;
            case MODE_NETWORK:
                if (NetDiagConfig.islandShowDot) {
                    drawStatusDot(canvas, x + iconSize / 2f, cy, dp(5), netColor(netSample), a);
                }
                break;
        }
        if (hasLeftIcon(mode)) {
            x += iconSize + dp(8);
        }

        // Right element.
        float rightW = measureRightWidth(mode);
        float rightX = compactRect.right - dp(12) - rightW;
        switch (mode) {
            case MODE_CALL: {
                float amp = Math.max(micAmplitude, speakerAmplitude);
                drawBars(canvas, rightX, cy, amp, callEstablished, COLOR_GREEN, a);
                rightPaint.setColor(rightTextColor);
                rightPaint.setAlpha(a);
                canvas.drawText(rightText, rightX + dp(22), cy + rightPaint.getTextSize() * 0.35f, rightPaint);
                break;
            }
            case MODE_RECORDING:
            case MODE_DOWNLOAD:
            case MODE_NETWORK:
                rightPaint.setColor(rightTextColor);
                rightPaint.setAlpha(a);
                canvas.drawText(rightText, rightX, cy + rightPaint.getTextSize() * 0.35f, rightPaint);
                break;
            case MODE_PLAYER: {
                // Tapping this glyph toggles playback instead of expanding the pill.
                compactPlayRect.set(rightX - dp(8), compactRect.top, compactRect.right, compactRect.bottom);
                int glyphAlpha = pressed && pressedButton == BTN_PLAY ? (int) (a * 0.5f) : a;
                if (playerPaused) {
                    drawDrawable(canvas, playDrawable, rightX + dp(8), cy, dp(16), glyphAlpha, COLOR_TEXT);
                } else {
                    drawEqualizer(canvas, rightX, cy, true, glyphAlpha);
                }
                break;
            }
        }

        // Text.
        float textMax = rightX - (rightW > 0 ? dp(8) : 0) - x;
        if (textMax > dp(10)) {
            CharSequence text = TextUtils.ellipsize(compactText(mode), compactPaint, textMax, TextUtils.TruncateAt.END);
            compactPaint.setAlpha(a);
            canvas.drawText(text, 0, text.length(), x, cy + compactPaint.getTextSize() * 0.35f, compactPaint);
        }
    }

    private void drawExpanded(Canvas canvas, float alpha) {
        if (alpha <= 0f) {
            return;
        }
        int a = (int) (255 * alpha);
        float l = expandedRect.left + dp(16);
        float r = expandedRect.right - dp(16);
        float t = expandedRect.top + dp(16);

        switch (mode) {
            case MODE_CALL:
                drawExpandedCall(canvas, l, t, r, a, alpha);
                break;
            case MODE_PLAYER:
                drawExpandedPlayer(canvas, l, t, r, a, alpha);
                break;
            case MODE_DOWNLOAD:
                drawExpandedDownload(canvas, l, t, r, a);
                break;
            case MODE_GHOST:
                drawExpandedGhost(canvas, l, t, r, a);
                break;
            case MODE_NETWORK:
                drawExpandedNetwork(canvas, l, t, r, a);
                break;
        }
    }

    private void drawExpandedCall(Canvas canvas, float l, float t, float r, int a, float alpha) {
        float avatar = dp(44);
        if (hasImage) {
            imageReceiver.setAlpha(alpha);
            imageReceiver.setRoundRadius((int) (avatar / 2));
            imageReceiver.setImageCoords(l, t, avatar, avatar);
            imageReceiver.draw(canvas);
        } else {
            fillPaint.setColor(COLOR_BUTTON);
            fillPaint.setAlpha((int) (0x2E * alpha));
            canvas.drawCircle(l + avatar / 2f, t + avatar / 2f, avatar / 2f, fillPaint);
            drawDrawable(canvas, hangupDrawable, l + avatar / 2f, t + avatar / 2f, dp(22), a, COLOR_GREEN);
        }
        float tx = l + avatar + dp(12);
        float maxW = r - tx;
        drawEllipsized(canvas, title, titlePaint, tx, t + dp(18), maxW, a, COLOR_TEXT);
        drawEllipsized(canvas, subtitle, subPaint, tx, t + dp(38), maxW, a, callEstablished ? COLOR_GREEN : COLOR_SUBTEXT);

        float by = t + avatar + dp(18);
        float bs = dp(48);
        float span = r - l;
        float[] centers = {l + span * 0.2f, l + span * 0.5f, l + span * 0.8f};
        VoIPService service = VoIPService.getSharedInstance();
        boolean muted = service != null && service.isMicMute();
        boolean speaker = service != null && service.isSpeakerphoneOn();

        drawRoundButton(canvas, BTN_MUTE, centers[0], by + bs / 2f, bs, muted ? COLOR_TEXT : COLOR_BUTTON, muted, muted ? muteDrawable : unmuteDrawable, muted ? COLOR_BG : COLOR_TEXT, a, alpha);
        drawRoundButton(canvas, BTN_SPEAKER, centers[1], by + bs / 2f, bs, speaker ? COLOR_TEXT : COLOR_BUTTON, speaker, soundDrawable, speaker ? COLOR_BG : COLOR_TEXT, a, alpha);
        drawRoundButton(canvas, BTN_HANGUP, centers[2], by + bs / 2f, bs, COLOR_RED, true, hangupDrawable, COLOR_TEXT, a, alpha);
    }

    private void drawExpandedPlayer(Canvas canvas, float l, float t, float r, int a, float alpha) {
        float cover = dp(48);
        if (hasImage) {
            imageReceiver.setAlpha(alpha);
            imageReceiver.setRoundRadius(dp(8));
            imageReceiver.setImageCoords(l, t, cover, cover);
            imageReceiver.draw(canvas);
        } else {
            fillPaint.setColor(COLOR_BUTTON);
            fillPaint.setAlpha((int) (0x2E * alpha));
            tmpRect.set(l, t, l + cover, t + cover);
            canvas.drawRoundRect(tmpRect, dp(8), dp(8), fillPaint);
            drawNote(canvas, l + cover / 2f, t + cover / 2f, dp(14), a);
        }
        float closeSize = dp(28);
        IslandButton close = button(BTN_CLOSE, r - closeSize, t, r, t + closeSize);
        fillPaint.setColor(COLOR_BUTTON);
        fillPaint.setAlpha((int) (0x2E * alpha));
        canvas.drawCircle(close.bounds.centerX(), close.bounds.centerY(), closeSize / 2f, fillPaint);
        drawCross(canvas, close.bounds.centerX(), close.bounds.centerY(), dp(5), a);
        close.bounds.inset(-dp(6), -dp(6));

        float tx = l + cover + dp(12);
        float maxW = close.bounds.left - dp(6) - tx;
        drawEllipsized(canvas, title, titlePaint, tx, t + dp(19), maxW, a, COLOR_TEXT);
        drawEllipsized(canvas, subtitle, subPaint, tx, t + dp(39), maxW, a, COLOR_SUBTEXT);

        // Progress.
        float py = t + cover + dp(16);
        float progress = playingMessage != null ? Math.max(0f, Math.min(1f, playingMessage.audioProgress)) : 0f;
        float pv = progressT.set(progress);
        fillPaint.setColor(COLOR_BUTTON);
        fillPaint.setAlpha((int) (0x2E * alpha));
        tmpRect.set(l, py, r, py + dp(4));
        canvas.drawRoundRect(tmpRect, dp(2), dp(2), fillPaint);
        fillPaint.setColor(COLOR_TEXT);
        fillPaint.setAlpha(a);
        tmpRect.set(l, py, l + (r - l) * pv, py + dp(4));
        canvas.drawRoundRect(tmpRect, dp(2), dp(2), fillPaint);

        int duration = playingMessage != null ? (int) playingMessage.getDuration() : 0;
        int elapsed = (int) (duration * pv);
        subPaint.setTextSize(dp(11));
        subPaint.setColor(COLOR_SUBTEXT);
        subPaint.setAlpha(a);
        String left = AndroidUtilities.formatShortDuration(elapsed);
        String right = "-" + AndroidUtilities.formatShortDuration(Math.max(0, duration - elapsed));
        canvas.drawText(left, l, py + dp(4 + 6 + 11), subPaint);
        canvas.drawText(right, r - subPaint.measureText(right), py + dp(4 + 6 + 11), subPaint);
        subPaint.setTextSize(dp(13));

        // Buttons.
        float by = py + dp(4 + 6 + 14 + 10);
        float bs = dp(48);
        float cx = (l + r) / 2f;
        drawRoundButton(canvas, BTN_PREV, cx - dp(84), by + bs / 2f, dp(40), COLOR_BUTTON, false, prevDrawable, COLOR_TEXT, a, alpha);
        drawRoundButton(canvas, BTN_PLAY, cx, by + bs / 2f, bs, COLOR_TEXT, true, playerPaused ? playDrawable : pauseDrawable, COLOR_BG, a, alpha);
        drawRoundButton(canvas, BTN_NEXT, cx + dp(84), by + bs / 2f, dp(40), COLOR_BUTTON, false, nextDrawable, COLOR_TEXT, a, alpha);
    }

    private void drawExpandedDownload(Canvas canvas, float l, float t, float r, int a) {
        float alpha = a / 255f;
        drawDownloadRing(canvas, l + dp(12), t + dp(12), dp(10), downloadProgress, a);
        float tx = l + dp(34);
        rightPaint.setColor(COLOR_BLUE);
        rightPaint.setAlpha(a);
        float rw = rightPaint.measureText(rightText);
        canvas.drawText(rightText, r - rw, t + dp(17), rightPaint);
        drawEllipsized(canvas, subtitle, titlePaint, tx, t + dp(17), r - rw - dp(8) - tx, a, COLOR_TEXT);

        float y = t + dp(24 + 6);
        int rows = Math.min(MAX_DOWNLOAD_ROWS, downloading.size());
        for (int i = 0; i < rows; i++) {
            MessageObject mo = downloading.get(i);
            float p = i < downloadProgresses.size() ? downloadProgresses.get(i) : 0f;
            String name = safe(FileLoader.getDocumentFileName(mo.getDocument()));
            String pct = (int) (p * 100) + "%";
            subPaint.setColor(COLOR_SUBTEXT);
            subPaint.setAlpha(a);
            float pw = subPaint.measureText(pct);
            canvas.drawText(pct, r - pw, y + dp(13), subPaint);
            drawEllipsized(canvas, name, subPaint, l, y + dp(13), r - pw - dp(8) - l, a, COLOR_TEXT);
            fillPaint.setColor(COLOR_BUTTON);
            fillPaint.setAlpha((int) (0x2E * alpha));
            tmpRect.set(l, y + dp(19), r, y + dp(21));
            canvas.drawRoundRect(tmpRect, dp(1), dp(1), fillPaint);
            fillPaint.setColor(COLOR_BLUE);
            fillPaint.setAlpha(a);
            tmpRect.set(l, y + dp(19), l + (r - l) * p, y + dp(21));
            canvas.drawRoundRect(tmpRect, dp(1), dp(1), fillPaint);
            y += dp(26);
        }
        y += dp(8);
        drawPillButton(canvas, BTN_CANCEL_ALL, l, y, r, y + dp(40), COLOR_BUTTON_RED, getString(R.string.AyuIslandCancelAll), COLOR_RED, a);
    }

    private void drawExpandedGhost(Canvas canvas, float l, float t, float r, int a) {
        float alpha = a / 255f;
        float size = dp(44);
        fillPaint.setColor(COLOR_BUTTON);
        fillPaint.setAlpha((int) (0x2E * alpha));
        canvas.drawCircle(l + size / 2f, t + size / 2f, size / 2f, fillPaint);
        drawDrawable(canvas, ghostDrawable, l + size / 2f, t + size / 2f, dp(24), a, COLOR_TEXT);

        String btn = getString(R.string.AyuIslandGhostOff);
        float bw = buttonTextPaint.measureText(btn) + dp(28);
        float bh = dp(36);
        drawPillButton(canvas, BTN_GHOST_OFF, r - bw, t + (size - bh) / 2f, r, t + (size + bh) / 2f, COLOR_TEXT, btn, COLOR_BG, a);

        float tx = l + size + dp(12);
        float maxW = r - bw - dp(12) - tx;
        drawEllipsized(canvas, title, titlePaint, tx, t + dp(18), maxW, a, COLOR_TEXT);
        drawEllipsized(canvas, subtitle, subPaint, tx, t + dp(37), maxW, a, COLOR_SUBTEXT);
    }

    private void drawExpandedNetwork(Canvas canvas, float l, float t, float r, int a) {
        final NetworkDiagnostics.Sample s = netSample;
        final int accent = netColor(s);

        // --- header: dot + "Network" + ping ---
        drawStatusDot(canvas, l + dp(6), t + dp(13), dp(5), accent, a);
        String ping = s == null ? "—" : NetworkDiagnostics.formatPing(s.ping);
        rightPaint.setColor(accent);
        rightPaint.setAlpha(a);
        float pw = rightPaint.measureText(ping);
        canvas.drawText(ping, r - pw, t + dp(18), rightPaint);
        drawEllipsized(canvas, getString(R.string.AyuNetDiagIslandTitle), titlePaint,
                l + dp(18), t + dp(18), r - pw - dp(8) - l - dp(18), a, COLOR_TEXT);
        String state = s == null
                ? getString(R.string.AyuNetDiagGraphEmpty)
                : LocaleController.formatString(R.string.AyuNetDiagDc, s.datacenterId) + " · " + NetworkDiagnostics.getStateText(s.connectionState);
        drawEllipsized(canvas, state, subPaint, l, t + dp(36), r - l, a, COLOR_SUBTEXT);

        // --- two rows of stats ---
        float mid = (l + r) / 2f;
        float y = t + dp(40);
        subPaint.setTextSize(dp(12));
        drawStat(canvas, l, mid - dp(8), y + dp(12), getString(R.string.AyuNetDiagDownload),
                s == null ? "—" : NetworkDiagnostics.formatSpeed(s.downSpeed), COLOR_TEXT, a);
        drawStat(canvas, mid, r, y + dp(12), getString(R.string.AyuNetDiagUpload),
                s == null ? "—" : NetworkDiagnostics.formatSpeed(s.upSpeed), COLOR_TEXT, a);
        y += dp(20);
        drawStat(canvas, l, mid - dp(8), y + dp(12), getString(R.string.AyuNetDiagLoss),
                s == null ? "—" : NetworkDiagnostics.formatLoss(s.loss),
                s != null && s.loss > NetworkDiagnostics.LOSS_WARN ? COLOR_RED : COLOR_TEXT, a);
        drawStat(canvas, mid, r, y + dp(12), getString(R.string.AyuNetDiagProxy),
                s == null || !s.proxyActive ? getString(R.string.AyuNetDiagNoProxy) : NetworkDiagnostics.formatProxyPing(s.proxyPing),
                s != null && s.proxyActive && s.proxyPing < 0 ? COLOR_RED : COLOR_TEXT, a);
        y += dp(20 + 4);

        // --- 30 s ping sparkline ---
        drawPingSparkline(canvas, l, y, r, y + dp(26), accent, a);
        y += dp(26 + 8);

        // --- slowdown reason ---
        drawEllipsized(canvas, s == null ? "" : NetworkDiagnostics.getReasonText(s.reason), subPaint,
                l, y + dp(12), r - l, a, accent);
        subPaint.setTextSize(dp(13));
        y += dp(18 + 8);

        // --- quick actions ---
        float half = (r - l - dp(8)) / 2f;
        float oldSize = buttonTextPaint.getTextSize();
        buttonTextPaint.setTextSize(dp(13));
        drawPillButton(canvas, BTN_NETDIAG, l, y, l + half, y + dp(38), COLOR_TEXT, getString(R.string.AyuNetDiagOpen), COLOR_BG, a);
        drawPillButton(canvas, BTN_PROXY, l + half + dp(8), y, r, y + dp(38), COLOR_BUTTON, getString(R.string.AyuNetDiagProxySettings), COLOR_TEXT, a);
        buttonTextPaint.setTextSize(oldSize);
    }

    /** Label on the left, value right-aligned at {@code right}. */
    private void drawStat(Canvas canvas, float x, float right, float baseline, String label, String value, int valueColor, int a) {
        subPaint.setColor(valueColor);
        subPaint.setAlpha(a);
        float vw = subPaint.measureText(value);
        canvas.drawText(value, right - vw, baseline, subPaint);
        drawEllipsized(canvas, label, subPaint, x, baseline, right - vw - dp(6) - x, a, COLOR_SUBTEXT);
    }

    private void drawStatusDot(Canvas canvas, float cx, float cy, float r, int color, int a) {
        fillPaint.setColor(color);
        fillPaint.setAlpha((int) (a * 0.28f));
        canvas.drawCircle(cx, cy, r * 1.9f, fillPaint);
        fillPaint.setColor(color);
        fillPaint.setAlpha(a);
        canvas.drawCircle(cx, cy, r, fillPaint);
    }

    /** Last 30 samples of the ping history, drawn as a thin polyline. */
    private void drawPingSparkline(Canvas canvas, float l, float t, float r, float b, int color, int a) {
        fillPaint.setColor(COLOR_BUTTON);
        fillPaint.setAlpha((int) (0x2E * (a / 255f)));
        tmpRect.set(l, t, r, b);
        canvas.drawRoundRect(tmpRect, dp(6), dp(6), fillPaint);

        int n = NetworkDiagnostics.getInstance().pingHistory.copyTo(netPingBuf);
        int from = Math.max(0, n - 30);
        int count = n - from;
        if (count < 2) {
            return;
        }
        float max = 100f;
        for (int i = from; i < n; i++) {
            max = Math.max(max, netPingBuf[i]);
        }
        float top = t + dp(4);
        float bottom = b - dp(4);
        barPaint.setColor(color);
        barPaint.setAlpha(a);
        barPaint.setStrokeWidth(dp(1.6f));
        float px = 0, py = 0;
        for (int i = 0; i < count; i++) {
            float x = l + dp(6) + (r - l - dp(12)) * i / (count - 1f);
            float y = bottom - (bottom - top) * Math.min(1f, netPingBuf[from + i] / max);
            if (i > 0) {
                canvas.drawLine(px, py, x, y, barPaint);
            }
            px = x;
            py = y;
        }
    }

    // ------------------------------------------------------------------ primitives

    private IslandButton button(int id, float l, float t, float r, float b) {
        IslandButton btn = new IslandButton(id);
        btn.bounds.set(l, t, r, b);
        buttons.add(btn);
        return btn;
    }

    private void drawRoundButton(Canvas canvas, int id, float cx, float cy, float size, int bg, boolean opaque, Drawable icon, int iconColor, int a, float alpha) {
        IslandButton btn = button(id, cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f);
        boolean isPressed = pressed && pressedButton == id;
        fillPaint.setColor(bg);
        fillPaint.setAlpha((int) (Color.alpha(bg) * alpha * (isPressed ? 0.7f : 1f)));
        canvas.drawCircle(cx, cy, size / 2f, fillPaint);
        drawDrawable(canvas, icon, cx, cy, size * 0.48f, a, iconColor);
        if (!opaque) {
            btn.bounds.inset(-dp(4), -dp(4));
        }
    }

    private void drawPillButton(Canvas canvas, int id, float l, float t, float r, float b, int bg, String text, int textColor, int a) {
        IslandButton btn = button(id, l, t, r, b);
        boolean isPressed = pressed && pressedButton == id;
        float alpha = a / 255f;
        fillPaint.setColor(bg);
        fillPaint.setAlpha((int) (Color.alpha(bg) * alpha * (isPressed ? 0.7f : 1f)));
        float radius = (b - t) / 2f;
        canvas.drawRoundRect(btn.bounds, radius, radius, fillPaint);
        buttonTextPaint.setColor(textColor);
        buttonTextPaint.setAlpha(a);
        float tw = buttonTextPaint.measureText(text);
        canvas.drawText(text, (l + r) / 2f - tw / 2f, (t + b) / 2f + buttonTextPaint.getTextSize() * 0.35f, buttonTextPaint);
    }

    private void drawEllipsized(Canvas canvas, String text, TextPaint paint, float x, float baseline, float maxW, int a, int color) {
        if (TextUtils.isEmpty(text) || maxW <= dp(8)) {
            return;
        }
        paint.setColor(color);
        paint.setAlpha(a);
        CharSequence cs = TextUtils.ellipsize(text, paint, maxW, TextUtils.TruncateAt.END);
        canvas.drawText(cs, 0, cs.length(), x, baseline, paint);
    }

    private void drawDrawable(Canvas canvas, Drawable d, float cx, float cy, float size, int a, int color) {
        if (d == null) {
            return;
        }
        d.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
        d.setAlpha(a);
        int half = (int) (size / 2f);
        d.setBounds((int) cx - half, (int) cy - half, (int) cx + half, (int) cy + half);
        d.draw(canvas);
    }

    private void drawCross(Canvas canvas, float cx, float cy, float r, int a) {
        barPaint.setColor(COLOR_TEXT);
        barPaint.setAlpha(a);
        barPaint.setStrokeWidth(dp(2));
        canvas.drawLine(cx - r, cy - r, cx + r, cy + r, barPaint);
        canvas.drawLine(cx - r, cy + r, cx + r, cy - r, barPaint);
    }

    private void drawNote(Canvas canvas, float cx, float cy, float size, int a) {
        fillPaint.setColor(COLOR_TEXT);
        fillPaint.setAlpha(a);
        float headR = size * 0.36f;
        float stemH = size * 1.2f;
        float stemW = Math.max(1.5f, size * 0.16f);
        float hx = cx - size * 0.25f;
        float hy = cy + size * 0.45f;
        canvas.drawOval(hx - headR * 1.15f, hy - headR * 0.8f, hx + headR * 1.15f, hy + headR * 0.8f, fillPaint);
        canvas.drawRect(hx + headR * 1.15f - stemW, hy - stemH, hx + headR * 1.15f, hy, fillPaint);
        canvas.drawRect(hx + headR * 1.15f - stemW, hy - stemH, hx + headR * 1.15f + size * 0.5f, hy - stemH + stemW * 1.4f, fillPaint);
    }

    private void drawDownloadRing(Canvas canvas, float cx, float cy, float r, float progress, int a) {
        ringPaint.setColor(COLOR_BUTTON);
        ringPaint.setAlpha((int) (0x2E * (a / 255f)));
        canvas.drawCircle(cx, cy, r, ringPaint);
        ringPaint.setColor(COLOR_BLUE);
        ringPaint.setAlpha(a);
        tmpRect.set(cx - r, cy - r, cx + r, cy + r);
        canvas.drawArc(tmpRect, -90, 360 * Math.max(0.02f, progress), false, ringPaint);
        barPaint.setColor(COLOR_TEXT);
        barPaint.setAlpha(a);
        barPaint.setStrokeWidth(dp(1.8f));
        float ah = r * 0.55f;
        canvas.drawLine(cx, cy - ah, cx, cy + ah * 0.9f, barPaint);
        canvas.drawLine(cx - ah * 0.6f, cy + ah * 0.3f, cx, cy + ah * 0.9f, barPaint);
        canvas.drawLine(cx + ah * 0.6f, cy + ah * 0.3f, cx, cy + ah * 0.9f, barPaint);
    }

    private void drawBars(Canvas canvas, float x, float cy, float amplitude, boolean active, int color, int a) {
        barPaint.setColor(color);
        barPaint.setAlpha(a);
        barPaint.setStrokeWidth(dp(2.5f));
        float gap = dp(4.5f);
        float base = dp(4);
        float max = dp(12);
        double time = System.currentTimeMillis() / 140.0;
        for (int i = 0; i < 3; i++) {
            float h = base;
            if (active) {
                float wave = (float) (0.5 + 0.5 * Math.sin(time + i * 1.3));
                h = base + (max - base) * Math.max(0.08f, amplitude) * wave;
            }
            float bx = x + i * gap + dp(1.5f);
            canvas.drawLine(bx, cy - h / 2f, bx, cy + h / 2f, barPaint);
        }
        if (active) {
            postInvalidateDelayed(50);
        }
    }

    private void drawEqualizer(Canvas canvas, float x, float cy, boolean playing, int a) {
        barPaint.setColor(COLOR_TEXT);
        barPaint.setAlpha(a);
        barPaint.setStrokeWidth(dp(2.5f));
        float gap = dp(4.5f);
        float base = dp(4);
        float max = dp(13);
        double time = System.currentTimeMillis() / 160.0;
        for (int i = 0; i < 4; i++) {
            float h = base;
            if (playing) {
                float wave = (float) (0.5 + 0.5 * Math.sin(time * (1 + i * 0.27) + i));
                h = base + (max - base) * wave;
            }
            float bx = x + i * gap + dp(1.5f);
            canvas.drawLine(bx, cy - h / 2f, bx, cy + h / 2f, barPaint);
        }
    }

    // ------------------------------------------------------------------ touch

    private boolean insideIsland(float x, float y) {
        return showT.get() > 0f && effectiveTargetMode() != MODE_NONE && rect.contains(x, y);
    }

    private IslandButton findButton(float x, float y) {
        for (int i = 0; i < buttons.size(); i++) {
            IslandButton b = buttons.get(i);
            if (b.bounds.contains(x, y)) {
                return b;
            }
        }
        return null;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                if (insideIsland(x, y)) {
                    pressed = true;
                    pressX = x;
                    pressY = y;
                    if (expanded) {
                        IslandButton b = findButton(x, y);
                        pressedButton = b != null ? b.id : 0;
                    } else if (mode == MODE_PLAYER && !compactPlayRect.isEmpty() && compactPlayRect.contains(x, y)) {
                        // collapsed music pill: the glyph on the right is the play/pause button
                        pressedButton = BTN_PLAY;
                    } else {
                        pressedButton = 0;
                    }
                    bumpAutoCollapse();
                    invalidate();
                    return true;
                }
                if (expanded) {
                    setExpanded(false);
                    return true;
                }
                return false;
            }
            case MotionEvent.ACTION_MOVE: {
                if (pressed && (Math.abs(x - pressX) > touchSlop * 2 || Math.abs(y - pressY) > touchSlop * 2)) {
                    pressed = false;
                    pressedButton = 0;
                    invalidate();
                }
                return pressed;
            }
            case MotionEvent.ACTION_UP: {
                if (pressed) {
                    pressed = false;
                    int btn = pressedButton;
                    pressedButton = 0;
                    invalidate();
                    performClick();
                    if (expanded) {
                        if (btn != 0) {
                            onButtonClick(btn);
                        } else if (rect.contains(x, y)) {
                            openSource();
                        }
                    } else if (btn != 0) {
                        onButtonClick(btn);
                    } else if (isExpandable(mode)) {
                        setExpanded(true);
                    } else {
                        openSource();
                    }
                    return true;
                }
                return false;
            }
            case MotionEvent.ACTION_CANCEL: {
                boolean was = pressed;
                pressed = false;
                pressedButton = 0;
                invalidate();
                return was;
            }
        }
        return false;
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    private void onButtonClick(int id) {
        bumpAutoCollapse();
        VoIPService service = VoIPService.getSharedInstance();
        switch (id) {
            case BTN_MUTE:
                if (service != null) {
                    service.setMicMute(!service.isMicMute(), false, true);
                }
                invalidate();
                break;
            case BTN_SPEAKER:
                if (service != null) {
                    service.toggleSpeakerphoneOrShowRouteSheet(getContext(), false);
                }
                invalidate();
                break;
            case BTN_HANGUP:
                if (service != null) {
                    service.hangUp();
                }
                setExpanded(false);
                break;
            case BTN_PREV:
                MediaController.getInstance().playPreviousMessage();
                break;
            case BTN_NEXT:
                MediaController.getInstance().playNextMessage();
                break;
            case BTN_PLAY: {
                MessageObject mo = MediaController.getInstance().getPlayingMessageObject();
                if (mo != null) {
                    if (MediaController.getInstance().isMessagePaused()) {
                        MediaController.getInstance().playMessage(mo);
                    } else {
                        MediaController.getInstance().pauseMessage(mo);
                    }
                }
                update();
                break;
            }
            case BTN_CLOSE:
                MediaController.getInstance().cleanupPlayer(true, true);
                setExpanded(false);
                break;
            case BTN_CANCEL_ALL: {
                ArrayList<MessageObject> copy = new ArrayList<>(downloading);
                for (int i = 0; i < copy.size(); i++) {
                    MessageObject mo = copy.get(i);
                    try {
                        FileLoader.getInstance(mo.currentAccount).cancelLoadFile(mo.getDocument());
                        DownloadController.getInstance(mo.currentAccount).onDownloadFail(mo, 0);
                    } catch (Exception ignore) {
                    }
                }
                setExpanded(false);
                update();
                break;
            }
            case BTN_GHOST_OFF:
                AyuConfig.setGhostMode(false);
                setExpanded(false);
                update();
                break;
            case BTN_NETDIAG:
                setExpanded(false);
                presentFragment(new NetworkDiagnosticsActivity());
                break;
            case BTN_PROXY:
                setExpanded(false);
                presentFragment(new ProxyListActivity());
                break;
        }
    }

    private void presentFragment(BaseFragment fragment) {
        BaseFragment last = LaunchActivity.getSafeLastFragment();
        if (last == null || LaunchActivity.instance == null) {
            return;
        }
        last.presentFragment(fragment);
    }

    private void openSource() {
        setExpanded(false);
        switch (mode) {
            case MODE_CALL: {
                VoIPService service = VoIPService.getSharedInstance();
                if (service == null) {
                    return;
                }
                if (service.groupCall != null && LaunchActivity.instance != null) {
                    GroupCallActivity.create(LaunchActivity.instance, AccountInstance.getInstance(service.getAccount()), null, null, false, null);
                } else {
                    Intent intent = new Intent(getContext(), LaunchActivity.class).setAction("voip");
                    getContext().startActivity(intent);
                }
                break;
            }
            case MODE_PLAYER: {
                MessageObject mo = MediaController.getInstance().getPlayingMessageObject();
                if (mo == null) {
                    return;
                }
                if (mo.isMusic()) {
                    try {
                        final Activity activity = AndroidUtilities.findActivity(getContext());
                        if (activity instanceof LaunchActivity) {
                            new AudioPlayerAlert(activity, null).show();
                        } else if (AndroidUtilities.isContextSafe(LaunchActivity.instance)) {
                            new AudioPlayerAlert(LaunchActivity.instance, null).show();
                        }
                    } catch (Exception ignore) {
                    }
                } else {
                    openChatForMessage(mo);
                }
                break;
            }
            case MODE_NETWORK:
                presentFragment(new NetworkDiagnosticsActivity());
                break;
            default:
                break;
        }
    }

    private void openChatForMessage(MessageObject mo) {
        BaseFragment fragment = LaunchActivity.getSafeLastFragment();
        if (fragment == null || LaunchActivity.instance == null) {
            return;
        }
        long dialogId = mo.getDialogId();
        if (fragment instanceof ChatActivity && ((ChatActivity) fragment).getDialogId() == dialogId && fragment.getCurrentAccount() == mo.currentAccount) {
            ((ChatActivity) fragment).scrollToMessageId(mo.getId(), 0, false, 0, true, 0);
            return;
        }
        if (fragment.getCurrentAccount() != mo.currentAccount) {
            LaunchActivity.instance.switchToAccount(mo.currentAccount, true);
            fragment = LaunchActivity.getSafeLastFragment();
            if (fragment == null) {
                return;
            }
        }
        Bundle args = new Bundle();
        if (DialogObject.isEncryptedDialog(dialogId)) {
            args.putInt("enc_id", DialogObject.getEncryptedChatId(dialogId));
        } else if (DialogObject.isUserDialog(dialogId)) {
            args.putLong("user_id", dialogId);
        } else {
            args.putLong("chat_id", -dialogId);
        }
        args.putInt("message_id", mo.getId());
        fragment.presentFragment(new ChatActivity(args));
    }
}
