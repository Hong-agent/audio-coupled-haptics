package com.gaolou.boneconduction;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.function.BooleanSupplier;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.gaolou.boneconduction.core.EngineConfig;
import com.gaolou.boneconduction.core.EngineSettings;
import com.gaolou.boneconduction.core.EngineStatus;
import com.gaolou.boneconduction.service.EngineService;
import com.gaolou.boneconduction.ui.HapticOrbView;
import com.gaolou.boneconduction.ui.ParamSliderRow;

/**
 * 控制面板：启动/停止、实时状态、以及全部可调参数。
 *
 * <p>所有滑杆直接写 {@link EngineSettings}，引擎在下一分片（默认 500ms 内）即读取新值，
 * 因此不需要「应用」按钮，拖动即生效。
 */
public final class MainActivity extends Activity {

    private static final int REQ_PERMISSIONS = 100;
    private static final int REQ_PROJECTION = 101;
    private static final long TICK_MS = 250;

    private EngineSettings settings;
    private EngineStatus status;

    private HapticOrbView orb;
    private TextView stateBadge;
    private TextView logLine;
    private LinearLayout metricsCard;
    private LinearLayout paramContainer;

    private final List<ParamSliderRow> sliders = new ArrayList<>();
    private final List<TextView> presetChips = new ArrayList<>();
    private final Map<String, TextView> metricValues = new LinkedHashMap<>();
    private final List<SwitchBinding> switches = new ArrayList<>();

    private EngineSettings.Listener settingsListener;

    /** 开关与参数源的绑定：恢复默认/切换预设后，开关也要跟着走。 */
    private static final class SwitchBinding {
        final Switch view;
        final BooleanSupplier getter;
        CompoundButton.OnCheckedChangeListener listener;

        SwitchBinding(Switch view, BooleanSupplier getter) {
            this.view = view;
            this.getter = getter;
        }
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean tickScheduled;
    private boolean startAfterPermission;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!tickScheduled) {
                return;
            }
            refreshState();
            handler.postDelayed(this, TICK_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        settings = EngineSettings.get(this);
        status = EngineStatus.get();

        orb = findViewById(R.id.orb);
        stateBadge = findViewById(R.id.stateBadge);
        logLine = findViewById(R.id.logLine);
        metricsCard = findViewById(R.id.metricsCard);
        paramContainer = findViewById(R.id.paramContainer);

        orb.setOnClickListener(v -> toggleEngine());

        buildMetrics();
        buildParams();

        settingsListener = key -> handler.post(() -> {
            if (EngineSettings.Keys.PRESET.equals(key) || "reset".equals(key)) {
                refreshSliders();
            }
            updateChips();
        });
        settings.addListener(settingsListener);
    }

