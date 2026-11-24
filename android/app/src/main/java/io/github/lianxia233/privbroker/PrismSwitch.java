package io.github.lianxia233.privbroker;

import android.util.AttributeSet;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.CompoundButton;

/**
 * FlClash（prism_ui/switch.dart）同款开关：轨道 52×32、拇指 24，
 * 按压时拇指涨到 40×22 并向轨道中心拉伸，松手回弹。
 *
 * <p>用自绘而不是系统 {@code Switch}：系统开关自带水波纹与描边，
 * 在 FlClash 的卡片里会显得脏；这里只在拇指上做白化与放大。</p>
 */
public final class PrismSwitch extends View {
    private static final float TRACK_W = 52f;
    private static final float TRACK_H = 32f;
    private static final float THUMB = 24f;
    private static final float MID_THUMB_W = 34f;
    private static final float MID_THUMB_H = 22f;
    private static final float HELD_THUMB = 40f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF trackRect = new RectF();
    private final RectF thumbRect = new RectF();
    private android.animation.ValueAnimator animator;
    private float progress;
    private float press;
    private boolean checked;
    private boolean enabled = true;
    private CompoundButton.OnCheckedChangeListener listener;

    PrismSwitch(Context c) {
        super(c);
        init();
    }

    /** 必须走 {@code super(c, attrs)}，否则 XML 上的 android:id 不会被解析。 */
    public PrismSwitch(Context c, AttributeSet attrs) {
        super(c, attrs);
        init();
    }

    private void init() {
        setClickable(true);
        setFocusable(true);
        setContentDescription("开关");
    }

    void setChecked(boolean value) {
        setChecked(value, true);
    }

    void setChecked(boolean value, boolean animate) {
        if (checked == value) {
            return;
        }
        checked = value;
        float target = value ? 1f : 0f;
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        if (!animate) {
            progress = target;
            invalidate();
            return;
        }
        animate().cancel();
        final float from = progress;
        animateValue(from, target, Prism.DUR_SWITCH, new OvershootInterpolator(1.6f),
                v -> progress = v);
    }

    boolean isChecked() {
        return checked;
    }

    void setOnCheckedChangeListener(CompoundButton.OnCheckedChangeListener l) {
        listener = l;
    }

    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        setAlpha(enabled ? 1f : 0.4f);
        super.setEnabled(enabled);
    }

    private void animateValue(float from, float to, int duration,
                              android.animation.TimeInterpolator interpolator,
                              java.util.function.Consumer<Float> apply) {
        if (this.animator != null) {
            this.animator.cancel();
        }
        android.animation.ValueAnimator animator =
                android.animation.ValueAnimator.ofFloat(from, to);
        this.animator = animator;
        animator.setDuration(duration);
        animator.setInterpolator(interpolator);
        animator.addUpdateListener(a -> {
            apply.accept((Float) a.getAnimatedValue());
            invalidate();
        });
        animator.start();
    }

    private void animatePress(float to) {
        animateValue(press, to, Prism.DUR_PRESS, new DecelerateInterpolator(),
                v -> press = v);
    }

    @Override
    public boolean performClick() {
        if (!enabled) {
            return false;
        }
        boolean next = !checked;
        setChecked(next);
        if (listener != null) {
            listener.onCheckedChanged(null, next);
        }
        return super.performClick();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = Prism.dp(getContext(), TRACK_W + 8);
        int h = Prism.dp(getContext(), 48);
        setMeasuredDimension(resolveSize(w, widthMeasureSpec),
                resolveSize(h, heightMeasureSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        Context c = getContext();
        float trackW = Prism.dp(c, TRACK_W);
        float trackH = Prism.dp(c, TRACK_H);
        float left = (getWidth() - trackW) / 2f;
        float top = (getHeight() - trackH) / 2f;
        trackRect.set(left, top, left + trackW, top + trackH);

        int offTrack = Prism.color(c, R.attr.pbSurfaceInset);
        int onTrack = Prism.color(c, R.attr.pbPrimary);
        paint.setColor(blend(offTrack, onTrack, progress));
        canvas.drawRoundRect(trackRect, trackH / 2f, trackH / 2f, paint);

        float p = Math.max(0f, Math.min(1f, progress));
        float stretch = (float) Math.sin(Math.PI * p);
        float base = Prism.dp(c, THUMB);
        float midW = Prism.dp(c, MID_THUMB_W);
        float midH = Prism.dp(c, MID_THUMB_H);
        float held = Prism.dp(c, HELD_THUMB);
        float thumbW = base + (midW - base) * stretch;
        float thumbH = base + (midH - base) * stretch;
        thumbW = thumbW + (held - thumbW) * press;
        thumbH = thumbH - Prism.dp(c, 2) * press;

        float along = trackH / 2f + (trackW - trackH) * p;
        float cx = left + along;
        float cy = top + trackH / 2f;
        thumbRect.set(cx - thumbW / 2f, cy - thumbH / 2f, cx + thumbW / 2f, cy + thumbH / 2f);

        int offThumb = Prism.color(c, R.attr.pbTextMuted);
        int onThumb = Prism.color(c, R.attr.pbOnPrimary);
        int thumbColor = blend(offThumb, onThumb, p);
        thumbColor = blend(thumbColor, Color.WHITE, press);
        paint.setColor(thumbColor);
        canvas.drawRoundRect(thumbRect, thumbH / 2f, thumbH / 2f, paint);
    }

    private static int blend(int from, int to, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int a = Math.round(Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * t);
        int r = Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * t);
        int g = Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * t);
        int b = Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t);
        return Color.argb(a, r, g, b);
    }
}
