package io.github.lianxia233.privbroker;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * 主题页的迷你屏幕预览，对应 FlClash 的 {@code MiniScreen}：
 * 一屏「状态卡 + 两行指标 + 三段控件 + 开关 + Dock」，用来直观展示
 * 当前色板、底栏形态、纯黑与切页动画。
 */
public final class MiniScreenView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private boolean floatingBar = true;
    private int selected = 0;
    private boolean liquidGlass;
    private int tabAnimation = PageSwitcher.SLIDE;

    MiniScreenView(Context c) {
        super(c);
    }

    public MiniScreenView(Context c, AttributeSet attrs) {
        super(c, attrs);
    }

    void setState(boolean floatingBar, boolean liquidGlass, int tabAnimation) {
        this.floatingBar = floatingBar;
        this.liquidGlass = liquidGlass;
        this.tabAnimation = tabAnimation;
        invalidate();
    }

    void setSelected(int selected) {
        this.selected = Math.max(0, Math.min(2, selected));
        invalidate();
    }

    private int c(int attr) {
        return Prism.attrColor(getContext(), attr);
    }

    private float dp(float v) {
        return Prism.dp(getContext(), v);
    }

    private void round(float l, float t, float r, float b, float radius, int color) {
        rect.set(l, t, r, b);
        paint.setColor(color);
        canvas.drawRoundRect(rect, radius, radius, paint);
    }

    private Canvas canvas;

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        this.canvas = canvas;
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        float pad = dp(6);
        round(0, 0, w, h, dp(10), c(R.attr.pbSurfacePage));

        // 状态 hero 卡
        float heroTop = pad;
        float heroH = h * 0.30f;
        round(pad, heroTop, w - pad, heroTop + heroH, dp(7), c(R.attr.pbSurfaceCard));
        round(w - pad - heroH * 0.62f, heroTop + heroH * 0.18f, w - pad - heroH * 0.08f,
                heroTop + heroH * 0.82f, heroH * 0.32f, c(R.attr.pbPrimary));
        round(pad + dp(6), heroTop + dp(7), pad + w * 0.38f, heroTop + dp(11), dp(2),
                c(R.attr.pbSurfaceBlock));

        // 两行指标
        float rowY = heroTop + heroH + dp(6);
        for (int i = 0; i < 2; i++) {
            float x = pad + i * ((w - pad * 2) / 2f + dp(3));
            float rw = (w - pad * 2 - dp(3)) / 2f;
            round(x, rowY, x + rw, rowY + h * 0.13f, dp(5), c(R.attr.pbSurfaceCard));
            round(x + dp(4), rowY + dp(5), x + rw * 0.55f, rowY + dp(9), dp(2),
                    c(R.attr.pbSurfaceBlock));
        }

        // 三段控件：选中段用主色胶囊
        float segY = rowY + h * 0.13f + dp(6);
        float segH = h * 0.11f;
        round(pad, segY, w - pad, segY + segH, segH / 2f, c(R.attr.pbSurfaceBlock));
        float segW = (w - pad * 2) / 3f;
        float sx = pad + segW * selected;
        round(sx + dp(2), segY + dp(2), sx + segW - dp(2), segY + segH - dp(2), segH / 2f,
                c(R.attr.pbPrimary));

        // 开关行
        float swY = segY + segH + dp(6);
        round(pad, swY, w - pad, swY + h * 0.10f, dp(4), c(R.attr.pbSurfaceCard));
        round(w - pad - dp(30), swY + h * 0.025f, w - pad - dp(6), swY + h * 0.075f,
                dp(6), c(R.attr.pbPrimary));

        // Dock：悬浮胶囊 or 通栏
        float dockH = h * 0.15f;
        float dockY = h - pad - dockH;
        int dockColor = c(R.attr.pbSurfaceCard);
        if (liquidGlass) {
            dockColor = Prism.blend(dockColor, c(R.attr.pbPrimary), 0.10f);
        }
        if (floatingBar) {
            round(pad + dp(4), dockY, w - pad - dp(4), dockY + dockH, dockH / 2f, dockColor);
        } else {
            round(pad, dockY, w - pad, dockY + dockH, dp(4), c(R.attr.pbSurfaceFloating));
        }
        float dot = dockH * 0.34f;
        float gap = (w - pad * 2) / 3f;
        for (int i = 0; i < 3; i++) {
            float cx = pad + gap * (i + 0.5f);
            if (i == selected) {
                round(cx - gap * 0.30f, dockY + dockH * 0.18f, cx + gap * 0.30f,
                        dockY + dockH * 0.82f, dockH * 0.28f,
                        Prism.blend(dockColor, c(R.attr.pbTextPrimary), 0.10f));
            }
            int tint = i == selected
                    ? c(R.attr.pbPrimary) : c(R.attr.pbTextSecondary);
            paint.setColor(tint);
            canvas.drawCircle(cx, dockY + dockH * 0.5f, dot / 2.6f, paint);
        }

        if (tabAnimation == PageSwitcher.FADE) {
            paint.setColor(Prism.alpha(c(R.attr.pbPrimary), 0.10f));
            canvas.drawRect(0, 0, w, h, paint);
        }
    }
}
