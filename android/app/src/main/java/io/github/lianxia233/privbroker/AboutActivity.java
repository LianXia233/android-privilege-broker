package io.github.lianxia233.privbroker;

import android.content.Context;
import android.app.Activity;
import android.os.Bundle;

/** 独立关于页入口：复用关于 Tab 的同一份页面。 */
public final class AboutActivity extends Activity {

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(Prism.wrap(base));
    }
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Prism.applyTheme(this);
        Prism.edgeToEdge(this);
        setContentView(R.layout.page_about);
        new AboutPage(this, findViewById(R.id.aboutScroll));
    }
}
