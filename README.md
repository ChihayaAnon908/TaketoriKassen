# TaketoriKassen · 竹取合战复刻

超时空辉夜姬 / 竹取合战的 Minecraft 服务端复刻插件，运行在 **Paper 1.21.1（Java 21）** 上。
包含战斗层（角色 / 武器 / 技能 / 输入 / 冷却 / 表现）与玩法层（3v3 积分赛、PVE 合作、大厅与观众）。

- **8 把武器 / 6 个角色 / 15 种技能类型**，数值全部外置 YAML，游戏内也能直接改（`/taketori editor`）
- 物品身份只认 PDC（PersistentDataContainer）：改名、改 Lore 都不影响识别
- 只用原版粒子与音效：**不需要资源包，也不需要客户端 mod**

## 文档导航

| 文档 | 内容 |
| --- | --- |
| `操作手册.md` | 从零到开一局：安装、场地与大厅划定、指令总表、配置速查、常见问题、上线自检清单 |
| `玩法说明.md` | 3v3 与 PVE 规则、大厅流程、旁观模式、可调参数 |
| `技能清单.md` | 每把武器每个按键的做法、数值与冷却 |
| `技能树.md` | 角色 / 武器 / 技能的整体结构 |
| `效果修改指南.md` | 想改某个效果时该动哪个键 |
| `效果对照表.md` | 配置值 ↔ 对铁甲玩家的实际伤害 |
| `CHANGELOG.md` | 每个版本的改动记录 |

## 特性

### 战斗层

| 能力 | 说明 |
| --- | --- |
| 武器体系 | 8 把武器，载体是原版物品（钻石锹 / 下界合金斧 / 剑 / 钓鱼竿 / 弓 / 盾 / 玻璃板 / 三叉戟） |
| 技能体系 | 15 种技能类型：扇形近战、弹体、冲击波、火箭跳、自身推进、突进、钩索、拉拽、反射、护盾反弹、范围减速致盲、易伤标记、连击叠伤等 |
| 四个按键槽 | 左键 / 右键 / Shift+右键 / Q，逐槽独立冷却；屏幕上方 BossBar 进度条（可改 actionbar 或关闭） |
| 模式切换 | Q 在武器模式间循环（辉夜 锤击⇄火箭、帝 近战⇄远程、雷 防御⇄壁垒、乃依 单发⇄三连射…），真源在玩家档案 |
| 身份与防伪 | PDC 记录角色 / 武器 / 模式 / 实例 / 归属；绑定武器无法放进箱子，也不会被他人捡走 |
| 载体护栏 | 手持插件武器时不会挖方块、铲路、去皮、放置方块、抛钩、投掷三叉戟，Q 不掉落、F 不换副手 |
| 版本适配 | 属性 / 粒子 / 音效 / 药水效果的名字统一走 `VersionAdapter` 的注册表解析，改名不生效只告警不崩服 |

### 玩法层

- **3v3 积分赛**：600 分目标、20 分钟时限；击杀月人 `+3`、击杀玩家 `+10`、拆除基地 `+50`；
  基地占点读条 10 秒、开局 60 秒禁止占点；月人每 9 秒刷新一批、场上最多 15 个。
- **PVE 合作模式**：`/taketori match mode pve` 切换 —— 所有人同一队打月人，不占点，结算独立（达成目标分即「挑战成功」）。
- **友伤保护**：`auto` = PVP 开启（同队互免伤害）/ PVE 关闭；也可强制 `on` / `off`。
- **大厅与分队**：出生点、区域、告示牌（加入 / 旁观 / 选角色 / 回大厅）；点「加入对局」自动随机分队并入队，
  人数达到阈值自动开局；人数不够时管理员可用 `/taketori match force` 强制开局。
- **观众模式**：聊天栏直接给出可点击的 **`[ 退出观战 ]`** 按钮，并定期重发；
  阵亡后自动进入旁观（跳过死亡界面），倒计时结束回到己方出生点复活。
- **跨局战绩**：总得分 / 场次 / 胜场 / 单局最高写入 `data/stats.yml`，`/taketori stats` 查看排行榜。

### 管理工具

- **选区锄**：默认绑定下界合金锄，左键 / 右键点方块划区域，手持时用粒子描出选区边框，
  点到的位置若已在别的区域里会当场提示，避免区域重叠。
