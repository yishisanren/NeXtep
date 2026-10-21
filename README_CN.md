<p align="center">
  <img src="assets/nextep-logo.png" alt="NeXtep 图标" width="220">
</p>

<h2 align="center">NeXtep —— 致敬并延续 OneStep</h2>

<p align="center">
  <a href="README.md">English</a> / <a href="README_CN.md">中文</a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Android-16%2B-3DDC84?style=flat&logo=android&logoColor=white" alt="Android 16+">
  <img src="https://img.shields.io/github/v/release/lujinxin/NeXtep?display_name=tag&sort=semver" alt="最新版本">
  <img src="https://img.shields.io/badge/status-custom_preview-E59B2D?style=flat" alt="状态：定制预发布版">
  <img src="https://img.shields.io/badge/Root-Required-C62828?style=flat" alt="需要 Root">
  <img src="https://img.shields.io/badge/license-Apache--2.0-4C1?style=flat" alt="Apache 2.0 许可证">
  <img src="https://img.shields.io/badge/LSPosed-Module-3F51B5?style=flat" alt="LSPosed 模块">
  <img src="https://img.shields.io/badge/Kotlin-Source-7F52FF?style=flat&logo=kotlin&logoColor=white" alt="Kotlin 源码">
</p>

<p align="center">
  <img src="assets/screenshots/workspace-overview.jpg" alt="NeXtep 工作区概览" width="17%">&nbsp;
  <img src="assets/screenshots/quick-settings.jpg" alt="NeXtep 快捷设置工作区" width="17%">&nbsp;
  <img src="assets/screenshots/widgets-workspace.jpg" alt="NeXtep 小组件工作区" width="17%">&nbsp;
  <img src="assets/screenshots/app-settings.jpg" alt="NeXtep 应用设置" width="17%">&nbsp;
  <img src="assets/screenshots/multitasking.jpg" alt="NeXtep 多任务工作区" width="17%">
</p>

# NeXtep

