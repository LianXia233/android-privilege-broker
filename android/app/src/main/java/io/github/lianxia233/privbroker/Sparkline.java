package io.github.lianxia233.privbroker;

import android.util.AttributeSet;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.view.View;

/**
 * 仪表盘卡片里的迷你面积图，复刻 FlClash 网络速度卡的波浪填充。
 *
 * <p>只有观感，不做坐标轴与刻度：一条平滑折线 + 同色渐变填充。
 * 归一化后按视图宽度铺满，数据点少于 2 个时画一条水平线。</p>
 */
public final class Sparkline extends View {
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path line = new Path();
    private final Path area = new Path();
    private float[] data = new float[0];

    Sparkline(Context c) {
        super(c);
        init();
    }

    /** 必须走 {@code super(c, attrs)}，否则 XML 上的 android:id 不会被解析。 */
    public Sparkline(Context c, AttributeSet attrs) {
        super(c, attrs);
        init();
    }

    private void init() {
        Context c = getContext();
        int primary = c.getColor(R.color.pb_primary);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Prism.dp(c, 2));
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setColor(primary);
        fillPaint.setStyle(Paint.Style.FILL);
    }

    void setData(float[] values) {
        data = values == null ? new float[0] : values;
        rebuildShader();
        invalidate();
    }

    private void rebuildShader() {
        int primary = getContext().getColor(R.color.pb_primary);
        fillPaint.setShader(new LinearGradient(0, 0, 0, getHeight(),
                Prism.alpha(primary, 0.28f), Prism.alpha(primary, 0f),
                Shader.TileMode.CLAMP));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        rebuildShader();
        build();
    }

    private void build() {
        line.reset();
        area.reset();
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        float padding = Prism.dp(getContext(), 2);
        float usableW = w - padding * 2;
        float usableH = h - padding * 2;
        if (data.length < 2) {
            float y = padding + usableH / 2f;
            line.moveTo(padding, y);
            line.lineTo(w - padding, y);
            area.addPath(line);
            area.lineTo(w - padding, h);
            area.lineTo(padding, h);
            area.close();
            return;
        }
        float max = 0.0001f;
        for (float v : data) {
            max = Math.max(max, v);
        }
        float step = usableW / (data.length - 1);
        float prevX = padding;
        float prevY = padding + usableH * (1f - data[0] / max);
        line.moveTo(prevX, prevY);
        for (int i = 1; i < data.length; i++) {
            float x = padding + step * i;
            float y = padding + usableH * (1f - data[i] / max);
            // 用控制点在两点中线的二次曲线平滑过渡，避免锯齿
            float cx = (prevX + x) / 2f;
            line.quadTo(cx, prevY, cx, (prevY + y) / 2f);
            line.quadTo(cx, y, x, y);
            prevX = x;
            prevY = y;
        }
        area.addPath(line);
        area.lineTo(w - padding, h);
        area.lineTo(padding, h);
        area.close();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (line.isEmpty()) {
            build();
        }
        canvas.drawPath(area, fillPaint);
        canvas.drawPath(line, stroke);
    }
}
