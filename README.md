# TaketoriKassen · 竹取合战复刻（Paper 1.21.1）

> 超时空辉夜姬 / 竹取合战的 Minecraft 服务端复刻插件：**战斗层**（角色 / 武器 / 技能 / 输入 / 冷却 / 表现）
> \+ **玩法层**（3v3 积分赛与 PVE 合作、大厅与随机分队、观众模式、基地占点、月人刷新）。

- 8 把武器 / 6 个角色 / 15 种技能类型，数值全部外置 YAML，游戏内也能改（`/taketori editor`）；
- 物品身份只认 PDC（PersistentDataContainer），改名改 Lore 不影响识别；
- 只用原版粒子与音效，**不需要资源包、不需要客户端 mod**。

### 文档导航

| 文档 | 内容 |
| --- | --- |
| `操作手册.md` | 从零到开一局：安装、场地与大厅划定、指令总表、配置速查、常见问题 |
| `玩法说明.md` | 3v3 与 PVE 规则、大厅流程、旁观模式、可调参数 |
| `技能清单.md` | 每把武器每个按键做了什么、数值与冷却 |
| `技能树.md` | 角色 / 武器 / 技能的整体结构 |
| `效果修改指南.md` | 想改某个效果时该动哪个键 |
| `效果对照表.md` | 配置值 ↔ 对铁甲玩家的实际伤害 |
| `CHANGELOG.md` | 每个版本的改动记录 |

---

## 1. 构建

### 方式 A：Gradle（有网络时推荐）

```bash
cd taketori-kassen
gradle build          # 产物在 build/libs/
```

`build.gradle.kts` 已内置兼容护栏任务 `checkCorePurity`：`core/` 层一旦出现
`org.bukkit` / `io.papermc` / `net.minecraft` / `craftbukkit` 引用，**构建直接失败**。

### 方式 B：离线脚本（无网络、无 Gradle 时）

```powershell
pwsh -File build-offline.ps1
pwsh -File build-offline.ps1 -LibsDirs "<依赖目录1>","<依赖目录2>"   # 依赖不在默认位置时
pwsh -File build-offline.ps1 -JavaHome "<JDK 21 安装目录>"            # 没配 JAVA_HOME 时
```

依赖 jar 的查找顺序：仓库下的 `libs/adv19` → 仓库下的 `libs/`（同名 artifact 取先找到的那个）；
也可以用环境变量 `TAKETORI_LIBS`（多个目录用分号分隔）或 `-LibsDirs` 指定别的位置。
**换机器时**把 paper-api 与 adventure 的 jar 丢进仓库下的 `libs/` 目录即可。
脚本会执行与 Gradle 等价的 core 纯度检查与技能参数键检查，然后 `javac` 编译并打包：

```
build/dist/TaketoriKassen-0.7.6.jar
```

> 离线脚本只要求 **JDK 21** 与任意一份 **paper-api jar**（1.21.x）。
> 开发环境用 **paper-api 1.21.4** 编译、按 Paper 1.21.1 运行：所有版本敏感的名字
> （属性 / 粒子 / 音效 / 药水效果）都经 `VersionAdapter` 的注册表解析，
> 因此同一份源码在 1.21.1 与 1.21.4 上都能编译与运行。

### 离线回归测试（不需要服务端）

```powershell
javac -encoding UTF-8 --release 21 -d build/test-classes `
  src/main/java/com/taketori/kassen/core/match/TeamId.java `
  src/main/java/com/taketori/kassen/core/match/BaseArgParser.java `
  src/main/java/com/taketori/kassen/paper/editor/WeaponYamlEditor.java `
  tools/*.java
java -cp build/test-classes BaseArgParserTest        # 指令参数解析
java -cp build/test-classes WeaponYamlEditorTest     # weapons.yml 保留注释的写回
java -cp "<adventure jars>;build/test-classes" MiniMessageClickTest   # 聊天栏按钮
```

### 版本号

版本号**只有一处来源** —— `gradle.properties` 的 `version=`：

