package io.github.jiangjinyue2333.actiongrid.hook;

/**
 * 页码指示器翻页方向的共享偏好协议。
 *
 * 模块 App 写入，桌面进程内的模块读取；两侧共用同一分组，避免各存一份。
 */
public final class PageOrderProtocol {
    public static final String PREFERENCES_GROUP = "page_order";
    public static final String KEY_REVERSED = "page_indicator_reversed";

    private PageOrderProtocol() {
    }
}
