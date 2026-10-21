package io.github.jiangjinyue2333.actiongrid;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

public final class ReleaseRegressionContractTest {
    @Test
    public void desktopRestartDoesNotInvokeRoot() throws IOException {
        String source = source("io/github/jiangjinyue2333/actiongrid/HomeRestarter.java");

        assertFalse(source.contains("\"su\""));
        assertTrue(source.contains("XposedService"));
    }

    @Test
    public void sharedLogExposesMetadataAndPropagatesUriGrant() throws IOException {
        String activity = source("io/github/jiangjinyue2333/actiongrid/MainActivity.java");
        String provider = source(
                "io/github/jiangjinyue2333/actiongrid/diagnostics/DiagnosticsProvider.java");
        String share = between(activity, "private void shareLogs", "private void checkUpdates");

        assertTrue(share.contains("ClipData.newRawUri"));
        assertTrue(provider.contains("OpenableColumns.DISPLAY_NAME"));
        assertTrue(provider.contains("OpenableColumns.SIZE"));
    }

    @Test
    public void verifiedUpdateDirectlyLaunchesInstallerWithReadableMetadata() throws IOException {
        String receiver = source(
                "io/github/jiangjinyue2333/actiongrid/update/UpdateDownloadReceiver.java");
        String installer = source(
                "io/github/jiangjinyue2333/actiongrid/update/UpdateInstallActivity.java");
        String provider = source(
                "io/github/jiangjinyue2333/actiongrid/update/UpdateFileProvider.java");
        String state = source("io/github/jiangjinyue2333/actiongrid/update/UpdateState.java");

        assertTrue(receiver.contains("UpdateInstallActivity.launch(foreground)"));
        assertTrue(installer.contains("ClipData.newRawUri"));
        assertTrue(installer.contains("openFileDescriptor(updateUri, \"r\")"));
        assertTrue(provider.contains("OpenableColumns.DISPLAY_NAME"));
        assertTrue(provider.contains("OpenableColumns.SIZE"));
        assertTrue(state.contains("VERIFIED_VERSION_CODE"));
        assertTrue(state.contains("versionCode <= BuildConfig.VERSION_CODE"));
    }

    @Test
    public void updatePromptReadsUpdateCancelThenIgnore() throws IOException {
        String activity = source("io/github/jiangjinyue2333/actiongrid/MainActivity.java");
        String prompt = between(activity, "private void handleUpdateResult",
                "private void startDownload");

        assertTrue(prompt.contains(".setNeutralButton(R.string.update_now"));
        assertTrue(prompt.contains(".setNegativeButton(R.string.cancel"));
        assertTrue(prompt.contains(".setPositiveButton(R.string.ignore_version"));
    }

    @Test
    public void diagnosticFileOpenUsesPlatformUriGrantEnforcement() throws IOException {
        String provider = source(
                "io/github/jiangjinyue2333/actiongrid/diagnostics/DiagnosticsProvider.java");
        String openFile = between(provider, "public ParcelFileDescriptor openFile",
                "private boolean isTrustedCaller");
        String manifest = manifest();

        assertFalse(openFile.contains("checkUriPermission"));
        assertFalse(openFile.contains("Binder.getCallingUid"));
        assertTrue(manifest.contains("<path-permission"));
        assertTrue(manifest.contains("android:readPermission="));
    }

    @Test
    public void downloadCompletionReachesVerifierAndRequiresRecoveryNotification()
            throws IOException {
        String activity = source("io/github/jiangjinyue2333/actiongrid/MainActivity.java");
        String startDownload = between(activity, "private void startDownload",
                "private void beginDownload");
        String manifest = manifest();
        String receiver = between(manifest,
                "android:name=\".update.UpdateDownloadReceiver\"", "</receiver>");

        assertTrue(startDownload.contains("UpdateNotification.canNotify(this)"));
        assertTrue(receiver.contains("android:exported=\"true\""));
        assertTrue(receiver.contains(
                "android:permission=\"android.permission.SEND_DOWNLOAD_COMPLETED_INTENTS\""));
    }

