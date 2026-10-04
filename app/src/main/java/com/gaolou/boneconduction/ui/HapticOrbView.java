package com.gaolou.boneconduction.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/**
 * 主控球：中心是启动/停止按钮，外圈随触觉电平呼吸并旋转，
 * 内部竖条实时反映马达驱动波形的强度——让「参数改动即时生效」肉眼可见。
 */
public final class HapticOrbView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arcRect = new RectF();

    private RadialGradient glowShader;
    private RadialGradient innerShader;
    private float shaderRadius;
    private boolean shaderRunning;
    private float glowRadius;
    private int shaderQuant = -1;

    private boolean running;
    private boolean starting;
    private float targetLevel;
    private float targetInput;
    private float smoothLevel;
    private float smoothInput;
    private float phase;
    private String centerText = "启动";
    private String caption = "点我开始录制系统内声音";

    public HapticOrbView(Context context) {
        super(context);
        init();
    }

    public HapticOrbView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public HapticOrbView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setClickable(true);
        setFocusable(true);
    }

    public void setRunning(boolean running) {
        this.running = running;
        this.centerText = running ? "停止" : "启动";
        this.caption = running ? "正在录制系统内声音并驱动马达" : "点我开始录制系统内声音";
        invalidate();
    }

    public void setStarting(boolean starting) {
        this.starting = starting;
        this.centerText = starting ? "启动中" : (running ? "停止" : "启动");
        invalidate();
    }

    public void setLevels(float hapticLevel, float inputLevel) {
        this.targetLevel = clamp(hapticLevel);
        this.targetInput = clamp(inputLevel);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        float cx = w / 2f;
        float cy = h / 2f + 6f;
        float radius = Math.min(w, h) * 0.34f;

        smoothLevel += (targetLevel - smoothLevel) * 0.22f;
        smoothInput += (targetInput - smoothInput) * 0.22f;
        float active = Math.max(smoothLevel, running ? 0.06f : 0.02f);

        // shader 只在尺寸/状态/量化后的强度变化时重建，避免每帧分配（lint DrawAllocation）。
        // 触发半径与绘制半径保持一致，圆边界处 alpha 自然归零，不会再出现硬边方块。
        int quant = (int) (active * 20f);
        if (glowShader == null || Math.abs(radius - shaderRadius) > 0.5f
                || shaderRunning != running || shaderQuant != quant) {
            rebuildShaders(cx, cy, radius, running, quant, w, h);
        }

        // 1) 光晕
        paint.setShader(glowShader);
        paint.setAlpha(clampAlpha((int) (60 + active * 180)));
        canvas.drawCircle(cx, cy, glowRadius, paint);
        paint.setAlpha(255);
        paint.setShader(null);

        // 2) 外圈旋转弧
        float ringRadius = radius + 26f;
        arcRect.set(cx - ringRadius, cy - ringRadius, cx + ringRadius, cy + ringRadius);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(3f);
        paint.setColor(0x1AFFFFFF);
        canvas.drawCircle(cx, cy, ringRadius, paint);

        float sweep = 70f + active * 150f;
        paint.setStrokeWidth(4f);
        paint.setColor(0xFF5EEAD4);
        canvas.drawArc(arcRect, phase, sweep, false, paint);
        paint.setColor(0xFF818CF8);
        canvas.drawArc(arcRect, phase + 130f, sweep * 0.7f, false, paint);
        paint.setColor(0xFFF472B6);
        canvas.drawArc(arcRect, phase + 250f, sweep * 0.45f, false, paint);

        // 3) 内圆
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(innerShader);
        canvas.drawCircle(cx, cy, radius, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.5f);
        paint.setColor(running ? 0x665EEAD4 : 0x33FFFFFF);
        canvas.drawCircle(cx, cy, radius, paint);

        // 4) 波形竖条
        paint.setStyle(Paint.Style.FILL);
        int bars = 7;
        float barW = 5f;
        float gap = 9f;
        float totalW = bars * barW + (bars - 1) * gap;
        float x0 = cx - totalW / 2f + barW / 2f;
        for (int i = 0; i < bars; i++) {
            double t = (System.currentTimeMillis() % 1400) / 1400.0 * Math.PI * 2 + i * 0.7;
            float wobble = (float) (0.5 + 0.5 * Math.abs(Math.sin(t)));
            float amp = 10f + (radius * 0.72f) * clamp(smoothLevel * 1.15f + smoothInput * 0.25f) * wobble;
            float x = x0 + i * (barW + gap);
            int color = i % 3 == 0 ? 0xFF5EEAD4 : (i % 3 == 1 ? 0xFF818CF8 : 0xFFF472B6);
            paint.setColor(running ? color : 0xFF3A4658);
            canvas.drawRoundRect(x - barW / 2f, cy - amp / 2f, x + barW / 2f, cy + amp / 2f,
                    barW / 2f, barW / 2f, paint);
        }

        // 5) 中央文字
        paint.setColor(running ? 0xFFE8EFF9 : 0xFFB9C7DA);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(30f);
        paint.setFakeBoldText(true);
        canvas.drawText(centerText, cx, cy + radius + 62f, paint);
        paint.setFakeBoldText(false);

        paint.setTextSize(20f);
        paint.setColor(0xFF8A9BB4);
        canvas.drawText(caption, cx, cy + radius + 90f, paint);

        // 6) 启动中 / 运行中的呼吸动画
        if (running || starting) {
            phase += 1.6f + active * 6f;
            if (phase > 360f) {
                phase -= 360f;
            }
            postInvalidateOnAnimation();
        } else if (smoothLevel > 0.01f || smoothInput > 0.01f) {
            postInvalidateOnAnimation();
        }
    }

    /** 重建两个径向渐变（仅在尺寸 / 运行状态 / 量化强度变化时调用）。 */
    private void rebuildShaders(float cx, float cy, float radius, boolean running,
                                int quant, float w, float h) {
        float wanted = radius * (1.75f + quant / 20f * 1.15f);
        float limit = Math.min(w, h) * 0.5f - 2f;
        glowRadius = Math.max(radius * 1.2f, Math.min(wanted, limit));
        glowShader = new RadialGradient(cx, cy, glowRadius,
                new int[]{0xFF5EEAD4, 0x80818CF8, Color.TRANSPARENT},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP);
        innerShader = new RadialGradient(cx, cy - radius * 0.4f, radius * 1.5f,
                running ? 0xFF16283A : 0xFF111823, 0xFF0A0F17, Shader.TileMode.CLAMP);
        shaderRadius = radius;
        shaderRunning = running;
        shaderQuant = quant;
    }

    private static int clampAlpha(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    private static float clamp(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}
