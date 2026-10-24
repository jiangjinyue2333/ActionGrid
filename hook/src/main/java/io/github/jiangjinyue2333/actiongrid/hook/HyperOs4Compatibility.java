package io.github.jiangjinyue2333.actiongrid.hook;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class HyperOs4Compatibility {
    static final long NO_SCREEN = Long.MIN_VALUE;
    static final long CURRENT_SUPPORTED_LAUNCHER_VERSION = 801028565L;
    static final long PREVIOUS_SUPPORTED_LAUNCHER_VERSION = 801027722L;
    static final long OLDER_SUPPORTED_LAUNCHER_VERSION = 801027653L;

    private HyperOs4Compatibility() {
    }

    public static boolean isNativeCandidate(int sdk, long launcherVersion, boolean hasCode) {
        return sdk >= 37 && launcherVersion >= 800000000L && !hasCode;
    }

    public static boolean supports(int sdk, long launcherVersion, boolean hasCode) {
        // This selects the backend. Native code validates each actual ABI before use.
        return isNativeCandidate(sdk, launcherVersion, hasCode);
    }

    public static String profileName(long launcherVersion) {
        if (launcherVersion == CURRENT_SUPPORTED_LAUNCHER_VERSION) {
            return "8.01.02.8565（当前 FRB profile）";
        }
        if (launcherVersion == PREVIOUS_SUPPORTED_LAUNCHER_VERSION) {
            return "8.01.02.7722（旧 FRB profile）";
        }
        if (launcherVersion == OLDER_SUPPORTED_LAUNCHER_VERSION) {
            return "8.01.02.7653（旧 FRB profile）";
        }
        return "自动检测接口兼容性";
    }

    static int cellIndex(int center, int start, int size, int count) {
        if (size <= 0 || count <= 0 || center < start || center >= start + size) {
            return -1;
        }
        return (int) (((long) center - start) * count / size);
    }

    static long resolveDesktopScreen(
            List<FavoriteCell> favorites,
            List<SemanticCell> visibleCells
    ) {
        Map<Long, Integer> scores = new HashMap<>();
        for (FavoriteCell favorite : favorites) {
            if (favorite.container() != StableItemRef.CONTAINER_DESKTOP) {
                continue;
            }
            for (SemanticCell cell : visibleCells) {
                if (favorite.cellX() == cell.cellX() && favorite.cellY() == cell.cellY()
                        && titleMatches(cell.label(), favorite.title())) {
                    scores.merge(favorite.screenId(), 1, Integer::sum);
                }
            }
        }
        long winner = NO_SCREEN;
        int best = 0;
        boolean tied = false;
        for (Map.Entry<Long, Integer> entry : scores.entrySet()) {
            if (entry.getValue() > best) {
                winner = entry.getKey();
                best = entry.getValue();
                tied = false;
            } else if (entry.getValue() == best) {
                tied = true;
            }
        }
        return best == 0 || tied ? NO_SCREEN : winner;
    }

    static boolean titleMatches(String spokenLabel, String databaseTitle) {
        if (spokenLabel == null || databaseTitle == null) {
            return false;
        }
        String label = spokenLabel.strip();
        String title = databaseTitle.strip();
        if (label.isEmpty() || title.isEmpty()) {
            return false;
        }
        return label.equals(title)
                || label.startsWith(title + "，")
                || label.startsWith(title + ",")
                || label.startsWith(title + " ")
                || label.endsWith("，" + title)
                || label.endsWith("," + title);
    }

    record FavoriteCell(
            long id,
            String title,
            long container,
            long screenId,
            int cellX,
            int cellY,
            int itemType,
            int spanX,
            int spanY,
            String intentUri
    ) {
        FavoriteCell(
                long id,
                String title,
                long container,
                long screenId,
                int cellX,
                int cellY,
                int itemType,
                int spanX,
                int spanY
        ) {
            this(id, title, container, screenId, cellX, cellY, itemType, spanX, spanY, null);
        }

        StableItemRef stableRef() {
            return new StableItemRef(id, container, screenId, cellX, cellY,
                    itemType, spanX, spanY);
        }

        boolean movable() {
            return stableRef().isSupportedLiftSource();
        }
    }

    record SemanticCell(String label, int cellX, int cellY) {
    }
}
