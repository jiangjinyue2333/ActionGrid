package io.github.jiangjinyue2333.actiongrid.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class UpdateSourceTest {
    @Test
    public void unknownSourceFallsBackToGithub() {
        assertEquals(UpdateSource.GITHUB, UpdateSource.fromId("missing"));
        assertEquals(UpdateSource.GITHUB, UpdateSource.fromId(null));
    }

    @Test
    public void githubReleaseAssetIsBoundToActionGridRepository() {
        assertTrue(UpdateSource.GITHUB.acceptsApkUrl(
                "https://github.com/jiangjinyue2333/ActionGrid/releases/download/v1/ActionGrid-1.apk"));
        assertFalse(UpdateSource.GITHUB.acceptsApkUrl(
                "https://github.com/other/repository/releases/download/v1/ActionGrid-1.apk"));
        assertFalse(UpdateSource.GITHUB.acceptsApkUrl("https://example.com/ActionGrid.apk"));
    }

    @Test
    public void gitcodeAssetRemainsSupported() {
        assertTrue(UpdateSource.GITCODE.acceptsApkUrl(
                "https://api.gitcode.com/api/v5/repos/jinyue66/actiongrid-releases/raw/ActionGrid.apk"));
    }
}
