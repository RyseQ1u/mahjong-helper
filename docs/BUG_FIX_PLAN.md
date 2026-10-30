# 微乐柳州麻将助手 · Bug 修复计划（分步执行）

> 版本：v1（2026-10-29）
> 覆盖范围：上一轮《Bug 分析报告》中确认的 14 处问题（严重 2 / 中等 4 / 轻微 8）。
> 本文只给出**修复思路、改动点、测试与验收标准**，不落地完整修复代码；每步完成后运行构建与测试，双变体全绿再进入下一步。

---

## 0. 总体原则与执行顺序

1. **顺序**：先核心判定逻辑（严重）→ 决策与计分（中等）→ 边界/资源/安全（轻微）。
2. **构建环境**：构建前必须 `export JAVA_HOME=D:/SDK/.jdks/ms-21.0.11`（shell 默认 JDK 8 会导致构建失败）。
3. **回归命令**：`./gradlew assembleDebug test`（110 个纯 JVM 单测，双变体全绿为准）。
4. **依赖关系（关键）**：
   - BUG-2（向听算法）与 BUG-3（totalMelds）**必须同一阶段一起改**：一旦 BUG-2 让 `melds` 参数真正生效，BUG-3 传入的「全场四家副露总数」会立刻变成显式错误。先改 BUG-3，再改 BUG-2。
5. **红线**：不 Root、不 Hook 微信、不读内存/协议、不自动代打、不上传数据；修复不得触碰这些边界。

---

## 阶段一：核心判定逻辑（严重，最高优先级）

### 步骤 1 — 修复 BUG-1：`TileCounts.validate()` 箭牌判断取反

- **文件**：`app/src/main/java/com/weile/liuzhou/mahjong/domain/mahjong/TileCounts.kt`（第 59–63 行）
- **问题**：`if (hasDragons)` 应为 `if (!hasDragons)`，导致「允许箭牌时误拒箭牌、禁止箭牌时不校验」两个方向都错。
- **修复思路**：
  1. 将外层条件改为 `if (!hasDragons)`，保持内部循环不变（枚举 `DRAGON_START..MAX_CODE`，命中即返回 `Invalid`）。
  2. 复核报错文案「牌集设置为无箭牌，但出现了箭牌…」在修正后语义正确，无需改文案。
- **补测试**（`TileCountsTest`）：
  1. `hasDragons=true` 且手牌含红中 → `validate()` 返回 `Ok`。
  2. `hasDragons=false` 且通过 `copy()` 塞入箭牌（绕过 `add()` 的 `require`）→ `validate()` 返回 `Invalid`，文案含「箭牌」。
- **验收**：上述两个用例由红转绿；既有 110 用例不回归。

### 步骤 2 — 修复 BUG-3：决策引擎副露数改用「自家副露」

- **文件**：`app/src/main/java/com/weile/liuzhou/mahjong/domain/analysis/AnalysisEngine.kt`（第 130 行）
- **问题**：`val melds = state.totalMelds` 把四家副露总数当自家副露。
- **修复思路**：改为 `val melds = state.meldsOf(com.weile.liuzhou.mahjong.domain.game.Seat.SELF).size`。
- **补测试**（`RecommendationEngineTest`）：
  1. 构造「自家 1 副露 + 对手合计 2 副露」的状态，断言引擎使用的 `melds == 1`（通过向听/进张结果间接校验，见步骤 3）。
- **验收**：本步仅替换取值来源，与步骤 3 一起验证。

### 步骤 3 — 修复 BUG-2：向听算法（`melds` 失效 + 无将孤立牌偏差）

- **文件**：`app/src/main/java/com/weile/liuzhou/mahjong/domain/mahjong/HandEvaluator.kt`（第 57–137 行）
- **问题（两点根因）**：
  1. `mentsuShanten` 递归中 `melds` 只传递、从不读取，向听公式缺失 `8 - 2*melds` 减项；
  2. 「无将」路径下孤立牌一律 `+1`，未把「即将成为将的孤立牌」免费处理，导致单钓将（tanki）被多算 1 向听。
