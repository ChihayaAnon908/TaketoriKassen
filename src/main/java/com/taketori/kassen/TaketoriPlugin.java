package com.taketori.kassen;

import com.taketori.kassen.config.ConfigManager;
import com.taketori.kassen.config.ConfigValidator;
import com.taketori.kassen.core.cooldown.CooldownManager;
import com.taketori.kassen.core.skill.SkillSlot;
import com.taketori.kassen.data.PlayerDataStore;
import com.taketori.kassen.data.YamlPlayerDataStore;
import com.taketori.kassen.paper.effect.Fx;
import com.taketori.kassen.paper.item.ItemFactory;
import com.taketori.kassen.paper.item.PDCKeys;
import com.taketori.kassen.paper.listener.CarrierGuardListener;
import com.taketori.kassen.paper.listener.CombatListener;
import com.taketori.kassen.paper.listener.InputListener;
import com.taketori.kassen.paper.listener.ProjectileListener;
import com.taketori.kassen.paper.lobby.CharacterMenu;
import com.taketori.kassen.paper.lobby.LobbyListener;
import com.taketori.kassen.paper.lobby.LobbyManager;
import com.taketori.kassen.paper.listener.PlayerListener;
import com.taketori.kassen.paper.listener.SetupWandListener;
import com.taketori.kassen.paper.listener.WeaponEditorListener;
import com.taketori.kassen.paper.editor.WeaponEditor;
import com.taketori.kassen.paper.setup.SetupWand;
import com.taketori.kassen.paper.setup.SetupWandService;
import com.taketori.kassen.paper.match.ArenaManager;
import com.taketori.kassen.paper.match.BaseCaptureManager;
import com.taketori.kassen.paper.match.MatchListener;
import com.taketori.kassen.paper.match.MatchManager;
import com.taketori.kassen.paper.match.MatchScoreboard;
import com.taketori.kassen.paper.match.MinionSpawner;
import com.taketori.kassen.paper.match.SpectatorManager;
import com.taketori.kassen.paper.match.StatsTracker;
import com.taketori.kassen.paper.scheduler.SchedulerAdapter;
import com.taketori.kassen.paper.skill.SkillManager;
import com.taketori.kassen.paper.skill.SkillRegistry;
import com.taketori.kassen.paper.skill.impl.BlinkSkill;
import com.taketori.kassen.paper.skill.impl.EquipSwitchSkill;
import com.taketori.kassen.paper.skill.impl.GrappleSkill;
import com.taketori.kassen.paper.skill.impl.MeleeSmashSkill;
import com.taketori.kassen.paper.skill.impl.MirrorBurstSkill;
import com.taketori.kassen.paper.skill.impl.MirrorSkill;
import com.taketori.kassen.paper.skill.impl.ModeSwitchSkill;
import com.taketori.kassen.paper.skill.impl.ProjectileSkill;
import com.taketori.kassen.paper.skill.impl.PullSkill;
import com.taketori.kassen.paper.skill.impl.ReflectSkill;
import com.taketori.kassen.paper.skill.impl.RocketJumpSkill;
import com.taketori.kassen.paper.skill.impl.ShockwaveSkill;
import com.taketori.kassen.paper.skill.impl.SelfBoostSkill;
import com.taketori.kassen.paper.skill.impl.ShieldGuardSkill;
import com.taketori.kassen.paper.skill.impl.SpecialShotToggleSkill;
import com.taketori.kassen.paper.state.CombatStates;
import com.taketori.kassen.version.DefaultVersionAdapter;
import com.taketori.kassen.version.VersionAdapter;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * 插件主类：负责装配与卸载，本身不含玩法逻辑。
 *
 * <p>分层（计划 §5.2）：core 为纯逻辑，paper 为 Bukkit 适配，version 收拢版本差异，
 * data 管持久化。主类只做 wire-up，方便单测与将来替换实现。</p>
 */
public final class TaketoriPlugin extends JavaPlugin {

    private ConfigManager config;
    private VersionAdapter versions;
    private ItemFactory items;
    private Fx fx;
    private SchedulerAdapter scheduler;
    private CombatStates states;
    private SkillRegistry registry;
    private CooldownManager cooldowns;
    private SkillManager skills;
    private ProjectileSkill projectileSkill;
    private PlayerDataStore dataStore;

    // ---- 玩法层（3v3 积分赛）----
    private ArenaManager arena;
    private MatchManager match;
    private MatchScoreboard matchBoard;
    private MinionSpawner minions;
    private BaseCaptureManager baseCapture;
    private StatsTracker stats;
    private SpectatorManager spectator;
    private LobbyManager lobby;
    private CharacterMenu characterMenu;

