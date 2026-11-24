package io.github.lianxia233.privbroker;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * 主题页：按 FlClash 的 Theme 页复刻。
 *
 * <p>分组：实时预览、主题模式、纯黑模式、主题色彩、配色风格、底栏、
 * 选项卡动画、液态玻璃、字体、文本缩放。每项选择写入 {@link UiPrefs}
 * 后 {@code recreate()}，由 {@link Prism#applyTheme(Activity)} 在
 * onCreate 之前装配色板/纯黑/字体覆盖层。</p>
 */
public final class ThemeActivity extends Activity {
    private static final String[] SEED_NAMES = {
            "blue", "violet", "magenta", "red", "orange", "yellow", "green", "gray"};
    private static final int[] SEED_COLORS = {
            0xFF007AFF, 0xFF8B5CF6, 0xFFD946EF, 0xFFEF4444,
            0xFFF97316, 0xFFEAB308, 0xFF22C55E, 0xFF6B7280};
    private static final String[] SEED_LABELS = {
            "蓝", "紫", "洋红", "红", "橙", "黄", "绿", "灰"};

    private MiniScreenView preview;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(Prism.wrap(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Prism.applyTheme(this);
        Prism.edgeToEdge(this);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Prism.color(this, R.attr.pbSurfacePage));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Prism.dp(this, 16);
        root.setPadding(pad, 0, pad, Prism.dp(this, 40));
        scroll.addView(root);

        // 顶部：返回 + 标题
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, 0, 0, 0);
        header.setMinimumHeight(Prism.dp(this, 72));
        ImageView back = new ImageView(this);
        back.setImageResource(R.drawable.ic_action_back);
        back.setColorFilter(Prism.color(this, R.attr.pbTextPrimary));
        int b = Prism.dp(this, 40);
        back.setPadding(Prism.dp(this, 9), Prism.dp(this, 9), Prism.dp(this, 9), Prism.dp(this, 9));
        back.setBackground(Prism.ripple(this, 999f));
        back.setOnClickListener(v -> finish());
        header.addView(back, new LinearLayout.LayoutParams(b, b));
        TextView title = new TextView(this);
        title.setText(R.string.theme_page_title);
        Prism.text(title, 24f, true, R.attr.pbTextPrimary);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = Prism.dp(this, 12);
        header.addView(title, tlp);
        Prism.padTopForStatusBar(header);
        root.addView(header);

        // 实时预览
        preview = new MiniScreenView(this);
        refreshPreview();
        LinearLayout previewCard = new LinearLayout(this);
        previewCard.setOrientation(LinearLayout.VERTICAL);
        Prism.fill(previewCard, R.attr.pbSurfaceCard, 28f);
        previewCard.setElevation(Prism.dp(this, 1));
        previewCard.setPadding(Prism.dp(this, 16), Prism.dp(this, 16),
                Prism.dp(this, 16), Prism.dp(this, 16));
        FrameLayout holder = new FrameLayout(this);
        holder.addView(preview, new FrameLayout.LayoutParams(
                Prism.dp(this, 168), Prism.dp(this, 168)));
        holder.setForegroundGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout wrapper = new FrameLayout(this);
        wrapper.addView(holder, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, Prism.dp(this, 168)));
        holder.setLayoutParams(new FrameLayout.LayoutParams(
                Prism.dp(this, 168), Prism.dp(this, 168), Gravity.CENTER_HORIZONTAL));
        previewCard.addView(wrapper);
        root.addView(previewCard, marginTop(12));

        // 主题模式
        root.addView(section(R.string.setting_theme), marginTop(24));
        String mode = UiPrefs.themeMode(this);
        root.addView(choiceGroup(new String[]{
                getString(R.string.theme_system),
                getString(R.string.theme_light),
                getString(R.string.theme_dark)}, switch (mode) {
            case "light" -> 1;
            case "dark" -> 2;
            default -> 0;
        }, i -> {
            String next = i == 1 ? "light" : i == 2 ? "dark" : "system";
            UiPrefs.setThemeMode(this, next);
            recreate();
        }));

        // 纯黑
        root.addView(section(R.string.theme_pure_black_title), marginTop(24));
        root.addView(choiceGroup(new String[]{
                getString(R.string.theme_standard),
                getString(R.string.theme_pure_black)}, UiPrefs.pureBlack(this) ? 1 : 0, i -> {
            UiPrefs.setPureBlack(this, i == 1);
            recreate();
        }));

        // 主题色彩
        root.addView(section(R.string.theme_colors_title), marginTop(24));
        root.addView(swatches());

        // 配色风格
        root.addView(section(R.string.theme_color_style), marginTop(24));
        String style = UiPrefs.colorStyle(this);
        root.addView(valueRow(styleLabel(style), () -> new AlertDialog.Builder(this)
                .setTitle(R.string.theme_color_style)
                .setItems(new String[]{
                        getString(R.string.color_style_vivid),
                        getString(R.string.color_style_soft),
                        getString(R.string.color_style_mono)}, (d, i) -> {
                    UiPrefs.setColorStyle(this,
                            i == 1 ? "soft" : i == 2 ? "mono" : "vivid");
                    recreate();
                }).show()));

        // 底栏
        root.addView(section(R.string.setting_dock), marginTop(24));
        root.addView(choiceGroup(new String[]{
                getString(R.string.dock_floating),
                getString(R.string.dock_docked)}, UiPrefs.dockMode(this), i -> {
            UiPrefs.setDockMode(this, i);
            recreate();
        }));

        // 选项卡动画
        root.addView(section(R.string.setting_tab_animation), marginTop(24));
        root.addView(choiceGroup(new String[]{
                getString(R.string.anim_slide),
                getString(R.string.anim_fade)}, UiPrefs.tabAnimation(this), i -> {
            UiPrefs.setTabAnimation(this, i);
            recreate();
        }));

        // 液态玻璃
        root.addView(section(R.string.theme_glass_title), marginTop(24));
        String glass = UiPrefs.glass(this);
        root.addView(choiceGroup(new String[]{
                getString(R.string.theme_none),
                getString(R.string.theme_standard),
                getString(R.string.theme_ultra)}, switch (glass) {
            case "standard" -> 1;
            case "ultra" -> 2;
            default -> 0;
        }, i -> {
            UiPrefs.setGlass(this, i == 1 ? "standard" : i == 2 ? "ultra" : "none");
            recreate();
        }));

        // 字体
        root.addView(section(R.string.theme_font_title), marginTop(24));
        root.addView(valueRow(fontLabel(UiPrefs.font(this)), () -> new AlertDialog.Builder(this)
                .setTitle(R.string.theme_font_title)
                .setItems(new String[]{
                        getString(R.string.font_default),
                        getString(R.string.font_serif),
                        getString(R.string.font_mono),
                        getString(R.string.font_condensed)}, (d, i) -> {
                    UiPrefs.setFont(this, i == 1 ? "serif" : i == 2 ? "mono"
                            : i == 3 ? "condensed" : "sans");
                    recreate();
                }).show()));

        // 文本缩放
        root.addView(section(R.string.theme_text_scale), marginTop(24));
        root.addView(textScaleCard());

        setContentView(scroll);
    }

    private void refreshPreview() {
        preview.setState(UiPrefs.dockMode(this) == NavigationDock.FLOATING,
                !"none".equals(UiPrefs.glass(this)), UiPrefs.tabAnimation(this));
        preview.setSelected(0);
    }

    // ------------------------------------------------------------ 组件

    private LinearLayout.LayoutParams marginTop(int dp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Prism.dp(this, dp);
        return lp;
    }

    private TextView section(int titleRes) {
        TextView tv = new TextView(this);
        tv.setText(titleRes);
        Prism.text(tv, 12f, true, R.attr.pbTextMuted);
        tv.setLetterSpacing(0.06f);
        return tv;
    }

    interface Choice {
        void onPick(int index);
    }

    /** 一行缩略图选择：每项是迷你屏幕 + 标签 + 选中勾。 */
    private View choiceGroup(String[] labels, int selected, Choice onPick) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        Prism.fill(card, R.attr.pbSurfaceCard, 24f);
        card.setElevation(Prism.dp(this, 1));
        int p = Prism.dp(this, 8);
        card.setPadding(p, p, p, p);
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            boolean active = i == selected;
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER_HORIZONTAL);
            item.setPadding(Prism.dp(this, 8), Prism.dp(this, 8),
                    Prism.dp(this, 8), Prism.dp(this, 8));
            if (active) {
                SquircleDrawable bg = new SquircleDrawable(Prism.dp(this, 18),
                        Prism.color(this, R.attr.pbSurfaceBlock));
                item.setBackground(bg);
            }
            MiniScreenView mini = new MiniScreenView(this);
            mini.setState(UiPrefs.dockMode(this) == NavigationDock.FLOATING,
                    !"none".equals(UiPrefs.glass(this)), UiPrefs.tabAnimation(this));
            mini.setSelected(i);
            item.addView(mini, new LinearLayout.LayoutParams(
                    Prism.dp(this, 56), Prism.dp(this, 82)));
            TextView label = new TextView(this);
            label.setText(labels[i]);
            label.setGravity(Gravity.CENTER);
            Prism.text(label, 13f, active, active ? R.attr.pbPrimary : R.attr.pbTextPrimary);
            item.addView(label, marginTop(6));
            if (active) {
                TextView check = new TextView(this);
                check.setText("✓");
                check.setGravity(Gravity.CENTER);
                Prism.text(check, 13f, true, R.attr.pbPrimary);
                item.addView(check);
            }
            Prism.press(item, () -> onPick.onPick(index));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            card.addView(item, lp);
        }
        return card;
    }

    private View swatches() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        Prism.fill(card, R.attr.pbSurfaceCard, 24f);
        card.setElevation(Prism.dp(this, 1));
        card.setPadding(Prism.dp(this, 12), Prism.dp(this, 12),
                Prism.dp(this, 12), Prism.dp(this, 12));
        String current = UiPrefs.seed(this);
        for (int i = 0; i < SEED_NAMES.length; i++) {
            final String seed = SEED_NAMES[i];
            FrameLayout dot = new FrameLayout(this);
            View fill = new View(this);
            fill.setBackground(new SquircleDrawable(Prism.dp(this, 999f), SEED_COLORS[i]));
            dot.addView(fill, new FrameLayout.LayoutParams(-1, -1));
            if (seed.equals(current)) {
                View ring = new View(this);
                SquircleDrawable r = new SquircleDrawable(Prism.dp(this, 999f), Color.TRANSPARENT);
                r.setStroke(Prism.dp(this, 3), Prism.color(this, R.attr.pbPrimary));
                ring.setBackground(r);
                dot.addView(ring, new FrameLayout.LayoutParams(-1, -1));
            }
            int size = Prism.dp(this, 32);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.rightMargin = Prism.dp(this, 8);
            dot.setContentDescription(SEED_LABELS[i]);
            Prism.press(dot, () -> {
                UiPrefs.setSeed(this, seed);
                recreate();
            });
            card.addView(dot, lp);
        }
        return card;
    }

    private View valueRow(String value, Runnable onTap) {
        LinearLayout card = new LinearLayout(this);
        card.setGravity(Gravity.CENTER_VERTICAL);
        Prism.fill(card, R.attr.pbSurfaceCard, 24f);
        card.setElevation(Prism.dp(this, 1));
        card.setPadding(Prism.dp(this, 16), Prism.dp(this, 16),
                Prism.dp(this, 16), Prism.dp(this, 16));
        TextView tv = new TextView(this);
        Prism.text(tv, 15f, false, R.attr.pbTextPrimary);
        tv.setText(value);
        card.addView(tv, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView chev = new TextView(this);
        chev.setText("›");
        Prism.text(chev, 20f, false, R.attr.pbTextMuted);
        card.addView(chev);
        Prism.press(card, onTap);
        return card;
    }

    private View textScaleCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        Prism.fill(card, R.attr.pbSurfaceCard, 24f);
        card.setElevation(Prism.dp(this, 1));
        card.setPadding(Prism.dp(this, 16), Prism.dp(this, 16),
                Prism.dp(this, 16), Prism.dp(this, 16));

        SegmentedControl seg = new SegmentedControl(this);
        seg.setSegments(getString(R.string.theme_follow_system),
                getString(R.string.theme_custom));
        boolean custom = UiPrefs.textScale(this) > 0f;
        seg.setSelection(custom ? 1 : 0, false);
        card.addView(seg, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Prism.dp(this, 44)));

        float scale = custom ? UiPrefs.textScale(this) : 1f;
        TextView value = new TextView(this);
        Prism.text(value, 13f, true, R.attr.pbTextSecondary);
        value.setText(Math.round(scale * 100) + "%");
        value.setGravity(Gravity.END);
        card.addView(value, marginTop(10));

        SeekBar bar = new SeekBar(this);
        bar.setMax(9); // 0.85 ~ 1.30，步长 0.05
        bar.setProgress(Math.round((scale - 0.85f) / 0.05f));
        bar.setEnabled(custom);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float next = 0.85f + progress * 0.05f;
                value.setText(Math.round(next * 100) + "%");
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                UiPrefs.setTextScale(ThemeActivity.this,
                        0.85f + seekBar.getProgress() * 0.05f);
                recreate();
            }
        });
        card.addView(bar);
        seg.setListener(index -> {
            if (index == 0) {
                UiPrefs.setTextScale(this, 0f);
            } else {
                UiPrefs.setTextScale(this, 0.85f + bar.getProgress() * 0.05f);
            }
            recreate();
        });
        return card;
    }

    private String styleLabel(String style) {
        return "soft".equals(style) ? getString(R.string.color_style_soft)
                : "mono".equals(style) ? getString(R.string.color_style_mono)
                : getString(R.string.color_style_vivid);
    }

    private String fontLabel(String font) {
        return switch (font) {
            case "serif" -> getString(R.string.font_serif);
            case "mono" -> getString(R.string.font_mono);
            case "condensed" -> getString(R.string.font_condensed);
            default -> getString(R.string.font_default);
        };
    }
}
