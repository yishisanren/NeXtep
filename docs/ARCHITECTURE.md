# NeXtep Architecture

NeXtep keeps the stock ColorOS launcher and adds a SystemUI-owned workspace with one main region and three live task slots.

![NeXtep architecture](../assets/nextep-architecture.png)

The diagram maps process boundaries and responsibilities. Explore the standalone [HTML viewer](architecture/nextep-architecture.html) or edit the [JSON source](architecture/nextep-architecture.json). Source references point to commit `79cc0f02a0621238995facca3c331bc7ed37ab61`.

## Process ownership

| Process | Responsibilities |
|---------|------------------|
| Ordinary NeXtep APK | Settings, Advanced and About pages; update checks; Quick Settings tile; trigger coordination and broker; persisted settings and permission-protected configuration services. |
| `com.android.systemui` | Workspace state and lifecycle; control and app strips; media sessions; input and drag handling; main-task presentation; three VirtualDisplay slots; privileged app and clone catalogue. |
| Resolved ColorOS HOME package | Launcher transforms and inverse input mapping; app-start redirection; desktop shortcut menus; reading the selected Recents card on supported launcher implementations. |
| `com.coloros.assistantscreen` | Separate assistant-panel window transforms, input mapping and restoration based on workspace state. It remains a separate process from HOME. |
| `com.oplus.multiapp` | Handles NeXtep-marked clone launches through the OEM resolver, preserving the requested Android user. Unmarked requests keep the OEM chooser behavior. |
| `system_server` | Window/display policy adaptations: keyboard targets, navigation bars, task surfaces, display hierarchy, native floating layers, orientation, video migration and display configuration. |

[`NeXtepHookEntry`](../app/src/main/kotlin/io/github/lujinxin/nextep/xposed/NeXtepHookEntry.kt) installs hooks by host package or the system-server lifecycle callback. Each feature is guarded; a failed hook returns control to the original host behavior where possible. The ordinary APK does not acquire SystemUI privileges.

## Workspace control

Quick Settings calls [`TriggerCoordinator`](../app/src/main/kotlin/io/github/lujinxin/nextep/trigger/TriggerCoordinator.kt). External commands target [`WorkspaceControlBrokerReceiver`](../app/src/main/kotlin/io/github/lujinxin/nextep/trigger/WorkspaceControlBrokerReceiver.kt), which enters the same coordinator.

The coordinator resolves the current HOME package and uses explicit ordered broadcasts to query or set state. A toggle checks Launcher and SystemUI readiness and protocol compatibility, updates SystemUI first, then synchronizes HOME. If the HOME update fails during a toggle, it attempts to restore the previous SystemUI state. Explicit set requests return the actual HOME result after applying the SystemUI request; their failure handling is not identical to toggle rollback.

The bottom-corner gesture is observed on the default display inside SystemUI through a gesture input monitor, including touches delivered to app and launcher windows. A confirmed inward/upward diagonal pilfers the pointer stream before toggling the local workspace controller. The receiver uses the Android 15+ framework callback API via reflection and fails open if unavailable. Exit/recovery paths also notify HOME and the assistant panel, with the broker available for reconciliation. The control area's exit button and gear button are distinct: exit closes the workspace; the gear opens NeXtep through task coordination while retaining workspace mode.

Control broadcasts use `io.github.lujinxin.nextep.permission.CONTROL_WORKSPACE`, a signature permission. The exported broker is protected by `android.permission.STATUS_BAR_SERVICE`. Host-to-host paths authenticate the expected package or immutable `PendingIntent` identity; these are separate checks rather than one generic broadcast trust rule.

## Configuration, language and app catalogue

[`SettingsRepository`](../app/src/main/kotlin/io/github/lujinxin/nextep/config/SettingsRepository.kt) persists behavior switches, top content, typography, blur strength and app ordering in the APK process. App language uses Android's persisted application locales. Changes notify SystemUI, whose background executor refreshes configuration without blocking its main thread.

[`WorkspaceConfigClient`](../app/src/main/kotlin/io/github/lujinxin/nextep/config/WorkspaceConfigContract.kt) reads configuration in this order:

1. Call `WorkspaceConfigProvider` through Binder.
2. If that produces no valid configuration, reuse the last verified local snapshot in SystemUI's device-protected storage.
3. If no snapshot is available, query `WorkspaceConfigReceiver` with an explicit ordered broadcast.

The provider checks the caller's UID or `STATUS_BAR_SERVICE` permission; the receiver is protected by that permission in the manifest. Runtime state, app-strip configuration and the selected language are applied to the appropriate views without modifying host application resources.