- **修复思路（推荐整体重构为标准公式，而非打补丁）**：
  1. 保留 `shantenStandard(c, melds)` 与 `shantenChiitoi(c)` 两个入口，`shanten = min(standard, chiitoi)` 不变。
  2. 将 `mentsuShanten` 的返回值语义改为标准公式：
     `shanten = 8 - 2*melds - 2*sets - partials - hasPair`
     其中 `sets` 为已成型面子数、`partials` 为搭子/孤立张数、`hasPair` 为是否已取将（0/1），并约束 `sets + partials ≤ 4 - melds`。
  3. 枚举路径保持不变（刻子/顺子/对子当将/对子做半刻/两面/嵌张/单张），但每一类不再「累加 +1」，而是计入 `partials` 或 `sets`，最终统一套公式，从而自然消除 tanki 偏差。
  4. `canWin` 复用同一分解逻辑，保持「4 - melds 面子 + 1 将」判和（当前 `canWin` 借手牌张数已正确，重构时用测试锁死，避免回归）。
- **补测试**（`HandEvaluatorTest`）：
  1. tanki：`123m456m789m123p5s` → `shanten == 0`。
  2. 带副露向听：`123m456m789m1p9p` + `melds=1` → `shanten == 0`。
  3. 带副露和牌（回归）：`456m789m111s55s` + `melds=1` → `canWin == true`。
  4. 带副露进张：上述 tanki 带 1 副露时 `effectiveDraws` 正确枚举将牌。
  5. 既有向听用例（十三张听牌、三顺加将加浮张、七对等）全部保持原值。
- **验收**：新增用例转绿且不破坏既有 110 用例；`discardCandidates`/`effectiveDraws` 对 tanki 与带副露手牌结果符合预期。

> 阶段一完成后：核心判定（守恒校验、向听、和牌、进张、舍牌）在「无副露 / 带副露 / 单钓」三类手牌上全部正确。

---

## 阶段二：决策与计分（中等）

### 步骤 4 — 修复 BUG-4：倍率改为乘法，消除重复计分

- **文件**：`app/src/main/java/com/weile/liuzhou/mahjong/domain/settlement/ScoringPolicy.kt`（第 102–208 行，重点 154–195）
- **问题**：抢杠 / 杠上花 / 海底捞月「×2」各作为独立 `+2` 计分行叠加在基础分之上，而非对基础分取倍率。
- **修复思路**：
  1. 在 `scoreWin` 中先算出「基础胡牌分」（点炮或自摸口径），再按 `robbingKan / afterKan / isLastTile` 计算**总倍率**，最后输出一条「基础分 × 倍率」的确定行，而不是多条加法行。
  2. 对仍属「未确认」的组合（如杠上花补杠支付人、抢杠与其他番型叠加）保持 `unsupported`，不得产出虚假精确分。
  3. `confirmedTotal` 改为对「确认行」求和，仍以 `hasUnsupported` 判定是否隐藏总分。
- **补测试**（`LiuzhouScoringPolicyTest`）：
  1. 基本胡 + 抢杠（点炮）→ 结果等于 `1×2 = 2`，且只有一条确定分值行。
  2. 基本胡自摸 + 杠上花 → 倍率叠加结果正确，无「+2 叠加」痕迹。
  3. 未确认组合仍返回 `hasUnsupported == true`。
- **验收**：上述用例转绿；结算顺序（胡牌分→吃三比→杠牌分→钓鱼分）不变。

### 步骤 5 — 修复 BUG-5：`TileCounts.parse()` 越界崩溃

- **文件**：`app/src/main/java/com/weile/liuzhou/mahjong/domain/mahjong/TileCounts.kt`（第 116 行）
- **问题**：数字扫描后 `val suit = spec[j]` 未校验 `j < spec.length`，输入 `"123m456"` 抛 `StringIndexOutOfBoundsException`。
- **修复思路**：在取 `spec[j]` 前加 `if (j >= spec.length)` 判定，抛 `IllegalArgumentException("牌谱缺少花色后缀: ...")`。
- **补测试**（`TileCountsTest`）：
  1. `parse("123m456")` 抛 `IllegalArgumentException`（而非越界异常）。
- **验收**：用例转绿。

### 步骤 6 — 修复 BUG-6：全求人基础胡型纳入加杠

