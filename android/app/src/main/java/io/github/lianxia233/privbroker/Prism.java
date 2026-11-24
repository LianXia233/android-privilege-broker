package io.github.lianxia233.privbroker;

import android.app.Activity;
import android.app.UiModeManager;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.os.Build;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * 复刻 FlClash（prism_ui）设计系统的 Android 原生实现。
 *
 * <p>只有视觉令牌与交互手感，不含业务逻辑：配色层级、圆角、字体层级、
 * 弹性按压、squircle 背景、边缘到边缘（edge-to-edge）系统栏。</p>
 */
final class Prism {
    /** 与 prism_ui/color_scheme.dart 的 SurfaceLevel 一致。 */
    enum Surface {
        PAGE(R.color.pb_page),
        CARD(R.color.pb_card),
        BLOCK(R.color.pb_block),
        INSET(R.color.pb_inset);

        final int colorRes;

        Surface(int colorRes) {
            this.colorRes = colorRes;
        }
    }

    /** AppCorner：sm 8 / md 16 / lg 20 / xl 24 / xxl 28 / alert 34。 */
    static final float CORNER_SM = 8f;
    static final float CORNER_MD = 16f;
    static final float CORNER_LG = 20f;
    static final float CORNER_XL = 24f;
    static final float CORNER_XXL = 28f;
    static final float CORNER_CARD = 32f;
    static final float CORNER_MOBILE_CARD = 34f;
    static final float CORNER_FULL = 999f;

    /** 动画时长：与 prism_ui 的 fadeTabDuration / commonDuration 对齐。 */
    static final int DUR_TAB_FADE = 220;
    static final int DUR_TAB_SLIDE = 300;
    static final int DUR_SWITCH = 300;
    static final int DUR_PRESS = 150;
    static final int DUR_MID = 200;

    private Prism() {
    }

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static int color(Context c, int res) {
        return c.getColor(res);
    }

    static int surfaceColor(Context c, Surface level) {
        return c.getColor(level.colorRes);
    }

    /** 带透明度的颜色，用于胶囊底的柔和主色底。 */
    static int alpha(int color, float factor) {
        return Color.argb(Math.round(255 * factor),
                Color.red(color), Color.green(color), Color.blue(color));
    }

