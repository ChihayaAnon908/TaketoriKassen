# 竹取合战 · TaketoriKassen

超时空辉夜姬「竹取合战」的 Minecraft 服务端复刻插件。

**9 名角色 · 17 把武器 · 15 种技能**，用原版事件接管按键做出技能战斗层，再叠上 **3v3 积分赛** 与 **PVE 月人入侵**两种玩法，含完整的大厅、分队、观战与跨局战绩流程。

| 项 | 说明 |
| --- | --- |
| 服务端 | Paper **1.21.4** |
| Java | 21 |
| 客户端 | 原版即可，不需要资源包或 mod |
| 额外依赖 | 无 |
| 许可 | MIT |

---

## 一、功能

### 战斗层

- **17 把武器 / 9 名角色 / 15 种技能类型**，全部数值外置在 `weapons.yml`、`characters.yml`，游戏内也能改（`/taketori editor`）。
- **四个按键槽**：左键 / 右键 / 第三槽 / Q，逐槽独立冷却；屏幕上方 BossBar 冷却进度条。
- **第三槽触发键可配**：默认双击潜行键，也可换成潜行 + Q、双击右键、F 键、潜行 + 右键，支持多选。
- **模式切换**：Q 在武器模式间循环（锤击 ⇄ 火箭、近战 ⇄ 远程、镜面 ⇄ 爆发…），真源在玩家档案。
- **载体护栏**：手持插件武器时不挖方块、不铲路、不去皮、不放置、不抛钩、不投掷、不丢弃、不换副手、不驯服实体。
- **身份与防伪**：PDC 记录角色 / 武器 / 模式 / 实例 / 归属；绑定武器不能放进箱子，也不会被他人捡走。
- **版本适配**：属性 / 粒子 / 音效 / 药水效果的名字统一走 `VersionAdapter` 的注册表解析，改名不生效只告警不崩服。

### 玩法层

- **3v3 积分赛**：600 分目标、20 分钟时限；击杀月人 `+3`、击杀玩家 `+10`、拆除基地 `+50`；基地占点读条 10 秒、开局 60 秒保护期。
- **PVE 月人入侵**：所有人同一队打月人，含**保卫据点**、**五大波次精英潮**、**三档难度**与**精英随人数变强**（详见第四节）。
- **大厅与分队**：出生点、区域、告示牌（加入 / 旁观 / 选角色 / 回大厅）；点「加入对局」自动随机分队入队，人数达阈值自动开局。
- **告示牌指向界面**：点击告示牌打开对应 GUI（玩家菜单 / 角色菜单 / 排行榜），具体操作由界面按钮完成。
- **菜单时钟**：发给玩家的一个道具，右键打开玩家菜单（默认进服发放、丢不掉）。
- **观众模式**：聊天栏给出可点击的「退出观战」按钮并定期重发；阵亡自动进入旁观，倒计时结束回己方出生点复活。
- **跨局战绩**：总积分 / 对局数 / 胜场 / 击杀 / 月人击杀 / 拆家 / 死亡 / 单局最高写入 `data/stats.yml`，`/taketori ranks` 图形查看。

### 管理工具

- **选区锄**：默认绑定下界合金锄，左键 / 右键点方块划区域，手持时粒子描出边框，区域重叠会当场提示。
- **道具点工具**：第二种选区工具（默认结构空位），划道具刷新点用。
- **删除已划区域**：`/taketori admin` 里点条目即删（基地 / 月人刷新区 / 道具点 / 据点），命令行形式见第五节。
- **武器数据编辑 GUI**：`/taketori editor` 三层菜单（武器 → 技能槽 → 参数），改完写回 `weapons.yml` 并保留注释。
- **管理员菜单**：`/taketori admin` 把对局控制、场地、大厅、维护指令做成按钮。
- **角色分配**：同一队伍不允许出现相同角色；同队抢角色时按玩家的**隐性标签权重**裁决。
- **自检**：`/taketori doctor` 一次报告识别链路、名字解析、配置校验与多世界范围；`/taketori keys` 回放最近的原始按键事件。

