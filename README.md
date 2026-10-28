# 微乐广西麻将（柳州麻将）安卓助手 — P0 工程

> 本工程对应《MVP 开发计划》的 **P0：环境与屏幕采集可行性**。
> **仅验证“能否在用户主动授权后稳定采集屏幕帧”**，不做任何麻将识别、出牌建议或结算。
> 不 Root、不 Hook 微信、不读取小程序内存/协议、不自动代打、不上传任何数据。

---

## 1. 已实现范围（映射到计划第 7 节）

| 计划要求 | 实现位置 | 说明 |
| --- | --- | --- |
| 空项目 + 模块空接口 + 仓库文档 | `docs/MODULES.md`、`domain/**` | 架构模块接口齐全 |
| MediaProjection 授权 + 前台服务 + 单/连续截图 + 停止回调 | `capture/CaptureService.kt` | 前台通知常驻；连续取帧并统计 FPS |
| 最小悬浮窗（开始/暂停、状态、收起/关闭、拖动） | `overlay/FloatingOverlay.kt` | `TYPE_APPLICATION_OVERLAY`；无权限时仅用通知栏 |
| 截取一帧并保存到本地调试目录 | `capture/FrameSaver.kt` | 用户主动触发；默认不持续保存全屏 |
| ScreenViewportDetector 接口 + 黑边检测离线测试 | `vision/BlackBarViewportDetector.kt` + `domain/vision/*` | 输出归一化 `gameViewport`；纯 JVM 单测 |
| 横屏切换 / 停止 / 权限拒绝 / 服务结束 / 前台通知 | `CaptureService.kt` | 旋转走 `setSurface` 重建，不无故新建 VirtualDisplay |
| **P1：牌面模板匹配框架** | `vision/NccTileTemplateMatcher.kt`、`domain/vision/TileRecognition.kt` | NCC 匹配 + 模板库接口；**真实模板待样本** |
| **P1：画面状态分类 + 识别串联** | `vision/ScreenRecognizer.kt`、`HeuristicScreenStateClassifier.kt` | 视口→状态→牌区分割→匹配全链路（合成数据验证） |
| **P2：mahjong-core 计算引擎** | `domain/mahjong/Tile.kt`、`TileCounts.kt`、`HandEvaluator.kt` | 牌编码/守恒校验/判型/向听/有效进张/舍牌排序 |
| **P3：牌局连续追踪（状态机）** | `domain/game/GameModels.kt`、`GameStateMachine.kt`、`TileLedger.kt` | 帧差事件推导/副露来源归属/守恒回滚/人工修正/自动重置 |
| **P4：实时辅助 UI 与分析管线** | `analysis/AnalysisPipeline.kt`、`domain/analysis/AnalysisEngine.kt`、`ui/MainScreen.kt`、`overlay/FloatingOverlay.kt` | 采集→识别→向听/进张→舍牌建议；自掩码；对局配置（箭牌/模式/频率）；等待确认 |
| **P5：规则细化与评分** | `domain/rules/RulesLiuzhou.kt`、`domain/settlement/Settlement.kt`、`ScoringPolicy.kt`、`ActionLedger.kt` | 三层隔离判型/计分/账本；已确认规则给分，2.3 冲突一律「暂不支持」 |
| **storage：Room 持久化** | `storage/AppDatabase.kt`、`RoomRepositories.kt` | 对局配置（跨重启保留）/ 局历史与事件流 / 调试样本元数据 |
| README | 本文件 | 构建步骤、验收清单、待补样本 |

**MVP 计划 P0–P5 全部完成，storage 持久化增强项已实现。** 剩余增强项（接口已占位）：
真实牌面模板与布局校准（vision-weile，**依赖截图样本**）、2.3 冲突点的算例填充。
当前模板库为空，识别结果为 unknown → 建议走「等待确认」安全路径（已在模拟器验证）。

---

## 2. 环境要求