    @Override
    protected void onDestroy() {
        if (settingsListener != null) {
            settings.removeListener(settingsListener);
            settingsListener = null;
        }
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!tickScheduled) {
            tickScheduled = true;
            handler.post(ticker);
        }
        updateChips();
        refreshSliders();
    }

    @Override
    protected void onPause() {
        tickScheduled = false;
        handler.removeCallbacks(ticker);
        super.onPause();
    }

    // ===================== 启停流程 =====================

    private void toggleEngine() {
        if (EngineService.isRunning()) {
            EngineService.stop(this);
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            startAfterPermission = true;
            List<String> perms = new ArrayList<>();
            perms.add(Manifest.permission.RECORD_AUDIO);
            if (Build.VERSION.SDK_INT >= 33) {
                perms.add(Manifest.permission.POST_NOTIFICATIONS);
            }
            requestPermissions(perms.toArray(new String[0]), REQ_PERMISSIONS);
            return;
        }
        requestProjection();
    }

    private void requestProjection() {
        // 先说明系统弹窗的含义：Android 抓内部音频只能借 MediaProjection 这道门，
        // 但本应用只取声音，不采集、不保存、不上传任何画面。
        new AlertDialog.Builder(this)
                .setTitle("录制系统内声音")
                .setMessage("接下来是 Android 的系统授权页，标题会写成「录制或投放」——"
                        + "这是系统抓取内部音频的唯一入口，任何应用都不能绕过。\n\n"
                        + "本应用只读取声音数据，不采集画面。\n\n"
                        + "授权页里建议选择「共享一个应用」并选中正在播放的音乐/视频 App："
                        + "这样只录制它的声音，更省电，也不会暴露屏幕上的其它内容。")
                .setPositiveButton("继续", (dialog, which) -> launchProjection())
                .setNegativeButton("取消", null)
                .show();
    }

    private void launchProjection() {
        MediaProjectionManager manager =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        Intent intent;
        if (Build.VERSION.SDK_INT >= 34) {
            // 允许用户只授权「单个应用」的音频，而不是整屏
            intent = manager.createScreenCaptureIntent(
                    MediaProjectionConfig.createConfigForUserChoice());
        } else {
            intent = manager.createScreenCaptureIntent();
        }
        startActivityForResult(intent, REQ_PROJECTION);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQ_PERMISSIONS) {
            return;
        }
        boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        if (granted && startAfterPermission) {
            startAfterPermission = false;
            requestProjection();
            return;
        }
        startAfterPermission = false;
        if (!granted) {
            status.message = "需要录音权限才能内录系统声音";
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PROJECTION) {
            return;
        }
        if (resultCode == RESULT_OK && data != null) {
            status.message = "正在启动…";
            EngineService.start(this, resultCode, data);
        } else {
            status.message = "未授权屏幕录制，无法内录系统声音";
        }
    }

    // ===================== 状态刷新 =====================

    private void refreshState() {
        EngineStatus.Snapshot s = status.snapshot(settings.segmentMs());
        boolean running = EngineService.isRunning();

        orb.setRunning(running);
        orb.setStarting(s.state == EngineStatus.STATE_STARTING);
        orb.setLevels(s.hapticPeak, s.inputLevel);

        if (s.state == EngineStatus.STATE_ERROR) {
            stateBadge.setText("异常");
            stateBadge.setTextColor(0xFFFB7185);
        } else if (running) {
            stateBadge.setText("运行中");
            stateBadge.setTextColor(0xFF5EEAD4);
        } else if (s.state == EngineStatus.STATE_STARTING) {
            stateBadge.setText("启动中");
            stateBadge.setTextColor(0xFFFBBF24);
        } else {
            stateBadge.setText("待机");
            stateBadge.setTextColor(0xFF8A9BB4);
        }

        setMetric("采集时长", String.format(Locale.US, "%.1f s", s.capturedFrames / 48000f));
        setMetric("编码耗时", String.format(Locale.US, "%.0f ms", s.lastEncodeMicros / 1000f));
        setMetric("平均编码", String.format(Locale.US, "%.0f ms", s.avgEncodeMicros / 1000f));
        setMetric("播放分片", String.valueOf(s.playedSegments));
        setMetric("待播队列", s.pendingSegments + " / " + settings.maxPending());
        setMetric("触觉峰值", String.format(Locale.US, "%.0f%%", s.hapticPeak * 100));
        setMetric("压缩增益", String.format(Locale.US, "%.2f×", s.compressionGain));
        setMetric("输入电平", String.format(Locale.US, "%.0f%%", s.inputLevel * 100));
        setMetric("触觉RMS", String.format(Locale.US, "%.1f%%", s.hapticRms * 100));
        setMetric("静音原声", s.mutedForeign
                ? (s.mutedSessionCount + " 个会话") : "未生效");

        StringBuilder sb = new StringBuilder();
        sb.append("状态   ").append(s.message).append('\n');
        sb.append("采集   仅系统内声音（不采集画面）").append('\n');
        sb.append("能力   触觉播放=").append(s.hapticSupported ? "支持" : "不支持")
                .append("  振幅=").append(s.amplitudeControl ? "支持" : "不支持").append('\n');
        sb.append("设备   ").append(s.deviceName).append('\n');
        sb.append("队列   采集=").append(s.captureQueueDepth)
                .append("  编码=").append(s.encodeQueueDepth)
                .append("  丢段=").append(s.droppedSegments).append('\n');
        sb.append("分片   ").append(s.segmentMs).append(" ms  品质=")
                .append(String.format(Locale.US, "%.2f", settings.encodeQuality()))
                .append("  对齐=").append(settings.alignMs()).append(" ms").append('\n');
        sb.append("路由   ").append(s.routeState)
                .append(s.routeApplied ? "（已下发）" : "（未确认）")
                .append("  通话音量=").append(s.voiceCallVolume).append('/')
                .append(s.voiceCallMax).append('\n');
        sb.append("静音   ").append(s.muteBackend)
                .append("  编码失败=").append(s.encodedFailures);
        logLine.setText(sb.toString());
    }

    private void setMetric(String key, String value) {
        TextView tv = metricValues.get(key);
        if (tv != null) {
            tv.setText(value);
        }
    }

    // ===================== 指标卡 =====================

    private void buildMetrics() {
        metricsCard.removeAllViews();
        metricsCard.addView(sectionTitle("实时状态", "参数改动会在下一个分片内生效"));
        LinearLayout row = null;
        String[] keys = {"采集时长", "编码耗时", "平均编码", "播放分片",
                "待播队列", "触觉峰值", "输入电平", "触觉RMS",
                "压缩增益", "静音原声"};
        for (int i = 0; i < keys.length; i++) {
            if (i % 2 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rlp.topMargin = dp(10);
                metricsCard.addView(row, rlp);
            }
            row.addView(buildMetricCell(keys[i]), new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
    }

    private View buildMetricCell(String key) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);

        TextView label = new TextView(this);
        label.setText(key);
        label.setTextColor(0xFF8A9BB4);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        cell.addView(label);

        TextView value = new TextView(this);
        value.setText("—");
        value.setTextColor(0xFFE8EFF9);
        value.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        value.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        value.setPadding(0, dp(2), 0, dp(2));
        cell.addView(value);

        metricValues.put(key, value);
        return cell;
    }

    // ===================== 参数区 =====================

    private void buildParams() {
        paramContainer.removeAllViews();
        sliders.clear();
        presetChips.clear();
        switches.clear();

        // ---- 预设 ----
        LinearLayout presetCard = buildCard("调音预设", "一键切换一整套互相自洽的触觉参数", 0xFF5EEAD4);
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout chipRow = new LinearLayout(this);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        scroll.addView(chipRow);
        for (EngineSettings.Preset preset : EngineSettings.Preset.values()) {
            TextView chip = buildChip(preset);
            chipRow.addView(chip);
            presetChips.add(chip);
        }
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(10);
        presetCard.addView(scroll, slp);
        paramContainer.addView(presetCard);

        // ---- 触觉塑形 ----
        LinearLayout shaping = buildCard("触觉塑形",
                "高通去低音轰击；高频上限与载波驱动用于消除破音（默认关闭，保持原有手感）", 0xFF818CF8);
        addSlider(shaping, "震动强度", EngineConfig.OUTPUT_SCALE_MIN, EngineConfig.OUTPUT_SCALE_MAX, 0.01, 100,
                "%.0f%%", settings::outputScale, v -> settings.setOutputScale((float) v));
        addSlider(shaping, "高通截止", 40, 1200, 10, 1,
                "%.0f Hz", settings::highpassHz, v -> settings.setHighpassHz((float) v));
        addSlider(shaping, "高通阶数", 1, 4, 1, 1,
                "%.0f 阶", settings::highpassOrder, v -> settings.setHighpassOrder((int) v));
        addSlider(shaping, "低频混回", 0, 0.6, 0.01, 100,
                "%.0f%%", settings::lowShelf, v -> settings.setLowShelf((float) v));
        addSlider(shaping, "高频上限（低通）", 60, 20000, 50, 1,
                "%.0f Hz", settings::lowpassHz, v -> settings.setLowpassHz((float) v));
        addSlider(shaping, "安静门槛", 0, 0.1, 0.002, 1,
                "%.3f RMS", settings::gateRms, v -> settings.setGateRms((float) v));
        addSlider(shaping, "软削波上限", 0.3, 1.0, 0.01, 100,
                "%.0f%%", settings::softClipLimit, v -> settings.setSoftClipLimit((float) v));
        addSwitch(shaping, "启用软削波", "平滑限制峰值，避免刺耳失真",
                settings::softClipEnabled, settings::setSoftClipEnabled);
        addSwitch(shaping, "谐振载波驱动",
                "用马达谐振频率重新合成振感：彻底不破音，但会改变原有手感（默认关）",
                settings::carrierEnabled, settings::setCarrierEnabled);
        addSlider(shaping, "谐振频率", 80, 260, 5, 1,
                "%.0f Hz", settings::carrierHz, v -> settings.setCarrierHz((float) v));
        addSwitch(shaping, "只保留振动（静音左右声道）", "推荐开启：只让马达跟节奏，不叠加延迟重放声（避免发闷/像卡顿）",
                settings::muteLr, settings::setMuteLr);
        paramContainer.addView(shaping);

        // ---- 动态压缩 ----
        LinearLayout comp = buildCard("动态压缩", "安静段不丢、响段不炸，调音量也不失效", 0xFFF472B6);
        addSwitch(comp, "启用动态压缩", "关闭后触觉随绝对电平走，调音量会变弱",
                settings::compressEnabled, settings::setCompressEnabled);
        addSlider(comp, "压缩阈值", 0.02, 1.0, 0.01, 100,
                "%.0f%%", settings::compressThreshold, v -> settings.setCompressThreshold((float) v));
        addSlider(comp, "压缩比", 1, 12, 0.1, 1,
                "%.1f : 1", settings::compressRatio, v -> settings.setCompressRatio((float) v));
        addSlider(comp, "起音响应", 0.01, 1.0, 0.01, 1,
                "%.2f", settings::compressAttack, v -> settings.setCompressAttack((float) v));
        addSlider(comp, "释放响应", 0.01, 0.6, 0.01, 1,
                "%.2f", settings::compressRelease, v -> settings.setCompressRelease((float) v));
        paramContainer.addView(comp);

        // ---- 编码与队列 ----
        LinearLayout encode = buildCard("编码与队列", "编码耗时接近分片时长就会断震，可在此权衡", 0xFFFBBF24);
        addSlider(encode, "编码质量", 0.05, 1.0, 0.05, 1,
                "%.2f", settings::encodeQuality, v -> settings.setEncodeQuality((float) v));
        addSlider(encode, "分片时长", 200, 1000, 100, 1,
                "%.0f ms", settings::segmentMs, v -> settings.setSegmentMs((int) v));
        addSlider(encode, "播放队列上限", 2, 16, 1, 1,
                "%.0f 段", settings::maxPending, v -> settings.setMaxPending((int) v));
        addSlider(encode, "采集队列上限", 2, 16, 1, 1,
                "%.0f 块", settings::captureQueue, v -> settings.setCaptureQueue((int) v));
        addSlider(encode, "起播预缓冲", 1, 8, 1, 1,
                "%.0f 段", settings::startPrebuffer, v -> settings.setStartPrebuffer((int) v));
        paramContainer.addView(encode);

        // ---- 对齐与静音 ----
        LinearLayout align = buildCard("对齐与静音", "对齐延迟用于让振感与听到的声音匹配", 0xFF5EEAD4);
        addSlider(align, "震动对齐", -2000, 2000, 50, 1,
                "%+.0f ms", settings::alignMs, v -> settings.setAlignMs((int) v));
        addSwitch(align, "静音系统原声（消回声）", "需要 root / Shizuku，否则自动降级属预期",
                settings::muteForeign, settings::setMuteForeign);
        addSlider(align, "静音轮询周期", 400, 5000, 100, 0.001,
                "%.1f s", settings::mutePollMs, v -> settings.setMutePollMs((int) v));
        addSlider(align, "静音深度", -96, -10, 2, 1,
                "%.0f dB", settings::muteGainDb, v -> settings.setMuteGainDb((float) v));
        paramContainer.addView(align);

        // ---- 底部操作 ----
        LinearLayout actions = buildCard("维护", "参数会持久保存，下次启动沿用", 0xFF8A9BB4);
        TextView reset = buildActionButton("恢复出厂默认参数");
        reset.setOnClickListener(v -> {
            settings.resetToDefaults();
            refreshSliders();
            updateChips();
        });
        actions.addView(reset);

        TextView hint = new TextView(this);
        hint.setText("首次开始需要授予「录音」与「屏幕录制」权限；"
                + "声音会被路由到听筒，因此外放音量偏小属正常现象。"
                + "若设备只显示「能播不震」，说明该机型未开放音频耦合触觉。");
        hint.setTextColor(0xFF5A6B84);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        hint.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = dp(10);
        actions.addView(hint, hlp);
        paramContainer.addView(actions);
    }

    private TextView buildChip(EngineSettings.Preset preset) {
        TextView chip = new TextView(this);
        chip.setText(preset.label);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        chip.setPadding(dp(16), dp(8), dp(16), dp(8));
        chip.setBackgroundResource(R.drawable.chip_bg);
        chip.setTag(preset);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        chip.setLayoutParams(lp);
        chip.setOnClickListener(v -> settings.applyPreset((EngineSettings.Preset) v.getTag()));
        return chip;
    }

    private void updateChips() {
        String current = settings.presetName();
        for (TextView chip : presetChips) {
            EngineSettings.Preset preset = (EngineSettings.Preset) chip.getTag();
            boolean selected = preset.name().equals(current);
            chip.setSelected(selected);
            chip.setTextColor(selected ? 0xFFE8EFF9 : 0xFF8A9BB4);
        }
    }

    private void refreshSliders() {
        for (ParamSliderRow row : sliders) {
            row.refresh();
        }
        for (SwitchBinding binding : switches) {
            boolean value = binding.getter.getAsBoolean();
            if (binding.view.isChecked() != value) {
                binding.view.setOnCheckedChangeListener(null);
                binding.view.setChecked(value);
                binding.view.setOnCheckedChangeListener(binding.listener);
            }
        }
    }

    private LinearLayout buildCard(String title, String subtitle, int accent) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.card_bg);
        int p = dp(16);
        card.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(14);
        card.setLayoutParams(lp);

        card.addView(sectionTitle(title, subtitle, accent));
        return card;
    }

    private View sectionTitle(String title, String subtitle) {
        return sectionTitle(title, subtitle, 0xFF5EEAD4);
    }

    private View sectionTitle(String title, String subtitle, int accent) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);

        View dot = new View(this);
        android.graphics.drawable.GradientDrawable dotBg =
                new android.graphics.drawable.GradientDrawable();
        dotBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        dotBg.setColor(accent);
        dot.setBackground(dotBg);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(8), dp(8));
        dlp.rightMargin = dp(10);
        box.addView(dot, dlp);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(0xFFE8EFF9);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        texts.addView(t);

        if (subtitle != null && !subtitle.isEmpty()) {
            TextView s = new TextView(this);
            s.setText(subtitle);
            s.setTextColor(0xFF8A9BB4);
            s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            s.setPadding(0, dp(2), 0, 0);
            texts.addView(s);
        }
        box.addView(texts);
        return box;
    }

    private void addSlider(LinearLayout card, String label, double min, double max, double step,
                           double displayScale, String format,
                           java.util.function.DoubleSupplier getter,
                           java.util.function.DoubleConsumer setter) {
        ParamSliderRow row = new ParamSliderRow(this, label, min, max, step, getter, setter,
                value -> String.format(Locale.US, format, value * displayScale));
        card.addView(row);
        sliders.add(row);
    }

    private void addSwitch(LinearLayout card, String label, String subtitle,
                           BooleanSupplier getter, java.util.function.Consumer<Boolean> setter) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(0, dp(9), 0, dp(3));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(0xFFB9C7DA);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        texts.addView(t);
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView s = new TextView(this);
            s.setText(subtitle);
            s.setTextColor(0xFF5A6B84);
            s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
            texts.addView(s);
        }
        box.addView(texts, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Switch sw = new Switch(this);
        sw.setChecked(getter.getAsBoolean());
        CompoundButton.OnCheckedChangeListener listener =
                (CompoundButton b, boolean checked) -> setter.accept(checked);
        sw.setOnCheckedChangeListener(listener);
        SwitchBinding binding = new SwitchBinding(sw, getter);
        binding.listener = listener;
        switches.add(binding);
        box.addView(sw);
        card.addView(box);
    }

    private TextView buildActionButton(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setGravity(Gravity.CENTER);
        tv.setTextColor(0xFFE8EFF9);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        tv.setPadding(dp(12), dp(12), dp(12), dp(12));
        tv.setBackgroundResource(R.drawable.chip_bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        tv.setLayoutParams(lp);
        tv.setClickable(true);
        return tv;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