    /** 管理用具：选区锄（划场地的快捷方式）。 */
    private SetupWandService setupWand;
    /** 管理用具：武器数据编辑 GUI。 */
    private WeaponEditor editor;

    /** 插件自身造成的伤害标记：防止与近战改写逻辑互相递归。 */
    private final Set<UUID> internalDamage = ConcurrentHashMap.newKeySet();

    @Override
    public void onEnable() {
        PDCKeys.init(this);
        versions = new DefaultVersionAdapter();

        config = new ConfigManager(this);
        try {
            config.load();
        } catch (Throwable throwable) {
            getLogger().log(Level.SEVERE, "配置加载失败，插件将禁用：" + throwable.getMessage(), throwable);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        items = new ItemFactory(this, versions);
        fx = new Fx(config, versions);
        scheduler = new SchedulerAdapter(this);
        states = new CombatStates();
        cooldowns = new CooldownManager();

        registry = new SkillRegistry();
        registerSkills();
        skills = new SkillManager(this, registry, cooldowns, items, fx);

        dataStore = new YamlPlayerDataStore(this);
        dataStore.loadAll();
        restoreOnlinePlayers();

        // ---- 玩法层：场地、对局、记分板、小怪、基地占点、跨局统计 ----
        arena = new ArenaManager(this);
        arena.load();
        matchBoard = new MatchScoreboard(this);
        stats = new StatsTracker(this);
        stats.load();
        match = new MatchManager(this);
        match.loadRules();
        minions = new MinionSpawner(this);
        baseCapture = new BaseCaptureManager(this);
        // ---- 大厅与旁观 ----
        spectator = new SpectatorManager(this);
        lobby = new LobbyManager(this);
        lobby.load();
        characterMenu = new CharacterMenu(this);
        // ---- 管理用具：选区锄（左键/右键点方块划区域，带边框可视化）----
        setupWand = new SetupWandService(this, new SetupWand(this));
        setupWand.start();
        getServer().getPluginManager().registerEvents(new SetupWandListener(this), this);
        // ---- 管理用具：武器数据编辑 GUI（改数值 → 写回 weapons.yml → 自动 reload）----
        editor = new WeaponEditor(this);
        getServer().getPluginManager().registerEvents(new WeaponEditorListener(this), this);
        getServer().getPluginManager().registerEvents(new MatchListener(this), this);
        getServer().getPluginManager().registerEvents(new LobbyListener(this), this);
        getServer().getPluginManager().registerEvents(characterMenu, this);
        // 每秒驱动对局计时（时限判定）
        scheduler.runTimerTask(() -> match.tick(), 20L, 20L);

        getServer().getPluginManager().registerEvents(new InputListener(this), this);
        getServer().getPluginManager().registerEvents(new CarrierGuardListener(this), this);
        getServer().getPluginManager().registerEvents(new CombatListener(this), this);
        getServer().getPluginManager().registerEvents(new ProjectileListener(this), this);
        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);

        var command = getCommand("taketori");
        if (command != null) {
            var executor = new com.taketori.kassen.paper.command.TaketoriCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }

        validateConfig();

        getLogger().info(String.format("TaketoriKassen v%s 已启用：%d 把武器 / %d 个角色 / %d 种技能类型（适配层 %s）",
                pluginVersion(), config.weapons().size(), config.characters().size(), registry.size(), versions.name()));
        getLogger().info("debug=" + config.debug()
                + "（/taketori debug on 可临时开启；/taketori doctor 可自检识别与名字解析）");
    }

    @Override
    public void onDisable() {
        if (scheduler != null) {
            scheduler.cancelAll();
        }
        if (dataStore != null) {
            dataStore.saveAll();
        }
        if (cooldowns != null) {
            cooldowns.clearAll();
        }
        if (states != null) {
            states.clearAll();
        }
        if (skills != null) {
            skills.cooldownBars().clearAll();   // 移除所有玩家屏幕上的冷却条
        }
        // 玩法层清理：停刷怪、停占点、保存场地与战绩、撤掉记分板
        if (minions != null) {
            minions.stop();
        }
        if (baseCapture != null) {
            baseCapture.stop();
        }
        if (matchBoard != null) {
            matchBoard.clearAll();
        }
        if (arena != null) {
            arena.save();
        }
        if (setupWand != null) {
            setupWand.stop();
        }
        if (lobby != null) {
            lobby.save();
        }
        if (spectator != null) {
            spectator.clearAll();
        }
        if (stats != null) {
            stats.save();
        }
        internalDamage.clear();
    }