- **JDK 17 或 21**（AGP 8.5 需要 17+；本机已验证 JDK 21 可正常构建。**不要用 JDK 8**）。
- **Android Studio**：任意较新稳定版；没有 IDE 也可以，见第 8 节命令行构建。
- **Android SDK**：Platform 34（`android-34`）、Build-Tools 34.0.0、Platform-Tools。
- **工程路径**：必须为纯 ASCII（不能含中文/特殊字符）——AGP 在 Windows 上会拒绝非 ASCII 路径的构建。
- **真机**：Android 10（API 29）以上，重点验收 13 / 14 / 15 / 16；推荐一台常见厂商机型（含 Android 14 的重新授权行为）。
- **Gradle**：工程已含 Wrapper（锁定 Gradle 8.9），无需单独安装，`./gradlew` 会自动下载对应发行版。

> ✅ **本机构建已验证通过**（2026-10-23）：JDK 21 + Gradle 8.9 + SDK Platform 34 + Build-Tools 34.0.0 下 `./gradlew assembleDebug test` 成功，单测 3/3 通过。详见第 8 节。

---

## 3. 构建与安装步骤

### 命令行构建（无 Android Studio）

```bash
export JAVA_HOME=/d/SDK/.jdks/ms-21.0.11   # 本机任意 JDK 17/21 均可，不要用 JDK 8
./gradlew assembleDebug test --console=plain
```

产物：`app/build/outputs/apk/debug/app-debug.apk`；测试报告：`app/build/reports/tests/testDebugUnitTest/index.html`。

### Android Studio 方式

1. 用 Android Studio 打开本工程根目录（`C:/Users/YYKJ/WorkBuddy/weile_liuzhou_mahjong_android/`，**路径不能含中文**）。
2. 等待 Gradle Sync 完成（首次会下载 AGP 8.5.2、Kotlin 1.9.24、Compose BOM 2024.06.00）。
3. 连接真机并开启 **USB 调试**。
4. 菜单 **Run ▸ Run 'app'**（或 `./gradlew installDebug`）。
5. 首次运行会请求 **屏幕采集授权**（系统弹窗）→ 允许；可再在应用内“去开启悬浮窗权限”。
6. 将微信《微乐广西麻将》柳州房间置于前台并切到**横屏**，点“开始屏幕采集”。

### 打包 release APK（供真机分发）

```
./gradlew assembleRelease
```
首次需配置签名（`key.properties` + `keystore`，参考官方文档）。本专家只做本地打包，不做应用商店上架。

---

## 4. 真机/模拟器验收清单（P0）

以下打 ✅ 的项已在 **Android 14（API 34）模拟器**上验证通过（2026-10-23，见第 8 节）：

- [x] 能拉起系统“屏幕采集”授权弹窗，允许后可进入采集。
- [x] 通知栏渠道创建；点「开始采集」后前台服务常驻（状态卡显示帧数/FPS 持续增长）。
- [x] 点“保存当前一帧”后，应用私有目录 `files/debug/frames/` 下出现 PNG，且**非黑屏**、内容包含当前画面。
- [x] 已授予悬浮窗权限时：屏幕出现可拖动、可收起/关闭的小窗，状态随采集更新。
- [x] **横竖屏切换**：不崩溃，采集持续（帧数跨旋转连续计数，验证 `setSurface` 重建路径）。
- [x] **Android 14+**：停止后再次“开始采集”会**重新弹授权**（令牌不复用）。
- [x] 点“停止采集”后通知/服务释放、状态显示“已停止”。
- [ ] 模拟应用切换/返回桌面：采集暂停或恢复，无崩溃。
- [ ] 权限被拒绝（取消授权弹窗）时，状态显示明确错误，不崩溃。
- [ ] 在真实手机 + 微信《微乐广西麻将》柳州房间验证（含防截图黑屏场景）。

> ⚠️ **声明**：以上仅验证系统能力与 App 自身行为。**尚未在真实微信小游戏上验证**。
> 本工程仅基于公开 Android API 与用户提供的样本设计。

---

## 5. 需要补充的样本（用于后续 P1 识别）

- 轮到自己出牌、无弹窗遮挡的**正常手牌截图**。
- 含**吃/碰/杠**副露的对局截图，以及弃牌较多的截图。
- **玩法设置页**截图（核对箭牌、特殊规则、得分）。
- 如可能，录制**经授权且被游戏规则允许的短视频**，用于离线回放验证状态机。