### 多世界兼容

给 Multiverse 之类的多世界服务器用，两项都默认收窄、可一键恢复旧行为：

- **进服送大厅只接管白名单世界**（默认仅大厅出生点所在的世界，其它世界位置不变）；
- **消息播报只发给消息所属世界**（对局播报 → 对局世界，大厅播报 → 大厅世界）。

---

## 二、安装

1. 把 `TaketoriKassen-1.0.0.jar` 放进服务端 `plugins/` 目录，重启服务器。
2. 首次启动会在 `plugins/TaketoriKassen/` 生成 `config.yml`、`weapons.yml`、`characters.yml`、`messages.yml`。
3. 控制台出现下面这行即加载成功：

```
TaketoriKassen v1.0.0 已启用：17 把武器 / 9 个角色 / 15 种技能类型（适配层 default）
```

| 权限 | 默认 | 用途 |
| --- | --- | --- |
| `taketori.play` | 所有人 | 玩家指令：菜单、选角色、加入队列、观战、技能查询 |
| `taketori.admin` | OP | 管理指令：场地、大厅、分队、开局、发武器、编辑器、重载、调试 |

> 插件的 `api-version` 声明为 `1.21.4`，服务端低于该版本会拒绝加载。

---

## 三、游戏内设置流程

从装好插件到能开一局，按顺序做完这九步即可（全部在游戏内执行，不需要改文件）。

### 1. 领选区锄

```
/taketori arena wand
```

### 2. 划月人刷新区

用锄头**左键点区域一角**、**右键点对角**，然后：

```
/taketori arena setminion
```

可配多个刷新区（`setminion 2`、`setminion 3`…），月人在其中随机分布。

### 3. 划双方基地

每个基地都用锄头点两个对角，然后：

```
/taketori arena setbase red 1
/taketori arena setbase red 2
/taketori arena setbase red 3
/taketori arena setbase blue 1
/taketori arena setbase blue 2
/taketori arena setbase blue 3
```

每队数量由 `base.count-per-team` 决定（默认 3，可写 1~16 或 `auto` 表示以实际划定为定）；编号可省略，会自动接下一个空位。建议每个基地 5×5 以上。

### 4. 设双方出生点

站到位置上执行（用的是**你的站位**，不是选区）：

```
/taketori arena setspawn red
/taketori arena setspawn blue
```

### 5. 设大厅

站到大厅出生点执行，再用锄头划出大厅范围：

```
/taketori lobby setspawn
/taketori lobby pos1        # 站在大厅一角
/taketori lobby pos2        # 走到对角
/taketori lobby setregion
```

### 6. 摆告示牌

放好牌子，准星对着牌子执行（6 格内）：

```
/taketori lobby addsign join        # 加入对局
/taketori lobby addsign spectate    # 旁观
/taketori lobby addsign character   # 选角色
/taketori lobby addsign menu        # 玩家菜单
/taketori lobby addsign ranks       # 排行榜
```

可绑动作：`join` / `leave` / `spectate` / `character` / `character:<角色id>` / `menu` / `ranks` / `lobby`。

### 7. 检查配置

```
/taketori arena list
/taketori lobby list
```

`arena list` 末尾要显示 **是否可开局：是**，`lobby list` 要显示 **是否可用：是**。

### 8. 开一局

玩家点「加入对局」告示牌（或菜单里的匹配按钮）自动随机分队入队，队列人数达到 `lobby.auto-start-players`（默认 6）自动开局。管理员也可以手动开：

```
/taketori match start          # 双方各至少 1 人
/taketori match force          # 人数不够也开（把大厅里的人自动分队）
```

### 9. PVE（可选）

```
/taketori arena setoutpost     # 划 PVE 保卫据点（选区中心，或你站的位置）
/taketori match mode pve       # 切到 PVE（写回 config.yml，下一局生效）
/taketori pve difficulty hard  # 切难度（easy / normal / hard）
```

