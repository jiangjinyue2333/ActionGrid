package io.github.jiangjinyue2333.actiongrid.hook;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class PageOrderProtocolTest {
    @Test
    public void onlyJava7xLaunchersCanInstallTheOption() {
        assertTrue(PageOrderProtocol.supports(700000000L, true));
        assertTrue(PageOrderProtocol.supports(750000000L, true));
        assertTrue(PageOrderProtocol.supports(799999999L, true));
        assertFalse(PageOrderProtocol.supports(750000000L, false));
        assertFalse(PageOrderProtocol.supports(0L, true));
        assertFalse(PageOrderProtocol.supports(499999999L, true));
        assertFalse(PageOrderProtocol.supports(800000000L, true));
        assertFalse(PageOrderProtocol.supports(801027748L, false));
        assertFalse(PageOrderProtocol.supports(801028565L, false));
        assertFalse(PageOrderProtocol.supports(801028565L, true));
    }
}
