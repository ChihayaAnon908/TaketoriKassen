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
    public static final int CURRENT_VERSION = 3;

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
            notes.add("每队基地数量固定为 3：arena.yml 里第 4 个及以后的基地会被忽略");
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