---

## 四、玩法

### 4.1 按键

| 按键 | 作用 |
| --- | --- |
| 左键 | 第一槽技能（近战 / 射击类主输出），受武器攻击冷却限制，连点无效 |
| 右键 | 第二槽技能（核心技能，冷却 1~20 秒） |
| **第三槽键** | 位移 / 控制类技能；默认**双击潜行键**，成功触发时附带 2 秒跳跃提升 V |
| Q | 切换该武器自己的模式（彩叶为切换装备） |
| 1 / 2 | 切换快捷栏武器（有两件武器的角色） |
| F | 交换副手（已被插件接管，不会真的换副手） |

第三槽的触发方式在 `input.shift-right-trigger` 配置，可多选（逗号分隔），写 `all` 全部启用：

| 写法 | 怎么按 | 说明 |
| --- | --- | --- |
| `double-sneak`（默认） | 双击潜行键 | 最可靠：潜行是独立按键，原版一定会发出事件；300 毫秒内两次才算 |
| `sneak-q` | 潜行 + Q | 可靠，但要同时按两个键 |
| `double-right` | 双击右键 | 不需要新键；第二次右键改派给第三槽 |
| `f` | F 键 | 部分服务器 / 插件会吞掉这个事件，按了没反应就换别的 |
| `sneak-right` | 潜行 + 右键 | 对着方块时原版不发事件，只有右键空气可靠 |

按了没反应时：先用 `/taketori keys` 看按键有没有传到服务端，再用 `/taketori f` 手动触发同一个技能。

### 4.2 角色与武器

| 角色 | id | 血量 | 移速 | 武器 |
| --- | --- | --- | --- | --- |
| 辉夜 | `kaguya` | 20 | 0.10 | 火箭锤（钻石锹） |
| 帝 | `mikado` | 24 | 0.10 | 金棒（下界合金斧）、大太刀（钻石剑） |
| 彩叶 | `iroha` | 20 | 0.11 | 剑（下界合金剑）、钢丝 / 苦无（钓鱼竿） |
| 乃依 | `noi` | 18 | 0.10 | 弓、短匕（铁剑） |
| 雷 | `rai` | 26 | 0.095 | 大盾（盾牌）、壁垒令旗（白色旗帜） |
| 八千代 | `yachiyo` | 22 | 0.10 | 月镜（玻璃板）、冰冻旗鱼（三叉戟） |
| 宅公 | `takumi` | 24 | 0.098 | 忠犬之牙（骨头）、忠犬之锁（拴绳） |
| 真实 | `masami` | 20 | 0.105 | 真实之铳（弩）、真实之镜（望远镜） |
| 芦花 | `ashika` | 19 | 0.11 | 芦花之苇（甘蔗）、绫䌷之丝（线） |

- 选中角色会立即绑定并发放武器到快捷栏，同时把一套**保护 II 的铁甲**放进背包（`loadout` 段可配）。
- 同一个队伍里不允许出现相同角色；不同队伍之间可以有相同角色。
- 每把武器的四个槽位分别绑了什么，用 `/taketori skills` 看手里的武器即可。

武器分组（`/taketori give <玩家> <武器id>` 里的 id）：

| 分组 | 武器 id |
| --- | --- |
| 辉夜 | `kaguya_hammer` |
| 帝 | `mikado_konbo`、`mikado_odachi` |
| 彩叶 | `iroha_sword`、`iroha_wire` |
| 乃依 | `noi_bow`、`noi_dagger` |
| 雷 | `rai_shield`、`rai_banner` |
| 八千代 | `moon_mirror`、`frozen_swordfish` |
| 宅公 | `takumi_fang`、`takumi_collar` |
| 真实 | `masami_gun`、`masami_lens` |
| 芦花 | `ashika_reed`、`ashika_thread` |

