package io.github.jiangjinyue2333.actiongrid.hook;

/** Shared settings for the HyperOS 3 page indicator option, contributed in PR #2. */
public final class PageOrderProtocol {
    public static final String PREFERENCES_GROUP = "page_order";
    public static final String KEY_REVERSED = "page_indicator_reversed";

    private PageOrderProtocol() {
    }

    public static boolean supports(long launcherVersion, boolean hasCode) {
        return hasCode && launcherVersion >= 700000000L && launcherVersion < 800000000L;
    }
}
