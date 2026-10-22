<p align="center">
  <img src="assets/panes-logo.png" alt="Panes icon" width="160">
</p>

<h1 align="center">Panes (格窗)</h1>

<p align="center">One window, several things at once.</p>

<p align="center">
  <a href="README.md">English</a> / <a href="README_CN.md">中文</a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/ColorOS-16-1F5CDE?style=flat" alt="ColorOS 16">
  <img src="https://img.shields.io/github/v/release/yishisanren/NeXtep?display_name=tag&sort=semver" alt="Latest release">
  <img src="https://img.shields.io/badge/LSPosed-Required-C62828?style=flat" alt="LSPosed required">
  <img src="https://img.shields.io/badge/License-Apache--2.0-blue?style=flat" alt="Apache-2.0">
</p>

Panes is an LSPosed module that adds a side workspace to the stock ColorOS 16 launcher: one main window to work in, four live panes waiting beside it, and the things you use most within reach. It does not replace the launcher or restyle the system; leave the workspace and the phone is as it was.

## What you get

- **One main pane, four small panes.** The small panes are real running apps on their own virtual displays, not snapshots. Tap one and it swaps with the main pane in place; long-press and drag to reorder or send to the background.
- **A control bar on top:** title and date, up to ten favourite apps chosen in Settings, four pay/scan entries (WeChat scan, WeChat payment code, Alipay scan, Alipay payment code), price comparison (one product searched in several delivery or shopping apps, each in its own pane), and a gear that opens Settings.
- **Landscape video moves up.** When the main app asks for landscape (for example a fullscreen player), it moves into a 16:9 pane at the top while the main pane stays usable; leaving fullscreen brings it back.
- **Light or dark, panes left or right, controls top or bottom**, applied immediately.
- **Privacy:** hide workspace apps from Recents while the workspace is open; screenshots are blocked unless allowed for the current session.

## Requirements

- ColorOS 16 (Android 16). Developed and verified on the OnePlus PLK110; other devices and ROMs need their own adaptation and are untested.
- Root (KernelSU or similar), Zygisk and LSPosed with the modern libxposed API.
- The module changes SystemUI, launcher and system_server behaviour. Keep a recovery path for the device.

## Install

1. Download the APK from [Releases](https://github.com/yishisanren/NeXtep/releases) and install it (package `io.github.yishisanren.panes`).
2. Enable "格窗 Panes" in LSPosed and tick every entry of the built-in [scope list](app/src/main/resources/META-INF/xposed/scope.list) that exists on your device: SystemUI, the framework, the launcher, the app-cloning service and the assistant screen.
3. If NeXtep (`io.github.lujinxin.nextep`) is installed, disable and uninstall it first; two modules must not hook SystemUI at once.
4. Reboot.
5. Open Panes, pick favourite apps and add the Quick Settings tile.

## Open and close

- **Bottom-corner gesture:** one finger from the bottom-left corner towards the upper right, or from the bottom-right corner towards the upper left. Start within 96 dp of the bottom and side edges and move at least 24 dp inward and upward; a straight swipe up (system home) or a horizontal swipe (system back) does not trigger it.
- **Quick Settings tile:** add it from Settings → Opening → Quick Settings shortcut, then toggle "Panes" in Quick Settings.
- **Exit:** the exit button in the control bar, or Settings → Troubleshooting → Exit.
- **Shell commands** for automation tools (root): see [docs/WORKBENCH.md](docs/WORKBENCH.md).

## Settings

One page, six groups: favourite apps, layout, opening, privacy, troubleshooting (export diagnostics, exit) and about (update check against this repository's Releases).

## Build

See [docs/BUILDING.md](docs/BUILDING.md). Feature limits and device validation are recorded in [docs/WORKBENCH.md](docs/WORKBENCH.md); the process layout is in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Credits and licence

Panes is a modified fork of [NeXtep by lujinxin](https://github.com/lujinxin/NeXtep), which itself pays tribute to OneStep in Smartisan OS. The repository keeps the [Apache License 2.0](LICENSE); third-party notices are in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

Copyright 2026 lujinxin (NeXtep); modifications Copyright 2026 yishisanren.
