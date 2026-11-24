package io.github.lianxia233.privbroker;

import android.app.Activity;
import android.os.Bundle;

/** 独立设置页入口：复用设置 Tab 的同一份页面与逻辑。 */
public final class SettingsActivity extends Activity {
    private SettingsPage page;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Prism.edgeToEdge(this);
        setContentView(R.layout.page_settings);
        page = new SettingsPage(this, findViewById(R.id.settingsScroll));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (page != null) {
            page.onResume();
        }
    }
}