    @Test
    public void verifiedDownloadUsesAppOwnedInstallerEntryInsteadOfDownloadsNotification()
            throws IOException {
        String downloader = source(
                "io/github/jiangjinyue2333/actiongrid/update/UpdateDownloader.java");
        String receiver = source(
                "io/github/jiangjinyue2333/actiongrid/update/UpdateDownloadReceiver.java");
        String installer = source(
                "io/github/jiangjinyue2333/actiongrid/update/UpdateInstallActivity.java");
        String provider = source(
                "io/github/jiangjinyue2333/actiongrid/update/UpdateFileProvider.java");
        String notification = source(
                "io/github/jiangjinyue2333/actiongrid/update/UpdateNotification.java");
        String manifest = manifest();

        assertTrue(downloader.contains("setDestinationInExternalFilesDir"));
        assertTrue(downloader.contains("DownloadManager.Request.VISIBILITY_VISIBLE"));
        assertFalse(downloader.contains("VISIBILITY_VISIBLE_NOTIFY_COMPLETED"));
        assertFalse(downloader.contains("setDestinationUri"));
        assertTrue(receiver.contains("ActionGridApp.resumedActivity()"));
        assertTrue(receiver.contains("UpdateInstallActivity.launch"));
        assertTrue(receiver.contains("UpdateNotification.showReady"));
        assertTrue(installer.contains("UpdateFileProvider.uriFor"));
        assertTrue(installer.contains("ClipData.newRawUri"));
        assertTrue(installer.indexOf("startActivity(install)")
                < installer.indexOf("UpdateState.clearVerified(this)"));
        assertFalse(provider.contains("UpdateState.verifiedFile"));
        assertTrue(provider.contains("new File(root, uri.getLastPathSegment())"));
        assertTrue(notification.contains("PendingIntent.getActivity"));
        assertTrue(notification.contains("UpdateInstallActivity.intent"));
        assertTrue(manifest.contains("android.permission.POST_NOTIFICATIONS"));
        assertTrue(manifest.contains("android:name=\".update.UpdateInstallActivity\""));
    }

    @Test
    public void moduleScopesOnlySupportedAppsAndRemovesStaleSystemScope() throws IOException {
        Path local = Path.of("src", "main", "resources", "META-INF", "xposed", "scope.list");
        Path scopePath = Files.exists(local) ? local : Path.of("app").resolve(local);
        String scope = Files.readString(scopePath);
        String activity = source("io/github/jiangjinyue2333/actiongrid/MainActivity.java");

        assertTrue(scope.lines().anyMatch(line -> line.strip().equals("com.miui.home")));
        assertTrue(scope.lines().anyMatch(line -> line.strip().equals("com.android.contacts")));
        assertFalse(scope.lines().anyMatch(line -> line.strip().equals("android")));
        assertTrue(activity.contains("isHyperOs4Home"));
        assertTrue(activity.contains("removeScope(List.of(\"android\"))"));
        assertFalse(activity.contains("grantSystemScope"));
        assertFalse(activity.contains("AdapterModeSpinner"));
    }

    @Test
    public void hyperOs4StatusSeparatesProcessAndNativeActionState() throws IOException {
        String activity = source("io/github/jiangjinyue2333/actiongrid/MainActivity.java");
        Path local = Path.of("src/main/res/layout/activity_main.xml");
        Path layoutPath = Files.exists(local) ? local : Path.of("app").resolve(local);
        String layout = Files.readString(layoutPath);

        assertTrue(activity.contains("ActivityManager"));
        assertTrue(activity.contains("launcherProcessStatus"));
        assertTrue(activity.contains("hyperOs4ActionStatus"));
        assertTrue(activity.contains("HyperOs4Compatibility.supports"));
        assertFalse(activity.contains("hyperOs4 ? running.equals(\"模块已加载\")"));
        assertTrue(layout.contains("desktop_process_status_row"));
    }

    @Test
    public void launcherProcessAbsenceRemainsUnknown() throws IOException {
        String activity = source("io/github/jiangjinyue2333/actiongrid/MainActivity.java");
        String process = between(activity, "private String launcherProcessStatus",
                "private String hyperOs4ActionStatus");

        assertFalse(process.contains("return \"未运行\""));
        assertTrue(process.contains("return \"无法确认\""));
    }

