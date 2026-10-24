# Native reader fixture

This test APK exposes the production `native_init.cpp` node, drag, hover, traversal,
and move-request code through a real Android `AccessibilityNodeProvider`. The Java
adapter copies the native child list without sorting it. System AIDL node allocation,
database snapshots, native event transport, and FRB dispatch are mocked only at their
function-pointer boundaries. It does not replace or simulate a screen reader.

Build from PowerShell:

```powershell
./scripts/build-reader-fixture.ps1
```

The output is `artifacts/drag-20261020/reader-fixture/reader-fixture.apk`, with a
disposable local signing key stored in a dedicated directory outside the workspace.
Neither signing material nor its password is copied into build artifacts.
It is not included in either product Gradle module.
The minimum Android version is 9, and the default ABI is x86_64; pass
`-Abi arm64-v8a` to build for ARM64. Existing D: SDK/JDK/NDK tools are used.

Install it on an authorized test emulator, enable the actual reader, and launch
`io.github.jiangjinyue2333.actiongrid.readerfixture/.ReaderFixtureActivity`.
The layout has four columns and six rows, QQ in row 1 column 1, WeChat in row 1
column 3, all other cells empty, and a separate bottom dock containing Phone.
The visible controls explicitly lift QQ and reset the test session.

The activity also accepts an explicit `--es command` intent extra:

| Command | Purpose |
| --- | --- |
| `selfcheck` | Run the production lift, child order, last-row move wire, and dock checks; write `SELF_CHECK PASS` or `FAIL`. |
| `lift` | Invoke the production lift action on QQ. |
| `cancel` | Invoke the production cancel action, including its feedback; use before lifting the same icon again. |
| `focus` | Explicitly focus QQ for the beginning of a reader gesture sequence. |
| `open` | Click QQ through the production action hook; the original app-launch boundary opens Android Settings for a real window transition. |
| `leave` / `home` | Exercise the production focus-loss and focus-return callbacks, then confirm the workspace via the production node hook. |
| `reset` | Clear the test session and restore the in-memory database snapshot. |
| `dump` | Log the production page node and its child order. |
| `announce` | Send the `--es text` value through the production announcement path. |
| `window` | Send an explicit platform window-state event with `--es text` for event-filter diagnosis. |

Use the actual reader's configured next/previous gestures. Verify QQ → row 1
column 2 → WeChat, the reverse sequence, touch exploration of the same empty cell,
and double tap on row 6 column 4. `adb logcat -s AGReaderFixture ActionGridNative`
records accessibility focus, actions, announcements, and the production FRB wire
destination as `DISPATCH ... row=6 column=4`. The dispatch boundary intentionally
ends the test request before the persistence worker starts; no real launcher
database is changed. This validates reader interoperability with production nodes,
not the HyperOS 4 system libraries or an end-to-end on-device move.

For app-return testing, reset the drag session, focus QQ, and double tap with the
reader. The original app-launch boundary opens Android Settings. Press system Back
to return, then observe the production window-focus callback and the reader's
consumption of “系统桌面”. The `open` command repeats the same production QQ click
path for repeated real window transitions. `leave`/`home` are separate callback
probes and should not be reported as actual app-switch tests. `reset` silently
resets fixture state, so use `cancel` to verify a user's cancel-and-lift flow.
