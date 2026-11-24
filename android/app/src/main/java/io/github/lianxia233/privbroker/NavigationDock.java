package io.github.lianxia233.privbroker;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Color;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * FlClash 的悬浮导航 Dock 与停靠导航栏。
 *
 * <p>悬浮态：62dp 白色胶囊浮在页面之上、左右各留 21dp；图标 28dp、标签 10sp
 * （w500），选中项背后是一枚**中性**胶囊（{@code onSurface 8%} 叠在
 * {@code SurfaceLevel.floating.next} 上，与 prism_ui 的 lens 同源），
 * 图标与标签转为 primary，图标放大 16%（lens magnify）。</p>
 *
 * <p>动效：选中胶囊 260ms 过冲放大淡入、图标 320ms 过冲放大、标签颜色
 * 200ms 渐变；按压时整项放大 6% 后回弹。</p>
 */
public final class NavigationDock extends LinearLayout {
    static final int FLOATING = 0;
    static final int DOCKED = 1;

    interface Listener {
        void onSelected(int index);
    }

    private final List<Item> items = new ArrayList<>();
    private int selected = 0;
    private int mode = FLOATING;
    private Listener listener;

    NavigationDock(Context c) {
        super(c);
        init();
    }

    /** 必须走 {@code super(c, attrs)}，否则 XML 上的 android:id 不会被解析。 */
    public NavigationDock(Context c, AttributeSet attrs) {
        super(c, attrs);
        init();
    }

    private void init() {
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setMode(FLOATING);
    }

    void setListener(Listener l) {
        listener = l;
    }

    void setMode(int mode) {
        this.mode = mode;
        Context c = getContext();
        if (mode == FLOATING) {
            SquircleDrawable dockBg = Prism.fill(this, R.attr.pbSurfaceCard, 34f);
            dockBg.setAlpha(Math.round(255 * Prism.glassAlpha(c)));
            setElevation(Prism.dp(c, 10));
            setPadding(Prism.dp(c, 4), Prism.dp(c, 4), Prism.dp(c, 4), Prism.dp(c, 4));
        } else {
            Prism.fill(this, R.attr.pbSurfaceFloating, 0f);
            setElevation(0f);
            setPadding(0, Prism.dp(c, 4), 0, Prism.dp(c, 4));
        }
        requestLayout();
    }

