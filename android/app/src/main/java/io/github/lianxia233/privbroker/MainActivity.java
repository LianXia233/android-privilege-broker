package io.github.lianxia233.privbroker;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;

/**
 * 主界面壳层：FlClash 同款「四页 + 悬浮导航 Dock」结构。
 *
 * <p>仪表盘 / 记录 / 设置 / 关于四个页面常驻挂载，切换只做位移动画或交叉
 * 淡入；Dock 的选中胶囊 250ms 回弹。页面偏好（主题、动画、Dock 形态、
 * 上次停留的页）存在 {@link UiPrefs}，重启后保持。</p>
 */
public final class MainActivity extends Activity implements NavigationDock.Listener {
    private static final int TAB_COUNT = 4;

    private PageSwitcher pages;
    private NavigationDock dock;
    private View dockHost;
    private DashboardPage dashboard;
    private LogsPage logs;
    private SettingsPage settings;
    private int index;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Prism.edgeToEdge(this);
        setContentView(R.layout.activity_main);

        pages = findViewById(R.id.pages);
        dock = findViewById(R.id.dock);
        dockHost = findViewById(R.id.dockHost);

        LayoutInflater inflater = getLayoutInflater();
        View dash = inflater.inflate(R.layout.page_dashboard, pages, false);
        View logPage = inflater.inflate(R.layout.page_logs, pages, false);
        View settingsPage = inflater.inflate(R.layout.page_settings, pages, false);
        View aboutPage = inflater.inflate(R.layout.page_about, pages, false);
        pages.addPage(dash);
        pages.addPage(logPage);
        pages.addPage(settingsPage);
        pages.addPage(aboutPage);

        dashboard = new DashboardPage(this, dash);
        dashboard.setOnOpenLogs(() -> select(1, true));
        logs = new LogsPage(this, logPage);
        settings = new SettingsPage(this, settingsPage);
        new AboutPage(this, aboutPage);

        dock.setItems(new String[]{
                getString(R.string.tab_dashboard),
                getString(R.string.tab_logs),
                getString(R.string.tab_settings),
                getString(R.string.tab_about),
        }, new int[]{
                R.drawable.ic_tab_dashboard,
                R.drawable.ic_tab_logs,
                R.drawable.ic_tab_settings,
                R.drawable.ic_tab_about,
        });
        dock.setListener(this);
        applyDockMode();
        pages.setMode(UiPrefs.tabAnimation(this));
        Prism.padBottomForNavBar(dockHost);

        int start = Math.max(0, Math.min(UiPrefs.lastTab(this), TAB_COUNT - 1));
        select(start, false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (dashboard != null) {
            dashboard.onResume();
        }
        if (logs != null) {
            logs.onResume();
        }
        if (settings != null) {
            settings.onResume();
        }
        applyDockMode();
        if (pages != null) {
            pages.setMode(UiPrefs.tabAnimation(this));
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (dashboard != null) {
            dashboard.onPause();
        }
    }

    @Override
    public void onSelected(int selected) {
        select(selected, true);
    }

    private void select(int next, boolean animate) {
        if (next < 0 || next >= TAB_COUNT) {
            return;
        }
        index = next;
        pages.show(next, animate);
        dock.setSelected(next);
        UiPrefs.setLastTab(this, next);
        if (next == 1) {
            logs.reload();
        } else if (next == 2) {
            settings.onResume();
        } else if (next == 0) {
            dashboard.onResume();
        }
    }

    private void applyDockMode() {
        int mode = UiPrefs.dockMode(this);
        dock.setMode(mode);
        ViewGroupShim.layout(dock, mode, this);
    }

    @Override
    public void onBackPressed() {
        if (index != 0) {
            select(0, true);
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (dashboard != null && dashboard.onActivityResult(requestCode, resultCode, data)) {
            return;
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    /** 只在需要时调一次 layoutParams，避免每次 onResume 重置安全区内边距。 */
    private static final class ViewGroupShim {
        static void layout(NavigationDock dock, int mode, Activity a) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) dock.getLayoutParams();
            int side = mode == NavigationDock.FLOATING ? Prism.dp(a, 21) : 0;
            int bottom = mode == NavigationDock.FLOATING ? Prism.dp(a, 21) : 0;
            if (lp.leftMargin == side && lp.rightMargin == side && lp.bottomMargin == bottom) {
                return;
            }
            lp.leftMargin = side;
            lp.rightMargin = side;
            lp.bottomMargin = bottom;
            lp.gravity = Gravity.BOTTOM;
            dock.setLayoutParams(lp);
        }
    }
}
