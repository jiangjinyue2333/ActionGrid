package io.github.jiangjinyue2333.actiongrid;

import android.content.Context;
import android.content.SharedPreferences;

import io.github.jiangjinyue2333.actiongrid.hook.PageOrderProtocol;
import io.github.libxposed.service.XposedService;

/**
 * 页码指示器翻页方向设置。
 *
 * 本地保存一份用于界面显示；桌面进程内的模块读的是 remote preferences，
 * 因此每次改动都要同步过去，服务可用时也会补写一次。
 */
final class PageOrderSettings {
    private static final String PREFS_NAME = "page_order";
    private static final String KEY_REVERSED = "reversed";

    private PageOrderSettings() {
    }

    static boolean isReversed(Context context) {
        return preferences(context).getBoolean(KEY_REVERSED, false);
    }

    static void setReversed(Context context, boolean reversed) {
        preferences(context).edit().putBoolean(KEY_REVERSED, reversed).apply();
    }

    static boolean pushToModule(XposedService service, Context context) {
        if (service == null) {
            return false;
        }
        try {
            SharedPreferences remote =
                    service.getRemotePreferences(PageOrderProtocol.PREFERENCES_GROUP);
            return remote.edit()
                    .putBoolean(PageOrderProtocol.KEY_REVERSED, isReversed(context))
                    .commit();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