    /**
     * /taketori doctor 的内容：把"按了没反应"的每一个可能环节都报一遍
     * （事件是否到达 → 是不是插件武器 → 角色是否绑定 → 槽位是否绑定技能 → 名字解析是否可用）。
     */
    public List<String> doctorReport(Player player, boolean includeConfigProblems) {
        List<String> lines = new java.util.ArrayList<>();
        lines.add("版本 " + pluginVersion() + " / 适配层 " + versions.name());
        lines.add("debug=" + config.debug() + "（用 /taketori debug on 可临时开启）");
        lines.add("已加载 " + config.weapons().size() + " 把武器 / " + config.characters().size()
                + " 个角色 / " + registry.size() + " 种技能类型");
        lines.add("Q 键行为: q-mode=" + config.qMode() + describeQMode());
        lines.add("config: weapon-slots=" + config.weaponSlots()
                + " soulbound=" + config.soulbound() + " allow-drop=" + config.allowDrop());

        lines.add("--- 名字解析（解析失败会让对应效果静默失效）---");
        lines.add("能力探测: attribute=" + versions.supports("registry.attribute")
                + " particle=" + versions.supports("registry.particle")
                + " sound=" + versions.supports("registry.sound")
                + " potion=" + versions.supports("registry.potion"));
        lines.add("属性 attack_damage : " + resolveState(versions.attribute("attack_damage")));
        lines.add("属性 attack_speed  : " + resolveState(versions.attribute("attack_speed")));
        lines.add("属性 max_health    : " + resolveState(versions.attribute("max_health")));
        lines.add("粒子 FLAME         : " + resolveState(versions.particle("FLAME")));
        lines.add("粒子 SNOWFLAKE     : " + resolveState(versions.particle("SNOWFLAKE")));
        lines.add("音效 ENTITY_PLAYER_ATTACK_STRONG : " + resolveState(versions.sound("ENTITY_PLAYER_ATTACK_STRONG")));
        lines.add("音效 ITEM_TRIDENT_THROW          : " + resolveState(versions.sound("ITEM_TRIDENT_THROW")));
        lines.add("药水 SLOWNESS      : " + resolveState(versions.potionEffect("SLOWNESS")));
        lines.add("药水 RESISTANCE    : " + resolveState(versions.potionEffect("RESISTANCE")));

        if (player != null) {
            lines.add("--- 你（" + player.getName() + "）---");
            var profile = config.characters().profile(player.getUniqueId());
            lines.add("角色绑定: " + (profile.hasCharacter()
                    ? profile.characterId()
                    : "无（用 /taketori character <你> <角色id> 绑定）"));

            ItemStack hand = player.getInventory().getItemInMainHand();
            var identity = items.read(hand);
            lines.add("主手: " + hand.getType() + " / 是否插件武器: "
                    + (identity != null ? "是" : "否（用 /taketori character 或 /taketori give 领取）"));
            if (identity != null) {
                lines.add("PDC: weapon_id=" + identity.weaponId() + " character_id=" + identity.characterId()
                        + " mode=" + identity.mode() + " soulbound=" + identity.soulbound());
                var weapon = config.weapons().get(identity.weaponId());
                if (weapon == null) {
                    lines.add("!! weapon_id 在 weapons.yml 里不存在");
                } else {
                    String mode = profile.mode(weapon.id(), weapon.defaultMode());
                    lines.add("当前模式: " + mode + " / 可用模式 " + weapon.modes().keySet());
                    for (SkillSlot slot : SkillSlot.values()) {
                        var def = weapon.skill(slot, mode);
                        if (!def.isPresent()) {
                            lines.add("  " + slot.key() + ": 未绑定（走原版行为）");
                            continue;
                        }
                        double cooldown = cooldowns.remainingSeconds(
                                CooldownManager.key(player.getUniqueId(), weapon.id(), slot));
                        lines.add("  " + slot.key() + ": " + def.type()
                                + (cooldown > 0 ? "（冷却 " + SkillManager.formatSeconds(cooldown) + "s）" : "（就绪）"));
                    }
                }
            }
        }

        if (includeConfigProblems) {
            List<String> problems = new ConfigValidator(this).validate();
            if (problems.isEmpty()) {
                lines.add("配置校验: 通过");
            } else {
                lines.add("配置校验: " + problems.size() + " 个问题");
                problems.stream().limit(10).forEach(problem -> lines.add("  - " + problem));
            }
        }
        return lines;
    }