    @Test
    public void nativeBackendResolvesVerifiedRustAbiWithoutRawOffsets() throws IOException {
        Path local = Path.of("src", "main", "cpp", "native_init.cpp");
        Path path = Files.exists(local) ? local : Path.of("app").resolve(local);
        String nativeSource = Files.readString(path);

        assertTrue(nativeSource.contains("kExpectedPrivateBuildId"));
        assertTrue(nativeSource.contains("kExpectedPublicBuildId"));
        assertTrue(nativeSource.contains("dlsym("));
        assertTrue(nativeSource.contains("input_MotionEvent_new"));
        assertTrue(nativeSource.contains("input_InputEvent_newWithMotionEvent"));
        assertTrue(nativeSource.contains("input_InputManager_injectInputEvent"));
        assertTrue(nativeSource.contains("0x1002"));
        assertTrue(nativeSource.contains("kShortcutMatchPinned"));
        assertTrue(nativeSource.contains("kShortcutMatchFlags"));
        assertFalse(nativeSource.contains(
                "rustResultOk(result, \"start shortcut\") && result.data[1]"));
        assertTrue(nativeSource.contains("不可用，请重新定位图标"));
        assertTrue(nativeSource.contains("resolveAppTarget(nodeId, label, target, screenHint)"));
        assertFalse(nativeSource.contains("0x01000001"));
        assertFalse(nativeSource.contains("0x23ca8"));
        assertFalse(nativeSource.contains("0x23ce4"));
        assertTrue(nativeSource.contains("native actions remain hidden"));
    }

    @Test
    public void nativeUninstallActionUsesSystemInstallerForMainUser() throws IOException {
        Path local = Path.of("src", "main", "cpp", "native_init.cpp");
        Path nativePath = Files.exists(local) ? local : Path.of("app").resolve(local);
        String nativeSource = Files.readString(nativePath);
        String manifest = manifest();

        assertTrue(nativeSource.contains("kUninstallAction"));
        assertTrue(nativeSource.contains("kUninstallLabel"));
        assertTrue(nativeSource.contains("target.user == 0"));
        assertTrue(nativeSource.contains("startUninstall(target)"));
        assertTrue(nativeSource.contains(
                "io.github.jiangjinyue2333.actiongrid.EXTRA_PACKAGE"));
        assertTrue(nativeSource.contains(
                "io.github.jiangjinyue2333.actiongrid.WeChatShortcutActivity"));
        assertFalse(nativeSource.contains("openLauncherUninstall"));
        assertFalse(nativeSource.contains("kLauncherContextClickAction"));
        assertFalse(nativeSource.contains("injectTouch(down, 0, x, y)"));
        assertFalse(manifest.contains("android:name=\".UninstallActivity\""));
    }

    @Test
    public void uninstallTrampolineUsesInstallerConfirmation() throws IOException {
        String source = source("io/github/jiangjinyue2333/actiongrid/WeChatShortcutActivity.java");
        assertTrue(source.contains("Intent.ACTION_DELETE"));
        assertTrue(source.contains("Intent.EXTRA_USER"));
        assertTrue(source.contains("Process.myUserHandle()"));
        assertTrue(source.contains("Uri.parse(\"package:\" + packageName)"));
        assertTrue(source.contains("getLaunchedFromPackage"));
        assertTrue(source.contains("getLaunchedFromUid"));
        assertTrue(source.contains("getPackagesForUid"));
        assertTrue(source.contains("isTrustedUninstallCaller"));
    }

    @Test
    public void nativeUninstallSharesLauncherIdentityThroughOptionsBundle() throws IOException {
        Path local = Path.of("src", "main", "cpp", "native_init.cpp");
        Path path = Files.exists(local) ? local : Path.of("app").resolve(local);
        String nativeSource = Files.readString(path);
        String uninstall = between(nativeSource, "bool startUninstall(", "// Layout is verified");
        String iconActions = between(nativeSource, "void injectIconActions(void *node) {",
                "void injectRecentActions(void *node) {");

        assertTrue(nativeSource.contains("BundleInsertBooleanFn"));
        assertTrue(nativeSource.contains("Bundle_insert_boolean"));
        assertTrue(uninstall.contains("android:activity.shareIdentity"));
        assertTrue(uninstall.contains("g_bundle_insert_boolean"));
        assertTrue(uninstall.replaceAll("\\s+", " ").contains(
                "g_scene_start_activity( scene, intent, 1, nullptr, 1, 0, -1, options"));
        assertTrue(uninstall.contains("g_bundle_drop(options)"));
        assertFalse(uninstall.contains("g_scene_start_activity(scene, intent, 1, nullptr"));
        assertTrue(iconActions.contains("uninstallLaunchReady()"));
    }