### 4.3 3v3 积分赛

| 项目 | 设定 |
| --- | --- |
| 获胜 | 先到 600 分；或 20 分钟到时比分高者胜（平分平局） |
| 击杀月人 / 玩家 / 拆基地 | `+3` / `+10` / `+50` |
| 击杀回血 | 击杀敌方玩家回复 3 颗心（`combat.kill-heal`，满血不回） |
| 基地 | 双方各 3 个（数量可配），开局 60 秒保护期内不能占点 |
| 占点 | 站进对方基地区域持续 10 秒；区域内没敌人时进度按 `base.decay-per-second` 衰减 |
| 月人 | 每 9 秒一批（铁甲僵尸 / 铁甲骷髅按权重随机），场上最多 15 个 |
| 精英月人 | 每 5 波出一批（钻甲 + 药水 buff + 可配攻击力） |
| 复活 | 死亡 5 秒后回己方出生点，死亡不掉落物品 |
| 出生增益 | 开局与复活后 10 秒药水增益（`combat.spawn-buff`） |
| 记分板 | 双方比分 / 你的得分 / 本局击杀 / 波次 / 剩余分钟 |

### 4.4 PVE 月人入侵

`/taketori match mode pve` 切换后：所有人同一队、不占点、玩家之间默认无伤害。

**保卫据点**：开局在据点点位放出一个取消移动 AI 的雪傀儡，本体无敌（玩家打不掉），耐久由插件结算。月人进入半径（默认 6 格）内就按 **在场月人数 × 每秒伤害**扣血；耐久归零播报「已被月人拆毁」并直接结束对局（可配成只播报）。位置用 `/taketori arena setoutpost` 划定，没划定则回落到第一个月人刷新区中心。

**五大波次**：除常规刷怪外，每约 60 秒来一波精英潮，默认 5 波、每波 8 名精英；每波开始会先清掉上一波残留的精英。

**三档难度**（`/taketori pve difficulty <easy|normal|hard>`）：

| 档位 | 据点耐久 | 每月人每秒 | 每波精英 | 精英额外 buff |
| --- | --- | --- | --- | --- |
| easy | 240 | 4.0 | 6 | +0 级 |
| normal（默认） | 320 | 7.0 | 8 | +1 级 |
| hard | 460 | 11.0 | 10 | +2 级 |

**精英随人数变强**：每多 1 名参战玩家，精英的药水等级整体 +1 级，叠加难度档加成后封顶 6 级（例：3 人打 normal，精英 buff 比配置值高 3 级）。

记分板显示总分、目标分、据点耐久百分比与大波次进度；`/taketori pve` 查看当前设置。

---

## 五、指令用法

所有指令统一使用全名 `/taketori`，没有缩写别名。

### 玩家指令（`taketori.play`）

```
/taketori menu                     打开玩家菜单（匹配 / 队伍 / 角色 / 排行榜）
/taketori play                     同 menu（快捷入口）
/taketori ranks [榜单]             总计排行榜 GUI
/taketori stats [数量]             聊天栏版排行榜（跨局累计）
/taketori character <角色id|none>  选择 / 取消自己的角色
/taketori skills                   查看手里武器四个槽位的技能与冷却
/taketori mode                     手动触发 Q 槽技能（Q 被别的插件吞掉时用）
/taketori f                        手动触发第三槽技能
/taketori keys                     按键诊断：回放最近收到的原始输入事件
/taketori tag <玩家> <标签|none>   查看 / 设置隐性标签（管理员）
/taketori tags                     隐性标签 GUI（管理员）
/taketori lobby join|leave|spectate 自助加入队列 / 退出 / 旁观
/taketori leave                    退出队列、退出观战、离开对局
/taketori doctor                   自检：识别链路、名字解析、配置校验、多世界范围
```

### 管理员指令（`taketori.admin`）

**场地**