    /** 把 q-mode 翻译成人话，避免"按 Q 没反应"时看不出配置在做什么。 */
    private String describeQMode() {
        String mode = config.qMode() == null ? "" : config.qMode().toLowerCase(java.util.Locale.ROOT);
        return switch (mode) {
            case "held-slot" -> "（只切快捷栏，不触发模式切换技能）";
            case "none" -> "（只拦截丢弃，不做任何切换）";
            default -> "（触发武器 q 槽技能：模式切换 / 装备切换）";
        };
    }

    private static String resolveState(Object value) {
        return value == null ? "解析失败（该效果不会生效）" : "OK";
    }

    /** 配置校验：把"写错了但不会崩"的问题在启动 / 重载时直接列到控制台。 */
    private void validateConfig() {
        List<String> problems = new ConfigValidator(this).validate();
        if (problems.isEmpty()) {
            getLogger().info("配置校验通过");
            return;
        }
        getLogger().warning("配置校验发现 " + problems.size() + " 个问题（技能仍可运行，但请核对这些条目）：");
        for (String problem : problems) {
            getLogger().warning("  - " + problem);
        }
    }

    private void registerSkills() {
        projectileSkill = new ProjectileSkill(this);
        registry.register(new MeleeSmashSkill(this));
        registry.register(projectileSkill);
        registry.register(new SelfBoostSkill(this));
        registry.register(new BlinkSkill(this));
        registry.register(new GrappleSkill(this));
        registry.register(new SpecialShotToggleSkill(this));
        registry.register(new ShieldGuardSkill(this));
        registry.register(new MirrorSkill(this));
        registry.register(new MirrorBurstSkill(this));
        registry.register(new ModeSwitchSkill(this));
        registry.register(new EquipSwitchSkill(this));
        registry.register(new ShockwaveSkill(this));
        registry.register(new RocketJumpSkill(this));
        registry.register(new PullSkill(this));
        registry.register(new ReflectSkill(this));
    }

    /** 重载配置：失败时保留上一份可用配置，避免把服务器改坏。 */
    public boolean reloadAll(CommandSender sender) {
        try {
            config.load();
        } catch (Throwable throwable) {
            getLogger().log(Level.SEVERE, "配置重载失败", throwable);
            if (sender != null) {
                sender.sendMessage(config.messages().prefixed("command.reload-failed",
                        "reason", String.valueOf(throwable.getMessage())));
            }
            return false;
        }
        match.loadRules();
        arena.load();
        validateConfig();
        return true;
    }

    /** 服务器热重载 / 插件启用时，把在线玩家的绑定恢复出来。 */
    private void restoreOnlinePlayers() {
        for (Player player : getServer().getOnlinePlayers()) {
            String characterId = dataStore.characterIdOf(player.getUniqueId());
            if (characterId != null && config.characters().has(characterId)) {
                config.characters().bind(player.getUniqueId(), characterId);
                if (config.autoGiveOnJoin()) {
                    giveCharacterWeapons(player, characterId);
                }
            }
        }
    }

    public void bindCharacter(Player player, String characterId) {
        if (characterId == null) {
            config.characters().unbind(player.getUniqueId());
            dataStore.remove(player.getUniqueId());
            clearCharacterWeapons(player);
            return;
        }
        config.characters().bind(player.getUniqueId(), characterId);
        dataStore.setCharacterId(player.getUniqueId(), characterId);
        giveCharacterWeapons(player, characterId);
    }

    /** 按角色定义发放武器到预留槽位，并应用角色属性。 */
    public void giveCharacterWeapons(Player player, String characterId) {
        var character = config.characters().get(characterId);
        if (character == null) {
            return;
        }
        List<Integer> slots = config.weaponSlots();
        int index = 0;
        for (String weaponId : character.weapons()) {
            if (index >= slots.size()) {
                getLogger().warning("角色 " + characterId + " 的武器数量超过预留槽位数量，已忽略多余武器");
                break;
            }
            var weapon = config.weapons().get(weaponId);
            if (weapon == null) {
                continue;
            }
            int slot = slots.get(index);
            player.getInventory().setItem(slot, items.create(weapon, player));
            index++;
        }
        if (!slots.isEmpty() && index > 0) {
            player.getInventory().setHeldItemSlot(slots.get(0));
        }
        applyCharacterAttributes(player, character);
    }

    private void applyCharacterAttributes(Player player, com.taketori.kassen.core.character.CharacterDef character) {
        Attribute health = versions.attribute("max_health");
        if (health != null) {
            AttributeInstance instance = player.getAttribute(health);
            if (instance != null) {
                instance.setBaseValue(character.attribute("max-health", 20.0D));
            }
        }
        Attribute speed = versions.attribute("movement_speed");
        if (speed != null) {
            AttributeInstance instance = player.getAttribute(speed);
            if (instance != null) {
                instance.setBaseValue(character.attribute("movement-speed", 0.1D));
            }
        }
    }

