package io.github.jiangjinyue2333.actiongrid.update;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import javax.net.ssl.HttpsURLConnection;

public final class UpdateClient {
    private static final int MAX_MANIFEST_BYTES = 256 * 1024;

    private UpdateClient() {
    }

    public static UpdateManifest fetch() throws IOException {
        return fetch(UpdateSource.GITHUB);
    }

    public static UpdateManifest fetch(UpdateSource source) throws IOException {
        if (source == null) {
            throw new IllegalArgumentException("Update source is required");
        }
        HttpsURLConnection connection = (HttpsURLConnection)
                new URL(source.manifestUrl()).openConnection();
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(15_000);
        // GitHub's stable latest-release asset URL redirects to the release asset.
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "ActionGrid-Android");
        try {
            if (connection.getResponseCode() != HttpsURLConnection.HTTP_OK) {
                throw new IOException("Unexpected HTTP response");
            }
            try (InputStream input = connection.getInputStream()) {
                byte[] bytes = input.readNBytes(MAX_MANIFEST_BYTES + 1);
                if (bytes.length > MAX_MANIFEST_BYTES) {
                    throw new IOException("Manifest is too large");
                }
                UpdateManifest manifest = UpdateManifest.parse(
                        new String(bytes, StandardCharsets.UTF_8));
                if (!source.acceptsApkUrl(manifest.apkUrl())) {
                    throw new IOException("Update asset is outside the selected source");
                }
                return manifest;
            }
        } finally {
            connection.disconnect();
        }
    }
}
