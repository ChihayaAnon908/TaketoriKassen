package com.taketori.kassen.paper.item;

import org.bukkit.NamespacedKey;
import org.bukkit.plugin.Plugin;

/**
 * PDC 键定义。身份识别的唯一依据（设计稿「识别原则」：
 * 绝不依赖显示名或 Lore，玩家改名改 Lore 后插件仍要能识别）。
 *
 * <p>除了武器身份，这里也存放"由技能发射的弹体/箭矢"的参数——弹体必须在飞行途中
 * 带着伤害、半径、衰减、点燃、定身、易伤标记等数值，否则命中时无从知道该结算什么。</p>
 */
public final class PDCKeys {

    /** 物品数据版本，将来做物品迁移（ItemMigrator）时靠它判断。 */
    public static final int CURRENT_DATA_VERSION = 1;

    private static NamespacedKey characterId;
    private static NamespacedKey weaponId;
    private static NamespacedKey mode;
    private static NamespacedKey instanceId;
    private static NamespacedKey owner;
    private static NamespacedKey soulbound;
    private static NamespacedKey dataVersion;
    private static NamespacedKey setupWand;
    /** 菜单时钟：右键打开玩家菜单的道具（与武器无关，人人可用）。 */
    private static NamespacedKey menuClock;

    private static NamespacedKey projDamage;
    private static NamespacedKey projRadius;
    private static NamespacedKey projIgnite;
    private static NamespacedKey projIgniteTicks;
    private static NamespacedKey projSlowDuration;
    private static NamespacedKey projSlowAmplifier;
    private static NamespacedKey projKnockback;
    private static NamespacedKey projMinFalloff;
    private static NamespacedKey projHitSound;
    private static NamespacedKey projHitParticle;
    private static NamespacedKey projShooter;
    private static NamespacedKey projFreezeTicks;

    private static NamespacedKey arrowMultiplier;
    private static NamespacedKey arrowMarkBonus;
    private static NamespacedKey arrowMarkTicks;
    private static NamespacedKey arrowHitRadius;
    private static NamespacedKey arrowHitRatio;
    private static NamespacedKey arrowDebuffs;
    private static NamespacedKey arrowDebuffTicks;
    private static NamespacedKey arrowDebuffAmplifier;
    private static NamespacedKey arrowDebuffChance;

    /** 召唤物归属（UUID 字符串）：用于友伤拦截、同队判定与结算不计分。 */
    private static NamespacedKey summonedOwner;
    /** 召唤物到期时间（毫秒时间戳）：房间清理时也据此兜底移除。 */
    private static NamespacedKey summonedExpire;
    /** 场地技能归属（UUID 字符串）：玩家退出 / 房间销毁时按它清理名下的领域。 */
    private static NamespacedKey zoneOwner;

    /** 弹体的兑现倍率：命中时目标身上有前置状态则放大伤害。 */
    private static NamespacedKey projEchoBonus;
    /** 弹体命中时给目标挂的易伤标记强度 / 时长。 */
    private static NamespacedKey projMarkBonus;
    private static NamespacedKey projMarkTicks;
    /** 弹体命中时给目标挂的破甲强度。 */
    private static NamespacedKey projArmorPierce;

    private PDCKeys() {
    }

    public static void init(Plugin plugin) {
        characterId = new NamespacedKey(plugin, "character_id");
        weaponId = new NamespacedKey(plugin, "weapon_id");
        mode = new NamespacedKey(plugin, "mode");
        instanceId = new NamespacedKey(plugin, "instance_id");
        owner = new NamespacedKey(plugin, "owner");
        soulbound = new NamespacedKey(plugin, "soulbound");
        dataVersion = new NamespacedKey(plugin, "data_version");
        setupWand = new NamespacedKey(plugin, "setup_wand");
        menuClock = new NamespacedKey(plugin, "menu_clock");

        projDamage = new NamespacedKey(plugin, "proj_damage");
        projRadius = new NamespacedKey(plugin, "proj_radius");
        projIgnite = new NamespacedKey(plugin, "proj_ignite");
        projIgniteTicks = new NamespacedKey(plugin, "proj_ignite_ticks");
        projSlowDuration = new NamespacedKey(plugin, "proj_slow_duration");
        projSlowAmplifier = new NamespacedKey(plugin, "proj_slow_amplifier");
        projKnockback = new NamespacedKey(plugin, "proj_knockback");
        projMinFalloff = new NamespacedKey(plugin, "proj_min_falloff");
        projHitSound = new NamespacedKey(plugin, "proj_hit_sound");
        projHitParticle = new NamespacedKey(plugin, "proj_hit_particle");
        projShooter = new NamespacedKey(plugin, "proj_shooter");
        projFreezeTicks = new NamespacedKey(plugin, "proj_freeze_ticks");

        arrowMultiplier = new NamespacedKey(plugin, "arrow_multiplier");
        arrowMarkBonus = new NamespacedKey(plugin, "arrow_mark_bonus");
        arrowMarkTicks = new NamespacedKey(plugin, "arrow_mark_ticks");
        arrowHitRadius = new NamespacedKey(plugin, "arrow_hit_radius");
        arrowHitRatio = new NamespacedKey(plugin, "arrow_hit_ratio");

        arrowDebuffs = new NamespacedKey(plugin, "arrow_debuffs");
        arrowDebuffTicks = new NamespacedKey(plugin, "arrow_debuff_ticks");
        arrowDebuffAmplifier = new NamespacedKey(plugin, "arrow_debuff_amplifier");
        arrowDebuffChance = new NamespacedKey(plugin, "arrow_debuff_chance");

        summonedOwner = new NamespacedKey(plugin, "summoned_owner");
        summonedExpire = new NamespacedKey(plugin, "summoned_expire");
        zoneOwner = new NamespacedKey(plugin, "zone_owner");

        projEchoBonus = new NamespacedKey(plugin, "proj_echo_bonus");
        projMarkBonus = new NamespacedKey(plugin, "proj_mark_bonus");
        projMarkTicks = new NamespacedKey(plugin, "proj_mark_ticks");
        projArmorPierce = new NamespacedKey(plugin, "proj_armor_pierce");
    }

