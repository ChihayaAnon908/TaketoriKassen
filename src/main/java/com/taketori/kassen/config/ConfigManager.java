package com.taketori.kassen.config;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.core.character.CharacterDef;
import com.taketori.kassen.core.character.CharacterManager;
import com.taketori.kassen.core.skill.SkillDef;
import com.taketori.kassen.core.skill.SkillSlot;
import com.taketori.kassen.core.weapon.WeaponDef;
import com.taketori.kassen.core.weapon.WeaponManager;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/**
 * 配置加载：config.yml / weapons.yml / characters.yml / messages.yml。
 *
 * <p>这里的职责只有一件事——<b>把 YAML 变成 core 层的数据对象</b>。
 * 任何数值都不写死在代码里（计划 §4.3），改武器数值 = 改 weapons.yml。</p>
 */
public final class ConfigManager {

    /**
     * 内置 weapons.yml 的模板版本（改动默认数值结构时 +1）。
     *
     * <p>插件不会覆盖玩家的 weapons.yml，所以这里只用于"提示玩家模板已更新"——
     * 否则会出现"插件升级了但技能还是老样子"的困惑。</p>
     */
    public static final int WEAPON_TEMPLATE_VERSION = 8;

    private final TaketoriPlugin plugin;
    private final WeaponManager weapons = new WeaponManager();
    private final CharacterManager characters = new CharacterManager();
    private final Messages messages = new Messages();

    private int configVersion = 1;
    private boolean debug;
    private boolean actionbar = true;
    private boolean particles = true;
    private boolean sounds = true;
    private boolean soulbound = true;
    private String cooldownDisplay = "bossbar";
    private boolean allowDrop;
    private boolean autoGiveOnJoin;
    private String qMode = "held-slot";
    private List<Integer> weaponSlots = List.of(0, 1, 2, 3);

    public ConfigManager(TaketoriPlugin plugin) {
        this.plugin = plugin;
    }

    /** 加载全部配置。失败时抛出异常，由主类决定是否保留上一份可用配置。 */
    public void load() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();

        // 先跑配置迁移：插件只在配置文件不存在时写默认值，
        // 老服务器的 config.yml 会一直停留在旧默认行为上（Q 键失效就是这么来的）。
        new ConfigMigrator(plugin).migrate();

        var cfg = plugin.getConfig();
        configVersion = cfg.getInt("config-version", 1);
        debug = cfg.getBoolean("debug", false);
        actionbar = cfg.getBoolean("feedback.actionbar", true);
        particles = cfg.getBoolean("feedback.particles", true);
        sounds = cfg.getBoolean("feedback.sounds", true);
        cooldownDisplay = cfg.getString("feedback.cooldown-display", "bossbar");
        soulbound = cfg.getBoolean("items.soulbound", true);
        allowDrop = cfg.getBoolean("items.allow-drop", false);
        autoGiveOnJoin = cfg.getBoolean("items.auto-give-on-join", false);
        // 回退默认值必须与 config.yml 模板保持一致：这里曾经是 held-slot，
        // 于是当配置文件里缺少该键时，Q 会退化成"只切快捷栏"，与文档描述不符。
        qMode = cfg.getString("input.q-mode", "drop");
        weaponSlots = List.copyOf(cfg.getIntegerList("input.weapon-slots"));
        if (weaponSlots.isEmpty()) {
            weaponSlots = List.of(0, 1, 2, 3);
        }

        // messages.yml 回填默认键（新文案在旧文件里不存在时会显示成 "skill.xxx" 键名）；
        // weapons / characters 不回填，避免改动用户的数值文件
        messages.load(loadYaml("messages.yml", true));

        YamlConfiguration weaponYaml = loadYaml("weapons.yml", false);
        int templateVersion = weaponYaml.getInt("config-version", 1);
        if (templateVersion < WEAPON_TEMPLATE_VERSION) {
            plugin.getLogger().warning("你的 weapons.yml 是模板 v" + templateVersion
                    + "，插件内置模板已是 v" + WEAPON_TEMPLATE_VERSION + "。");
            plugin.getLogger().warning("  插件不会覆盖你的数值文件 → 新机制与新默认值不会自动生效。");
            plugin.getLogger().warning("  同步方式：备份后删除 plugins/TaketoriKassen/weapons.yml，再执行 /taketori reload。");
        }

        WeaponManager parsedWeapons = new WeaponManager();
        parseWeapons(weaponYaml, parsedWeapons);

        CharacterManager parsedCharacters = new CharacterManager();
        parseCharacters(loadYaml("characters.yml", false), parsedCharacters);

