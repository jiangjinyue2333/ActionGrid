package io.github.jiangjinyue2333.actiongrid.hook;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class PageIndicatorOrderTest {
    @Test
    public void matchesSystemPageIndicatorLabels() {
        assertTrue(PageIndicatorOrder.isIndicator("第 1 页共 4 页"));
        assertTrue(PageIndicatorOrder.isIndicator("第 12 页共 30 页"));
        assertTrue(PageIndicatorOrder.isIndicator("第1页，共4页"));
        assertTrue(PageIndicatorOrder.isIndicator("第 2 页, 共 4 页"));
        assertTrue(PageIndicatorOrder.isIndicator("第 三 页共 十 页"));
    }

    @Test
    public void ignoresOtherLabels() {
        assertFalse(PageIndicatorOrder.isIndicator(""));
        assertFalse(PageIndicatorOrder.isIndicator("微信"));
        assertFalse(PageIndicatorOrder.isIndicator("第 1 页"));
        assertFalse(PageIndicatorOrder.isIndicator("共 4 页"));
        assertFalse(PageIndicatorOrder.isIndicator("设置，第 1 项"));
    }
}