```properties
version=0.7.6
```

`plugin.yml` 里的 `${version}` 占位符（Gradle 用 `processResources` 展开，离线脚本在打包时替换）、
产物 jar 的文件名、以及启动日志里的版本号都由它派生。**发版只改这一行**，
产物会输出为 `build/dist/TaketoriKassen-<version>.jar`。
每版的改动记录在 `CHANGELOG.md`。

---

## 2. 安装与上手

1. 把 jar 放进服务端 `plugins/`，重启服务器；
2. 首次启动会生成 `plugins/TaketoriKassen/`：`config.yml`、`weapons.yml`、`characters.yml`、`messages.yml`；
3. 给玩家绑定角色（会自动发放该角色武器到预留槽位）：

```
/taketori character <玩家> kaguya
/taketori give <玩家> frozen_swordfish      # 额外发放某把武器
/taketori play                              # 玩家查看自己的角色与武器
/taketori skills                            # 查看当前武器四个技能与冷却
/taketori debug on                          # 调试日志（[input]/[skill]/[combat]）
/taketori editor                            # 武器数据编辑 GUI（改数值 → 写回 weapons.yml）
/taketori reload                            # 改完配置热重载
```

### 玩法（3v3 积分赛）

```
/taketori arena wand                        # 领选区锄（左键 = 角点 1，右键 = 角点 2）
/taketori arena pos1 / pos2                 # 也可以用指令划定选区（两者共用同一份数据）
/taketori arena setminion                   # 选区设为小怪刷新区
/taketori arena setbase <red|blue> [1-3]     # 选区设为某队第 N 个基地（每队 3 个，编号可省略）
/taketori arena setspawn <red|blue>          # 当前位置设为某队出生点
/taketori arena list                        # 检查"是否可开局"

/taketori lobby setspawn                    # 大厅出生点
/taketori lobby addsign join                # 准星对准告示牌绑定动作
/taketori lobby addsign spectate
/taketori lobby addsign character
/taketori lobby list                        # 大厅配置与队列人数

/taketori match start / status / stop        # 开始 / 状态 / 结束
/taketori match force                        # 人不够也强制开局（大厅里的人自动分队）
/taketori leave                              # 退出观战 / 退出队列（观众聊天栏按钮执行的就是它）
/taketori team <玩家> <red|blue>             # 手动分队（也可以让玩家点告示牌随机分）
/taketori stats                             # 跨局历史排行榜
```

玩家进场流程：**进服 → 大厅 → 点「加入对局」告示牌（自动随机分队）→ 人数够自动开局 → 阵亡后旁观 → 倒计时复活**。
规则、告示牌动作与旁观说明详见 `玩法说明.md`。

命令**没有缩写别名**，统一使用全名 `/taketori`。

---

## 3. 按键与武器对照（全部由 weapons.yml 驱动）

| 角色 | 武器 | 载体 | 左键 | 右键 | Shift+右键 | Q |
| --- | --- | --- | --- | --- | --- | --- |
| 辉夜 | 火箭锤 | 钻石锹 | 锤击 | 火箭弹 | 喷射推进 | 锤击/火箭模式 |
| 帝 | 金棒 | 下界合金斧 | 金棒攻击 | 切换近战/远程 | 金棒震地 / 崩山一击 | 模式切换 |
| 彩叶 | 剑 | 下界合金剑 | 剑击 | 高速突进 | 强化机动（上抛） | 切换到钢丝 |
| 彩叶 | 钢丝/苦无 | 钓鱼竿 | —（映射表无技能） | 钢丝牵引 | 快速位移 | 切换到剑 |
| 乃依 | 弓 | 弓 | 原版射击 | 原版蓄力 | 切换特殊射击 | 单发射击/三连射（命中随机挂负面效果） |
| 雷 | 大盾 | 盾牌 | — | 举盾（原版） | 强化防御 | 防御/壁垒 |
| 特殊 | 月镜 | 玻璃板 | — | 镜面展开 | 镜光爆发 | 镜面/爆发 |
| 特殊 | 冰冻旗鱼 | 三叉戟 | 冰冻近战 | 冰冻弹 | 强化冰冻弹 | 冰冻/极寒 |

