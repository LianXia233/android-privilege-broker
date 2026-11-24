package io.github.lianxia233.privbroker;

import android.app.Activity;
import android.os.Bundle;

/** 独立日志页入口：复用记录 Tab 的同一份页面与逻辑。 */
public final class LogActivity extends Activity {
    private LogsPage page;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Prism.edgeToEdge(this);
        setContentView(R.layout.page_logs);
        page = new LogsPage(this, findViewById(R.id.logList).getRootView());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (page != null) {
            page.onResume();
        }
    }
}
