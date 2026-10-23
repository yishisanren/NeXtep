<p align="center">
  <img src="assets/panes-logo.png" alt="格窗图标" width="160">
</p>

<h1 align="center">格窗 Panes</h1>

<p align="center">一扇窗，几件事。</p>

<p align="center">
  <a href="README.md">English</a> / <a href="README_CN.md">中文</a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/ColorOS-16-1F5CDE?style=flat" alt="ColorOS 16">
  <img src="https://img.shields.io/github/v/release/yishisanren/NeXtep?display_name=tag&sort=semver" alt="最新版本">
  <img src="https://img.shields.io/badge/LSPosed-Required-C62828?style=flat" alt="需要 LSPosed">
  <img src="https://img.shields.io/badge/License-Apache--2.0-blue?style=flat" alt="Apache-2.0">
</p>

格窗是一个 LSPosed 模块，给 ColorOS 16 的原生桌面加一个侧边工作台：一个大窗做事，四个小窗候着，常用的都放在手边。不替换桌面，不改系统主题，退出后手机回到原样。

## 它长什么样

- **一大四小**：大窗是当前应用，侧栏四个小窗是四个真实运行中的应用（独立虚拟显示器，不是截图）。点小窗，它和大窗原地对调；长按拖动可以换位或退到后台。
- **顶部控制区**：标题和日期、常用应用（最近使用的应用自动排上，设置里固定的放最前面，最多十个）、「付款／扫码」四个入口（微信扫一扫、微信付款码、支付宝扫一扫、支付宝付款码）、「比价」（一件商品同时在几个外卖或购物应用里搜，各开一个小窗）、齿轮直达设置。
- **横屏视频自动上去**：大窗里的应用请求横屏（例如点全屏播放），视频收进顶部 16:9 宽窗，下面的大窗继续用；退出全屏自动回到大窗。
- **工作场景**：把当前大窗和四个小窗存成一套，设置页里一键恢复，也可以添加到桌面，点一下就打开整套（ColorOS 首次需允许「创建桌面快捷方式」权限）。
- **发到格窗**：在任何应用里分享链接或文字，选一个窗口打开。
- **跟随系统或固定浅色／深色、小窗靠左／靠右、控制区顶部／底部**，立即生效。
- **隐私**：工作台打开时可隐藏其应用不进最近任务；截屏默认禁止，可按次允许。

## 使用要求

- ColorOS 16（Android 16）。开发与验证机型为一加 PLK110；其他机型和 ROM 需要自行适配，未经测试不保证兼容。
- Root（KernelSU 等）、Zygisk 与 LSPosed（支持 libxposed 新 API）。
- 本模块会修改 SystemUI、桌面和 system_server 的行为，请确保设备有可用的恢复手段。

## 安装

1. 从 [Releases](https://github.com/yishisanren/NeXtep/releases) 下载 APK 并安装（包名 `io.github.yishisanren.panes`）。
2. 在 LSPosed 中启用「格窗 Panes」，勾选内置[作用域列表](app/src/main/resources/META-INF/xposed/scope.list)里适用于本机的全部项目：SystemUI、系统框架、桌面、应用分身服务、负一屏。
3. 如果以前装过 NeXtep（包名 `io.github.lujinxin.nextep`），先在 LSPosed 里停用并卸载它，两个模块不能同时钩住 SystemUI。
4. 重启手机。
5. 打开「格窗」，选常用应用，添加控制中心开关。

## 打开与关闭

- **底角手势**：单指从左下角向右上、或右下角向左上斜滑。起点在距底边和侧边各 96 dp 的角落内，向内、向上各滑 24 dp 以上；直上滑（系统主页手势）和水平滑（系统返回手势）不会触发。
- **控制中心开关**：设置页「打开方式 → 控制中心入口」添加，下拉控制中心点「格窗」切换。
- **退出**：控制区的退出按钮，或设置页「遇到问题 → 退出工作台」。
- **Shell 命令**（自动化工具用，需 Root）：见 [docs/WORKBENCH.md](docs/WORKBENCH.md#shell-命令)。

## 设置页

单页七组：常用应用、工作场景、布局、打开方式、隐私、遇到问题（导出诊断、退出）、关于（检查更新）。「检查更新」查询本仓库的 Releases。

## 构建

见 [docs/BUILDING.md](docs/BUILDING.md)。功能边界与真机验证情况见 [docs/WORKBENCH.md](docs/WORKBENCH.md)，进程结构见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)。

## 署名与许可证

格窗基于 [lujinxin 的 NeXtep](https://github.com/lujinxin/NeXtep) 修改而来，NeXtep 致敬锤子科技 Smartisan OS 的 OneStep。本仓库沿用 [Apache License 2.0](LICENSE)，第三方声明见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

Copyright 2026 lujinxin（NeXtep）；修改部分 Copyright 2026 yishisanren。