    @Test
    public void nativeRecentsAnnouncementsAreSurfaceScoped() throws IOException {
        Path local = Path.of("src", "main", "cpp", "native_init.cpp");
        Path path = Files.exists(local) ? local : Path.of("app").resolve(local);
        String nativeSource = Files.readString(path);

        assertTrue(nativeSource.contains("进入多任务界面"));
        assertTrue(nativeSource.contains("系统桌面"));
        assertTrue(nativeSource.contains("已清除"));
        assertTrue(nativeSource.contains("enum class SurfaceState"));
        assertTrue(nativeSource.contains("observeRecentSnapshot"));
        assertTrue(nativeSource.contains("g_recent_snapshot_initialized"));
        assertTrue(nativeSource.contains("g_surface_state"));
        assertTrue(nativeSource.contains("surfacePollLoop"));
        assertTrue(nativeSource.contains("ensureSurfacePoller"));
        assertTrue(nativeSource.contains("g_surface_scan_requested"));
        assertTrue(nativeSource.contains("queueSurfaceAnnouncement"));
        assertTrue(nativeSource.contains("flushSurfaceAnnouncements"));
        assertTrue(nativeSource.contains("handleRecentSwipeMotion"));
        assertTrue(nativeSource.contains("isEmptyRecentsLabel"));
        assertTrue(nativeSource.contains("rememberRecentRemoval"));
        assertTrue(nativeSource.contains("if (++g_recent_candidate_repeats < 2) return;"));
        assertTrue(nativeSource.contains("if (workspaceWindow == -1)"));
        assertFalse(nativeSource.contains("[DEBUG-"));
        assertTrue(nativeSource.contains("g_workspace_window_id"));
        assertTrue(nativeSource.contains("!= static_cast<int32_t>(SurfaceState::kWorkspace)"));
    }

    @Test
    public void nativeLiftRefreshesVirtualSlotsAfterPageChange() throws IOException {
        Path local = Path.of("src", "main", "cpp", "native_init.cpp");
        Path path = Files.exists(local) ? local : Path.of("app").resolve(local);
        String nativeSource = Files.readString(path);

        assertTrue(nativeSource.contains("refreshLiftSessionForPage"));
        assertTrue(nativeSource.contains("findCurrentDropParentNode"));
        assertTrue(nativeSource.contains("g_recent_candidate_repeats"));
        assertTrue(nativeSource.contains("if (pageChanged && g_lifted.load"));
        assertTrue(nativeSource.contains("g_virtual_focused_id.exchange(-1"));
    }

    @Test
    public void nativePageEventsDoNotHideLiftRefreshFromRealNodes() throws IOException {
        Path local = Path.of("src", "main", "cpp", "native_init.cpp");
        Path path = Files.exists(local) ? local : Path.of("app").resolve(local);
        String nativeSource = Files.readString(path);
        String paging = between(nativeSource, "bool rewritePageScrollEvent(",
                "void onSendEvent(void *controller, void *event) {");

        assertTrue(paging.contains("const bool lifted = g_lifted.load(std::memory_order_acquire);"));
        assertTrue(paging.contains("scheduleLiftSessionRefresh(lifted)"));
        assertTrue(nativeSource.contains("refreshLiftSessionForPage(provider)"));
    }