> 每个按键的"名字 ↔ 实际行为 ↔ 数值"逐条核对见 `效果对照表.md`。

关键设计（对应计划 §3.4）：

- **手里拿着插件武器时永远不参与方块交互**：不会挖方块、铲路、斧头去皮、放置玻璃板、抛钩、投掷三叉戟、把武器丢出去或换到副手。
- **某个槽位绑定了技能才取消原版行为**，没绑定就放行——所以弓的原版蓄力、盾的原版举盾天然不受影响。
- **左键的"打实体"与"空挥"是两个事件**，用 2 tick 窗口去重，避免同一次挥击结算两次。

---

## 4. 配置

### config.yml（节选）

| 段 | 关键项 | 说明 |
| --- | --- | --- |
| `match` | `mode` / `score-to-win` / `time-limit-minutes` / `team-size` / `respawn-delay-seconds` / `keep-inventory` | `mode: pvp`（红蓝对抗）或 `pve`（合作打月人）；默认 600 分 / 20 分钟 / 每队 3 人 / 5 秒复活 |
| `scoring` | `minion-kill` / `player-kill` / `base-capture` | 3 / 10 / 50 |
| `combat` | `kill-heal` / `minion-kill-heal` / `friendly-fire-protection` | 击杀回血（2 点 = 1 颗心）；友伤保护 `auto` = PVP 开 / PVE 关 |
| `minion` | `health` / `iron-armor` / `interval-seconds` / `per-spawn` / `max-alive` | 月人（40 血铁甲僵尸）的刷新节奏与上限 |
| `base` | `capture-seconds` / `capture-delay-seconds` / `decay-per-second` / `multi-player-bonus` | 占点读条、开局保护期、无人占点时的衰减 |
| `lobby` | `auto-start-players` / `teleport-on-join` / `protect` / `return-after-match` | 大厅与自动开局 |
| `setup-wand` | `enabled` / `material` / `outline-particles` / `give-on-join` | 选区锄（默认绑定下界合金锄） |
| `feedback` | `actionbar` / `particles` / `sounds` / `cooldown-display` | 表现层开关与冷却条形式（`bossbar` / `actionbar` / `both` / `none`） |
| `items` | `soulbound` / `auto-give-on-join` / `allow-drop` | 武器绑定与进服自动补发 |
| `input` | `q-mode` / `weapon-slots` | `drop`（默认，Q 触发 q 槽技能）/ `held-slot` / `none` |
| `debug` | 调试日志总开关 | 也可以用 `/taketori debug on` 运行时打开 |

### weapons.yml

数值**全部在这里**，改配置不需要重新编译；游戏里也能改（`/taketori editor`，会写回本文件并保留注释）。

- `attributes.attack-damage` / `attack-speed` 写的是**最终值**：实现会读该材质在原版的基础属性、算出差值再追加，
  所以你写 `11.0`，玩家面板上就是 `11.0`，不需要自己心算原版基础值。
- `modes.<MODE>.skills` 会**整段替换**武器级同名槽位，用来表达 Q 切换模式后的差异。
- `hit-effects` 是"命中附加"段（例如乃依的箭随机挂负面效果：`arrow-debuffs`）。
- 技能 `params` 的名字就是实现类读取的键；写错的键会被启动时的配置校验点名。

### 技能类型与参数