- **武器数据编辑 GUI**：`/taketori editor` 打开三层菜单（武器 → 技能槽 → 参数），
  改伤害 / 冷却 / 全部参数后写回 `weapons.yml` 并保留注释。
- **自检与调试**：`/taketori doctor` 一次打印识别链路与名字解析结果；`/taketori debug on` 打开运行时日志。

## 环境要求

| 项 | 要求 |
| --- | --- |
| 服务端 | Paper 1.21.1（1.21.x 亦可，版本敏感的名字经注册表解析） |
| Java | 21 |
| 额外依赖 | 无，只用服务端 API |
| 客户端 | 原版即可，无需资源包或 mod |

## 安装

1. 把 `TaketoriKassen-<版本>.jar` 放进服务端 `plugins/` 目录，重启服务器；
2. 首次启动会生成 `plugins/TaketoriKassen/`：`config.yml`、`weapons.yml`、`characters.yml`、`messages.yml`；
3. 给玩家绑定角色（会自动把该角色武器发放到预留槽位）：`/taketori character <玩家> kaguya`；
4. 用选区锄划好场地与大厅、摆上告示牌，即可开玩（完整步骤见 `操作手册.md`）。

## 指令速查

```
# ---- 玩家 ----
/taketori play                              查看自己的角色与武器
/taketori skills                            查看当前武器四个技能与冷却
/taketori mode                              手动触发 Q 槽技能（模式 / 装备切换）
/taketori lobby join | leave | spectate     加入队列 / 退出 / 旁观
/taketori leave                             退出观战 / 退出队列（观众按钮执行的就是它）
/taketori stats [数量]                       跨局历史排行榜

# ---- 场地（管理员）----
/taketori arena wand                        领选区锄（左键 = 角点 1，右键 = 角点 2）
/taketori arena pos1 | pos2                 用指令设置选区角点（与锄头共用同一份数据）
/taketori arena setminion                   选区设为月人刷新区
/taketori arena setbase <red|blue> [编号]    选区设为某队第 N 个基地（每队 3 个，编号可省略）
/taketori arena setspawn <red|blue>         当前位置设为某队出生点
/taketori arena list                        查看已配置场地与"是否可开局"

# ---- 大厅（管理员）----
/taketori lobby setspawn | pos1 | pos2 | setregion
/taketori lobby addsign <动作> | removesign | list

# ---- 对局（管理员）----
/taketori match start [force] | force | stop | status | mode <pvp|pve>
/taketori team <玩家> <red|blue|none>
/taketori character <玩家> <角色id|none>
/taketori give <玩家> <武器id>

# ---- 维护 ----
/taketori editor [武器id]                    武器数据编辑 GUI
/taketori debug on | off                    运行时调试日志
/taketori doctor                            自检：识别链路与名字解析
/taketori reload                            热重载配置
```

命令统一使用全名 `/taketori`，没有缩写别名。玩家进场流程：**进服 → 大厅 → 点「加入对局」（自动分队）→ 人数够自动开局 → 阵亡旁观 → 倒计时复活**。

## 按键与武器

| 角色 | 武器 | 载体 | 左键 | 右键 | Shift+右键 | Q |
| --- | --- | --- | --- | --- | --- | --- |
| 辉夜 | 火箭锤 | 钻石锹 | 锤击 | 火箭弹 | 喷射推进 | 锤击 / 火箭模式 |
| 帝 | 金棒 | 下界合金斧 | 金棒攻击 | 切换近战 / 远程 | 金棒震地 / 崩山一击 | 模式切换 |
| 彩叶 | 剑 | 下界合金剑 | 剑击（连击叠伤） | 高速突进 | 强化机动（上抛） | 切换到钢丝 |
| 彩叶 | 钢丝 / 苦无 | 钓鱼竿 | —（原版轻击） | 钢丝牵引 | 快速位移 | 切换到剑 |
| 乃依 | 弓 | 弓 | 原版射击 | 原版蓄力 | 切换特殊射击 | 单发 / 三连射 |
| 雷 | 大盾 | 盾牌 | — | 举盾（原版） | 强化防御 | 防御 / 壁垒 |
| 特殊 | 月镜 | 玻璃板 | — | 镜面展开 | 镜光爆发 | 镜面 / 爆发 |
| 特殊 | 冰冻旗鱼 | 三叉戟 | 冰冻近战 | 冰冻弹 | 强化冰冻弹 | 冰冻 / 极寒 |

