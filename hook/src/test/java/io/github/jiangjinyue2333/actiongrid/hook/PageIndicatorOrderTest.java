package io.github.jiangjinyue2333.actiongrid.hook;

import static android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS;
import static android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK;
import static android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
import static android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD;
import static org.junit.Assert.assertEquals;
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
        assertTrue(PageIndicatorOrder.isIndicator(" 第 １ 页共 ４ 页 "));
    }

    @Test
    public void ignoresOtherLabels() {
        assertFalse(PageIndicatorOrder.isIndicator(null));
        assertFalse(PageIndicatorOrder.isIndicator(""));
        assertFalse(PageIndicatorOrder.isIndicator("微信"));
        assertFalse(PageIndicatorOrder.isIndicator("第 1 页"));
        assertFalse(PageIndicatorOrder.isIndicator("共 4 页"));
        assertFalse(PageIndicatorOrder.isIndicator("设置，第 1 项"));
        assertFalse(PageIndicatorOrder.isIndicator("说明：第 1 页共 4 页"));
    }

    @Test
    public void reversesOnlyScrollActionsOnAnObservedIndicatorWhenEnabled() {
        PageIndicatorOrder order = new PageIndicatorOrder();
        Object controller = new Object();
        order.observe(controller, 2, 18L, true, 100L);
        assertEquals(-1, order.mapAction(controller, 18L, ACTION_SCROLL_FORWARD, 101L));
        order.setReversed(true);
        assertEquals(ACTION_SCROLL_BACKWARD,
                order.mapAction(controller, 18L, ACTION_SCROLL_FORWARD, 101L));
        assertEquals(ACTION_SCROLL_FORWARD,
                order.mapAction(controller, 18L, ACTION_SCROLL_BACKWARD, 101L));
        assertEquals(-1, order.mapAction(controller, 19L, ACTION_SCROLL_FORWARD, 101L));
        assertEquals(-1, order.mapAction(controller, 18L, ACTION_ACCESSIBILITY_FOCUS, 101L));
        assertEquals(-1, order.mapAction(controller, 18L, ACTION_CLICK, 101L));
        order.setReversed(false);
        assertEquals(-1, order.mapAction(controller, 18L, ACTION_SCROLL_BACKWARD, 101L));
    }

    @Test
    public void sameNodeIdInAnotherWindowDoesNotInheritTheIndicator() {
        PageIndicatorOrder order = new PageIndicatorOrder();
        Object home = new Object();
        Object dialog = new Object();
        order.setReversed(true);
        order.observe(home, 2, 18L, true, 100L);
        assertEquals(-1, order.mapAction(dialog, 18L, ACTION_SCROLL_FORWARD, 101L));
        order.observe(dialog, 3, 18L, false, 102L);
        assertEquals(ACTION_SCROLL_BACKWARD,
                order.mapAction(home, 18L, ACTION_SCROLL_FORWARD, 103L));
        order.observe(home, 4, 19L, true, 104L);
        assertEquals(-1, order.mapAction(home, 18L, ACTION_SCROLL_FORWARD, 105L));
    }

    @Test
    public void reusedNodesAndDetachedWindowsInvalidateCachedIndicators() {
        PageIndicatorOrder order = new PageIndicatorOrder();
        Object controller = new Object();
        order.setReversed(true);
        order.observe(controller, 2, 18L, true, 100L);
        order.observe(controller, 2, 18L, false, 101L);
        assertEquals(-1, order.mapAction(controller, 18L, ACTION_SCROLL_FORWARD, 102L));
        order.observe(controller, 2, 18L, true, 103L);
        order.observe(controller, -1, 19L, false, 104L);
        assertEquals(-1, order.mapAction(controller, 18L, ACTION_SCROLL_FORWARD, 105L));
    }

    @Test
    public void staleNodesCannotReverseLaterScrolls() {
        PageIndicatorOrder order = new PageIndicatorOrder();
        Object controller = new Object();
        order.setReversed(true);
        order.observe(controller, 2, 18L, true, 100L);
        assertEquals(ACTION_SCROLL_BACKWARD,
                order.mapAction(controller, 18L, ACTION_SCROLL_FORWARD, 20_100L));
        assertEquals(-1, order.mapAction(controller, 18L, ACTION_SCROLL_FORWARD, 20_101L));
        order.observe(controller, 2, 18L, true, 20_102L);
        assertEquals(ACTION_SCROLL_BACKWARD,
                order.mapAction(controller, 18L, ACTION_SCROLL_FORWARD, 20_103L));
    }
}