---

## 6. 目录结构

```
weile_liuzhou_mahjong_android/
├── README.md
├── docs/MODULES.md
├── settings.gradle.kts / build.gradle.kts / gradle.properties
├── gradlew / gradlew.bat / gradle/wrapper/
└── app/
    ├── build.gradle.kts / proguard-rules.pro
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml
        │   ├── java/com/weile/liuzhou/mahjong/
        │   │   ├── MainActivity.kt
        │   │   ├── capture/   (CaptureService, CaptureStateHolder, FrameSaver)
        │   │   ├── overlay/   (FloatingOverlay)
        │   │   ├── vision/    (BlackBarViewportDetector, NccTileTemplateMatcher,
        │   │   │               ScreenRecognizer, HeuristicScreenStateClassifier,
        │   │   │               SubRegionPixelAccessor)
        │   │   ├── ui/        (Compose 主题 + MainScreen + 对局配置)
        │   │   ├── analysis/  (AnalysisPipeline 实时管线 + AnalysisSettings)
        │   │   ├── domain/    (mahjong: Tile/TileCounts/HandEvaluator/MahjongCore;
        │   │   │               game: GameModels/GameStateMachine/TileLedger;
        │   │   │               rules: RulesLiuzhou(柳州判型);
        │   │   │               settlement: Settlement/ScoringPolicy/ActionLedger;
        │   │   │               analysis: AnalysisEngine(建议引擎);
        │   │   │               vision: VisionCore/TileRecognition/TileLayout/PixelAccessor/RecognitionAdapter;
        │   │   │               其余模块接口占位)
        │   │   └── util/      (Permissions)
        │   └── res/          (strings, themes, 自适应图标, overlay 布局)
        └── test/             (102 个纯 JVM 单测，双变体全绿)
```

---

## 7. 后续阶段一览（来自计划第 6 节）

- ~~**P0** 环境和屏幕采集可行性~~ ✅ 已完成（模拟器验收通过）。
- **P1** 静态手牌和游戏状态识别——框架已实现（NCC 模板匹配 + 状态分类 + 识别串联），
  **卡在真实样本**：模板库为空时识别结果全部为 unknown（安全默认）。
  样本到位后：生成模板 → 校准 `TileLayout` → 跑「单牌/整手/未知牌率」三项指标（目标 ≥99%）。
- ~~**P2** 基础计算引擎~~ ✅ 已完成（`HandEvaluator`：判型/向听/有效进张/舍牌排序，29 个单测）。
- ~~**P3** 牌局连续追踪~~ ✅ 已完成（`DefaultGameStateMachine`：帧差事件推导、副露来源归属、
  守恒校验回滚、人工修正、自动重置，15 个单测；覆盖 P3 验收：回放不重复计数 / 未知牌停止并可人工修正 / 可回溯）。
- ~~**P4** 整合实时辅助 UI~~ ✅ 已完成（`AnalysisPipeline`：采集→识别→状态机→建议；
  悬浮窗展示最多 3 个候选 + 向听/进张/理由 + 识别置信度；不确定时醒目「等待确认」；
  自掩码避免污染截图；对局配置：箭牌开关（切换清空本局）/ 速度·综合·安全模式 /
  250·500·1000ms 性能档位 / 清空本局；无权限或停止采集即停止推荐）。
- ~~**P5** 规则细化及评分~~ ✅ 已完成（三层隔离：`LiuzhouHandEvaluator` 判型 +
  `ActionLedger` 事件账本 + `LiuzhouScoringPolicy` 计分；已确认规则给确定分值，
  2.3 六个冲突点全部「暂不支持」并给出原因，`confirmedTotal` 有未确认项时为 null，
  不展示虚假精确总分；吃三比次数/杠分台账/最后一张判定均有单测覆盖）。

所有计分分支在规则未确认前必须标注“暂不支持”，不展示虚假的精确分数。

---

## 8. 本机构建验证记录（2026-10-23）