角色决定能拿到哪些武器与基础属性（血量 / 移速）：辉夜 20、帝 24、彩叶 20、乃依 18、雷 26。
每个按键的「名字 ↔ 实际行为 ↔ 数值」逐条核对见 `技能清单.md` 与 `效果对照表.md`。

设计上的一条硬规则：**手里拿着插件武器时永远不参与方块交互**（不挖、不铲、不去皮、不放置、不抛钩、不投掷）；
只有某个槽位确实绑定了技能时才取消对应的原版行为，所以弓的原版蓄力、盾的原版举盾天然不受影响。
左键的「打实体」与「空挥」是两个不同事件，用 2 tick 窗口去重，避免一次挥击结算两次。

## 配置

### config.yml

| 段 | 关键项 | 默认 |
| --- | --- | --- |
| `match` | `mode` / `score-to-win` / `time-limit-minutes` / `team-size` / `respawn-delay-seconds` / `keep-inventory` | pvp / 600 / 20 / 3 / 5 / true |
| `scoring` | `minion-kill` / `player-kill` / `base-capture` | 3 / 10 / 50 |
| `combat` | `kill-heal` / `minion-kill-heal` / `friendly-fire-protection` | 6.0 / 0.0 / auto |
| `minion` | `health` / `iron-armor` / `interval-seconds` / `per-spawn` / `max-alive` | 40 / true / 9 / 3 / 15 |
| `base` | `capture-seconds` / `capture-delay-seconds` / `decay-per-second` / `multi-player-bonus` | 10 / 60 / 0.5 / true |
| `lobby` | `auto-start-players` / `teleport-on-join` / `protect` / `return-after-match` | 6 / true / true / true |
| `setup-wand` | `enabled` / `material` / `outline-particles` / `give-on-join` | true / NETHERITE_HOE / true / false |
| `feedback` | `actionbar` / `particles` / `sounds` / `cooldown-display` | true / true / true / bossbar |
| `items` | `soulbound` / `auto-give-on-join` / `allow-drop` | true / false / false |
| `input` | `q-mode` / `weapon-slots` | drop / [0,1,2,3] |
| `debug` | 调试日志总开关 | false |

改完执行 `/taketori reload` 生效（`/taketori qmode`、`/taketori debug` 可在运行时临时切换）。

### weapons.yml

数值全部在这里，改配置不需要重新编译；游戏内也能改（`/taketori editor`，写回本文件且保留注释）。

- `attributes.attack-damage` / `attack-speed` 写的是**最终值**：实现会读该材质在原版的基础属性、算出差值再追加，
  写 `11.0` 面板上就是 `11.0`，不需要自己心算原版基础值。
- `modes.<MODE>.skills` 会**整段替换**武器级同名槽位，用来表达 Q 切换模式后的差异。
- `hit-effects` 是「命中附加」段（例如乃依的箭随机挂负面效果：`arrow-debuffs`）。
- 技能 `params` 的键名由实现类读取，写错的键会在启动时被配置校验点名。

### 技能类型

| type | 用途 | 代表参数 |
| --- | --- | --- |
| `melee_smash` | 前方扇形近战（可连击叠伤 / 减速 / 定身） | `damage` `range` `angle` `combo-bonus` `combo-cap` |
| `projectile` | 发射弹体（爆炸 / 点燃 / 减速 / 击退 / 距离衰减） | `projectile` `speed` `damage` `radius` `min-falloff` |
| `shockwave` | 以自身为中心的冲击波 | `damage` `radius` `launch` `min-falloff` |
| `rocket_jump` | 火箭跳（上抛 + 后坐 + 免摔窗口） | `upward` `backward` `fall-immunity-ticks` |
| `self_boost` | 自身推进（药水机动 / 传送 / 速度三种模式） | `power` `upward` `boost-mode` |
| `blink` | 安全落点瞬移 | `distance` `min-distance` |
| `grapple` | 钩索牵引 | `range` `power` `upward` |
| `pull` | 把目标拉向自己，或钩方块把自己拉过去 | `mode` `range` `power` `duration` |
| `reflect` | 反射窗口：把飞行物弹回去 | `duration-ticks` `speed-multiplier` |
| `shield_guard` | 强化防御窗口（抗性 + 吸收 + 反弹） | `duration-ticks` `resistance-amplifier` `reflect-ratio` |
| `mirror_skill` | 镜面防御窗口 | `duration-ticks` `absorption` `reflect-ratio` |
| `mirror_burst` | 范围减速 + 致盲 | `radius` `damage` `slow-amplifier` `blindness-ticks` |
| `special_shot_toggle` | 弓的特殊射击开关（含三连射参数） | `damage-multiplier` `mark-bonus` `volley-spread` |
| `mode_switch` | Q 循环切换武器模式 | — |
| `equip_switch` | Q 切换装备（彩叶 剑 ⇄ 钢丝） | `target` |

