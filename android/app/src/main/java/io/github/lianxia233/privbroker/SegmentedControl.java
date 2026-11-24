package io.github.lianxia233.privbroker;

import android.util.AttributeSet;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * FlClash / prism_ui 的分段控件：44dp 胶囊底，选中项是一枚主色胶囊，
 * 250ms easeOutCubic 滑动过去；文案在 200ms 内完成「次要色 → 主色前景」过渡。
 */
public final class SegmentedControl extends FrameLayout {
    private static final float HEIGHT = 44f;
    private static final float INSET = 4f;

    interface Listener {
        void onChanged(int index);
    }

    private View pill;
    private LinearLayout row;
    private TextView[] labels = new TextView[0];
    private int selected = 0;
    private int count = 1;
    private float pillLeft;
    private float pillWidth;
    private ValueAnimator animator;
    private Listener listener;

    void setListener(Listener l) {
        listener = l;
    }

    SegmentedControl(Context c) {
        super(c);
        init();
    }

    /** 必须走 {@code super(c, attrs)}，否则 XML 上的 android:id 不会被解析。 */
    public SegmentedControl(Context c, AttributeSet attrs) {
        super(c, attrs);
        init();
    }

    private void init() {
        Context c = getContext();
        int inset = Prism.dp(c, INSET);
        setPadding(inset, inset, inset, inset);
        SquircleDrawable bg = new SquircleDrawable(Prism.dp(c, HEIGHT / 2f),
                c.getColor(R.color.pb_block));
        setBackground(bg);

        pill = new View(c);
        pill.setBackground(new SquircleDrawable(Prism.dp(c, HEIGHT / 2f),
                c.getColor(R.color.pb_primary)));
        addView(pill, new LayoutParams(0, LayoutParams.MATCH_PARENT));

        row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        addView(row, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    void setSegments(String... segments) {
        row.removeAllViews();
        count = Math.max(1, segments.length);
        labels = new TextView[segments.length];
        for (int i = 0; i < segments.length; i++) {
            final int index = i;
            TextView tv = new TextView(getContext());
            tv.setText(segments[i]);
            tv.setGravity(Gravity.CENTER);
            tv.setSingleLine(true);
            Prism.text(tv, 15f, false, R.color.pb_on_surface_variant);
            tv.setBackground(Prism.ripple(getContext(), HEIGHT / 2f));
            tv.setOnClickListener(v -> {
                if (selected != index) {
                    setSelection(index, true);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
            row.addView(tv, lp);
            labels[i] = tv;
        }
        selected = Math.min(selected, segments.length - 1);
        post(this::layoutPill);
    }

    void setSelection(int index, boolean animate) {
        if (index < 0 || index >= labels.length) {
            return;
        }
        selected = index;
        if (!animate) {
            layoutPill();
            applyLabelColors(1f);
            return;
        }
        if (listener != null) {
            listener.onChanged(index);
        }
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        final float usable = Math.max(0, getWidth() - getPaddingLeft() - getPaddingRight());
        final float targetWidth = usable / (float) count;
        final float targetLeft = getPaddingLeft() + targetWidth * index;
        final float fromLeft = pillLeft;
        final float fromWidth = pillWidth;
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        animator = a;
        a.setDuration(250);
        a.setInterpolator(new DecelerateInterpolator(1.6f));
        a.addUpdateListener(v -> {
            float t = (float) v.getAnimatedValue();
            pillLeft = fromLeft + (targetLeft - fromLeft) * t;
            pillWidth = fromWidth + (targetWidth - fromWidth) * t;
            applyPill();
            applyLabelColors(t);
        });
        a.start();
    }

    int getSelection() {
        return selected;
    }

    private void layoutPill() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        if (getWidth() == 0) {
            return;
        }
        float usable = getWidth() - getPaddingLeft() - getPaddingRight();
        pillWidth = usable / count;
        pillLeft = getPaddingLeft() + pillWidth * selected;
        applyPill();
        applyLabelColors(1f);
    }

    private void applyPill() {
        int usable = getWidth() - getPaddingLeft() - getPaddingRight();
        if (usable <= 0 || count <= 0) {
            return;
        }
        int pw = usable / count;
        int x = Math.round(pillLeft);
        int y = getPaddingTop();
        int right = Math.round(pillLeft + pillWidth);
        int bottom = getHeight() - getPaddingBottom();
        if (right <= x) {
            return;
        }
        // 直接在 onLayout 之后摆放，避免在 onSizeChanged 里改 LayoutParams
        // 触发二次 layout 被吞掉、胶囊宽度停在 0 的问题。
        pill.layout(x, y, right, bottom);
    }

    private void applyLabelColors(float t) {
        int on = getContext().getColor(R.color.pb_on_primary);
        int off = getContext().getColor(R.color.pb_on_surface_variant);
        for (int i = 0; i < labels.length; i++) {
            boolean active = i == selected;
            int color = active ? on : off;
            labels[i].setTextColor(color);
            labels[i].setAlpha(1f);
            Prism.text(labels[i], 15f, active, active
                    ? R.color.pb_on_primary : R.color.pb_on_surface_variant);
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int h = Prism.dp(getContext(), HEIGHT);
        super.onMeasure(widthMeasureSpec,
                MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        if (changed || pillWidth <= 0) {
            layoutPill();
        }
        applyPill();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        layoutPill();
    }
}