- **文件**：`app/src/main/java/com/weile/liuzhou/mahjong/domain/rules/RulesLiuzhou.kt`（第 136–138 行）
- **问题**：`quanqiurenBase` 判「全刻子」时只认 `PON/MINKAN/ANKAN`，漏掉 `SHOUMINKAN`。
- **修复思路**：把 `SHOUMINKAN` 纳入「全刻子」判定集合（加杠本质仍是刻子）。
- **补测试**（`LiuzhouHandEvaluatorTest`）：
  1. 4 副露含 1 个 `SHOUMINKAN` 且其余为刻子 → `quanqiurenBase == ALL_TRIPLETS`。
- **验收**：用例转绿。

---

## 阶段三：边界 / 资源 / 安全（轻微）

| 步骤 | Bug | 文件 | 修复思路 | 验收 |
|---|---|---|---|---|
| 7 | BUG-7 `isLastTileWinReliable` 从不返回 false | `domain/settlement/ActionLedger.kt:111–119` | 补「和牌非紧邻摸牌但可判定非海底」的 `false` 分支；删除第 117 行冗余越界检查。 | 补充可判定 false 的用例转绿 |
| 8 | BUG-8 `correctOwnHand` 未刷新张数 | `domain/game/GameStateMachine.kt:195–207` | 修正成功后补调 `refreshHandCounts()`。 | 用例断言 `handCounts[SELF]` 同步更新 |
| 9 | BUG-9 折叠态 `params` 错配 / `show` 重复挂窗 | `overlay/FloatingOverlay.kt:128–147` | `setVisible` 的 `view` 与 `params` 配对取（`cardView`→`cardParams`，否则 `collapsedView`→`collapsedParams`）；`show()` 在折叠态先展开再更新。 | 折叠态自掩码位置不跳变，无重复窗口 |
| 10 | BUG-10 `rotationDeg` 未应用 / `cells` 末格余数 | `domain/vision/TileLayout.kt` + `vision/ScreenRecognizer.kt` | 模板匹配前按 `rotationDeg` 旋转子区域（或先读方向后按竖排切格）；`cells()` 末格覆盖到边界。 | 接入真实模板后左右弃牌区匹配正确 |
| 11 | BUG-11 NCC 模板均值/范数重复计算 | `vision/NccTileTemplateMatcher.kt:72–79` | 将模板均值/范数预计算并缓存（构造时一次算出）。 | 无行为变化，性能提升 |
| 12 | BUG-12 `SubRegionPixelAccessor` 越界 | `vision/SubRegionPixelAccessor.kt:20` | 加 `parent.width/height == 0` 保护返回 0。 | 极端输入不崩溃 |
| 13 | BUG-13 `fallbackToDestructiveMigration` | `storage/RoomRepositories.kt:82` | 版本升级时提供 `Migration` 或至少显式记录「破坏性迁移」风险；短期保留但补注释与版本管理策略。 | 未来 schema 升级不静默丢数据 |
| 14 | BUG-14 加杠 `fromDiscard` 误判 | `domain/game/GameModels.kt:98–99` + `GameStateMachine.kt:266` | `fromDiscard` 排除 `SHOUMINKAN`；`reconcileMelds` 对加杠不查找来源弃牌。 | 加杠不误标记弃牌消耗 |

---

## 验证清单（全部完成后）

- [ ] `./gradlew assembleDebug test` 双变体全绿，且新增用例覆盖 14 项修复点。
- [ ] 核心判定：守恒校验（箭牌开关双向）、向听（tanki / 带副露）、和牌、进张、舍牌在关键手牌上正确。
- [ ] 计分：抢杠 / 杠上花 / 海底倍率正确，未确认组合不产出虚假精确分。
- [ ] 模拟器回归：`P0_API34` 采集→识别→状态机→悬浮窗主链路不因改动回归（模板库为空时仍走「等待确认」安全路径）。
- [ ] 隐私与边界红线未被触碰（无新权限、无网络、无自动代打）。

---

## 风险与回滚

- **最大风险点在 BUG-2 重构**：向听算法是核心，重构需用既有 110 用例 + 新增用例双重锁死；建议在独立分支进行，`canWin` 行为用测试锁定。
- **阶段一/二互锁**：BUG-2 与 BUG-3 必须同阶段提交，避免出现「半修复」导致回归。
- **回滚策略**：每个步骤一个独立提交，任一阶段 `test` 不过即回退该提交，不影响后续。