新增一种技能类型 = 加一个实现类 + 在 `TaketoriPlugin#registerSkills` 注册一行，输入层与冷却层不用改动。

## 构建

### Gradle（有网络时）

```bash
gradle build          # 产物在 build/libs/
```

`build.gradle.kts` 内置两道护栏：`checkCorePurity`（`core/` 层一旦出现 Bukkit / NMS 引用就构建失败）
与 `checkParamKeys`（技能读取的参数键必须在校验表里注册）。

### 离线脚本（无网络、无 Gradle 时）

```powershell
pwsh -File build-offline.ps1
pwsh -File build-offline.ps1 -LibsDirs "<依赖目录1>","<依赖目录2>"   # 依赖不在默认位置时
pwsh -File build-offline.ps1 -JavaHome "<JDK 21 安装目录>"            # 没配 JAVA_HOME 时
```

依赖 jar 的查找顺序：仓库下的 `libs/adv19` → 仓库下的 `libs/`（同名 artifact 取先找到的那个）；
也可以用环境变量 `TAKETORI_LIBS`（多个目录用分号分隔）或 `-LibsDirs` 指定别的位置。
脚本执行与 Gradle 等价的检查后编译打包，产物为 `build/dist/TaketoriKassen-<版本>.jar`。

只需要 **JDK 21** 与任意一份 **paper-api 1.21.x** jar。开发环境用 paper-api 1.21.4 编译、按 Paper 1.21.1 运行。

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

版本号只有一处来源 —— `gradle.properties` 的 `version=`（当前 `0.7.6`）。
`plugin.yml` 里的 `${version}`、产物 jar 的文件名、启动日志里的版本号都由它派生，发版只改这一行。

## 项目结构

```
core/      纯 Java，零 Bukkit 依赖（受构建期静态检查保护）
           character（角色 / 玩家档案）· weapon（武器 / 模式）· skill（槽位 / 技能定义）· match（队伍 / 规则）
paper/     Bukkit 适配
           listener（输入 · 载体护栏 · 战斗结算 · 弹体命中 · 生命周期 · 选区锄 · 编辑器）
           skill/impl（15 种技能实现）· item（PDC 身份 / 物品工厂）· effect（粒子音效）
           match（对局 · 场地 · 占点 · 月人 · 记分板 · 旁观 · 战绩）· lobby（大厅 / 告示牌 / 角色菜单）
           setup（选区锄）· editor（武器数据编辑）· command · scheduler · state
version/   版本适配：属性 / 粒子 / 音效 / 药水的名字解析（注册表 + 规范化匹配 + 枚举回退）
data/      玩家数据持久化（接口 + YAML 实现）
config/    配置加载 / 迁移 / 校验 / 文案
tools/     不依赖服务端的离线回归测试
```

`core` 层零 Bukkit 依赖是硬约束：将来升级 MC 版本时，业务逻辑不需要返工。

## 已知限制

- **无资源包**：技能辨识依赖粒子与音效，没有自定义动作动画；
- **名字解析失败只告警**：某个属性 / 粒子 / 音效 / 药水在当前服务端不存在时会被跳过（`/taketori doctor` 可见），不会崩服；
- **未适配 Folia**：调度已集中在 `SchedulerAdapter` 单一出口，便于后续接入；
- **实机行为请按 `操作手册.md` 的上线自检清单过一遍**：开发期验证方式是离线 `javac` 编译、
  两道构建护栏，以及 `tools/` 下三个不依赖服务端的回归测试。

## 许可

本仓库代码以 **MIT 许可证**发布，全文见 `LICENSE`。

游戏《竹取合战 / 超时空辉夜姬》的名称、角色与设定归原作者所有。本仓库是粉丝向的技术复刻实现，
**不包含原作的任何美术、音频或其他素材** —— 所有技能表现都用 Minecraft 原版粒子与音效，
也不需要资源包或客户端 mod。