        // 校验：角色引用的武器必须存在，否则加载期就报错，不留到运行时
        List<String> dangling = new ArrayList<>();
        for (CharacterDef def : parsedCharacters.all()) {
            for (String weaponId : def.weapons()) {
                if (!parsedWeapons.has(weaponId)) {
                    dangling.add(def.id() + " -> " + weaponId);
                }
            }
        }
        if (!dangling.isEmpty()) {
            throw new IllegalStateException("characters.yml 引用了不存在的武器: " + String.join(", ", dangling));
        }

        weapons.clear();
        for (WeaponDef def : parsedWeapons.all()) {
            weapons.register(def);
        }
        characters.clearDefinitions();
        for (CharacterDef def : parsedCharacters.all()) {
            characters.register(def);
        }
    }

    private YamlConfiguration loadYaml(String resourceName, boolean backfillDefaults) {
        File file = new File(plugin.getDataFolder(), resourceName);
        if (!file.exists()) {
            plugin.saveResource(resourceName, false);
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        // 与 jar 内默认值合并：新增字段在升级后自动补齐（不改动用户已改的值）
        try (var stream = plugin.getResource(resourceName)) {
            if (stream != null) {
                yaml.setDefaults(YamlConfiguration.loadConfiguration(
                        new java.io.InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8)));
            }
        } catch (Exception ex) {
            plugin.getLogger().log(Level.WARNING, "读取内置默认配置失败: " + resourceName, ex);
        }
        if (backfillDefaults) {
            // 把新增的键写回用户文件：否则文件里看不到新键，用户也不知道有哪些能改
            yaml.options().copyDefaults(true);
            try {
                yaml.save(file);
            } catch (Exception ex) {
                plugin.getLogger().log(Level.WARNING, "回填默认配置失败: " + resourceName, ex);
            }
        }
        return yaml;
    }

    private void parseWeapons(YamlConfiguration yaml, WeaponManager target) {
        ConfigurationSection root = yaml.getConfigurationSection("weapons");
        if (root == null) {
            plugin.getLogger().warning("weapons.yml 里没有 weapons: 段");
            return;
        }
        for (String id : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(id);
            if (section == null) {
                continue;
            }
            String material = section.getString("material", "STICK");
            Map<String, Double> attributes = toDoubleMap(section.getConfigurationSection("attributes"));

            Map<String, WeaponDef.ModeDef> modes = new LinkedHashMap<>();
            ConfigurationSection modeSection = section.getConfigurationSection("modes");
            if (modeSection != null) {
                for (String modeId : modeSection.getKeys(false)) {
                    ConfigurationSection mode = modeSection.getConfigurationSection(modeId);
                    if (mode == null) {
                        continue;
                    }
                    modes.put(modeId.toUpperCase(java.util.Locale.ROOT), new WeaponDef.ModeDef(
                            modeId.toUpperCase(java.util.Locale.ROOT),
                            mode.getString("display", modeId),
                            parseSkills(mode.getConfigurationSection("skills"))));
                }
            }

            String defaultMode = section.getString("default-mode", modes.isEmpty() ? "" : modes.keySet().iterator().next());
            if (defaultMode != null) {
                defaultMode = defaultMode.toUpperCase(java.util.Locale.ROOT);
            }

            // 命中附加效果（hit-effects）：乃依的箭随机挂负面效果等
            Map<String, Object> hitEffects = new LinkedHashMap<>();
            ConfigurationSection hitSection = section.getConfigurationSection("hit-effects");
            if (hitSection != null) {
                hitEffects.putAll(hitSection.getValues(false));
            }

            WeaponDef def = new WeaponDef(
                    id,
                    section.getString("character", ""),
                    section.getString("display", id),
                    material,
                    section.getInt("model-data", 0),
                    section.getStringList("lore"),
                    attributes,
                    defaultMode,
                    modes,
                    parseSkills(section.getConfigurationSection("skills")),
                    hitEffects);
            target.register(def);
        }
    }

    private Map<SkillSlot, SkillDef> parseSkills(ConfigurationSection section) {
        if (section == null) {
            return Collections.emptyMap();
        }
        Map<SkillSlot, SkillDef> result = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            SkillSlot slot = SkillSlot.byKey(key);
            ConfigurationSection skill = section.getConfigurationSection(key);
            if (slot == null || skill == null) {
                if (slot == null) {
                    plugin.getLogger().warning("weapons.yml 出现未知按键槽位: " + key);
                }
                continue;
            }
            String type = skill.getString("type", "");
            double cooldown = skill.getDouble("cooldown", 0.0D);
            Map<String, Object> params = new LinkedHashMap<>();
            ConfigurationSection paramSection = skill.getConfigurationSection("params");
            if (paramSection != null) {
                params.putAll(paramSection.getValues(false));
            }
            result.put(slot, new SkillDef(type, cooldown, params));
        }
        return result;
    }

    private void parseCharacters(YamlConfiguration yaml, CharacterManager target) {
        ConfigurationSection root = yaml.getConfigurationSection("characters");
        if (root == null) {
            plugin.getLogger().warning("characters.yml 里没有 characters: 段");
            return;
        }
        for (String id : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(id);
            if (section == null) {
                continue;
            }
            target.register(new CharacterDef(
                    id,
                    section.getString("display", id),
                    section.getStringList("weapons"),
                    toDoubleMap(section.getConfigurationSection("attributes")),
                    section.getStringList("lore")));
        }
    }

    private Map<String, Double> toDoubleMap(ConfigurationSection section) {
        if (section == null) {
            return Collections.emptyMap();
        }
        Map<String, Double> result = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            result.put(key, section.getDouble(key));
        }
        return result;
    }

    public WeaponManager weapons() {
        return weapons;
    }

    public CharacterManager characters() {
        return characters;
    }

    public Messages messages() {
        return messages;
    }

    public int configVersion() {
        return configVersion;
    }

    public boolean debug() {
        return debug;
    }

    public void setDebug(boolean value) {
        this.debug = value;
    }

    /** 运行时切换 Q 键行为（不落盘；永久生效需要改 config.yml 后 reload）。 */
    public void setQMode(String value) {
        if (value != null && !value.isBlank()) {
            this.qMode = value;
        }
    }

    public boolean actionbar() {
        return actionbar;
    }

    public boolean particles() {
        return particles;
    }

    public boolean sounds() {
        return sounds;
    }

    /** 冷却条是否用屏幕上方进度条（骑马时马血条那种位置）。 */
    public boolean cooldownBossBar() {
        return "bossbar".equalsIgnoreCase(cooldownDisplay) || "both".equalsIgnoreCase(cooldownDisplay);
    }

    /** 冷却提示是否用底部文字加进度字符。 */
    public boolean cooldownActionBar() {
        return "actionbar".equalsIgnoreCase(cooldownDisplay) || "both".equalsIgnoreCase(cooldownDisplay);
    }

    // ---- 大厅与队伍（低频读取，直接取配置，不再维护第二份缓存）----

    /** 每队人数上限，用于随机分队的满员判定。 */
    public int matchTeamSize() {
        return Math.max(1, plugin.getConfig().getInt("match.team-size", 3));
    }

    /** 队列人数达到多少自动开局（0 = 只允许手动开局）。 */
    public int lobbyAutoStartPlayers() {
        return plugin.getConfig().getInt("lobby.auto-start-players", 6);
    }

    /** 大厅内是否禁止掉血。 */
    public boolean lobbyProtect() {
        return plugin.getConfig().getBoolean("lobby.protect", true);
    }

    /** 对局结束后是否把参赛者送回大厅。 */
    public boolean lobbyReturnAfterMatch() {
        return plugin.getConfig().getBoolean("lobby.return-after-match", true);
    }

    /** 玩家进服是否自动传送到大厅。 */
    public boolean lobbyTeleportOnJoin() {
        return plugin.getConfig().getBoolean("lobby.teleport-on-join", true);
    }

    public boolean soulbound() {
        return soulbound;
    }

    public boolean allowDrop() {
        return allowDrop;
    }

    public boolean autoGiveOnJoin() {
        return autoGiveOnJoin;
    }

    public String qMode() {
        return qMode;
    }

    // ---- 管理用具：选区锄 ----

    /** 选区锄是否可用（关掉后这把锄头就是普通锄头）。 */
    public boolean setupWandEnabled() {
        return plugin.getConfig().getBoolean("setup-wand.enabled", true);
    }

    /** 选区锄绑定的物品名（默认下界合金锄）。 */
    public String setupWandMaterial() {
        return plugin.getConfig().getString("setup-wand.material", "NETHERITE_HOE");
    }

    /** 手持选区锄时是否用粒子描出选区边框。 */
    public boolean setupWandOutline() {
        return plugin.getConfig().getBoolean("setup-wand.outline-particles", true);
    }

    /** 有管理权限的玩家进服时是否自动发一把选区锄。 */
    public boolean setupWandGiveOnJoin() {
        return plugin.getConfig().getBoolean("setup-wand.give-on-join", false);
    }

    public List<Integer> weaponSlots() {
        return weaponSlots;
    }
}