| type | 用途 | 主要参数 |
| --- | --- | --- |
| `melee_smash` | 前方扇形近战 | `damage` `range` `knockback` `angle` `slow-duration` `slow-amplifier` `particle` `sound` |
| `projectile` | 弹体发射 | `projectile` `speed` `damage` `radius` `ignite` `slow-duration` `slow-amplifier` `knockback` `sound` `hit-sound` `particle` |
| `self_boost` | 自身推进 | `power` `upward` `duration-ticks` `fall-immunity` |
| `blink` | 安全落点瞬移 | `distance` |
| `grapple` | 钢丝牵引（实体优先，其次钩方块） | `range` `power` `upward` |
| `special_shot_toggle` | 弓的特殊射击开关 | `damage-multiplier` `speed-multiplier` |
| `shield_guard` | 强化防御窗口 | `duration-ticks` `resistance-amplifier` `absorption` `reflect-ratio` |
| `mirror_skill` | 镜面防御窗口 | `duration-ticks` `absorption` `reflect-ratio` |
| `mirror_burst` | 范围减速 + 致盲 | `radius` `damage` `duration-ticks` `slow-amplifier` `blindness-ticks` |
| `mode_switch` | Q 循环切换模式 | — |
| `equip_switch` | Q 切换装备（彩叶） | `target` |

新增技能类型 = 加一个实现类 + 在 `TaketoriPlugin#registerSkills` 注册一行，输入层与冷却层不用动。

---

## 5. 代码结构

```
core/      纯 Java，零 Bukkit 依赖（受构建期静态检查保护）
           character（角色 / 玩家档案）· weapon（武器 / 模式）· skill（槽位 / 技能定义）· cooldown
paper/     Bukkit 适配
           listener（Input 输入 · CarrierGuard 载体护栏 · Combat 结算 · Projectile 命中 · Player 生命周期）
           skill/impl（11 个技能实现）· item（PDC 身份 / 物品工厂）· effect（粒子音效）
           command · scheduler · state
version/   版本适配：属性 / 粒子 / 音效 / 药水效果的名字解析（注册表 + 规范化匹配 + 枚举回退）
data/      PlayerDataStore 接口 + YAML 实现
config/    ConfigManager / Messages
```

`core` 层零 Bukkit 依赖是硬约束：这样将来升级 MC 版本时，业务逻辑不需要返工。

---

## 6. 已实现 / 未实现

战斗层

- 插件骨架、命令组、配置热重载、配置校验（角色引用不存在的武器会在加载期报错）
- PDC 身份闭环：8 把武器生成、回读、绑定归属、防伪（改名改 Lore 不影响识别）
- 统一输入层 + 载体护栏 + 左键去重 + Q/F 键处理
- 技能框架 + 15 种技能类型 + 8 把武器全部按键映射
- 冷却系统（BossBar 冷却条）、模式切换、防御窗口与反弹、易伤标记、连击叠伤
- 击杀回血；乃依箭矢随机负面效果；数值 100% 外置 YAML；架构护栏（core 纯度检查）

玩法层

- 3v3 积分赛：600 分目标、击杀 / 拆家计分、基地占点与开局保护期、月人刷新与上限
- **PVE 合作模式**（`/taketori match mode pve`）：所有人同一队打月人、不占点、独立结算
- 友伤保护策略（`auto` = PVP 开 / PVE 关，可强制 `on` / `off`）
- 大厅：出生点 / 区域 / 告示牌（加入、旁观、选角色、回大厅），队列满员自动开局，`match force` 强制开局
- 观众模式（聊天栏可点击「退出观战」）、死亡旁观与自动复活、跨局战绩 `stats.yml`

管理工具

- **选区锄**（绑定下界合金锄）：左键 / 右键点方块划区域，手持时粒子描出边框，防区域重叠提示
- **武器数据编辑 GUI**（`/taketori editor`）：三层菜单改伤害 / 冷却 / 全部参数，写回 `weapons.yml` 且保留注释
- `/taketori doctor` 自检、`/taketori debug on` 运行时调试日志

未实现（后续）

- 资源包贴图（当前只用原版粒子与音效）
- Folia 兼容（调度已收在 `SchedulerAdapter` 单一出口）
- 多版本构建矩阵

---

## 7. 手动测试清单（上服务器后逐项确认）

身份与物品

1. `/taketori give <你> kaguya_hammer` → 物品带正确名称与 Lore，`/taketori debug` 能看到 `weapon=kaguya_hammer`；
2. 用铁砧改名、用命令改 Lore 后仍然能识别（识别只看 PDC）；
3. 重启服务器后物品身份不变。

