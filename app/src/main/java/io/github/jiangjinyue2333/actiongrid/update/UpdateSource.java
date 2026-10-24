package io.github.jiangjinyue2333.actiongrid.update;

import java.net.URI;

/** Public update feeds supported by the management app. */
public enum UpdateSource {
    GITHUB(
            "github",
            "GitHub",
            "https://github.com/jiangjinyue2333/ActionGrid/releases/latest/download/latest.json"),
    GITCODE(
            "gitcode",
            "GitCode",
            "https://api.gitcode.com/api/v5/repos/jinyue66/actiongrid-releases/raw/latest.json?ref=main");

    private final String id;
    private final String label;
    private final String manifestUrl;

    UpdateSource(String id, String label, String manifestUrl) {
        this.id = id;
        this.label = label;
        this.manifestUrl = manifestUrl;
    }

    public String id() {
        return id;
    }

    public String label() {
        return label;
    }

    public String manifestUrl() {
        return manifestUrl;
    }

    public boolean acceptsApkUrl(String apkUrl) {
        URI uri;
        try {
            uri = URI.create(apkUrl);
        } catch (IllegalArgumentException exception) {
            return false;
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            return false;
        }
        return switch (this) {
            case GITHUB -> "github.com".equalsIgnoreCase(uri.getHost())
                    && uri.getPath().startsWith("/jiangjinyue2333/ActionGrid/releases/download/");
            case GITCODE -> "api.gitcode.com".equalsIgnoreCase(uri.getHost());
        };
    }

    public static UpdateSource fromId(String value) {
        if (value != null) {
            for (UpdateSource source : values()) {
                if (source.id.equalsIgnoreCase(value)) {
                    return source;
                }
            }
        }
        return GITHUB;
    }
}
