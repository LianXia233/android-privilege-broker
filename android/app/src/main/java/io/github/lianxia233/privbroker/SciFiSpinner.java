package io.github.lianxia233.privbroker;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * 科幻风圆形旋转指示器：多层渐变弧 + 反向内环 + 刻度 + 脉冲核心。
 * 尺寸很小（约 40dp）、整体半透明，用于静默安装期间表示「正在安装」。
 */
public final class SciFiSpinner extends View {
    private static final int CYAN = 0xFF4DE3FF;
    private static final int BLUE = 0xFF3B7BFF;
    private static final int VIOLET = 0xFF8A5CFF;
    private static final int DISC = 0xE6111830;

    private final Paint disc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint inner = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ticks = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint core = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF outer = new RectF();
    private final RectF mid = new RectF();

    private ValueAnimator animator;
    private float rotation;
    private float progress = -1f;
    private boolean running;

    public SciFiSpinner(Context c) {
        super(c);
        init();
    }

    public SciFiSpinner(Context c, AttributeSet a) {
        super(c, a);
        init();
    }

    private void init() {
        disc.setColor(DISC);
        disc.setStyle(Paint.Style.FILL);
        disc.setShadowLayer(dp(2.5f), 0, 0, 0x66000000);

        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeCap(Paint.Cap.ROUND);
        ring.setColor(CYAN);

        inner.setStyle(Paint.Style.STROKE);
        inner.setStrokeCap(Paint.Cap.ROUND);
        inner.setColor(VIOLET);

        ticks.setStyle(Paint.Style.FILL);
        ticks.setColor(0x8059CFFF);

        core.setStyle(Paint.Style.FILL);
        core.setColor(CYAN);

        glow.setStyle(Paint.Style.STROKE);
        glow.setStrokeWidth(dp(1f));
        glow.setColor(0x554DE3FF);

        setLayerType(LAYER_TYPE_SOFTWARE, null);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    /** 0..100，负数表示不确定进度（纯旋转）。 */
    public void setProgress(int pct) {
        progress = pct < 0 ? -1f : Math.max(0f, Math.min(100f, pct));
        postInvalidateOnAnimation();
    }

    public void startAnimation() {
        if (running) {
            return;
        }
        running = true;
        animator = ValueAnimator.ofFloat(0f, 360f);
        animator.setDuration(1600L);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(a -> {
            rotation = (float) a.getAnimatedValue();
            postInvalidateOnAnimation();
        });
        animator.start();
    }

    public void stopAnimation() {
        running = false;
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        startAnimation();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopAnimation();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        float cx = w / 2f, cy = h / 2f;
        int[] colors = {CYAN, BLUE, VIOLET, CYAN};
        ring.setShader(new SweepGradient(cx, cy, colors, null));
        inner.setShader(new SweepGradient(cx, cy,
                new int[]{VIOLET, CYAN, VIOLET}, null));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        float cx = w / 2f, cy = h / 2f;
        float r = Math.min(w, h) / 2f;
        float pulse = (float) (0.5 + 0.5 * Math.sin(SystemClock.uptimeMillis() / 260.0));

        // 深色玻璃底盘 + 细边框，保证浅色/深色壁纸上都看得见但不刺眼
        canvas.drawCircle(cx, cy, r - dp(1.5f), disc);
        glow.setAlpha(120);
        canvas.drawCircle(cx, cy, r - dp(2f), glow);

        float rr = r - dp(4.5f);
        outer.set(cx - rr, cy - rr, cx + rr, cy + rr);
        ring.setStrokeWidth(dp(2.6f));

        // 外圈：两条对称渐变弧，随 rotation 旋转；进度已知时弧长跟随进度
        canvas.save();
        canvas.rotate(rotation, cx, cy);
        float sweep = progress < 0 ? 118f : 40f + 260f * (progress / 100f);
        ring.setAlpha(235);
        canvas.drawArc(outer, -90f, Math.min(sweep, 320f), false, ring);
        ring.setAlpha(150);
        canvas.drawArc(outer, 90f, Math.min(sweep * 0.55f, 150f), false, ring);
        canvas.restore();

        // 刻度：12 个渐隐小点
        float tickR = r - dp(2.2f);
        for (int i = 0; i < 12; i++) {
            double a = Math.toRadians(-90 + i * 30 + rotation * 0.25);
            float x = cx + (float) (Math.cos(a) * tickR);
            float y = cy + (float) (Math.sin(a) * tickR);
            ticks.setAlpha(40 + (int) (150 * ((i + 1) / 12f)));
            canvas.drawCircle(x, y, dp(0.9f), ticks);
        }

        // 内环：反向旋转的细弧 + 底盘分隔线
        float mr = r - dp(9f);
        mid.set(cx - mr, cy - mr, cx + mr, cy + mr);
        inner.setStrokeWidth(dp(1.6f));
        inner.setAlpha(210);
        canvas.save();
        canvas.rotate(-rotation * 0.8f, cx, cy);
        canvas.drawArc(mid, 20f, 96f, false, inner);
        canvas.drawArc(mid, 200f, 62f, false, inner);
        canvas.restore();

        // 中心脉冲核心
        core.setAlpha(150 + (int) (105 * pulse));
        canvas.drawCircle(cx, cy, dp(1.6f) + dp(1.1f) * pulse, core);
        glow.setAlpha(60 + (int) (90 * pulse));
        glow.setStrokeWidth(dp(0.8f));
        canvas.drawCircle(cx, cy, dp(4.2f) + dp(1.8f) * pulse, glow);
    }
}