    void setItems(String[] labels, int[] icons) {
        removeAllViews();
        items.clear();
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            Item item = new Item(getContext(), labels[i], icons[i]);
            item.root.setOnClickListener(v -> select(index, true));
            item.attachPress();
            addView(item.root, new LayoutParams(0, LayoutParams.MATCH_PARENT, 1f));
            items.add(item);
        }
        for (int i = 0; i < items.size(); i++) {
            items.get(i).apply(i == selected, false, mode);
        }
    }

    void setSelected(int index) {
        select(index, false);
    }

    int getSelected() {
        return selected;
    }

    private void select(int index, boolean animate) {
        if (index < 0 || index >= items.size()) {
            return;
        }
        int previous = selected;
        selected = index;
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            item.apply(i == selected, animate && (i == index || i == previous), mode);
        }
        if (animate && listener != null) {
            listener.onSelected(index);
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int h = Prism.dp(getContext(), mode == FLOATING ? 62 : 80);
        super.onMeasure(widthMeasureSpec,
                MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
    }

    /** 单个导航项：胶囊 + 图标 + 标签。 */
    private static final class Item {
        final FrameLayout root;
        final View capsule;
        final ImageView icon;
        final TextView label;
        final int capsuleColor;

        Item(Context c, String text, int iconRes) {
            root = new FrameLayout(c);
            root.setClickable(true);
            root.setFocusable(true);
            root.setClipChildren(false);
            int inset = Prism.dp(c, 4);
            root.setPadding(inset, inset, inset, inset);

            capsuleColor = Prism.lensColor(c);
            capsule = new View(c);
            capsule.setBackground(new SquircleDrawable(Prism.dp(c, 999f), capsuleColor));
            capsule.setAlpha(0f);
            root.addView(capsule, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));

            LinearLayout column = new LinearLayout(c);
            column.setOrientation(VERTICAL);
            column.setGravity(Gravity.CENTER);

            icon = new ImageView(c);
            icon.setImageResource(iconRes);
            column.addView(icon, new LinearLayout.LayoutParams(
                    Prism.dp(c, 28), Prism.dp(c, 28)));

            label = new TextView(c);
            label.setText(text);
            label.setGravity(Gravity.CENTER);
            label.setSingleLine(true);
            // FlClash：10sp / leading 14.5 / w500
            label.setTextSize(10f);
            label.setIncludeFontPadding(false);
            label.setLetterSpacing(0f);
            label.setTypeface(android.graphics.Typeface.create(
                    "sans-serif-medium", android.graphics.Typeface.NORMAL));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = Prism.dp(c, 2);
            column.addView(label, lp);

            FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            flp.gravity = Gravity.CENTER;
            root.addView(column, flp);
        }

        /** 按压：整项放大 6%，松手回弹（prism 的 pressGrowth）。 */
        void attachPress() {
            root.setOnTouchListener((v, e) -> {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        v.animate().scaleX(1.06f).scaleY(1.06f)
                                .setDuration(150)
                                .setInterpolator(new DecelerateInterpolator())
                                .start();
                        break;
                    case MotionEvent.ACTION_UP:
                        v.animate().scaleX(1f).scaleY(1f)
                                .setDuration(280)
                                .setInterpolator(new OvershootInterpolator(1.8f))
                                .start();
                        v.performClick();
                        break;
                    case MotionEvent.ACTION_CANCEL:
                        v.animate().scaleX(1f).scaleY(1f)
                                .setDuration(220)
                                .setInterpolator(new OvershootInterpolator(1.4f))
                                .start();
                        break;
                    default:
                        break;
                }
                return true;
            });
        }

        void apply(boolean active, boolean animate, int mode) {
            Context c = root.getContext();
            int tint = Prism.color(c, active
                    ? R.attr.pbPrimary : R.attr.pbTextSecondary);
            icon.setColorFilter(tint);
            animateLabel(tint, animate);

            float alpha = active ? 1f : 0f;
            float capScale = active ? 1f : 0.9f;
            float iconScale = active ? 1.16f : 1f;
            if (animate) {
                capsule.animate().cancel();
                capsule.animate().alpha(alpha)
                        .scaleX(capScale).scaleY(capScale)
                        .setDuration(260)
                        .setInterpolator(new OvershootInterpolator(1.4f))
                        .start();
                icon.animate().cancel();
                icon.animate().scaleX(iconScale).scaleY(iconScale)
                        .setDuration(320)
                        .setInterpolator(new OvershootInterpolator(1.7f))
                        .start();
            } else {
                capsule.animate().cancel();
                capsule.setAlpha(alpha);
                capsule.setScaleX(capScale);
                capsule.setScaleY(capScale);
                icon.animate().cancel();
                icon.setScaleX(iconScale);
                icon.setScaleY(iconScale);
            }

            // 停靠态：指示胶囊只包住图标（64×32），标签留在下方
            FrameLayout.LayoutParams lp =
                    (FrameLayout.LayoutParams) capsule.getLayoutParams();
            if (mode == DOCKED) {
                lp.width = Prism.dp(c, 64);
                lp.height = Prism.dp(c, 32);
                lp.gravity = Gravity.CENTER_HORIZONTAL | Gravity.TOP;
                lp.topMargin = Prism.dp(c, 6);
            } else {
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
                lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
                lp.gravity = Gravity.CENTER;
                lp.topMargin = 0;
            }
            capsule.setLayoutParams(lp);
        }

        private void animateLabel(int target, boolean animate) {
            int current = label.getCurrentTextColor();
            if (!animate || current == target) {
                label.setTextColor(target);
                return;
            }
            ValueAnimator a = ValueAnimator.ofObject(new ArgbEvaluator(), current, target);
            a.setDuration(Prism.DUR_MID);
            a.addUpdateListener(v -> label.setTextColor((Integer) v.getAnimatedValue()));
            a.start();
        }
    }
}
