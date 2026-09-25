package com.taketori.kassen.config;

import com.taketori.kassen.TaketoriPlugin;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.List;

/**
 * 配置迁移（计划 §5.6 第 4 条：配置带版本号，启动时按迁移链升级）。
 *
 * <p>为什么必须有它：插件只在<b>配置文件不存在</b>时写出默认值，
 * 所以一旦改过默认行为，老服务器的 config.yml 会一直停留在旧默认值上，
 * 表现为"插件更新了但行为没变"——正是 Q 键只切快捷栏、不触发模式切换的成因。</p>
 */
public final class ConfigMigrator {

    /** 当前配置结构版本；每次改动默认值或键名都要 +1，并在下面加一段迁移。 */
    public static final int CURRENT_VERSION = 7;

    private final TaketoriPlugin plugin;

    public ConfigMigrator(TaketoriPlugin plugin) {
        this.plugin = plugin;
    }

    /** 执行迁移；返回 true 表示改动过配置（已写回文件）。 */
    public boolean migrate() {
        FileConfiguration config = plugin.getConfig();
        int version = config.getInt("config-version", 1);
        if (version >= CURRENT_VERSION) {
            return false;
        }

        List<String> notes = new ArrayList<>();

        if (version < 2) {
            // v1 → v2：Q 键的默认行为曾经是 held-slot（只切快捷栏），
            // 导致"按 Q 没反应、模式切换不生效"。改为 drop（触发 q 槽技能）。
            String qMode = config.getString("input.q-mode", "");
            if (qMode == null || qMode.isBlank() || "held-slot".equalsIgnoreCase(qMode)) {
                config.set("input.q-mode", "drop");
                notes.add("input.q-mode: " + (qMode == null || qMode.isBlank() ? "(缺失)" : qMode)
                        + " → drop（Q 现在会触发武器 q 槽声明的技能，例如辉夜/帝/雷/月镜/旗鱼的模式切换）");
            }
        }

        if (version < 3) {
            // v2 → v3：对局节奏调整。每队基地数 6 → 3（在 ArenaManager 里，不受 config 控制），
            // 刷怪 15 秒 → 9 秒、场上上限 30 → 15，并新增"开局 60 秒禁止占点"的保护期。
            // 只改写"还停在旧默认值"的项：自己调过的数值不动，避免覆盖服主的设置。
            if (config.getInt("minion.interval-seconds", 15) == 15) {
                config.set("minion.interval-seconds", 9);
                notes.add("minion.interval-seconds: 15 → 9（刷怪更快）");
            }
            if (config.getInt("minion.max-alive", 30) == 30) {
                config.set("minion.max-alive", 15);
                notes.add("minion.max-alive: 30 → 15（场上小兵最多 15 个，达到就不再刷）");
            }
            if (!config.isSet("base.capture-delay-seconds")) {
                config.set("base.capture-delay-seconds", 60);
                notes.add("base.capture-delay-seconds: 新增 60（开局 60 秒内禁止占点）");
            }
            notes.add("每队基地数量由 config.yml 的 base.count-per-team 决定（默认 3）：arena.yml 里编号超出的基地会被忽略");
        }

        if (version < 4) {
            // v3 → v4：两项新增默认行为——
            //   ① 第三槽技能（F 键）附带 2 秒跳跃提升 V
            //   ② 绑定角色时发一套保护 II 的铁甲
            // 只补缺失的键，已经自己写过的值不动。
            if (!config.isSet("combat.third-slot-buff.enabled")) {
                config.set("combat.third-slot-buff.enabled", true);
                config.set("combat.third-slot-buff.type", "JUMP_BOOST");
                config.set("combat.third-slot-buff.amplifier", 4);
                config.set("combat.third-slot-buff.ticks", 40);
                notes.add("combat.third-slot-buff: 新增（第三槽技能附带 2 秒跳跃提升 V，关掉请设 enabled: false）");
            }
            if (!config.isSet("loadout.armor-enabled")) {
                config.set("loadout.armor-enabled", true);
                config.set("loadout.armor-material", "IRON");
                config.set("loadout.armor-protection", 2);
                notes.add("loadout: 新增（开局发一套保护 II 铁甲，关掉请设 armor-enabled: false）");
            }
        }

        if (version < 5) {
            // v4 → v5：第三槽技能的默认触发方式从 F 键换成"双击潜行键"。
            // F 键（交换副手）在部分服务器/插件下事件根本不到达，表现就是"按 F 完全没反应"；
            // 潜行键是独立按键，原版一定会发出事件，且不受"对着方块右键"这类原版分支影响。
            // 只改还停留在旧默认值（f）或缺失的配置；自己写成 sneak-right / both 等的不动。
            String trigger = config.getString("input.shift-right-trigger", "");
            if (trigger == null || trigger.isBlank() || "f".equalsIgnoreCase(trigger.trim())) {
                config.set("input.shift-right-trigger", "double-sneak");
                notes.add("input.shift-right-trigger: "
                        + (trigger == null || trigger.isBlank() ? "(缺失)" : trigger)
                        + " → double-sneak（第三槽技能改为「双击潜行键」触发；"
                        + "想用回 F 就写 f，想多种方式都行就用逗号分隔，写 all 全部启用）");
            }
        }

        if (version < 6) {
            // v5 → v6：每队基地数量从代码常量改成配置项 base.count-per-team。
            // 默认值就是原来的 3，所以行为不变；想改成别的数量（或者 auto = 以实际划定为定）改这一行即可。
            if (!config.isSet("base.count-per-team")) {
                config.set("base.count-per-team", 3);
                notes.add("base.count-per-team: 新增 3（每队基地数量现在可配：正整数或 auto；"
                        + "改成 1 就是每队 1 个基地，改成 auto 则以你实际划定的为准）");
            }
        }

        if (version < 7) {
            // v6 → v7：多世界兼容（接管白名单 + 播报范围）与菜单时钟。
            // 这些键"不写就用新默认值"，这里只是把它们写进文件，方便你看到并修改。
            if (!config.isSet("worlds.broadcast-scope")) {
                config.set("worlds.broadcast-scope", "world");
                notes.add("worlds.broadcast-scope: 新增 world（多世界服务器下播报只发给消息所属世界；"
                        + "写 all 恢复全服广播）");
            }
            if (!config.isSet("lobby.takeover-worlds")) {
                config.set("lobby.takeover-worlds", new ArrayList<String>());
                notes.add("lobby.takeover-worlds: 新增空列表（进服只接管大厅所在世界；"
                        + "写 [\"*\"] 恢复「接管所有世界」的旧行为）");
            }
            if (!config.isSet("menu-clock.enabled")) {
                config.set("menu-clock.enabled", true);
                config.set("menu-clock.material", "CLOCK");
                config.set("menu-clock.give-on-join", true);
                notes.add("menu-clock: 新增（给玩家发一个右键打开玩家菜单的时钟，可关或换材质）");
            }
        }

        config.set("config-version", CURRENT_VERSION);
        plugin.saveConfig();

        plugin.getLogger().warning("检测到旧版配置，已自动迁移到 v" + CURRENT_VERSION + "：");
        for (String note : notes) {
            plugin.getLogger().warning("  - " + note);
        }
        if (notes.isEmpty()) {
            plugin.getLogger().warning("  - 仅更新 config-version（没有需要改写的行为项）");
        }
        plugin.getLogger().warning("  若你想保留旧行为，请手动改回并 reload。");
        return true;
    }
}