    public void clearCharacterWeapons(Player player) {
        PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (items.isPluginWeapon(stack)) {
                inventory.setItem(slot, null);
            }
        }
    }

    /** 玩家退出时的清理：避免状态与冷却泄漏。 */
    public void forgetPlayer(Player player) {
        UUID uuid = player.getUniqueId();
        cooldowns.clearPlayer(uuid);
        states.clear(uuid);
        internalDamage.remove(uuid);
        if (skills != null) {
            skills.cooldownBars().clear(uuid);   // 清掉屏幕上残留的冷却条
        }
        config.characters().forget(uuid);
    }

    public void markInternalDamage(UUID uuid) {
        if (uuid != null) {
            internalDamage.add(uuid);
        }
    }

    public void unmarkInternalDamage(UUID uuid) {
        if (uuid != null) {
            internalDamage.remove(uuid);
        }
    }

    public boolean isInternalDamage(UUID uuid) {
        return uuid != null && internalDamage.contains(uuid);
    }

    /** /taketori debug 用的状态摘要。 */
    public String describe(Player player) {
        var profile = config.characters().profile(player.getUniqueId());
        StringBuilder builder = new StringBuilder();
        builder.append("character=").append(profile.hasCharacter() ? profile.characterId() : "none");

        ItemStack hand = player.getInventory().getItemInMainHand();
        var identity = items.read(hand);
        if (identity == null) {
            builder.append(" weapon=none");
        } else {
            var weapon = config.weapons().get(identity.weaponId());
            builder.append(" weapon=").append(identity.weaponId());
            builder.append(" mode=").append(profile.mode(identity.weaponId(),
                    weapon == null ? "?" : weapon.defaultMode()));
            if (weapon != null) {
                for (SkillSlot slot : SkillSlot.values()) {
                    var def = weapon.skill(slot, profile.mode(identity.weaponId(), weapon.defaultMode()));
                    if (def.isPresent()) {
                        String key = CooldownManager.key(player.getUniqueId(), weapon.id(), slot);
                        builder.append(' ').append(slot.key()).append('=').append(def.type());
                        if (!cooldowns.isReady(key)) {
                            builder.append("(cd ").append(SkillManager.formatSeconds(cooldowns.remainingSeconds(key))).append(')');
                        }
                    }
                }
            }
            builder.append(" specialShot=").append(profile.specialShot());
        }
        var defense = states.defense(player.getUniqueId());
        builder.append(" defense=").append(defense == null ? "none" : defense.source() + "("
                + SkillManager.formatSeconds(defense.remainingSeconds()) + "s)");
        builder.append(" cooldownEntries=").append(cooldowns.size());
        return builder.toString();
    }

    /**
     * 插件版本（来自 plugin.yml，而 plugin.yml 的版本由 gradle.properties 生成）。
     * 状态输出与日志都用它，便于确认服务器上跑的是哪个构建。
     */
    @SuppressWarnings("deprecation")
    public String pluginVersion() {
        return getDescription().getVersion();
    }

    // ---------------------------------------------------------------- 玩法层访问器

    public ArenaManager arena() {
        return arena;
    }

    public MatchManager match() {
        return match;
    }

    public MatchScoreboard matchBoard() {
        return matchBoard;
    }

    public MinionSpawner minions() {
        return minions;
    }

    public BaseCaptureManager baseCapture() {
        return baseCapture;
    }

    public StatsTracker stats() {
        return stats;
    }

    public SpectatorManager spectator() {
        return spectator;
    }

    public LobbyManager lobby() {
        return lobby;
    }

    public CharacterMenu characterMenu() {
        return characterMenu;
    }

    /** 选区锄（划场地用具）：物品工厂 + 选区行为 + 边框可视化。 */
    public SetupWandService setupWand() {
        return setupWand;
    }

    /** 武器数据编辑 GUI。 */
    public WeaponEditor editor() {
        return editor;
    }

    public ConfigManager config() {
        return config;
    }

    public VersionAdapter versions() {
        return versions;
    }

    public ItemFactory items() {
        return items;
    }

    public Fx fx() {
        return fx;
    }

    public SchedulerAdapter scheduler() {
        return scheduler;
    }

    public CombatStates states() {
        return states;
    }

    public SkillRegistry registry() {
        return registry;
    }

    public CooldownManager cooldowns() {
        return cooldowns;
    }

    public SkillManager skills() {
        return skills;
    }

    public ProjectileSkill projectileSkill() {
        return projectileSkill;
    }

    public PlayerDataStore dataStore() {
        return dataStore;
    }
}
