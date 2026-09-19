package org.telegram.ui.ayu.netdiag;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.text.TextPaint;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.ayu.netdiag.NetworkDiagnostics;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.ProxyListActivity;

import java.util.Locale;

/**
 * AyuGram network diagnostics screen: the live values produced by
 * {@link NetworkDiagnostics}, three 60 second graphs, the current slowdown reason with a hint,
 * an on-demand test and a copy-to-clipboard report.
 */
public class NetworkDiagnosticsActivity extends BaseFragment implements NetworkDiagnostics.Listener {

    private static final int LINE_DOWN = 0xFF3390EC;
    private static final int LINE_UP = 0xFF4DB24D;
    private static final int LINE_PING = 0xFF3390EC;
    private static final int LINE_LOSS = 0xFFE8A33D;
    private static final int COLOR_BAD = 0xFFE03B3B;
    private static final int COLOR_WARN = 0xFFE8A33D;
    private static final int COLOR_OK = 0xFF4DB24D;

    private NetworkDiagnostics.Sample sample;
    private NetworkDiagnostics.TestResult testResult;
    private boolean sampling;
    private boolean testing;

    private TextSettingsCell dcCell;
    private TextSettingsCell stateCell;
    private TextSettingsCell pingCell;
    private TextSettingsCell proxyCell;
    private TextSettingsCell lossCell;
    private TextSettingsCell downCell;
    private TextSettingsCell upCell;
    private TextSettingsCell transfersCell;

    private TextSettingsCell reasonCell;
    private TextInfoPrivacyCell reasonHintCell;

    private GraphView pingGraph;
    private GraphView lossGraph;
    private GraphView speedGraph;

    private TextSettingsCell runTestCell;
    private TextSettingsCell copyCell;

    private LinearLayout testSection;
    private TextSettingsCell testPingCell;
    private TextSettingsCell testJitterCell;
    private TextSettingsCell testLossCell;
    private TextSettingsCell testBurstCell;

    private TextSettingsCell meteredCell;
    private TextSettingsCell dataSaverCell;
    private TextSettingsCell batteryCell;
    private TextSettingsCell wifiCell;

    // ------------------------------------------------------------------ lifecycle

    @Override
    public boolean onFragmentCreate() {
        if (!sampling) {
            sampling = true;
            NetworkDiagnostics.getInstance().addListener(this);
            NetworkDiagnostics.getInstance().start();
        }
        sample = NetworkDiagnostics.getInstance().getLastSample();
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        if (sampling) {
            sampling = false;
            NetworkDiagnostics.getInstance().removeListener(this);
            NetworkDiagnostics.getInstance().stop();
        }
        super.onFragmentDestroy();
    }

    @Override
    public void onNetworkSample(NetworkDiagnostics.Sample s) {
        sample = s;
        updateValues();
    }