    public static NamespacedKey characterId() {
        return characterId;
    }

    public static NamespacedKey weaponId() {
        return weaponId;
    }

    /** 展示用冗余：mode 的真源在 PlayerProfile（计划 §3.3）。 */
    public static NamespacedKey mode() {
        return mode;
    }

    public static NamespacedKey instanceId() {
        return instanceId;
    }

    public static NamespacedKey owner() {
        return owner;
    }

    public static NamespacedKey soulbound() {
        return soulbound;
    }

    public static NamespacedKey dataVersion() {
        return dataVersion;
    }

    /** 选区锄标记：管理员划场地用的工具，不属于武器（有它才不会被当普通锄头）。 */
    public static NamespacedKey setupWand() {
        return setupWand;
    }

    /** 菜单时钟的标记键。 */
    public static NamespacedKey menuClock() {
        return menuClock;
    }

    public static NamespacedKey projDamage() {
        return projDamage;
    }

    public static NamespacedKey projRadius() {
        return projRadius;
    }

    public static NamespacedKey projIgnite() {
        return projIgnite;
    }

    /** 命中后点燃目标多少 tick（只点燃实体，不点燃地形）。 */
    public static NamespacedKey projIgniteTicks() {
        return projIgniteTicks;
    }

    public static NamespacedKey projSlowDuration() {
        return projSlowDuration;
    }

    public static NamespacedKey projSlowAmplifier() {
        return projSlowAmplifier;
    }

    public static NamespacedKey projKnockback() {
        return projKnockback;
    }

    /** 爆炸边缘保留的最低伤害比例（1.0 = 无衰减）。 */
    public static NamespacedKey projMinFalloff() {
        return projMinFalloff;
    }

    public static NamespacedKey projHitSound() {
        return projHitSound;
    }

    public static NamespacedKey projHitParticle() {
        return projHitParticle;
    }

    /** 弹体的发射者 UUID，便于命中时正确归属伤害。 */
    public static NamespacedKey projShooter() {
        return projShooter;
    }

    /** 弹体命中后定身多少 tick（0 = 不定身）。 */
    public static NamespacedKey projFreezeTicks() {
        return projFreezeTicks;
    }

    /** 特殊射击的伤害倍率，写在被强化的箭矢上。 */
    public static NamespacedKey arrowMultiplier() {
        return arrowMultiplier;
    }

    /** 命中后给目标打的"易伤标记"比例（0.25 = 受到伤害 +25%）。 */
    public static NamespacedKey arrowMarkBonus() {
        return arrowMarkBonus;
    }

    /** 易伤标记持续多少 tick。 */
    public static NamespacedKey arrowMarkTicks() {
        return arrowMarkTicks;
    }

    /** 特殊射击命中时的范围伤害半径（0 = 无范围伤害）。 */
    public static NamespacedKey arrowHitRadius() {
        return arrowHitRadius;
    }

    /** 范围伤害相对于主伤害的比例。 */
    public static NamespacedKey arrowHitRatio() {
        return arrowHitRatio;
    }

    /** 命中时要随机挑一种施加的负面效果名单（逗号分隔，写在箭矢上）。 */
    public static NamespacedKey arrowDebuffs() {
        return arrowDebuffs;
    }

    /** 负面效果持续时间（tick）。 */
    public static NamespacedKey arrowDebuffTicks() {
        return arrowDebuffTicks;
    }

    /** 负面效果等级（0 = I 级）。 */
    public static NamespacedKey arrowDebuffAmplifier() {
        return arrowDebuffAmplifier;
    }

    /** 触发概率（1.0 = 每箭必触发）。 */
    public static NamespacedKey arrowDebuffChance() {
        return arrowDebuffChance;
    }

    /** 召唤物归属（UUID 字符串）。 */
    public static NamespacedKey summonedOwner() {
        return summonedOwner;
    }

    /** 召唤物到期时间（毫秒时间戳）。 */
    public static NamespacedKey summonedExpire() {
        return summonedExpire;
    }

    /** 场地技能归属（UUID 字符串）。 */
    public static NamespacedKey zoneOwner() {
        return zoneOwner;
    }

    /** 弹体的兑现倍率（>1 时命中带前置状态的目标会放大伤害）。 */
    public static NamespacedKey projEchoBonus() {
        return projEchoBonus;
    }

    /** 弹体命中时施加的易伤标记强度。 */
    public static NamespacedKey projMarkBonus() {
        return projMarkBonus;
    }

    /** 弹体命中时施加的易伤标记时长（tick）。 */
    public static NamespacedKey projMarkTicks() {
        return projMarkTicks;
    }

    /** 弹体命中时施加的破甲强度。 */
    public static NamespacedKey projArmorPierce() {
        return projArmorPierce;
    }
}