```
/taketori arena wand                             领选区锄（左键 = 角点 1，右键 = 角点 2，潜行+左键 = 清空）
/taketori arena pos1 | pos2                      用当前位置设置选区角点
/taketori arena setminion [编号]                 选区设为月人刷新区（可配多个）
/taketori arena setbase <red|blue> [编号]        选区设为某队基地（编号可省略）
/taketori arena setspawn <red|blue>              当前位置设为某队出生点
/taketori arena setloot [编号]                   选区设为道具刷新点
/taketori arena lootwand                         领道具点工具
/taketori arena setoutpost                       选区中心（或当前位置）设为 PVE 保卫据点
/taketori arena delbase <red|blue> <编号>        删除某个基地
/taketori arena delminion <编号>                 删除某个月人刷新区
/taketori arena delloot <编号>                   删除某个道具刷新点
/taketori arena deloutpost                       清除据点位置
/taketori arena clearselection                   清空你的选区
/taketori arena list                             查看已配置场地与「是否可开局」
```

**大厅**

```
/taketori lobby setspawn                         当前位置设为大厅出生点
/taketori lobby pos1 | pos2 | setregion          划大厅范围（用于大厅保护与识别）
/taketori lobby addsign <动作>                   准星对准告示牌绑定动作
/taketori lobby removesign                       移除准星所指告示牌的绑定
/taketori lobby list                             查看大厅配置与告示牌
```

**对局**

```
/taketori match start [force]                    开始对局
/taketori match force                            人数不够也开（自动把大厅里的人分队）
/taketori match stop [原因]                      强制结束对局
/taketori match status                           阶段 / 比分 / 剩余 / 基地 / 场上月人
/taketori match mode <pvp|pve>                   切换模式并写回 config.yml（下一局生效）
/taketori match difficulty <easy|normal|hard>    同下面的 pve difficulty
/taketori pve                                    查看 PVE 设置（难度 / 波次 / 据点耐久）
/taketori pve difficulty <easy|normal|hard>      切换 PVE 难度（下一局生效）
/taketori team <玩家> <red|blue|none>            手动分队
/taketori character <玩家> <角色id|none>         指定角色（同队不能重复）
```

**维护**

```
/taketori editor [武器id]                        武器数据编辑 GUI
/taketori give <玩家> <武器id>                   发放武器
/taketori debug [on|off]                         调试日志开关
/taketori qmode <drop|held-slot|none>            运行时切换 Q 键行为
/taketori reload                                 热重载全部配置
/taketori admin                                  管理员菜单（含删除区域等按钮）
```

---

## 六、配置项

改完 `config.yml` 执行 `/taketori reload` 生效（场地坐标在 `arena.yml`、大厅在 `lobby.yml`，不受 reload 影响）。

| 段 | 关键项 | 默认 |
| --- | --- | --- |
| `match` | `mode` / `score-to-win` / `time-limit-minutes` / `team-size` / `respawn-delay-seconds` / `keep-inventory` | pvp / 600 / 20 / 3 / 5 / true |
| `scoring` | `minion-kill` / `player-kill` / `base-capture` | 3 / 10 / 50 |
| `combat` | `kill-heal` / `minion-kill-heal` / `friendly-fire-protection` / `third-slot-buff` | 6.0 / 0.0 / auto / 2 秒跳跃提升 V |
| `loadout` | `armor-enabled` / `armor-material` / `armor-protection` | true / IRON / 2（保护 II） |
| `minion` | `health` / `iron-armor` / `interval-seconds` / `per-spawn` / `max-alive` / `types` / `elite` | 40 / true / 9 / 3 / 15 / 僵尸骷髅权重 / 每 5 波 |
| `pve` | `difficulty` / `big-waves` / `elite-scaling` / `outpost` | normal / 5 波·8 精英·60 秒 / 每多 1 人 +1 级 / 三档数值 |
| `base` | `count-per-team` / `capture-seconds` / `capture-delay-seconds` / `decay-per-second` / `multi-player-bonus` | 3（或 auto）/ 10 / 60 / 0.5 / true |
| `lobby` | `auto-start-players` / `teleport-on-join` / `takeover-worlds` / `protect` / `return-after-match` | 6 / true / `[]`（仅大厅世界）/ true / true |
| `worlds` | `broadcast-scope` | world（只发给消息所属世界；`all` = 全服） |
| `menu-clock` | `enabled` / `material` / `give-on-join` / `name` / `lore` | true / CLOCK / true / … |
| `input` | `q-mode` / `weapon-slots` / `shift-right-trigger` | drop / [0,1,2,3] / double-sneak |
| `items` | `soulbound` / `auto-give-on-join` / `allow-drop` | true / false / false |
| `feedback` | `actionbar` / `particles` / `sounds` / `cooldown-display` | true / true / true / bossbar |
| `setup-wand` | `enabled` / `material` / `loot-material` / `give-on-join` | true / NETHERITE_HOE / STRUCTURE_VOID / false |
| `loot` | `enabled` / `interval-seconds` / `max-drops` / `items` | true / 30 / 6 / 道具池 |
| `tags` | `default` / `vip` / `staff` 的权重 | 0 / 10 / 100 |

