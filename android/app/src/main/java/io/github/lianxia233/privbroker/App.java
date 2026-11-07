package io.github.lianxia233.privbroker;

import android.app.Application;
import android.content.Context;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class App extends Application {
    private static Context appContext;
    private static ExecutorService worker;

    @Override
    public void onCreate() {
        super.onCreate();
        appContext = getApplicationContext();
        // 生命周期跟随进程：静默安装不依赖任何 Activity 是否存活。
        worker = Executors.newCachedThreadPool();
    }

    public static Context context() {
        return appContext;
    }

    /** 全进程共享的后台执行器，供静默入口与各 UI 复用。 */
    public static ExecutorService executor() {
        if (worker == null) {
            worker = Executors.newCachedThreadPool();
        }
        return worker;
    }
}
