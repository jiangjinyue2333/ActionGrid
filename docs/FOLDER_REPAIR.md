# 澎湃 3 文件夹拖出修复

## 代码边界

- `MiuiHomeBridge.startNativeDrag` 为 7.x 单个文件夹项目移到其他容器预检源状态，并在原生拖动建立后、目标放置前补齐源清理；清理入口失败时取消合成触摸和原生拖动。
- `FolderDragTransfer` 复用桌面的 `removeItemsFromSourceWhenMultiDrag`，检查源文件夹 ID、原生拖动项目对象、拖动来源和数量；不写桌面数据库、不按应用名去重、不修删已有残留图标。
- 文件夹内排序和已有多选路径继续使用桌面自己的流程。跨容器前需要先关闭源文件夹；若当前文件夹视图已绑定其他文件夹，或桌面缺少该原生接口，则在拖动前失败。
- `FolderDragTransferTest` 覆盖已关闭文件夹拖出无重复、取消恢复、两项文件夹原生解散时机、错误/多选拖动拒绝，以及接口缺失时保持原数据。

## 证据

用户已确认重复图标反馈发生于澎湃 3。检查的 APK 为 `com.miui.home-RELEASE-7.00.00.2259-04301440.APK`，SHA-256 为 `0588e62e59f4ac2e4b05b3a55bc19d11f2ba14c76b0385c2a5a2007a470366d6`。REA 的 Android provider 因未配置 `REA_JADX_MCP_JAR` 不可用，因此使用本机已安装 JADX 1.5.6 离线读取。以下为静态证据，不能替代真机执行结果：

1. `BaseLauncher.onLongClick` → `dragSingleItem` → `FolderView.startDrag` 建立拖动并设置 `mDragItem`，未从源 `FolderInfo` 移除项目。
2. `FolderCling.mCloseConfirm` 在原生拖出关闭回调中执行 `removeItem`。用户先按返回关闭文件夹，再通过模块选择目标，不经过这条拖动关闭回调，源列表因此可能残留。
3. 同 APK 的 `BaseLauncher.removeItemsFromSourceWhenMultiDrag` 在原生拖动建立后，按来源 `container` 查找 `FolderInfo`、移除源项目、刷新内容和预览。修复复用此入口完成单个项目的相同步骤。
4. `DragController.createDragView` 在开始拖动时注册 `PendingSource`；失败/取消由 `DragObject.onDragCompleted` → `FolderView.onDropBack` 恢复源，完成时 `finishPending` → `FolderIcon.removePendingItem` → `checkToDeleteSelf` 按桌面规则处理空文件夹或允许解散的单项文件夹。模块不自行提前解散文件夹。

手指直接拖动路径也已静态检查：7.x 的模块 Hook 没有接管 `startDrag`、文件夹关闭或原生 `onDrop`，普通拖动仍由上述 `mCloseConfirm` 清理源项。新增辅助类仅由模块显式放置入口调用，未加入普通触摸回调。澎湃 4 的模型写入只响应模块动作；7748 不安装其他版本的触摸偏移 Hook。当前没有直接手指拖动造成重复的复现证据，因此不能将该路径认定为已实机修复。

## 验证

- 修复前，离线脚本 `artifacts/folder-repair/reproduce-closed-folder.ps1` 编译执行真实 `startNativeDrag` 方法，使用上述原生协议夹具模拟先关闭文件夹再落点，得到 `AssertionError: DUPLICATE: moved icon is still in the source folder`。
- 修复后相同 `startNativeDrag` 场景通过。`FolderDragTransferTest` 的 7 项测试已用独立 Javac/JUnit 运行通过；Gradle 入口为 `:hook:testDebugUnitTest --tests '*FolderDragTransferTest'`。完整结构、单元测试、Lint 和构建由本次主任务统一执行。
- 自动验证覆盖的是模块调用与原生协议的模型，没有在用户桌面上移动、删除或重新创建图标。仍需澎湃 3 真机验收：两个应用合并文件夹 → 托起一个 → 返回关闭文件夹 → 放到空位/与其他图标合并 → 重新打开源文件夹并重启桌面确认不残留；另验取消、目标拒绝、多选和文件夹自身解散规则。
- 澎湃 4 无 DEX 原生路径不经过本辅助类。它已拒绝创建文件夹；文件夹容器的原生移动维护未验证，由 native 后端单独限制范围。
