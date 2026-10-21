# 工作台定制版

版本：`1.3.0-workbench.1`，versionCode `15`。基于本 Fork 的 corner.3，保留底角手势、原有应用选择和配置，以及先前的输入、动画、拖动与绘制优化。

## 窗口和外观

- 一个主窗加四个独立实时 VirtualDisplay 侧窗。侧窗点击交换、内部拖动、左右布局与任务退出恢复继续使用原来的任务协调器。
- 深浅主题、渐变控制面板、圆角侧窗和主窗金色边框。主窗圆角由本模块的覆盖层绘制，不覆盖原生任务的圆角状态。
- 顶部常用应用与原有选择方式继续使用；应用栏增加“比价”入口，付款入口独立放在右上角。
- 横向视频／游戏可以占用顶部宽窗，下方保留主窗和另外三个侧窗，总计仍为五个任务。识别依据是应用的横向请求、固定方向，以及已横屏的媒体／游戏任务；不会仅因普通应用随手机旋转就自动置顶。
- “更多 → 横向应用置顶”可手动切换。将宽窗应用切回主窗、关闭或退出横向场景后恢复四侧窗。系统不提供兼容的方向 Hook 时保留原生方向行为。
- 极短的控制区不裁切媒体卡片；完整媒体控制始终可从“更多”打开。

## 付款与比价

右上角“¥”提供微信扫一扫、微信收付款码、支付宝扫一扫和支付宝付款码。优先查询目标应用公开的桌面快捷方式，再使用其已注册的启动入口；不读取账户、密码或付款码，也不自动确认支付。

比价有两个分组：

| 分组 | 目标 |
| --- | --- |
| 外卖 | 京东秒送、淘宝闪购、美团外卖 |
| 购物 | 拼多多、抖音商城、京东、淘宝 |

输入一次商品词，在已安装并能解析入口的应用中搜索同一内容。先预留侧窗，只复用同一目标应用或空窗；空窗不够时不覆盖其他任务。至少需要两个可用目标。比价指并排查看应用搜索结果，不抓取价格、自动匹配同款、下单或付款。

这些应用内路由属于版本相关的兼容入口。本地检查覆盖参数编码、目标包限制和窗口预留；没有在当前手机的微信、支付宝或购物应用内实际打开验证。能解析 Intent 不等于已证明该版本会进入预期页面；应用拒绝或调整入口时需从应用内操作，并根据真机反馈适配。

京东即时零售使用其公开前端的 `#/IndexSearch` 路由，淘宝闪购使用 `tb.ele.me/app/eleme/et-search/result` 和 `keyword` 参数；后者的搜索页脚本也用该路径进入结果页。美团使用公开客户端注册的 `/takeout/poi/search`，传入其搜索页读取的 `query` 参数。公开页面与路由的静态核对不代表当前安装版本的真机跳转已通过。

## 跨应用内容

从主窗应用发起 Android 全局拖动，将文字、图片或文件放到已占用的侧窗，会通过标准 `ACTION_SEND`／`ACTION_SEND_MULTIPLE` 交给目标应用的接收界面。目标应用可能仍要求选择会话或确认操作，不会自动发送给他人。

文件必须是来源授予访问权限的 `content://` URI。模块临时接收拖动权限，启动接收界面后释放；不读取任意本地路径，不执行拖动数据中的 Intent，不以 SystemUI 权限补读未授权文件。目标没有相应接收界面时提示不支持。这不是对所有应用提供任意光标位置的原地粘贴。

微信小程序沿用其真实任务：先在微信内打开小程序，再用空窗的“＋”停放当前任务。普通微信和独立小程序任务按任务 ID、根组件区分，普通应用入口不会仅按包名抢占小程序。

## 显示和隐私

- 小窗使用 secure VirtualDisplay 与 SurfaceView，替代不能承载受保护内容的 TextureView。所有降级候选仍要求 secure，不通过去掉其他应用的 `FLAG_SECURE` 来显示付款页。
- 默认保护工作台截图／录屏。“更多 → 本次允许截图／录屏”只对当前会话生效，退出、锁屏、回桌面／最近任务或打开模块设置时复位，不保存为永久开关。
- 目标应用自身的系统保护、DRM、企业设备策略仍有效。允许截图不保证付款码、密码等内容可以被截取；显示兼容也需真机确认。
- 最近任务隐藏只过滤工作台当前拥有的任务，不删除任务或改写应用的永久最近任务标记；退出／暂停工作台即解除。
- 诊断导出只保存当前 SystemUI 进程内本模块最近128条事件元数据到下载目录，不抓取全量 logcat，不导出搜索词、拖动正文、URI、应用账户或画面。

## 安装和验证

这是预发布版。用户已决定自行真机验证，本轮没有安装到手机、重启手机、操作付款账户或开展真实交易，也没有测量实际帧率、功耗或五应用长期并行稳定性。

可覆盖安装同一 Fork 签名的 corner.1／corner.2／corner.3；安装后需重新加载注入宿主，通常重启一次。上游原版签名不同。内置更新仍指向上游，更新为上游包会替换本 Fork 功能。

本地回归包含旧配置迁移、四侧窗／顶部宽窗在竖屏和两种横屏下的布局、控制区边界、主窗与小程序任务区分、搜索词编码和不覆盖其他侧窗、内容授权边界、会话捕获权限复位，以及实际 Android View 的付款入口位置、主题、媒体布局和圆角遮罩。测试依赖只用于本地，不打包进 APK。

完整 Lint 仍有上游遗留的四项错误：`AppCompatCustomView`、两项 `SoonBlockedPrivateApi` 和 `WrongConstant`。构建与测试结果随 Release 的 `verification.json` 提供；不将 Lint 写成全绿。

## 参考

交互参考用户提供的[第一篇酷安帖子](https://www.coolapk.com/feed/74190065?s=OTZmNmMzNzAxYThlMDY5ZzZhZDhjZjU4egi1656)和[第二篇酷安帖子](https://www.coolapk.com/feed/74244277?s=NDk4NTY4MTgxYThlMDY5ZzZhZDhkMzhhegi1656)。仅借鉴公开描述与配图，不使用作者 APK、源码或图片资产；没有据静态图声称复现其完整动画。

显示与授权接入使用 [SurfaceView secure 输出](https://developer.android.com/reference/android/view/SurfaceView#setSecure(boolean))、[secure VirtualDisplay](https://developer.android.com/reference/android/hardware/display/DisplayManager#VIRTUAL_DISPLAY_FLAG_SECURE)、[Android 图形输出限制](https://source.android.com/docs/core/graphics/arch-tv)和 [AOSP DragAndDropPermissions](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/view/DragAndDropPermissions.java)。私有系统接口缺失时跳过相关适配，不影响原生系统行为。

搜索入口静态核对来源：[京东到家公开前端](https://daojia.jd.com/)、[淘宝闪购公开搜索页](https://tb.ele.me/app/eleme/et-search/search)、[淘宝官方安装包](https://download.alicdn.com/wireless/taobao4android/latest/702757.apk)及[美团官方安装包下载页](https://i.meituan.com/client/homemt)。只使用接口名称与参数，不分发第三方应用代码或资源。