本 Fork 的 `1.2.3-corner.3` 使用左右底角斜向内上滑手势，并优化常驻监听、任务动画、拖拽、壁纸模糊和应用栏刷新中的重复开销。[定制版 APK 下载](https://github.com/yishisanren/NeXtep/releases) · [优化内容与本地验证](docs/PERFORMANCE.md)。本版等待用户自行真机验收，不宣称未经测量的帧率提升。内置更新和下方原有兼容性说明仍属于上游项目；安装上游版本会恢复原手势。

**NeXtep** 是一个 **LSPosed 模块**。它在保留 **ColorOS 16 / 17 原生桌面**的同时，为系统加入**原生侧边工作区**，提供**一个主窗口和三个实时小窗**，并将任务切换、拖动操作、媒体控制和自定义应用快捷方式集成到 SystemUI 中。

本项目主要适配**一加 PLK110**，此前已在 **Android 16 / ColorOS 16** 上验证，近期功能与兼容性验证基于 **Android 17 / ColorOS 17**。项目依赖 Android 和 ColorOS 的私有行为，因此**其他设备、系统版本和厂商桌面需要经过适配**。

[下载与更新记录](https://github.com/lujinxin/NeXtep/releases) · [哔哩哔哩演示视频](https://www.bilibili.com/video/BV1iKHj6fEtL)

[功能](#功能) · [使用要求](#使用要求) · [安装](#安装) · [工作区入口](#打开与关闭工作区) · [外部命令](#外部调用命令) · [行为开关](#行为开关)

## 功能

### 工作区与布局

- **保留原生桌面**：不将 NeXtep 注册为替代的主屏幕应用，并对桌面快捷菜单、桌面搜索和负一屏进行适配。
- **一个主窗口、三个实时小窗**：同时展示多个应用任务，可在主窗口与小窗之间移动整个任务。
- **左右侧布局**：通过控制区的布局按钮，将小窗侧栏切换到屏幕左侧或右侧。
- **竖屏与双向横屏**：控制区保持在手机物理顶部，主窗口应用随横屏方向旋转；横屏视频播放时仍保留控制区和三个小窗。
- **虚拟按键适配**：可根据系统导航栏边界调整主窗与小窗布局。三键导航时开启，手势导航时关闭；更改系统导航方式后，重新开关一次此选项。
- **任务保留与恢复**：关闭并重新打开工作区时，可恢复仍保留在系统中的符合条件的小窗任务；锁屏时暂停小窗输出，解锁后恢复工作区及原有任务。
- **系统交互适配**：支持与系统原生悬浮窗共存，原生悬浮窗位于工作区面板上方；同时适配输入法焦点、通知栏、控制中心、亮度控件和视频窗口尺寸。

### 任务管理

| 操作 | 使用方法 |
|------|----------|
| 将主窗口应用加入小窗 | 主窗口为普通应用时，点击空小窗的 **＋**。 |
| 从多任务页添加 | 将目标后台卡片移到屏幕中间，点击空小窗的 **＋**。添加后保持多任务页打开，可连续添加；分屏组合卡片需先退出分屏。 |
| 主窗口与小窗交换 | 点击已有应用的小窗，或长按小窗后拖到主窗口。 |
| 小窗之间移动或交换 | 长按已有应用的小窗，拖到空小窗时移动，拖到已占用小窗时交换；拖动时高亮目标区域。 |
| 在主窗口打开应用 | 点击顶部 App 栏中的应用图标。 |
| 从 App 栏加入小窗 | 长按应用图标，拖到目标小窗后松手。 |
| 横滑移到后台 | 在小窗上直接向屏幕外侧滑动：左侧小窗向左滑，右侧小窗向右滑，无需长按；可在设置中关闭。 |
| 拖动移到后台 | 长按小窗并拖到控制区，看到**“松手移到后台”**后释放；关闭横滑退后台后仍可使用。 |

移到后台会释放小窗位置，同时保留应用任务，当前主窗口保持原样。从桌面再次打开已在小窗中的应用时，会与主窗口交换。

可选的**“顶栏切换时缩小原应用”**功能，会在点击顶部 App 栏切换应用时，将原主窗口应用放入空小窗；没有空位时正常切换。此功能默认关闭。

### App 栏与系统分身

- **自动排序**：按最近使用情况排列应用快捷方式，默认使用此模式。
- **手动排序**：最多选择 **36 个应用**，支持移除快捷方式，并长按已选项目拖动调整顺序。
- **系统分身应用**：分身应用可出现在 App 栏和手动选择列表中，通过分身标记与原应用区分。

### 媒体控制

- 从系统媒体会话中显示歌曲封面、标题和歌手信息，提供**上一首、播放／暂停、下一首**按钮，具体可用操作取决于播放应用。
- 点击封面、标题等非播放按钮区域，在主窗口打开播放应用；如果播放应用已在小窗中，则与主窗口交换。
- 有可用封面时，自动生成柔和的封面取色渐变，暂停时保留配色；没有媒体会话或可用封面时，回退到壁纸磨砂背景。

### 外观与文字

控制区和小窗背景使用壁纸，并对 ColorOS 动态壁纸进行适配。磨砂效果不影响应用内容的清晰度。

| 设置项 | 选项与行为 |
|--------|------------|
| 顶部内容 | NeXtep 图标、时间、日期＋星期、留空、自定义文本；默认显示图标，切换选项即时生效。 |
| 显示秒 | 默认关闭，开关位于时间选项右侧。 |
| 自定义文本 | 选择自定义文本时显示编辑框；切换模式会保留草稿，编辑后点击**“保存文本”**生效。 |
| 循环滚动文字 | 默认关闭；开启后短文本与长文本均循环滚动，关闭时居中显示，超长部分省略；工作区隐藏时暂停滚动。 |
| 字体 | 搜索、预览并选择本机已安装字体，也可使用通用字体；系统默认跟随手机的字体设置。 |
| 字号与粗体 | 字号范围 **10–28 sp**，默认 **16 sp**，支持粗体和输入预览。 |
| 磨砂强度 | 范围 **0–100**，默认 **50**；松开滑块后保存并应用到控制区和小窗背景。 |

### 设置、语言与更新

- **设置／高级／关于**：通过底部导航切换工作区设置、外部调用命令和项目信息；设置界面跟随系统浅色／深色主题，底部导航提供液态玻璃效果。
- **语言选择**：应用与工作区可跟随系统，或手动选择**简体中文、繁体中文、英语、日语、韩语**。
- **设置快捷入口**：点击工作区控制区的齿轮按钮打开 NeXtep 设置，并保持小窗模式。
- **关于页面**：查看已安装版本、检查更新、打开发布页面和源码，以及查看开源协议与致谢名单。
- **更新下载**：查看可用版本的更新说明，通过浏览器下载 APK，下载后手动安装。

## 使用要求

- **一加 PLK110**：此前已在 **Android 16 / API 36、ColorOS 16** 上验证，近期部分真机验证使用 **Android 17 / API 37、ColorOS 17**。APK 最低安装版本为 API 35，不代表适配所有达到此 API 等级的设备。
- 与已适配设备相匹配的 **ColorOS 16 / 17** 系统版本。
- **KernelSU** 或其他兼容的 Root 方案。
- **Zygisk 和 LSPosed**，并支持新版 libxposed API。
- 在 LSPosed 中勾选内置[作用域列表](app/src/main/resources/META-INF/xposed/scope.list)中适用于当前设备的全部项目，包括 SystemUI、系统框架、桌面、应用分身服务和负一屏。

本模块会修改 **SystemUI、桌面和 system server 的行为**。**请确保设备具备可用的恢复手段**；如果设备出现无法开机或核心界面不稳定等问题，请停用本模块。

真机验证尚未覆盖所有应用和边界场景，其他设备和 ROM 仍需单独适配，未经测试的环境不作兼容性保证。

## 安装

1. 从 [GitHub Releases](https://github.com/lujinxin/NeXtep/releases) 下载 APK，或按照 [docs/BUILDING.md](docs/BUILDING.md) 自行构建。
2. 将 APK 安装到目标设备。
3. 在 LSPosed 中为内置作用域列表中适用于当前设备的全部项目启用 NeXtep。
4. 重启设备。
5. 打开 NeXtep 的**“设置”**页，配置工作区并添加控制中心入口。

## 打开与关闭工作区

启用模块并重启设备后，在解锁状态下，可通过以下入口操作：

- **底角手势**：单指从左下角向右上、或右下角向左上斜滑，打开或关闭工作区。横竖屏均以当前屏幕的两个底角为起点；从侧边和底边各 72 dp 内起手，向内和向上各滑动至少 32 dp。短滑、水平滑、直上滑和多指操作不触发。此手势默认开启，可独立关闭，并沿用原手势开关设置。
- **控制中心开关**：在 NeXtep 设置页的**“控制中心入口”**一行点击**“添加”**并确认，随后下拉控制中心，点击 **NeXtep** 开关切换工作区状态。应用内添加失败时，可在控制中心编辑页面手动添加。
- **外部命令**：在**“高级”**页复制命令，配置到手势、快捷操作、自动化或终端工具中，具体见下文。
- **退出按钮**：点击工作区控制区的退出按钮，返回全屏模式。

### 外部调用命令

**“高级”**页提供进入、退出小窗模式及查询状态的命令和复制按钮。请先启用模块并重启设备；进入和退出命令需在**手机解锁后，以 Root 权限执行**。

进入小窗模式：

```sh
am broadcast -n io.github.lujinxin.nextep/.trigger.WorkspaceControlBrokerReceiver -a io.github.lujinxin.nextep.action.HOST_SET_REQUEST --ez io.github.lujinxin.nextep.extra.REQUESTED_ACTIVE true
```

退出小窗模式：

```sh
am broadcast -n io.github.lujinxin.nextep/.trigger.WorkspaceControlBrokerReceiver -a io.github.lujinxin.nextep.action.HOST_SET_REQUEST --ez io.github.lujinxin.nextep.extra.REQUESTED_ACTIVE false
```

查询当前状态：

```sh
settings get global nextep_workspace_active_v1
```

工作区布局处于开启状态时返回 `1`，未开启时返回 `0`。锁屏挂起期间也会记录为未开启，自动化场景请在解锁后查询。

## 行为开关

以下开关均位于**“设置”**页，外观和 App 排列选项见上方功能说明。

| 设置项 | 默认值 | 行为 |
|--------|--------|------|
| 底角触发手势 | 开启 | 控制左右底角斜向内上滑动手势；关闭后仍可使用控制中心开关。 |
| 小窗 App 横滑退到后台 | 开启 | 控制向外侧滑动移入后台；关闭后仍可拖到控制区移入后台。 |
| 顶栏切换时缩小原应用 | 关闭 | 从顶栏切换应用时，将原主窗口应用移入空小窗；没有空位时正常切换。 |
| 虚拟按键适配 | 关闭 | 三键导航时开启，手势导航时关闭；更改系统导航方式后，重新开关一次。 |

## 项目架构

下图展示普通 APK 与经 LSPosed 接入的 SystemUI、原生桌面、负一屏、应用分身和 system_server 之间的协作。图中区分主窗口呈现与三个实时小窗，并标注配置读取、变更通知及共享状态链路。兼容性 Hook 分别进行保护，在目标不可用时跳过对应处理。

[架构说明](docs/ARCHITECTURE.md) · [交互式架构图（下载 HTML 后打开）](docs/architecture/nextep-architecture.html) · [图源 JSON](docs/architecture/nextep-architecture.json)

<p align="center">
  <a href="assets/nextep-architecture.png">
    <img src="assets/nextep-architecture.png" alt="NeXtep 项目架构图" width="100%">
  </a>
</p>

## 反馈交流群

QQ 群：**1128561895**

<p align="center">
  <img src="assets/qq-group-qrcode.jpg" alt="NeXtep QQ 反馈群二维码" width="360">
</p>

## 致谢

特别感谢锤子科技 **Smartisan OS OneStep**，为本项目提供了产品理念与交互设计灵感。

## 许可证与署名

NeXtep 使用 [Apache License 2.0](LICENSE) 授权。第三方署名和设计来源请参阅 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

Copyright 2026 lujinxin.