    @Test
    public void nativeLiftTouchAnnouncesEmptyCellCoordinates() throws IOException {
        Path local = Path.of("src", "main", "cpp", "native_init.cpp");
        Path path = Files.exists(local) ? local : Path.of("app").resolve(local);
        String nativeSource = Files.readString(path);
        String touch = between(nativeSource, "void updateTouchExplorationTarget(",
                "bool onLauncherMotionV2(");

        assertTrue(touch.contains("kActionHoverMove"));
        assertTrue(touch.contains("g_virtual_hovered_id.exchange"));
        assertTrue(touch.contains("sendEvent(target, 128)"));
        assertTrue(touch.contains("sendEvent(previous, 256)"));
        assertTrue(nativeSource.contains("sendEvent(nodeId, 32768)"));
        assertTrue(nativeSource.contains("g_event_push_text(event"));
        assertTrue(nativeSource.contains("virtualNodeDescription(nodeId)"));
        assertTrue(touch.contains("sendEvent(target, 32768)"));
        assertTrue(touch.contains("sendEvent(previous, 65536)"));
    }

    @Test
    public void nativeLiftPagerRebuildsSlotsForEachVisiblePage() throws IOException {
        Path local = Path.of("src", "main", "cpp", "native_init.cpp");
        Path path = Files.exists(local) ? local : Path.of("app").resolve(local);
        String nativeSource = Files.readString(path);
        String pager = between(nativeSource, "bool predictPageSwipe(",
                "void handleRecentSwipeMotion(");

        assertFalse(pager.contains("g_lifted.load(std::memory_order_acquire)\n            ||"));
        assertTrue(pager.contains("refreshLiftSessionForPage(g_last_provider.load"));
        assertTrue(nativeSource.contains("suppressOriginal = true;"));
        assertTrue(nativeSource.contains("return true;"));
    }

    @Test
    public void nativeLiftAcceptsObservedPageWithoutTouchPrediction() throws IOException {
        Path local = Path.of("src", "main", "cpp", "native_init.cpp");
        Path path = Files.exists(local) ? local : Path.of("app").resolve(local);
        String nativeSource = Files.readString(path);
        String nodeHook = between(nativeSource, "NodeResult onCreateNode(void *provider, int32_t nodeId) {",
                "void injectDirectAction");

        assertTrue(nodeHook.contains("predictedPage <= 0"));
        assertTrue(nodeHook.contains("&& rememberVisibleScreen(pageDescription);"));
        assertFalse(nodeHook.contains("(!lifted || predictedPage) && rememberVisibleScreen"));
    }

    @Test
    public void nativeTouchHookKeepsUnverifiedCurrentFrbProfileDisabled() throws IOException {
        Path local = Path.of("src", "main", "cpp", "native_init.cpp");
        Path path = Files.exists(local) ? local : Path.of("app").resolve(local);
        String nativeSource = Files.readString(path);

        assertTrue(nativeSource.contains("kExpectedCurrentLauncherDartBuildId"));
        assertTrue(nativeSource.contains("4f1bdaeda5978273de7d3ebca639ae02"));
        assertTrue(nativeSource.contains("matchesLauncherMotionBuildId"));
        assertTrue(nativeSource.contains("matchesFrbModelBuildId"));
        assertTrue(nativeSource.contains("kExpectedLegacyPrivateBuildId"));
        assertTrue(nativeSource.contains("profileMatchesSystemAbi"));
        assertTrue(nativeSource.contains("refreshLauncherAbiProfile"));
        assertTrue(nativeSource.contains("motionBuildMatch"));
        assertTrue(nativeSource.contains("if (g_frb_dispatcher != nullptr && frbBuildMatch)"));
        assertTrue(nativeSource.contains("resolveLauncherApis(launcherHandle)"));
    }

    @Test
    public void nativeLiftTrustsSourcePlacementOverStalePageIndicator() throws IOException {
        Path local = Path.of("src", "main", "cpp", "native_init.cpp");
        Path path = Files.exists(local) ? local : Path.of("app").resolve(local);
        String nativeSource = Files.readString(path);
        String lift = between(nativeSource, "if (actionId == kLiftAction)",
                "if (actionId == kCancelAction)");

        assertTrue(lift.contains("source placement is the reliable page"));
        assertTrue(lift.contains("pageIndicatorSeen = true"));
        assertTrue(lift.contains("g_visible_screen.store(placement.placement.screen - 1"));
    }