    // ------------------------------------------------------------------ ui

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(getString(R.string.AyuNetDiagTitle));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        // ---- live ----
        LinearLayout live = section(context);
        live.addView(header(context, getString(R.string.AyuNetDiagLive)));
        dcCell = cell(context);
        stateCell = cell(context);
        pingCell = cell(context);
        proxyCell = cell(context);
        lossCell = cell(context);
        downCell = cell(context);
        upCell = cell(context);
        transfersCell = cell(context);
        live.addView(dcCell);
        live.addView(stateCell);
        live.addView(pingCell);
        live.addView(proxyCell);
        live.addView(lossCell);
        live.addView(downCell);
        live.addView(upCell);
        live.addView(transfersCell);
        root.addView(live, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        root.addView(spacer(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 12));

        // ---- slowdown reason ----
        LinearLayout reason = section(context);
        reason.addView(header(context, getString(R.string.AyuNetDiagSlowdown)));
        reasonCell = cell(context);
        reason.addView(reasonCell);
        root.addView(reason, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        reasonHintCell = new TextInfoPrivacyCell(context);
        root.addView(reasonHintCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // ---- graphs ----
        NetworkDiagnostics nd = NetworkDiagnostics.getInstance();
        LinearLayout graphs = section(context);
        graphs.addView(header(context, getString(R.string.AyuNetDiagGraphs)));
        pingGraph = new GraphView(context, getString(R.string.AyuNetDiagGraphPing), GraphView.KIND_PING, nd.pingHistory, null, LINE_PING, 0);
        lossGraph = new GraphView(context, getString(R.string.AyuNetDiagGraphLoss), GraphView.KIND_LOSS, nd.lossHistory, null, LINE_LOSS, 0);
        speedGraph = new GraphView(context, getString(R.string.AyuNetDiagGraphThroughput), GraphView.KIND_SPEED, nd.downHistory, nd.upHistory, LINE_DOWN, LINE_UP);
        graphs.addView(pingGraph, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 116));
        graphs.addView(lossGraph, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 116));
        graphs.addView(speedGraph, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 116));
        root.addView(graphs, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        root.addView(spacer(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 12));

        // ---- device ----
        LinearLayout device = section(context);
        device.addView(header(context, getString(R.string.AyuNetDiagDevice)));
        meteredCell = cell(context);
        dataSaverCell = cell(context);
        batteryCell = cell(context);
        wifiCell = cell(context);
        device.addView(meteredCell);
        device.addView(dataSaverCell);
        device.addView(batteryCell);
        device.addView(wifiCell);
        root.addView(device, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        root.addView(spacer(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 12));

        // ---- actions ----
        LinearLayout actions = section(context);
        runTestCell = cell(context);
        runTestCell.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
        runTestCell.setOnClickListener(v -> runTest());
        copyCell = cell(context);
        copyCell.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
        copyCell.setOnClickListener(v -> copyReport());
        TextSettingsCell proxyLink = cell(context);
        proxyLink.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
        proxyLink.setText(getString(R.string.AyuNetDiagProxySettings), false);
        proxyLink.setOnClickListener(v -> presentFragment(new ProxyListActivity()));
        actions.addView(runTestCell);
        actions.addView(copyCell);
        actions.addView(proxyLink);
        root.addView(actions, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // ---- test results ----
        testSection = section(context);
        testSection.addView(header(context, getString(R.string.AyuNetDiagTestResults)));
        testPingCell = cell(context);
        testJitterCell = cell(context);
        testLossCell = cell(context);
        testBurstCell = cell(context);
        testSection.addView(testPingCell);
        testSection.addView(testJitterCell);
        testSection.addView(testLossCell);
        testSection.addView(testBurstCell);
        testSection.setVisibility(View.GONE);
        LinearLayout testWrap = new LinearLayout(context);
        testWrap.setOrientation(LinearLayout.VERTICAL);
        testWrap.addView(spacer(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 12));
        testWrap.addView(testSection, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        root.addView(testWrap, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextInfoPrivacyCell footer = new TextInfoPrivacyCell(context);
        footer.setText(getString(R.string.AyuNetDiagFooter));
        root.addView(footer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        ScrollView scrollView = new ScrollView(context);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        scrollView.addView(root, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        fragmentView = scrollView;
        updateValues();
        return fragmentView;
    }

    private LinearLayout section(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        return layout;
    }

    private View spacer(Context context) {
        return new View(context);
    }

    private HeaderCell header(Context context, String text) {
        HeaderCell cell = new HeaderCell(context);
        cell.setText(text);
        return cell;
    }

    private TextSettingsCell cell(Context context) {
        TextSettingsCell cell = new TextSettingsCell(context);
        cell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        return cell;
    }

    // ------------------------------------------------------------------ values

    private void updateValues() {
        if (dcCell == null) {
            return;
        }
        NetworkDiagnostics.Sample s = sample;
        final String dash = "—";

        dcCell.setTextAndValue(getString(R.string.AyuNetDiagDatacenter),
                s == null || s.datacenterId <= 0 ? dash : LocaleController.formatString(R.string.AyuNetDiagDc, s.datacenterId), true, true);
        stateCell.setTextAndValue(getString(R.string.AyuNetDiagState),
                s == null ? dash : NetworkDiagnostics.getStateText(s.connectionState), true, true);
        pingCell.setTextAndValue(getString(R.string.AyuNetDiagPing),
                s == null ? dash : NetworkDiagnostics.formatPing(s.ping), true, true);
        proxyCell.setTextAndValue(getString(R.string.AyuNetDiagProxyPing),
                s == null || !s.proxyActive ? getString(R.string.AyuNetDiagNoProxy) : NetworkDiagnostics.formatProxyPing(s.proxyPing), true, true);
        lossCell.setTextAndValue(getString(R.string.AyuNetDiagLoss),
                s == null ? dash : NetworkDiagnostics.formatLoss(s.loss), true, true);
        downCell.setTextAndValue(getString(R.string.AyuNetDiagDownload),
                s == null ? dash : NetworkDiagnostics.formatSpeed(s.downSpeed) + " (" + NetworkDiagnostics.formatSpeed(s.activeDownSpeed) + ")", true, true);
        upCell.setTextAndValue(getString(R.string.AyuNetDiagUpload),
                s == null ? dash : NetworkDiagnostics.formatSpeed(s.upSpeed) + " (" + NetworkDiagnostics.formatSpeed(s.activeUpSpeed) + ")", true, true);
        transfersCell.setTextAndValue(getString(R.string.AyuNetDiagActiveTransfers),
                s == null ? dash : LocaleController.formatString(R.string.AyuNetDiagTransfersValue, s.activeDownloads, s.activeUploads), true, false);

        int reasonColor = s == null ? Theme.getColor(Theme.key_windowBackgroundWhiteBlackText) : severityColor(s.severity());
        reasonCell.setTextColor(reasonColor);
        reasonCell.setText(s == null ? dash : NetworkDiagnostics.getReasonText(s.reason), false);
        reasonHintCell.setText(s == null ? "" : NetworkDiagnostics.getReasonHint(s.reason));

        meteredCell.setTextAndValue(getString(R.string.AyuNetDiagMetered), yesNo(s != null && s.metered), true, true);
        dataSaverCell.setTextAndValue(getString(R.string.AyuNetDiagDataSaver), yesNo(s != null && s.dataSaver), true, true);
        batteryCell.setTextAndValue(getString(R.string.AyuNetDiagBatterySaver), yesNo(s != null && s.powerSave), true, true);
        wifiCell.setTextAndValue(getString(R.string.AyuNetDiagWifiSignal),
                s == null || !s.wifi || s.wifiRssi == 0 ? dash : LocaleController.formatString(R.string.AyuNetDiagDbm, s.wifiRssi), true, false);

        runTestCell.setText(getString(testing ? R.string.AyuNetDiagRunning : R.string.AyuNetDiagRunTest), true);
        copyCell.setText(getString(R.string.AyuNetDiagCopyReport), true);

        if (pingGraph != null) {
            pingGraph.invalidate();
            lossGraph.invalidate();
            speedGraph.invalidate();
        }
    }

    private static int severityColor(int severity) {
        switch (severity) {
            case NetworkDiagnostics.SEVERITY_BAD:
                return COLOR_BAD;
            case NetworkDiagnostics.SEVERITY_WARN:
                return COLOR_WARN;
            default:
                return COLOR_OK;
        }
    }

    private static String yesNo(boolean value) {
        return getString(value ? R.string.AyuNetDiagYes : R.string.AyuNetDiagNo);
    }

    // ------------------------------------------------------------------ test

    private void runTest() {
        if (testing || NetworkDiagnostics.getInstance().isTestRunning()) {
            return;
        }
        testing = true;
        updateValues();
        NetworkDiagnostics.getInstance().runTest(UserConfig.selectedAccount, new NetworkDiagnostics.TestCallback() {
            @Override
            public void onProgress(float progress) {
                // the row already says "Testing…"; nothing else to show while it runs
            }

            @Override
            public void onFinished(NetworkDiagnostics.TestResult result) {
                testing = false;
                testResult = result;
                showTestResult(result);
                updateValues();
            }
        });
    }

    private void showTestResult(NetworkDiagnostics.TestResult r) {
        if (testSection == null || r == null) {
            return;
        }
        testSection.setVisibility(View.VISIBLE);
        String dash = "—";
        testPingCell.setTextAndValue(getString(R.string.AyuNetDiagTestPing),
                r.avgPing < 0 ? dash : LocaleController.formatString(R.string.AyuNetDiagTestPingValue, r.minPing, r.avgPing, r.maxPing), true, true);
        testJitterCell.setTextAndValue(getString(R.string.AyuNetDiagTestJitter),
                r.avgPing < 0 ? dash : LocaleController.formatString(R.string.AyuNetDiagMs, (int) r.jitter), true, true);
        testLossCell.setTextAndValue(getString(R.string.AyuNetDiagTestLoss),
                LocaleController.formatString(R.string.AyuNetDiagTestLossValue, r.lost, r.probes), true, true);
        testBurstCell.setTextAndValue(getString(R.string.AyuNetDiagTestBurst),
                r.burstRequests == 0 ? getString(R.string.AyuNetDiagTestFailed)
                        : LocaleController.formatString(R.string.AyuNetDiagTestBurstValue,
                        NetworkDiagnostics.formatSpeed(r.burstSpeed), r.burstRequests, (int) r.burstDurationMs), true, false);
    }

    // ------------------------------------------------------------------ report

    private void copyReport() {
        StringBuilder sb = new StringBuilder();
        NetworkDiagnostics.Sample s = sample;
        sb.append("OcoderX-gram network diagnostics\n");
        sb.append("account: ").append(UserConfig.selectedAccount).append('\n');
        if (s != null) {
            sb.append("dc: ").append(s.datacenterId).append('\n');
            sb.append("state: ").append(stateName(s.connectionState)).append('\n');
            sb.append("ping: ").append(s.ping).append(" ms\n");
            sb.append("loss: ").append(String.format(Locale.US, "%.1f", s.loss)).append("% (")
                    .append(s.probesLost).append('/').append(s.probesSent).append(" probes)\n");
            sb.append("down: ").append(s.downSpeed).append(" B/s (files ").append(s.activeDownSpeed).append(" B/s, ").append(s.activeDownloads).append(" active)\n");
            sb.append("up: ").append(s.upSpeed).append(" B/s (files ").append(s.activeUpSpeed).append(" B/s, ").append(s.activeUploads).append(" active)\n");
            sb.append("proxy: ").append(s.proxyActive ? s.proxyAddress + " " + s.proxyPing + " ms" : "off").append('\n');
            sb.append("metered: ").append(s.metered).append(", dataSaver: ").append(s.dataSaver)
                    .append(", batterySaver: ").append(s.powerSave).append('\n');
            sb.append("wifi: ").append(s.wifi ? s.wifiRssi + " dBm" : "no").append('\n');
            sb.append("reason: ").append(NetworkDiagnostics.getReasonText(s.reason)).append('\n');
        } else {
            sb.append("no sample yet\n");
        }
        NetworkDiagnostics.TestResult r = testResult;
        if (r != null) {
            sb.append("--- test ---\n");
            sb.append("ping min/avg/max: ").append(r.minPing).append('/').append(r.avgPing).append('/').append(r.maxPing).append(" ms\n");
            sb.append("jitter: ").append(String.format(Locale.US, "%.1f", r.jitter)).append(" ms\n");
            sb.append("lost: ").append(r.lost).append('/').append(r.probes).append('\n');
            if (r.proxyChecked) {
                sb.append("proxy check: ").append(r.proxyPing).append(" ms\n");
            }
            sb.append("burst: ").append(r.burstRequests).append(" replies in ").append(r.burstDurationMs)
                    .append(" ms, ").append(r.burstSpeed).append(" B/s\n");
        }
        sb.append("--- history (newest last) ---\n");
        sb.append("ping: ").append(dumpRing(NetworkDiagnostics.getInstance().pingHistory)).append('\n');
        sb.append("loss: ").append(dumpRing(NetworkDiagnostics.getInstance().lossHistory)).append('\n');

        AndroidUtilities.addToClipboard(sb.toString());
        if (BulletinFactory.canShowBulletin(this)) {
            BulletinFactory.of(this).createCopyBulletin(getString(R.string.AyuNetDiagCopied)).show();
        }
    }

    private static String dumpRing(NetworkDiagnostics.Ring ring) {
        float[] buf = new float[NetworkDiagnostics.HISTORY_SIZE];
        int n = ring.copyTo(buf);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append((int) buf[i]);
        }
        return sb.toString();
    }

    private static String stateName(int state) {
        switch (state) {
            case ConnectionsManager.ConnectionStateConnecting:
                return "connecting";
            case ConnectionsManager.ConnectionStateWaitingForNetwork:
                return "waitingForNetwork";
            case ConnectionsManager.ConnectionStateConnectingToProxy:
                return "connectingToProxy";
            case ConnectionsManager.ConnectionStateUpdating:
                return "updating";
            default:
                return "connected";
        }
    }

    // ------------------------------------------------------------------ graph

    /** A tiny line chart over one or two {@link NetworkDiagnostics.Ring}s. */
    private static class GraphView extends View {

        static final int KIND_PING = 0;
        static final int KIND_LOSS = 1;
        static final int KIND_SPEED = 2;

        private final String title;
        private final int kind;
        private final NetworkDiagnostics.Ring primary;
        private final NetworkDiagnostics.Ring secondary;
        private final int primaryColor;
        private final int secondaryColor;

        private final float[] buf = new float[NetworkDiagnostics.HISTORY_SIZE];
        private final float[] buf2 = new float[NetworkDiagnostics.HISTORY_SIZE];
        private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint gridPaint = new Paint();
        private final TextPaint titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint valuePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        GraphView(Context context, String title, int kind, NetworkDiagnostics.Ring primary,
                  NetworkDiagnostics.Ring secondary, int primaryColor, int secondaryColor) {
            super(context);
            this.title = title;
            this.kind = kind;
            this.primary = primary;
            this.secondary = secondary;
            this.primaryColor = primaryColor;
            this.secondaryColor = secondaryColor;

            linePaint.setStyle(Paint.Style.STROKE);
            linePaint.setStrokeWidth(dp(1.8f));
            linePaint.setStrokeCap(Paint.Cap.ROUND);
            linePaint.setStrokeJoin(Paint.Join.ROUND);
            gridPaint.setStrokeWidth(Math.max(1, dp(0.5f)));
            titlePaint.setTextSize(dp(13));
            titlePaint.setTypeface(AndroidUtilities.bold());
            valuePaint.setTextSize(dp(12));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final float left = dp(21);
            final float right = getWidth() - dp(21);
            final float top = dp(28);
            final float bottom = getHeight() - dp(14);
            if (right <= left || bottom <= top) {
                return;
            }

            titlePaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            canvas.drawText(title, left, dp(19), titlePaint);

            int n = primary.copyTo(buf);
            int n2 = secondary != null ? secondary.copyTo(buf2) : 0;

            float max = minimumScale();
            for (int i = 0; i < n; i++) {
                max = Math.max(max, buf[i]);
            }
            for (int i = 0; i < n2; i++) {
                max = Math.max(max, buf2[i]);
            }

            valuePaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
            String maxLabel = formatScale(max);
            canvas.drawText(maxLabel, right - valuePaint.measureText(maxLabel), dp(19), valuePaint);

            gridPaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText4));
            gridPaint.setAlpha(50);
            for (int i = 0; i <= 2; i++) {
                float y = top + (bottom - top) * i / 2f;
                canvas.drawLine(left, y, right, y, gridPaint);
            }

            if (n < 2) {
                valuePaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
                String empty = getString(R.string.AyuNetDiagGraphEmpty);
                canvas.drawText(empty, (left + right) / 2f - valuePaint.measureText(empty) / 2f, (top + bottom) / 2f, valuePaint);
                return;
            }

            drawSeries(canvas, buf, n, max, left, top, right, bottom, primaryColor);
            if (n2 >= 2) {
                drawSeries(canvas, buf2, n2, max, left, top, right, bottom, secondaryColor);
            }
        }

        private void drawSeries(Canvas canvas, float[] values, int count, float max,
                                float left, float top, float right, float bottom, int color) {
            path.rewind();
            for (int i = 0; i < count; i++) {
                float x = left + (right - left) * i / (count - 1f);
                float y = bottom - (bottom - top) * Math.min(1f, values[i] / max);
                if (i == 0) {
                    path.moveTo(x, y);
                } else {
                    path.lineTo(x, y);
                }
            }
            linePaint.setColor(color);
            canvas.drawPath(path, linePaint);
        }

        private float minimumScale() {
            switch (kind) {
                case KIND_LOSS:
                    return 10f;
                case KIND_SPEED:
                    return 64 * 1024f;
                default:
                    return 100f;
            }
        }

        private String formatScale(float max) {
            switch (kind) {
                case KIND_LOSS:
                    return String.format(Locale.US, "%.0f%%", max);
                case KIND_SPEED:
                    return NetworkDiagnostics.formatSpeed((long) max);
                default:
                    return LocaleController.formatString(R.string.AyuNetDiagMs, (int) max);
            }
        }
    }
}
