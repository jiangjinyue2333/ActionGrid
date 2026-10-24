package io.github.jiangjinyue2333.actiongrid;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.ApplicationInfo;

import io.github.jiangjinyue2333.actiongrid.hook.PageOrderProtocol;
import io.github.libxposed.service.XposedService;

/** Local UI state and remote preferences for the page indicator option from PR #2. */
final class PageOrderSettings {
    private static final String PREFS_NAME = "page_order";
    private static final String KEY_REVERSED = "reversed";

    private PageOrderSettings() {
    }

    static boolean supports(PackageInfo homeInfo) {
        return homeInfo != null && homeInfo.applicationInfo != null
                && PageOrderProtocol.supports(homeInfo.getLongVersionCode(),
                (homeInfo.applicationInfo.flags & ApplicationInfo.FLAG_HAS_CODE) != 0);
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
            if (service.getApiVersion() != XposedService.API_102
                    || (service.getFrameworkProperties() & XposedService.PROP_CAP_REMOTE) == 0) {
                return false;
            }
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
