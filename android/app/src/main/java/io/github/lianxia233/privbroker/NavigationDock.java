package io.github.lianxia233.privbroker;

import android.util.AttributeSet;
import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
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
 * <p>悬浮态：62dp 胶囊浮在页面之上，左右各留 21dp；图标 28dp、标签 10sp，
 * 选中项背一浅主色胶囊；停靠态：80dp 通栏，选中项是 64×32 的指示胶囊。</p>
 *
 * <p>切换选中项用 250ms 过冲回弹，与 prism_ui 的 Dock 手感一致；图标/标签
 * 颜色在 200ms 内渐变，不做位移动画（Dock 本身的压缩伸展已足够表达状态）。</p>
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
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setMode(FLOATING);
    }

    public NavigationDock(Context c, AttributeSet attrs) {
        this(c);
    }

    void setListener(Listener l) {
        listener = l;
    }

    void setMode(int mode) {
        this.mode = mode;
        Context c = getContext();
        if (mode == FLOATING) {
            Prism.fill(this, R.color.pb_card, 34f);
            setElevation(Prism.dp(c, 10));
            setPadding(Prism.dp(c, 4), Prism.dp(c, 4),
                    Prism.dp(c, 4), Prism.dp(c, 4));
        } else {
            Prism.fill(this, R.color.pb_floating, 0f);
            setElevation(0f);
            setPadding(0, Prism.dp(c, 4), 0, Prism.dp(c, 4));
        }
        requestLayout();
    }

    void setItems(String[] labels, int[] icons) {
        removeAllViews();
        items.clear();
        for (int i = 0; i < labels.length; i++) {
            Item item = new Item(getContext(), labels[i], icons[i]);
            final int index = i;
            item.root.setOnClickListener(v -> select(index, true));
            LayoutParams lp = new LayoutParams(0, LayoutParams.MATCH_PARENT, 1f);
            addView(item.root, lp);
            items.add(item);
        }
        layoutItems();
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
            items.get(i).apply(i == selected, i == previous, animate, mode);
        }
        if (animate) {
            items.get(selected).bump();
        }
        if (listener != null && animate) {
            listener.onSelected(index);
        }
    }

    private void layoutItems() {
        for (int i = 0; i < items.size(); i++) {
            items.get(i).apply(i == selected, false, false, mode);
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int h = Prism.dp(getContext(), mode == FLOATING ? 62 : 80);
        super.onMeasure(widthMeasureSpec,
                MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
    }

    private static final class Item {
        final FrameLayout root;
        final View capsule;
        final ImageView icon;
        final TextView label;

        Item(Context c, String text, int iconRes) {
            root = new FrameLayout(c);
            root.setClickable(true);

            capsule = new View(c);
            FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
            capsule.setBackground(new SquircleDrawable(Prism.dp(c, 999f), Color.TRANSPARENT));
            root.addView(capsule, clp);

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
            label.setMaxLines(1);
            label.setSingleLine(true);
            Prism.text(label, 10f, false, R.color.pb_on_surface_variant);
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

        void apply(boolean active, boolean wasActive, boolean animate, int mode) {
            Context c = root.getContext();
            int tint = c.getColor(active ? R.color.pb_primary : R.color.pb_on_surface_variant);
            float capsuleAlpha = active ? 1f : 0f;
            if (animate && active) {
                capsule.setAlpha(0f);
                capsule.animate().alpha(1f).setDuration(220).start();
            } else {
                capsule.setAlpha(capsuleAlpha);
            }
            SquircleDrawable bg = (SquircleDrawable) capsule.getBackground();
            bg.setColor(Prism.alpha(c.getColor(R.color.pb_primary), 0.14f));
            icon.setColorFilter(tint);
            label.setTextColor(tint);
            Prism.text(label, 10f, active, active
                    ? R.color.pb_primary : R.color.pb_on_surface_variant);
            if (mode == DOCKED) {
                // 停靠态：指示胶囊只包住图标（64×32），标签留在下方
                ViewGroup.LayoutParams lp = capsule.getLayoutParams();
                if (lp instanceof FrameLayout.LayoutParams f) {
                    f.width = Prism.dp(c, 64);
                    f.height = Prism.dp(c, 32);
                    f.gravity = Gravity.CENTER_HORIZONTAL | Gravity.TOP;
                    f.topMargin = Prism.dp(c, 6);
                    capsule.setLayoutParams(f);
                }
            } else {
                ViewGroup.LayoutParams lp = capsule.getLayoutParams();
                if (lp instanceof FrameLayout.LayoutParams f) {
                    f.width = ViewGroup.LayoutParams.MATCH_PARENT;
                    f.height = ViewGroup.LayoutParams.MATCH_PARENT;
                    f.gravity = Gravity.CENTER;
                    f.topMargin = 0;
                    capsule.setLayoutParams(f);
                }
            }
        }

        void bump() {
            root.animate().cancel();
            root.setScaleX(0.94f);
            root.setScaleY(0.94f);
            root.animate().scaleX(1f).scaleY(1f)
                    .setDuration(280)
                    .setInterpolator(new OvershootInterpolator(1.8f))
                    .start();
        }
    }
}
