<p align="center">
  <img src="assets/panes-logo.png" alt="Panes icon" width="160">
</p>

<h1 align="center">Panes (格窗)</h1>

<p align="center">One window, several things at once.</p>

<p align="center">
  <a href="README.md">中文</a> / <a href="README_EN.md">English</a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/ColorOS-17-1F5CDE?style=flat" alt="ColorOS 17">
  <img src="https://img.shields.io/github/v/release/yishisanren/NeXtep?display_name=tag&sort=semver" alt="Latest release">
  <img src="https://img.shields.io/badge/LSPosed-Required-C62828?style=flat" alt="LSPosed required">
  <img src="https://img.shields.io/badge/License-Apache--2.0-blue?style=flat" alt="Apache-2.0">
</p>

Panes is an LSPosed module that adds a side workspace to the stock ColorOS 17 launcher: one main window to work in, four live panes waiting beside it, and the things you use most within reach. It does not replace the launcher or restyle the system; leave the workspace and the phone is as it was.

<p align="center">
  <img src="assets/readme/overview.gif" alt="A diagonal swipe from a bottom corner opens the workspace, another one closes it" width="300">
</p>

## Features

### One main pane, four small panes

The main pane is the app you are using. The four small panes beside it are four real running apps, each on its own virtual display, not snapshots. Tap a small pane and it swaps places with the main pane, scaled evenly with nothing stretched or cropped; long-press and drag to reorder the panes or send an app to the background.

<p align="center">
  <img src="assets/readme/swap.gif" alt="Tapping a small pane swaps it with the main pane, and back" width="300">
</p>

### Favourite apps

A row of favourite apps sits at the top: the ones you pin in Settings come first, and the rest is filled from your most-used apps of the last seven days, up to ten. Tap an icon to open it in the main pane, or long-press and drag it onto a small pane to open it there. The "+" on an empty pane adds an app too: the current main app moves into that pane, and the next app you pick from the Home screen opens in the main pane.

<p align="center">
  <img src="assets/readme/favorites.gif" alt="Tapping a favourite opens it in the main pane; dragging one opens it in a small pane" width="300">
</p>

### Landscape video moves up

When the main app asks for landscape, for example when you tap fullscreen in a player, the video moves into a wide pane at the top: full width at 16:9, narrowed to the picture (down to 2.4:1) when it has letterbox bars. The main pane and the other three small panes stay usable, and the wide pane takes taps for pause and seeking; leave fullscreen and the video returns to the main pane.

<p align="center">
  <img src="assets/readme/video.gif" alt="A fullscreen video moves into the wide pane at the top and back" width="300">
</p>

### Scenes

Put apps in the main pane and the small panes, then tap the add button at the far right of the top bar: the layout is saved as a scene. Give it one of eight preset icons (work, study, games, media, reading, shopping, travel, life) or a label of up to two Chinese characters or four letters. Tap a scene to switch to it; the scene matching the current layout is highlighted, and a long press changes its icon or deletes it. Up to six scenes; the add button hides when all six are used. Settings can also pin a scene to the Home screen, so one tap opens the whole set (ColorOS asks once for the "create shortcuts" permission).

<p align="center">
  <img src="assets/readme/scenes.gif" alt="Saving a scene with the add button and a preset icon, then switching to another scene" width="300">
</p>

### Price comparison

One of the two default scenes. Type a product once and it is searched in several delivery or shopping apps at the same time, each in its own pane, so the results sit side by side: delivery searches JD Miaosong, Taobao Shangou and Meituan Waimai; shopping searches Pinduoduo, Douyin Mall, JD and Taobao. Panes only opens the search results; it does not read prices, place orders or pay.

<p align="center">
  <img src="assets/readme/compare.gif" alt="The price comparison panel: pick a group and type a product" width="300">
</p>

### Pay and scan

The other default scene: WeChat scan, WeChat payment code, Alipay scan and Alipay payment code in one system dialog. It only opens those pages; it never reads accounts or confirms a payment for you.

<p align="center">
  <img src="assets/readme/pay.png" alt="The pay and scan dialog" width="300">
</p>

### Open in Panes

