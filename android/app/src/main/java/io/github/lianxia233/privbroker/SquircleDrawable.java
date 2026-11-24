package io.github.lianxia233.privbroker;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * 连续圆角（squircle）填充，等价于 FlClash / prism_ui 的
 * {@code RoundedSuperellipseBorder}。
 *
 * <p>普通 {@code GradientDrawable} 的圆角是四分之一圆，在 24～34dp 的大圆角上
 * 观感偏「方」；iOS / FlClash 用的是超椭圆过渡（指数 4），直边与圆角之间没有
 * 明显拐点。这里按超椭圆公式采样每段圆角，再连成路径。</p>
 *
 * <p>同时实现 {@link Outline}，让 elevation 阴影也贴合 squircle 轮廓。</p>
 */
final class SquircleDrawable extends Drawable {
    /** 超椭圆指数：4 接近 iOS 的连续圆角，数值越大越接近矩形。 */
    private static final float EXPONENT = 4f;
    private static final int SEGMENTS = 12;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Path outlinePath = new Path();
    private final RectF bounds = new RectF();
    private float radiusPx;
    private float strokeWidth;
    private boolean dirty = true;

    SquircleDrawable(float radiusPx, int color) {
        this.radiusPx = radiusPx;
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(color);
        stroke.setStyle(Paint.Style.STROKE);
    }

    void setColor(int color) {
        if (fill.getColor() != color) {
            fill.setColor(color);
            invalidateSelf();
        }
    }

    int getColor() {
        return fill.getColor();
    }

    void setRadius(float radiusPx) {
        if (this.radiusPx != radiusPx) {
            this.radiusPx = radiusPx;
            dirty = true;
            invalidateSelf();
        }
    }

    void setStroke(float widthPx, int color) {
        strokeWidth = widthPx;
        stroke.setStrokeWidth(widthPx);
        stroke.setColor(color);
        dirty = true;
        invalidateSelf();
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        if (b.isEmpty()) {
            return;
        }
        if (dirty || !bounds.equals(new RectF(b))) {
            rebuild(new RectF(b));
        }
        canvas.drawPath(path, fill);
        if (strokeWidth > 0) {
            canvas.drawPath(path, stroke);
        }
    }

    @Override
    public void getOutline(Outline outline) {
        Rect b = getBounds();
        if (b.isEmpty()) {
            return;
        }
        if (dirty || !bounds.equals(new RectF(b))) {
            rebuild(new RectF(b));
        }
        outline.setPath(outlinePath);
    }

    private void rebuild(RectF r) {
        bounds.set(r);
        path.reset();
        float radius = Math.min(radiusPx, Math.min(r.width(), r.height()) / 2f);
        if (radius <= 0.01f) {
            path.addRect(r, Path.Direction.CW);
            outlinePath.set(path);
            dirty = false;
            return;
        }
        float l = r.left;
        float t = r.top;
        float rt = r.right;
        float b = r.bottom;
        double power = 2.0 / EXPONENT;

        path.moveTo(l + radius, t);
        path.lineTo(rt - radius, t);
        // 右上角：沿 a: 0..90° 采样超椭圆象限
        for (int i = 1; i <= SEGMENTS; i++) {
            double a = Math.PI / 2 * i / SEGMENTS;
            float x = (float) (rt - radius + radius * Math.pow(Math.sin(a), power));
            float y = (float) (t + radius - radius * Math.pow(Math.cos(a), power));
            path.lineTo(x, y);
        }
        path.lineTo(rt, b - radius);
        for (int i = 1; i <= SEGMENTS; i++) {
            double a = Math.PI / 2 * i / SEGMENTS;
            float x = (float) (rt - radius + radius * Math.pow(Math.cos(a), power));
            float y = (float) (b - radius + radius * Math.pow(Math.sin(a), power));
            path.lineTo(x, y);
        }
        path.lineTo(l + radius, b);
        for (int i = 1; i <= SEGMENTS; i++) {
            double a = Math.PI / 2 * i / SEGMENTS;
            float x = (float) (l + radius - radius * Math.pow(Math.sin(a), power));
            float y = (float) (b - radius + radius * Math.pow(Math.cos(a), power));
            path.lineTo(x, y);
        }
        path.lineTo(l, t + radius);
        for (int i = 1; i <= SEGMENTS; i++) {
            double a = Math.PI / 2 * i / SEGMENTS;
            float x = (float) (l + radius - radius * Math.pow(Math.cos(a), power));
            float y = (float) (t + radius - radius * Math.pow(Math.sin(a), power));
            path.lineTo(x, y);
        }
        path.close();

        // Outline 需要凸路径：用圆角矩形近似即可让阴影贴合。
        outlinePath.reset();
        outlinePath.addRoundRect(r, radius, radius, Path.Direction.CW);
        dirty = false;
    }

    @Override
    public void setAlpha(int alpha) {
        fill.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        fill.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
