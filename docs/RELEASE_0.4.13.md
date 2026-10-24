# ActionGrid 0.4.13 验证记录

版本 `0.4.13` / `29`。

## 澎湃 4 拖动与播报

文件夹节点首次解析失败时，托起流程会复用此前按节点缓存的 favorite 位置，并从当前快照按同页唯一名称兜底；放置阶段继续使用托起时确认的 favorite ID，不再因为节点或快照短暂重建而提示“未能确认托起的图标”。

页码事件现在记录 workspace host。原生无障碍套件重建窗口或虚拟节点后，只要 host 仍属于桌面，模块继续替换为“第 X 页共 Y 项”，并更新后续事件窗口。

## 验证

- `gradlew.bat test lint assembleRelease --console=plain`
- `scripts/verify-project-structure.ps1`
- `scripts/test-native-policy.ps1`
- `scripts/test-apk-abi.ps1 -ApkPath app/build/outputs/apk/release/app-release-unsigned.apk`
- `git diff --check`

原生网格回归需要指定已连接的 Android 设备；本次环境 `adb devices` 没有在线设备，因此未执行 `scripts/test-native-grid.ps1`，也未完成真机 TalkBack 验收。