    static boolean isNight(Context c) {
        return (c.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    // ------------------------------------------------------------ 背景

    /** squircle 填充背景；card=true 时附带轻微 elevation。 */
    static SquircleDrawable fill(View v, int colorRes, float cornerDp) {
        SquircleDrawable d = new SquircleDrawable(dp(v.getContext(), cornerDp),
                v.getContext().getColor(colorRes));
        v.setBackground(d);
        v.setClipToOutline(false);
        return d;
    }

    static SquircleDrawable fillWithStroke(View v, int colorRes, int strokeRes,
                                           float cornerDp, float strokeDp) {
        SquircleDrawable d = new SquircleDrawable(dp(v.getContext(), cornerDp),
                v.getContext().getColor(colorRes));
        d.setStroke(dp(v.getContext(), strokeDp), v.getContext().getColor(strokeRes));
        v.setBackground(d);
        return d;
    }

    static void card(View v, float cornerDp) {
        fill(v, R.color.pb_card, cornerDp);
        v.setElevation(dp(v.getContext(), isNight(v.getContext()) ? 3 : 1.5f));
    }

    static void block(View v, float cornerDp) {
        fill(v, R.color.pb_block, cornerDp);
    }

    /** 圆角水波纹：按下时只在内侧泛出浅色，不描边、不带方形阴影。 */
    static RippleDrawable ripple(Context c, float cornerDp) {
        SquircleDrawable mask = new SquircleDrawable(dp(c, cornerDp), Color.WHITE);
        return new RippleDrawable(
                ColorStateList.valueOf(alpha(c.getColor(R.color.pb_primary), 0.18f)),
                null, mask);
    }

    static void borderRadius(View v, float cornerDp) {
        v.setClipToOutline(true);
        v.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                float r = dp(view.getContext(), cornerDp);
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
            }
        });
    }

    // ------------------------------------------------------------ 交互

    /**
     * 弹性按压：按下缩到 0.97，松开用轻微过冲弹回，和 prism_ui 的
     * {@code ElasticPress}（stiffness 165 / damping 0.8）观感一致。
     */
    static void press(View v, Runnable onClick) {
        v.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        view.animate().scaleX(0.97f).scaleY(0.97f)
                                .setDuration(DUR_PRESS)
                                .setInterpolator(new DecelerateInterpolator())
                                .start();
                        break;
                    case MotionEvent.ACTION_CANCEL:
                        view.animate().scaleX(1f).scaleY(1f)
                                .setDuration(DUR_MID)
                                .setInterpolator(new OvershootInterpolator(1.2f))
                                .start();
                        break;
                    case MotionEvent.ACTION_UP:
                        view.animate().scaleX(1f).scaleY(1f)
                                .setDuration(260)
                                .setInterpolator(new OvershootInterpolator(1.6f))
                                .start();
                        view.performClick();
                        break;
                    default:
                        break;
                }
                return true;
            }
        });
        v.setOnClickListener(x -> {
            x.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            if (onClick != null) {
                onClick.run();
            }
        });
    }

    static void pressHaptic(View v) {
        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
    }

    // ------------------------------------------------------------ 主题

    /**
     * 应用明暗模式：system / light / dark。
     *
     * <p>用 {@link UiModeManager#setApplicationNightMode} 而不是重建 Activity，
     * 系统会按新配置重新走一遍资源解析（values / values-night），页面无需重启。</p>
     */
    static void applyThemeMode(Context c, String mode) {
        if (Build.VERSION.SDK_INT < 31) {
            return;
        }
        UiModeManager manager = c.getSystemService(UiModeManager.class);
        if (manager == null) {
            return;
        }
        int nightMode = switch (mode == null ? "system" : mode) {
            case "light" -> UiModeManager.MODE_NIGHT_NO;
            case "dark" -> UiModeManager.MODE_NIGHT_YES;
            default -> UiModeManager.MODE_NIGHT_AUTO;
        };
        try {
            manager.setApplicationNightMode(nightMode);
        } catch (Throwable ignored) {
            // 部分 ROM 不支持按应用设置夜间模式：保持系统当前值即可
        }
    }

    /** 边缘到边缘：透明状态栏 / 导航栏，图标颜色随明暗切换。 */
    static void edgeToEdge(Activity activity) {
        Window w = activity.getWindow();
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        w.setDecorFitsSystemWindows(false);
        View decor = w.getDecorView();
        decor.setSystemUiVisibility(decor.getSystemUiVisibility()
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        int flags = decor.getSystemUiVisibility();
        boolean night = isNight(activity);
        if (!night) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        } else {
            flags &= ~(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
        decor.setSystemUiVisibility(flags);
    }

    /** 给页面顶部视图加状态栏高度的内边距。 */
    static void padTopForStatusBar(View v) {
        int originalLeft = v.getPaddingLeft();
        int originalTop = v.getPaddingTop();
        int originalRight = v.getPaddingRight();
        int originalBottom = v.getPaddingBottom();
        int extra = dp(v.getContext(), topExtraDp(v.getContext()));
        v.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(originalLeft, originalTop + bars.top + extra,
                    originalRight, originalBottom);
            return insets;
        });
        v.requestApplyInsets();
    }

    /**
     * 状态栏之外再多留的一点呼吸空间。
     *
     * <p>不针对任何 ROM 做特判：系统在状态栏第二行画什么（网速、运营商）
     * 属于系统自己的事，应用只按上报的 inset 排版。</p>
     */
    static int topExtraDp(Context c) {
        return 8;
    }

    /** 颜色混合：把 over 以 alpha 叠在 base 上，返回不透明结果。 */
    static int blend(int base, int over, float alpha) {
        float a = Math.max(0f, Math.min(1f, alpha));
        int r = Math.round(Color.red(base) * (1 - a) + Color.red(over) * a);
        int g = Math.round(Color.green(base) * (1 - a) + Color.green(over) * a);
        int b = Math.round(Color.blue(base) * (1 - a) + Color.blue(over) * a);
        return Color.rgb(r, g, b);
    }

    /**
     * 导航 Dock 选中胶囊的颜色，等价于 prism_ui 的
     * {@code alphaBlend(onSurface 8%, SurfaceLevel.floating.next)}：
     * 亮色是浮层上一档的中性灰，暗色是 inset 再叠 8% 前景。
     */
    static int lensColor(Context c) {
        int base = isNight(c) ? c.getColor(R.color.pb_inset) : c.getColor(R.color.pb_block);
        return blend(base, c.getColor(R.color.pb_on_surface), 0.08f);
    }

    /** 给悬浮 Dock 加底部安全区内边距。 */
    static void padBottomForNavBar(View v) {
        int originalBottom = v.getPaddingBottom();
        v.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            ViewGroup.LayoutParams lp = view.getLayoutParams();
            if (lp instanceof ViewGroup.MarginLayoutParams m) {
                m.bottomMargin = originalBottom + bars.bottom;
                view.setLayoutParams(m);
            }
            return insets;
        });
        v.requestApplyInsets();
    }

    /** 页面滚动区底部留白：让内容能滚到悬浮 Dock 上沿之外。 */
    static void padBottomForDock(View v, int extraDp) {
        int pad = dp(v.getContext(), extraDp);
        v.setPadding(v.getPaddingLeft(), v.getPaddingTop(),
                v.getPaddingRight(), v.getPaddingBottom() + pad);
    }

    static void tint(ImageView iv, int color) {
        iv.setColorFilter(color);
    }

    static void text(TextView tv, float sizeSp, boolean medium, int colorRes) {
        tv.setTextSize(sizeSp);
        tv.setTextColor(tv.getContext().getColor(colorRes));
        tv.setLetterSpacing(0f);
        tv.setIncludeFontPadding(false);
        tv.setFontFeatureSettings("'tnum'");
        tv.setTypeface(android.graphics.Typeface.create(
                medium ? "sans-serif-medium" : "sans-serif",
                android.graphics.Typeface.NORMAL));
    }
}