Share a link or text from any app, choose "Open in Panes" and pick the main pane or one of the small panes. Links open in the default browser; other text goes to the default search.

<p align="center">
  <img src="assets/readme/share.gif" alt="Sharing a link from the browser into small pane 2" width="300">
</p>

### Light, dark or follow the system

The workspace follows the system's light and dark mode, or stays light or dark. Small panes on the left or right, controls at the top or bottom: all changed in Settings and applied immediately.

<p align="center">
  <img src="assets/readme/theme.gif" alt="The workspace follows the system switching between dark and light" width="300">
</p>

## Requirements

- ColorOS 17 (Android 17). Developed and verified on the OPPO Find X9s Pro (PME110); other devices and ROMs need their own adaptation and are untested.
- Root (KernelSU or similar), Zygisk and LSPosed with the modern libxposed API.
- The module changes SystemUI, launcher and system_server behaviour. Keep a recovery path for the device.

## Install

1. Download the APK from [Releases](https://github.com/yishisanren/NeXtep/releases) and install it (package `io.github.yishisanren.panes`).
2. Enable "格窗 Panes" in LSPosed and tick every entry of the built-in [scope list](app/src/main/resources/META-INF/xposed/scope.list) that exists on your device: SystemUI, the framework, the launcher, the app-cloning service and the assistant screen.
3. If NeXtep (`io.github.lujinxin.nextep`) is installed, disable and uninstall it first; two modules must not hook SystemUI at once.
4. Reboot.
5. Open Panes, pick favourite apps and add the Quick Settings tile.

## Open and close

- **Bottom-corner gesture:** one finger from the bottom-left corner towards the upper right, or from the bottom-right corner towards the upper left, opens the workspace; the same swipe inside the workspace closes it. Start within 96 dp of the bottom and side edges and move at least 24 dp inward and upward; a straight swipe up (system home) or a horizontal swipe (system back) does not trigger it.
- **Quick Settings tile:** add it from Settings → Opening the workspace → Quick Settings shortcut, then toggle "Panes" in Quick Settings.
- **Settings:** Troubleshooting → Exit small-window mode.
- **Shell commands** for automation tools (root): see [docs/WORKBENCH.md](docs/WORKBENCH.md).

## Settings

Open Panes from its Home screen icon. One page, seven groups:

| Group | What it holds |
| --- | --- |
| Favourite apps | Pick and order the favourites row; close the previous app when switching |
| Scenes | View and delete scenes, pin them to the Home screen, restore deleted default scenes |
| Layout | Follow system / light / dark, small panes left / right, controls top / bottom |
| Opening the workspace | Quick Settings tile, bottom-corner gesture |
| Privacy | Hide workspace apps from Recents, allow screenshots and recording this session |
| Troubleshooting | Export diagnostics, exit small-window mode |
| About | Version, update check, credits |

## Privacy and limits

- Screenshots and screen recording of the workspace are blocked by default. Settings can allow them for the current session; leaving the workspace, locking the phone or opening Settings blocks them again.
- With "Hide workspace tasks from Recents" on, workspace apps stay out of Recents until you leave the workspace.
- Apps keep their own protection (payment codes, DRM video, enterprise policy); Panes does not lift it.
- Exported diagnostics hold only the module's last 128 events, with no search terms, links, accounts or screen content.
- Landscape video detection depends on the app and the system: a muted portrait player without a media session may not be recognised.

## Build

See [docs/BUILDING.md](docs/BUILDING.md). Feature limits and device validation are recorded in [docs/WORKBENCH.md](docs/WORKBENCH.md); the process layout is in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Support the author

If Panes helps you, you can buy the author a coffee with WeChat Pay. The same code is under About in the app's settings.

<p align="center">
  <img src="assets/donate-wechat.jpg" alt="WeChat Pay donation code" width="240">
</p>

## Credits and licence

Panes is a modified fork of [NeXtep by lujinxin](https://github.com/lujinxin/NeXtep), which itself pays tribute to OneStep in Smartisan OS. The repository keeps the [Apache License 2.0](LICENSE); third-party notices are in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

Copyright 2026 lujinxin (NeXtep); modifications Copyright 2026 yishisanren.