载体护栏（**最容易出问题的一项，逐条确认**）

4. 手持插件武器左键地面 → 不破坏方块；
5. 手持钻石锹右键草方块 → 不产生土径；手持斧右键原木 → 不去皮；
6. 手持玻璃板右键 → 不放置方块；
7. 手持钓鱼竿右键 → 不抛钩（无钩子实体）；
8. 手持三叉戟右键 → 不投出三叉戟；
9. 按 Q → 物品不掉落，且触发模式/装备切换；按 F → 武器不被换到副手；
10. 绑定武器无法放进箱子、无法被其他玩家捡走。

技能与手感

11. 辉夜：左键锤击有击退与粒子（**连点不会加伤害**——攻击冷却未满不结算），右键发射火箭弹（命中点燃实体但**不烧地形**），Shift+右键喷射推进（**不是缓降**，是免摔落伤害窗口），Q 切换模式后右键更强；
12. 帝：右键切到远程模式后右键变弹体，Shift+右键特殊技能；
13. 彩叶：剑的右键突进、Shift+右键高跳；Q 切到钢丝；钢丝右键牵引（对实体与方块都试）、Shift+右键位移；
14. 乃依：**原版拉弓射击仍然正常**，Shift+右键切换特殊射击后箭更快、伤害更高；Q 切到"三连射"后会**真的射出三支箭**；
    **每次射中敌人还会随机附加一种负面效果**（减速 / 虚弱 / 中毒 / 失明 / 饥饿 / 反胃，4 秒；命中队友不触发）；
15. 雷：**原版举盾仍然正常**，Shift+右键强化防御后受到近战会反弹伤害；
16. 月镜：右键镜面展开、Shift+右键范围减速致盲；
17. 冰冻旗鱼：右键冰冻弹命中后目标减速，Shift+右键强化版范围更大；
18. 冷却提示：连续按同一技能会显示 `冷却中：x.xs`。

稳定性

19. 反复切换角色 / 重载配置 / 退出重进后无异常，冷却与状态被正确清理；
20. 8 人同局多人混战观察 TPS 是否稳定。

---

## 8. 排查：按技能没反应 / debug 没输出

### Q 键（模式切换）怎么启用

1. 确认 `plugins/TaketoriKassen/config.yml`：
   ```yaml
   input:
     q-mode: drop      # drop=触发 q 槽技能；held-slot=只切快捷栏；none=只拦截丢弃
   ```
2. 生效方式二选一：
   - 改完文件执行 `/taketori reload`；
   - 或者**根本不用改文件**：直接 `/taketori qmode drop`（运行时生效，重启后回到文件里的值）。
3. 验证：`/taketori doctor` 应显示
   `Q 键行为: q-mode=drop（触发武器 q 槽技能：模式切换 / 装备切换）`
4. 手持对应武器按 Q。**没有反应时按顺序查**：
   - `/taketori mode` 手动触发一次，看返回：
     `SUCCESS` = 技能正常（那就是 Q 键被别的插件吞了）；
     `NOT_BOUND` = 这把武器的 `q` 槽没绑定技能（配置问题）；
     `ON_COOLDOWN` = 还在冷却。
   - 按 Q 只有**多模式武器**才是"模式切换"：辉夜、帝、雷、月镜、冰冻旗鱼、乃依；
     彩叶的 Q 是"切换装备"（剑 ⇄ 钢丝），前提是你身上有另一件本角色武器。
5. 从 0.3.1 起，启动时会自动把旧的 `held-slot` 迁移为 `drop`，日志里会出现 `[配置迁移]` 提示。

### 第 1 步：确认插件加载成功

控制台应出现两行：

```
TaketoriKassen v0.2.1 已启用：8 把武器 / 6 个角色 / 11 种技能类型（适配层 default）
debug=false（/taketori debug on 可临时开启；/taketori doctor 可自检识别与名字解析）
```

