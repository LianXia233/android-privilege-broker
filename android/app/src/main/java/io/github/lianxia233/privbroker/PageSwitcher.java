package io.github.lianxia233.privbroker;

import android.util.AttributeSet;
import android.content.Context;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;

/**
 * 页面容器，复刻 FlClash 的两种切页动画：
 * <ul>
 *   <li>滑动：300ms easeOut，横向整页位移 + 轻微淡出（kTabScrollDuration）；</li>
 *   <li>淡入：220ms easeInOut 原地交叉淡入（fadeTabDuration / fadeTabCurve）。</li>
 * </ul>
 * 所有页面常驻挂载，只切可见性，避免重复 inflate 与状态丢失。
 */
public final class PageSwitcher extends FrameLayout {
    static final int SLIDE = 0;
    static final int FADE = 1;

    private int current = -1;
    private int mode = SLIDE;
    private boolean animating;

    PageSwitcher(Context c) {
        super(c);
        init();
    }

    /**
     * XML 构造器必须调用 {@code super(c, attrs)}：只调 {@code this(c)} 的话
     * View 不会解析 AttributeSet，{@code android:id} 等属性会全部丢失，
     * 运行时 findViewById 找不到控件（2.2.0 闪退的根因）。
     */
    public PageSwitcher(Context c, AttributeSet attrs) {
        super(c, attrs);
        init();
    }

    private void init() {
        setClipChildren(true);
    }

    void setMode(int mode) {
        this.mode = mode;
    }

    int getCurrentIndex() {
        return current;
    }

    void addPage(View page) {
        LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT);
        page.setVisibility(GONE);
        addView(page, lp);
    }

    /** 立即显示某页（首次进入用）。 */
    void showImmediate(int index) {
        if (index < 0 || index >= getChildCount()) {
            return;
        }
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            child.animate().cancel();
            child.setAlpha(1f);
            child.setTranslationX(0f);
            child.setVisibility(i == index ? VISIBLE : GONE);
        }
        current = index;
    }

    /** 切页；already 可见时直接返回。 */
    void show(int index, boolean animate) {
        if (index < 0 || index >= getChildCount() || index == current) {
            return;
        }
        if (current == -1 || !animate || getWidth() == 0 || animating) {
            showImmediate(index);
            return;
        }
        final View next = getChildAt(index);
        final View prev = getChildAt(current);
        final int direction = index > current ? 1 : -1;
        animating = true;

        if (mode == FADE) {
            next.setAlpha(0f);
            next.setTranslationX(0f);
            next.setVisibility(VISIBLE);
            next.animate().alpha(1f)
                    .setDuration(Prism.DUR_TAB_FADE)
                    .setInterpolator(new AccelerateDecelerateInterpolator())
                    .withEndAction(() -> animating = false)
                    .start();
            prev.animate().alpha(0f)
                    .setDuration(Prism.DUR_TAB_FADE)
                    .setInterpolator(new AccelerateDecelerateInterpolator())
                    .withEndAction(() -> {
                        prev.setVisibility(GONE);
                        prev.setAlpha(1f);
                    })
                    .start();
        } else {
            float width = getWidth();
            next.setTranslationX(direction * width);
            next.setAlpha(0.94f);
            next.setVisibility(VISIBLE);
            next.animate().translationX(0f).alpha(1f)
                    .setDuration(Prism.DUR_TAB_SLIDE)
                    .setInterpolator(new DecelerateInterpolator(1.8f))
                    .withEndAction(() -> animating = false)
                    .start();
            prev.animate().translationX(-direction * width * 0.28f).alpha(0f)
                    .setDuration(Prism.DUR_TAB_SLIDE)
                    .setInterpolator(new DecelerateInterpolator(1.8f))
                    .withEndAction(() -> {
                        prev.setVisibility(GONE);
                        prev.setTranslationX(0f);
                        prev.setAlpha(1f);
                    })
                    .start();
        }
        current = index;
    }
}