    @Test
    public void nativePagerAnnouncementUsesScrollEvents() throws IOException {
        Path local = Path.of("src", "main", "cpp", "native_init.cpp");
        Path path = Files.exists(local) ? local : Path.of("app").resolve(local);
        String nativeSource = Files.readString(path);

        assertTrue(nativeSource.contains("kTypeViewScrolled = 4096"));
        assertTrue(nativeSource.contains("kTypeAnnouncement = 16384"));
        assertTrue(nativeSource.contains("AccessibilityEvent_record_set_item_count"));
        assertTrue(nativeSource.contains("AccessibilityEvent_record_set_from_index"));
        assertTrue(nativeSource.contains("AccessibilityEvent_record_set_to_index"));
        assertTrue(nativeSource.contains("AccessibilityEvent_record_push_text"));
        assertTrue(nativeSource.contains("onSendEvent"));
        assertTrue(nativeSource.contains("kTypeWindowContentChanged = 2048"));
        assertTrue(nativeSource.contains("kSqliteDone = 101"));
        assertTrue(nativeSource.contains("clearWorkspacePageContext();"));
        assertTrue(nativeSource.contains("g_page_state_known.store(true"));
        assertTrue(nativeSource.contains("kSendEventSymbol"));
        assertTrue(nativeSource.contains("kEventSourceOffset = 0x88"));
        assertTrue(nativeSource.contains("kEventTypeOffset = 0x118"));
        assertTrue(nativeSource.contains("desktopItemCountForPage"));
        assertTrue(nativeSource.contains("kDesktopContainer = -100"));
        assertTrue(nativeSource.contains("LauncherSnapshot"));
        assertTrue(nativeSource.contains("snapshotWorkerLoop"));
        assertTrue(nativeSource.contains("requestSnapshotRefresh"));
        assertFalse(nativeSource.contains("SELECT COUNT(*) FROM favorites"));
        assertTrue(nativeSource.contains("+ \" 项\""));
        assertFalse(nativeSource.contains("rewritePageAnnouncement"));
        String sendEvent = between(nativeSource, "void onSendEvent(void *controller, void *event) {",
                "void sendEvent(int32_t nodeId, int32_t type) {");
        assertFalse(sendEvent.contains("g_sqlite_open_v2"));
        assertFalse(sendEvent.contains("launcherDatabasePath"));
        String nodeHook = between(nativeSource,
                "NodeResult onCreateNode(void *provider, int32_t nodeId) {",
                "void injectDirectAction");
        assertFalse(nodeHook.contains("setNodeString(g_node_set_content_description"));
    }

    @Test
    public void nativePredictedPageAnnouncementStillRebuildsLiftSession() throws IOException {
        Path local = Path.of("src", "main", "cpp", "native_init.cpp");
        Path path = Files.exists(local) ? local : Path.of("app").resolve(local);
        String nativeSource = Files.readString(path);
        String paging = between(nativeSource, "bool rewritePageScrollEvent(",
                "void onSendEvent(void *controller, void *event) {");
        String predicted = between(paging, "const bool predictedPage = g_predicted_page.exchange",
                "int32_t count = 0;");

        assertTrue(predicted.contains("suppressOriginal = true;"));
        assertFalse(predicted.contains("return true;"));
        assertTrue(paging.contains("scheduleLiftSessionRefresh(lifted)"));
    }

    @Test
    public void nativeMoveUsesExplicitDropSlotsAndAtomicOverflowRules() throws IOException {
        Path local = Path.of("src", "main", "cpp", "native_init.cpp");
        Path path = Files.exists(local) ? local : Path.of("app").resolve(local);
        String nativeSource = Files.readString(path);

        assertTrue(nativeSource.contains("kVirtualNodeCount = 64"));
        assertTrue(nativeSource.contains("放置到第"));
        assertTrue(nativeSource.contains("findDropParentNode"));
        assertTrue(nativeSource.contains("nodeId == g_drop_parent_node_id"));
        assertTrue(nativeSource.contains("kAccessibilityFocusAction"));
        assertTrue(nativeSource.contains("g_virtual_focused_id"));
        assertTrue(nativeSource.contains("loadNextDesktopScreen"));
        assertTrue(nativeSource.contains("没有可拖放的位置"));
        assertTrue(nativeSource.contains("BEGIN IMMEDIATE"));
        assertFalse(nativeSource.contains("item.cellX = sources.front().cellX"));
        assertFalse(nativeSource.contains("item.cellY = sources.front().cellY"));
    }

