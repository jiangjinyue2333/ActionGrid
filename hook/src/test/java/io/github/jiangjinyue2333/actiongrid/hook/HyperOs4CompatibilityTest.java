package io.github.jiangjinyue2333.actiongrid.hook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

public final class HyperOs4CompatibilityTest {
    @Test
    public void nativeRoutingDoesNotRejectCompatibleLauncherUpdatesByVersion() {
        assertTrue(HyperOs4Compatibility.supports(37, 801027748L, false));
        assertTrue(HyperOs4Compatibility.supports(37, 801028566L, false));
        assertTrue(HyperOs4Compatibility.supports(38, 901000001L, false));
        assertFalse(HyperOs4Compatibility.supports(37, 801027748L, true));
        assertFalse(HyperOs4Compatibility.supports(36, 801027748L, false));
    }

    @Test
    public void rustLauncherBackendIsSelectedAutomatically() {
        assertTrue(HyperOs4Compatibility.supports(37, 801028565L, false));
        assertTrue(HyperOs4Compatibility.supports(37, 801027722L, false));
        assertTrue(HyperOs4Compatibility.supports(37, 801027653L, false));
        assertTrue(HyperOs4Compatibility.isNativeCandidate(37, 801028566L, false));
        assertFalse(HyperOs4Compatibility.isNativeCandidate(37, 801028566L, true));
        assertTrue(HyperOs4Compatibility.supports(37, 801025436L, false));
        assertFalse(HyperOs4Compatibility.supports(37, 801025436L, true));
        assertTrue(HyperOs4Compatibility.supports(37, 801025437L, false));
        assertFalse(HyperOs4Compatibility.supports(36, 801025436L, false));
    }

    @Test
    public void desktopScreenRequiresOneBestVisibleFingerprint() {
        List<HyperOs4Compatibility.FavoriteCell> favorites = List.of(
                favorite(1, "相机", 3, 0, 0),
                favorite(2, "设置", 3, 1, 0),
                favorite(3, "相机", 4, 0, 0),
                favorite(4, "天气", 4, 1, 0));

        assertEquals(3L, HyperOs4Compatibility.resolveDesktopScreen(favorites,
                List.of(new HyperOs4Compatibility.SemanticCell("相机", 0, 0),
                        new HyperOs4Compatibility.SemanticCell("设置，2 个通知", 1, 0))));
        assertEquals(HyperOs4Compatibility.NO_SCREEN,
                HyperOs4Compatibility.resolveDesktopScreen(favorites,
                        List.of(new HyperOs4Compatibility.SemanticCell("相机", 0, 0))));
    }

    @Test
    public void virtualPositionIdsAreReservedUniqueAndRoundTripNodeIds() {
        int desktop = HyperOs4VirtualNodes.id(
                HyperOs4Workspace.GridKind.DESKTOP, 2, 3);
        int hotseat = HyperOs4VirtualNodes.id(
                HyperOs4Workspace.GridKind.HOTSEAT, 2, 0);

        assertTrue(HyperOs4VirtualNodes.contains(desktop));
        assertTrue(HyperOs4VirtualNodes.contains(hotseat));
        assertFalse(desktop == hotseat);
        long nodeId = ((long) desktop << 32) | 1234L;
        assertEquals(1234, HyperOs4VirtualNodes.accessibilityViewId(nodeId));
        assertEquals(desktop, HyperOs4VirtualNodes.virtualDescendantId(nodeId));
    }

    @Test
    public void flutterGridCoordinatesMapToCells() {
        assertEquals(0, HyperOs4Compatibility.cellIndex(24, 0, 100, 4));
        assertEquals(1, HyperOs4Compatibility.cellIndex(25, 0, 100, 4));
        assertEquals(3, HyperOs4Compatibility.cellIndex(99, 0, 100, 4));
        assertEquals(-1, HyperOs4Compatibility.cellIndex(100, 0, 100, 4));
    }

    private static HyperOs4Compatibility.FavoriteCell favorite(
            long id, String title, long screen, int cellX, int cellY) {
        return new HyperOs4Compatibility.FavoriteCell(id, title,
                StableItemRef.CONTAINER_DESKTOP, screen, cellX, cellY, 0, 1, 1);
    }
}