没有这两行 → 插件根本没加载，先看启动时的报错。

### 第 2 步：打开调试（不需要改文件、不需要重启）

```
/taketori debug on
```

然后按一次技能，控制台应**按顺序**出现两行：

```
[input] 玩家名 RIGHT_CLICK_AIR 主手=DIAMOND_SHOVEL weapon=kaguya_hammer → 派发 right → projectile
[skill] 玩家名 weapon=kaguya_hammer slot=right mode=HAMMER type=projectile -> SUCCESS
```

**缺哪一行，问题就在哪一环：**

| 现象 | 含义 | 处理 |
| --- | --- | --- |
| 连 `[input]` 都没有 | 交互事件没到达插件 | 被别的插件取消了（领地 / 反作弊 / 战斗类）；或你按的键本来就没绑定技能 |
| `[input] … 主手不是插件武器` | PDC 读不到身份 | 用 `/taketori character` 或 `/taketori give` 重新领取武器 |
| `[input] … 槽位 xxx 未绑定技能` | 该键设计上走原版 | 正常（弓的左右键、盾的左右键、钢丝左键） |
| `[input]` 有、`[skill]` 没有 | 派发前被拦下 | 看 `/taketori doctor` 的模式与槽位绑定 |
| `[skill] … -> ON_COOLDOWN` | 冷却中 | 正常 |
| `[skill] … -> UNKNOWN_TYPE` | 配置里的 `type` 拼错 | 启动日志的"配置校验"会点名 |
| `[skill] … -> FAILED` | 技能内部抛异常 | 同一行附近会有堆栈 |
| 左键打人没伤害 | 见 `[combat]` 日志 | 会逐行说明：内部伤害？插件武器？left 槽绑定？ |
| 推进后掉下来摔伤 | 免摔窗口只有对应 tick | 调 `fall-immunity-ticks` |
| 突进 / 位移"按了没动" | ① 创造模式飞行时客户端会覆盖速度；② 贴地时水平速度被地面摩擦吃掉；③ **服务端的位置纠正会清除 `setVelocity` 的速度**（实测 1.38 格/tick 只走出 1.18 格） | 0.4.2 起默认改用 **`boost-mode: teleport`**（逐 tick 传送，服务端权威，不依赖客户端接受速度）；嫌不够远就调 `power` / `boost-ticks`；想换回速度推进就加一行 `boost-mode: velocity` |
| 震地 / 冲击波"没效果" | 它是范围技能，半径内没有敌人时只有粒子音效 | 0.4.0 起命中 0 个目标会给出明确提示 |

### 第 3 步：`/taketori doctor` 一次看全

```
/taketori doctor
```

报告内容：插件版本、debug 状态、已加载武器/角色/技能数、`config` 关键值、
**名字解析能力**（属性 / 粒子 / 音效 / 药水，逐个显示 OK 或"解析失败"）、
你的角色绑定、主手物品与 PDC、当前模式、四个槽位的绑定与冷却、配置校验结果。

两个重点：

- **名字解析任何一项显示"解析失败"** → 对应的属性 / 粒子 / 音效 / 药水**不会生效**（插件不崩，但效果缺失），
  需要把该名字改成当前服务端存在的那一个。
- **"是否插件武器: 否"** → 识别链路断了，重新领取武器即可。

---

## 9. 已知限制与风险

- **实机验证请以第 7 节清单为准**：本机没有 Paper 服务端，开发期的验证方式是
  离线 `javac` 编译（74 源文件 / 94 class）、`checkCorePurity` 与 `checkParamKeys` 两道护栏、
  以及 `tools/` 下三个不依赖服务端的回归测试。**上服务器请先过一遍第 7 节清单。**
- 属性采用"额外加成"语义，配置值不是最终攻击力；
- 无客户端 Mod，因此没有自定义动作动画，技能辨识依赖粒子与音效；
- 若某版本无法解析某个属性/粒子/音效名字，会打 warning 并跳过，不会导致插件崩溃。