    @Test
    public void nativeLiftedIconsShareCancelActionAndRefreshWorkspace() throws IOException {
        Path local = Path.of("src", "main", "cpp", "native_init.cpp");
        Path path = Files.exists(local) ? local : Path.of("app").resolve(local);
        String nativeSource = Files.readString(path);
        String iconActions = between(nativeSource, "void injectIconActions(void *node) {",
                "void injectRecentActions(void *node) {");
        String cancel = between(nativeSource, "if (actionId == kCancelAction)",
                "if (isVirtualNode(nodeId)");

        assertTrue(iconActions.contains("if (lifted)"));
        assertTrue(iconActions.contains(
                "injectDirectAction(node, kCancelAction, kCancelLabel);"));
        assertFalse(iconActions.contains("if (lifted && liftedSource)"));
        assertTrue(cancel.contains("if (!g_lifted.load"));
        assertTrue(cancel.contains("clearLiftSession();"));
        assertTrue(cancel.contains("sendAnnouncement(nodeId, \"已取消托起\")"));
        assertTrue(cancel.contains("sendEvent(parent >= 0 ? parent : nodeId, 2048)"));
    }

    @Test
    public void nativePlacementRefreshesOnceAfterCommitWithCapturedContainerSource()
            throws IOException {
        Path local = Path.of("src", "main", "cpp", "native_init.cpp");
        Path path = Files.exists(local) ? local : Path.of("app").resolve(local);
        String nativeSource = Files.readString(path);
        String placement = between(nativeSource, "bool performNativePlacement(",
                "bool onPerformAction(");
        String action = between(nativeSource, "bool onPerformAction(", "void *findLoadedHandle");

        assertTrue(placement.contains("AccessibilityEventSource eventSource"));
        assertTrue(placement.contains("captureDropParentEventSource(nodeId)"));
        assertTrue(placement.contains("invokeWorkspaceReload()"));
        assertTrue(placement.contains("sendEvent(eventSource, kTypeWindowContentChanged)"));
        assertTrue(placement.indexOf("requestSnapshotRefresh(true)")
                < placement.indexOf("invokeWorkspaceReload()"));
        assertTrue(placement.indexOf("invokeWorkspaceReload()")
                < placement.indexOf("clearLiftSession()"));
        assertTrue(placement.indexOf("clearLiftSession()")
                < placement.indexOf("sendEvent(eventSource, kTypeWindowContentChanged)"));
        assertFalse(placement.contains("sendEvent(nodeId, 2048)"));
        assertFalse(action.contains("if (handled) sendEvent(nodeId, 2048)"));
    }

    @Test
    public void nativeWorkspaceReloadUsesBuildIdAndPrologueVerifiedProfiles() throws IOException {
        Path local = Path.of("src", "main", "cpp", "native_init.cpp");
        Path path = Files.exists(local) ? local : Path.of("app").resolve(local);
        String nativeSource = Files.readString(path);

        assertTrue(nativeSource.contains("resolveWorkspaceReloadForProfile"));
        assertTrue(nativeSource.contains("0x7a1660"));
        assertTrue(nativeSource.contains("0x7a2008"));
        assertTrue(nativeSource.contains("0x7aa050"));
        assertTrue(nativeSource.contains("d10343ff"));
        assertTrue(nativeSource.contains("a90b7bfd"));
        assertTrue(nativeSource.contains("a90c4ff4"));
        assertTrue(nativeSource.contains("9102c3fd"));
        assertTrue(nativeSource.contains("void *launcherLibraryBase()"));
        assertTrue(nativeSource.contains("dladdr(reinterpret_cast<void *>(g_frb_dispatcher)"));
        assertTrue(nativeSource.contains(
                "g_trigger_workspace_reload = resolveWorkspaceReloadForProfile"));
        assertFalse(nativeSource.contains(
                "g_trigger_workspace_reload = nullptr;\n    if (g_frb_dispatcher"));
    }