### 数值文件（插件不会覆盖）

- `weapons.yml`：全部武器数值与技能参数。`attack-damage` / `attack-speed` 写的是**最终值**；`modes.<MODE>.skills` 会整段替换武器级同名槽位；写错的参数键会在启动时被配置校验点名。
- `characters.yml`：角色血量、移速、武器列表与描述。
- `messages.yml`：所有提示文案（新键会自动回填默认值）。
- 插件升级后如果控制台提示「你的 weapons.yml 是模板 v…」，说明内置模板更新了；同步方式是备份并删除该文件后 `/taketori reload` 重新生成。

---

## 七、构建

### Gradle（有网络时）

```bash
gradle build          # 产物在 build/libs/
```

`build.gradle.kts` 内置两道护栏：`checkCorePurity`（`core/` 层出现 Bukkit / NMS 引用即失败）与 `checkParamKeys`（技能读取的参数键必须在校验表里登记）。

### 离线脚本（无网络、无 Gradle 时）

```powershell
pwsh -File build-offline.ps1 -LibsDirs "<依赖 jar 目录>","<另一个目录>"
```

只需要 **JDK 21** 与一份 **paper-api 1.21.4** jar（再加 adventure 系列的 api / key / minimessage 即可）。脚本会跑同样的两道护栏、编译、打包到 `build/dist/TaketoriKassen-<版本>.jar`。

### 离线自检工具

```powershell
# 核对 weapons.yml / characters.yml 里的材质、粒子、音效、药水、弹体、属性、技能类型与参数键
java -cp "<依赖>;build/classes;build/check" WeaponYamlCheck src/main/resources

# 多世界判定（接管白名单与播报范围）的纯逻辑回归
java -cp "build/check;build/classes" WorldScopeTest
```

---

## 八、目录结构

```
src/main/java/com/taketori/kassen/
├─ core/        纯 Java 逻辑（角色 / 武器 / 技能定义、对局规则、世界范围判定），禁止引用 Bukkit
├─ paper/       Bukkit 适配（监听器、技能实现、GUI、命令、对局与大堂管理）
├─ version/     版本差异收口（属性 / 粒子 / 音效 / 药水的注册表解析）
├─ config/      配置加载、校验与迁移
└─ data/        持久化（YAML 存档：角色绑定、隐性标签、跨局战绩）
src/main/resources/
├─ plugin.yml / config.yml / weapons.yml / characters.yml / messages.yml
tools/          离线测试与校验器（无需启动服务器）
build-offline.ps1  无网络环境的构建脚本
```

`.gitignore` 已排除构建产物、本地依赖 jar 与凭据文件（**不要把 token / 密钥提交进仓库**）。

---

## 九、许可

[MIT](LICENSE) © 2026 ChihayaAnon908
