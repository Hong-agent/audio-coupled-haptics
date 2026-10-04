package com.gaolou.boneconduction.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;

/**
 * 一行参数滑杆：标题 + 实时数值 + SeekBar。
 *
 * <p>拖动时立刻写入 {@code sink}（也就是 EngineSettings），引擎下一分片即生效；
 * {@link #refresh()} 反向同步（用于预设切换、恢复默认后让滑杆跟上）。
 */
public final class ParamSliderRow extends LinearLayout {

    public interface Formatter {
        String format(double value);
    }

    private final String label;
    private final double min;
    private final double max;
    private final double step;
    private final DoubleSupplier getter;
    private final DoubleConsumer setter;
    private final Formatter formatter;

    private final TextView valueView;
    private final SeekBar seekBar;
    private boolean suppressCallback;

    public ParamSliderRow(Context context, String label, double min, double max, double step,
                          DoubleSupplier getter, DoubleConsumer setter, Formatter formatter) {
        super(context);
        this.label = label;
        this.min = min;
        this.max = max;
        this.step = step;
        this.getter = getter;
        this.setter = setter;
        this.formatter = formatter;

        setOrientation(VERTICAL);
        int padV = dp(9);
        setPadding(0, padV, 0, padV);

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(context);
        title.setText(label);
        title.setTextColor(0xFFB9C7DA);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        header.addView(title, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        valueView = new TextView(context);
        valueView.setTextColor(0xFF5EEAD4);
        valueView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        valueView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        header.addView(valueView);

        addView(header, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        seekBar = new SeekBar(context);
        seekBar.setMax(steps());
        LayoutParams lp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34));
        lp.topMargin = dp(2);
        addView(seekBar, lp);

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                double value = snap(progress);
                valueView.setText(formatter == null
                        ? formatNumber(value) : formatter.format(value));
                if (fromUser && !suppressCallback) {
                    setter.accept(value);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
            }
        });
        refresh();
    }

    private int steps() {
        return (int) Math.round((max - min) / step);
    }

    private double snap(int progress) {
        double v = min + progress * step;
        return v < min ? min : (v > max ? max : v);
    }

    /** 从数据源回读并刷新 UI（不回调写回，避免预设切换时的抖动）。 */
    public void refresh() {
        double value = clamp(getter.getAsDouble());
        int progress = (int) Math.round((value - min) / step);
        suppressCallback = true;
        seekBar.setProgress(progress);
        suppressCallback = false;
        valueView.setText(formatter == null ? formatNumber(value) : formatter.format(value));
    }

    private double clamp(double v) {
        return v < min ? min : (v > max ? max : v);
    }

    private static String formatNumber(double v) {
        if (Math.abs(v) >= 100) {
            return String.valueOf(Math.round(v));
        }
        return String.format(java.util.Locale.US, "%.2f", v);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