The ordinary settings APK cannot enumerate every user's apps. [`LauncherAppCatalogContract`](../app/src/main/kotlin/io/github/lujinxin/nextep/apps/LauncherAppCatalogContract.kt) queries the privileged catalogue in SystemUI for clone/profile metadata, then reconstructs entries in the settings process. App-catalogue replies carry metadata rather than full-size icons.

## Shared runtime state and geometry

[`SystemServerWorkspaceBridge`](../app/src/main/kotlin/io/github/lujinxin/nextep/workspace/SystemServerWorkspaceBridge.kt) publishes workspace activity and geometry through `Settings.Global`, separately from APK configuration. The shared values include active state, sidebar side, screen dimensions, navigation insets, rotation and recorded layout profiles.

SystemUI owns publication. Launcher and the assistant panel use this state to align or restore their surfaces; system-server policy hooks consume the relevant active geometry. A `ContentObserver` keeps a local system-server snapshot so hot window callbacks can use memory rather than repeatedly querying SettingsProvider.

[`WorkspaceViewport`](../app/src/main/kotlin/io/github/lujinxin/nextep/systemui/WorkspaceViewport.kt) and [`WorkspaceLayoutProfile`](../app/src/main/kotlin/io/github/lujinxin/nextep/workspace/WorkspaceLayoutProfile.kt) derive and record the shared layout. Both landscape directions, left/right slot placement and the optional three-button navigation adaptation use that model. After changing the system navigation mode, the user toggles navigation-button adaptation again.

## Main window and three task slots

These are separate presentation responsibilities inside SystemUI.

[`MainTaskPresentationCoordinator`](../app/src/main/kotlin/io/github/lujinxin/nextep/task/MainTaskPresentationCoordinator.kt) handles foreground tasks on Display 0. HOME remains owned by launcher transforms. An ordinary fullscreen app uses `SurfaceTaskPresenter` and task-leash transforms, with bounds overrides cleared where required. Native freeform, PiP and split-screen surfaces keep their WM Shell ownership. Main-task transforms are restored before migration or workspace exit.

[`NeXtepWindowController`](../app/src/main/kotlin/io/github/lujinxin/nextep/systemui/NeXtepWindowController.kt) owns workspace windows, the viewport, visual transitions and slot coordination. `TopAppStripView` includes media, layout/settings/exit controls and app shortcuts. `MediaControlView` follows the active media session and dispatches playback controls; opening its playback app uses the same main/slot task coordination.

[`SlotTaskCoordinator`](../app/src/main/kotlin/io/github/lujinxin/nextep/display/SlotTaskCoordinator.kt) serializes task movement and exchanges. Each [`VirtualDisplaySlot`](../app/src/main/kotlin/io/github/lujinxin/nextep/display/VirtualDisplaySlot.kt) owns a `TextureView → Surface → VirtualDisplay` lifecycle. Slot resolution and density come from the current layout profile; they are not fixed device-specific constants.

Supported input paths include:

- Tapping an empty slot to park the regular foreground app.
- Querying the centered Recents card through an authenticated Launcher contract, then adding it while keeping Recents open.
- Tapping an occupied slot to exchange it with the main task.
- Dragging an app shortcut into a slot, or moving/exchanging an occupied slot with another slot or the main window.
- Swiping a slot outward or dropping it on the control area to move its app to the background.
- Optionally parking the previous main app in a genuinely empty slot when switching from the app strip.

Failed moves retain recovery information. On workspace exit, tasks must leave their slot displays before those displays are released. Eligible tasks can be retained for a later workspace session while they remain available in the system.

## System-server policy adaptations

[`SystemServerHook`](../app/src/main/kotlin/io/github/lujinxin/nextep/systemserver/SystemServerHook.kt) installs independently guarded adaptations for:

- Workspace input-method targets and navigation-bar layout.
- Slot native layout and workspace task surfaces.
- Display hierarchy and native floating-window layers.
- Fixed-orientation aspect ratios and size-compat display insets.
- Both landscape directions.
- Video fullscreen exit during display migration and slot display configuration.

These hooks complement SystemUI presentation. They do not move workspace UI ownership into system_server, and the main-window and slot engines still call Android task, surface and display APIs through checked compatibility wrappers.

## Lock, unlock and recovery

`WorkspaceState` includes `Disabled`, `Entering`, `Active`, `Suspended`, `Exiting` and `Faulted`. Transitions run on the host main thread, with cleanup or rollback when an operation cannot complete.

Locking suspends slot output and temporarily restores main-display presentation while retaining tasks. Unlocking restores the workspace geometry and slot output. The published shared active value is `0` while suspended, so external state queries should be interpreted after unlock. Configuration changes can reconfigure the existing workspace rather than always exiting it.

A workspace exit stops related observation and restores owned surfaces. Opening settings preserves the workspace. The module remains a normal APK activated by LSPosed; external Root commands are optional entry points and no separate Root daemon is part of this architecture.