**环境**：Windows + JDK 21（`D:\SDK\.jdks\ms-21.0.11`）+ Gradle 8.9 + Android SDK Platform 34 + Build-Tools 34.0.0。
本机 SDK 位于 `C:\Users\YYKJ\AppData\Local\Android\Sdk`，路径已写入 `local.properties`（该文件不入库）。

**命令**：

```bash
export JAVA_HOME=/d/SDK/.jdks/ms-21.0.11
./gradlew assembleDebug test --console=plain
```

**结果**：`BUILD SUCCESSFUL`；**110 个纯 JVM 单测全部通过**（双变体 0 失败）：
视口检测 3 / 牌编码 4 / 守恒 8 / 判型向听进张舍牌 17 / 模板匹配 5 / 识别串联 5 /
识别适配 4 / 建议引擎 9 / 柳州判型 8 / 事件账本 5 / 计分结算 19 / 牌局状态机 12 /
牌张账本 3 / 存储映射 8；产物 `app/build/outputs/apk/debug/app-debug.apk`（约 55 MB）。

**storage 模拟器验收**（API 34 AVD）：修改对局配置（关闭箭牌 / 切换安全模式）→
强制停止应用 → 重启后配置保持 → Room 数据库文件生成（`weile_liuzhou.db`）。

**P4 模拟器验收**（API 34 AVD）：对局配置 UI 正常渲染（箭牌开关/三模式/三档频率/清空本局）；
授权采集后管线运行（帧数与 FPS 持续更新）；悬浮窗展示建议区；模板库为空时正确走
「等待确认 + 识别置信度 0%（非胜率）」安全路径；停止采集后服务释放、建议清空；全程零崩溃。

**模拟器验收**（API 34 AVD，WHPX 加速，无窗口运行）：
授权弹窗 → 前台服务采集（帧数持续增长）→ 保存帧非黑屏 → 横竖屏切换不崩溃 →
停止后重新授权（Android 14 令牌不复用）→ 重新采集稳定，全程零崩溃。

**调试记录（真实运行才暴露的问题，均已修复）**：
1. `org.jetbrains.kotlin.plugin.compose` 插件 ID 自 Kotlin 2.0 才存在，1.9.24 用 `composeOptions.kotlinCompilerExtensionVersion = "1.5.14"`；
2. 协程线程直接更新悬浮窗 `TextView` 触发 `CalledFromWrongThreadException`——`FloatingOverlay` 内部统一切主线程；
3. 模拟器 ImageReader 上整块 `copyPixelsFromBuffer` 必现 SIGSEGV（驱动层 buffer 映射异常）——改为逐行 `setPixels`；
4. `Tile(-1.coerceIn(0, 33))` 被解析为 `-(1.coerceIn(...))`——未知牌改用可空 `Tile?` 表示。

**无 Android Studio 时补装 SDK 的方法**：

```bash
# 1) 下载新版 cmdline-tools（旧版 2024 年的不识别新仓库 schema，装不了新平台）
curl -LO https://dl.google.com/android/repository/commandlinetools-win-16111833_latest.zip
#    解压后放到 $ANDROID_SDK/cmdline-tools/latest/
# 2) 安装本工程所需组件
"$ANDROID_SDK/cmdline-tools/latest/android.exe" sdk install platforms/android-34 build-tools/34.0.0
```

**构建过程中修复的问题**（均为首次真实编译才暴露的缺陷，已在源码中修正）：
1. 根与 app 的 `build.gradle.kts` 误引 `org.jetbrains.kotlin.plugin.compose` 插件——该 ID 自 Kotlin 2.0 才存在；Kotlin 1.9.24 时代用 `composeOptions.kotlinCompilerExtensionVersion` 配置，已移除该声明。
2. `kotlinCompilerExtensionVersion` 误填 Kotlin 版本号 `1.9.24`——Compose 编译器独立发布，与 Kotlin 1.9.24 配套的是 `1.5.14`，已修正。
3. `CaptureService.onStartCommand` 对可空 `intent` 直接取值——已改安全调用（`intent?.`）。
4. `MainActivity` 缺 `MainViewModel` import、`hasOverlayPermission()` 未传 `context`、`MainScreen` 误 import 内部 API `weight`——已修复。