    @Test
    public void nativeAbiUsesStructuralFingerprintAndSeparatesMoveCapability()
            throws IOException {
        Path local = Path.of("src", "main", "cpp", "native_init.cpp");
        Path path = Files.exists(local) ? local : Path.of("app").resolve(local);
        String nativeSource = Files.readString(path);
        String iconActions = between(nativeSource, "void injectIconActions(void *node) {",
                "void injectRecentActions(void *node) {");
        String placement = between(nativeSource, "bool performNativePlacement(",
                "bool onPerformAction(");

        assertTrue(nativeSource.contains("struct ElfFingerprint"));
        assertTrue(nativeSource.contains("readModuleFingerprint"));
        assertTrue(nativeSource.contains("CAP_MOVE_PROFILE"));
        assertTrue(nativeSource.contains("fingerprintMatches"));
        assertTrue(iconActions.contains("moveActionsReady()"));
        assertTrue(placement.contains("CAP_MOVE_PROFILE"));
        assertTrue(nativeSource.contains(".comment"));
        assertTrue(nativeSource.contains("PT_NOTE"));
    }

    @Test
    public void unknownMoveProfileKeepsLiftSafeAndHidesVirtualPlacement() throws IOException {
        Path local = Path.of("src", "main", "cpp", "native_init.cpp");
        Path path = Files.exists(local) ? local : Path.of("app").resolve(local);
        String nativeSource = Files.readString(path);

        String virtualNode = between(nativeSource, "NodeResult makeVirtualNode(",
                "NodeResult onFindFocus(");
        String action = between(nativeSource, "void injectIconActions(void *node) {",
                "void injectRecentActions(void *node) {");

        assertTrue(virtualNode.contains("!moveActionsReady()"));
        assertTrue(action.contains("if (lifted)"));
        assertTrue(action.contains("lifted && !liftedSource && moveReady"));
    }

    @Test
    public void nativeDeviceProbeChecksLiftedPagingAndBothOccupiedMoveDirections()
            throws IOException {
        Path probeLocal = Path.of("src", "debug", "java", "io", "github",
                "jiangjinyue2333", "actiongrid", "NativeActionsProbe.java");
        Path probePath = Files.exists(probeLocal) ? probeLocal : Path.of("app").resolve(probeLocal);
        Path scriptLocal = Path.of("..", "scripts", "verify-native-probe.ps1");
        Path scriptPath = Files.exists(scriptLocal)
                ? scriptLocal : Path.of("scripts", "verify-native-probe.ps1");
        String probe = Files.readString(probePath);
        String script = Files.readString(scriptPath);

        assertTrue(probe.contains("\"page-next\".equals(requestedMode)"));
        assertTrue(probe.contains("virtualNodes = 0;"));
        assertTrue(probe.contains("findIconBounds"));
        assertTrue(probe.contains("? order < 0 : order > 0"));
        assertTrue(script.contains("-e action page-next"));
        assertTrue(script.contains("-e action page-previous"));
        assertTrue(script.contains("-e action move-after"));
        assertTrue(script.contains("-e action move-before"));
        assertTrue(script.contains("-e action uninstall"));
        assertTrue(script.contains("-e inspect true"));
        assertTrue(script.contains("-e icon QQ"));
        assertTrue(script.contains("-e stay true -e action uninstall"));
        assertTrue(script.contains("要卸载此应用吗？"));
        assertTrue(script.contains("pm path com.tencent.mobileqq"));
        assertTrue(probe.contains("if (!executed && (icons == 0 || directActions == 0))"));
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from);
        assertTrue(from >= 0 && to > from);
        return source.substring(from, to);
    }

    private static String source(String relative) throws IOException {
        Path local = Path.of("src", "main", "java").resolve(relative);
        Path path = Files.exists(local) ? local : Path.of("app").resolve(local);
        return Files.readString(path);
    }

    private static String manifest() throws IOException {
        Path local = Path.of("src", "main", "AndroidManifest.xml");
        Path path = Files.exists(local) ? local : Path.of("app").resolve(local);
        return Files.readString(path);
    }
}
