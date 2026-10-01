package com.taketori.kassen.config;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.core.skill.SkillDef;
import com.taketori.kassen.core.skill.SkillSlot;
import com.taketori.kassen.core.weapon.WeaponDef;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 配置校验：启动与 reload 时执行，把"写错了但不会崩"的问题直接列出来。
 *
 * <p>它检查的是最容易静默出错的东西：粒子名/音效名拼错、参数键拼错、
 * 技能类型未注册、弹体类型不存在、equip_switch 指向不存在的武器、
 * 以及明显不合理的数值（例如一招秒全场的伤害）。</p>
 */
public final class ConfigValidator {

    /** 每个技能类型允许的参数键。写错的键会被点名，而不是被默默忽略。 */
    private static final Map<String, Set<String>> ALLOWED_PARAMS = Map.ofEntries(
            Map.entry("melee_smash", Set.of("damage", "range", "knockback", "angle", "min-charge",
                    "combo-bonus", "combo-window", "combo-cap", "freeze-ticks", "slow-duration", "slow-amplifier",
                    "slow-type", "echo-bonus", "echo-ticks", "particle", "hit-particle", "sound", "miss-sound", "display")),
            Map.entry("projectile", Set.of("projectile", "speed", "spawn-offset", "gravity", "damage",
                    "radius", "ignite", "ignite-ticks", "freeze-ticks", "slow-duration", "slow-amplifier",
                    "knockback", "min-falloff", "particle", "hit-particle", "hit-sound", "sound", "display",
                    "homing-strength", "homing-range",
                    "echo-bonus", "echo-ticks", "mark-bonus", "mark-ticks", "armor-pierce")),
            Map.entry("shockwave", Set.of("damage", "radius", "knockback", "launch", "min-falloff",
                    "min-charge", "freeze-ticks", "slow-duration", "slow-amplifier", "slow-type",
                    "armor-pierce", "debuff-ticks", "debuff-stacks", "mark-bonus", "mark-ticks",
                    "particle", "sound", "display")),
            Map.entry("rocket_jump", Set.of("upward", "backward", "min-lift", "boost-ticks", "boost-keep-ratio",
                    "boost-mode", "potion-boost", "potion-ticks", "potion-speed", "potion-jump", "potion-slow-falling",
                    "potion-levitation", "potion-levitation-amplifier", "potion-levitation-ticks",
                    "cancel-flight", "self-damage", "fall-immunity-ticks", "particle", "sound", "display")),
            Map.entry("pull", Set.of("mode", "range", "power", "duration", "angle", "damage",
                    "decelerate-amplifier", "decelerate-ticks", "particle", "sound", "display")),
            Map.entry("reflect", Set.of("duration-ticks", "speed-multiplier", "absorption",
                    "particle", "sound", "display")),
            Map.entry("self_boost", Set.of("power", "upward", "min-lift", "boost-ticks", "boost-keep-ratio",
                    "boost-mode", "potion-boost", "potion-ticks", "potion-speed", "potion-jump", "potion-slow-falling",
                    "potion-levitation", "potion-levitation-amplifier", "potion-levitation-ticks",
                    "cancel-flight", "fall-immunity-ticks", "particle", "sound", "display")),
            Map.entry("blink", Set.of("distance", "min-distance",
                    "reserve-damage", "reserve-radius", "reserve-slow-duration", "reserve-slow-amplifier",
                    "reserve-particle", "particle", "sound", "display")),
            Map.entry("grapple", Set.of("range", "power", "upward", "particle", "sound", "display")),
            Map.entry("special_shot_toggle", Set.of("damage-multiplier", "speed-multiplier",
                    "volley-spread", "volley-interval", "mark-bonus", "mark-ticks",
                    "hit-radius", "hit-radius-ratio", "particle", "trail-particle", "sound", "display")),
            Map.entry("shield_guard", Set.of("duration-ticks", "resistance-amplifier", "absorption",
                    "reflect-ratio", "self-slow-amplifier", "self-slow-ticks",
                    "ally-radius", "ally-absorption", "ally-resistance-amplifier", "armor-pierce",
                    "particle", "sound", "display")),
            Map.entry("mirror_skill", Set.of("duration-ticks", "absorption", "reflect-ratio", "particle", "sound", "display")),
            Map.entry("mirror_burst", Set.of("radius", "damage", "slow-duration", "slow-amplifier",
                    "blindness-ticks", "particle", "sound", "display")),
            Map.entry("mode_switch", Set.of("particle", "sound", "display")),
            Map.entry("equip_switch", Set.of("target", "sound", "display")),
            // ---- 2.0 版新增的五个类型 ----
            Map.entry("mark_apply", Set.of("damage", "radius", "angle", "mark-bonus", "mark-ticks",
                    "invisible-ticks", "slow-duration", "slow-amplifier", "slow-type",
                    "particle", "sound", "display")),
            Map.entry("armor_break", Set.of("damage", "radius", "knockback", "launch", "min-falloff",
                    "armor-pierce", "debuff-ticks", "debuff-stacks",
                    "slow-duration", "slow-amplifier", "slow-type", "particle", "sound", "display")),
            Map.entry("echo_consume", Set.of("damage", "range", "angle", "knockback", "min-charge",
                    "echo-bonus", "echo-ticks", "slow-duration", "slow-amplifier", "slow-type",
                    "particle", "hit-particle", "sound", "miss-sound", "display")),
            Map.entry("deploy_zone", Set.of("radius", "duration-ticks", "period-ticks", "damage",
                    "slow-duration", "slow-amplifier", "slow-type", "heal-per-second", "absorption",
                    "ally-radius", "particle", "sound", "display")),
            Map.entry("summon_ally", Set.of("entity", "count", "health", "damage", "duration-ticks",
                    "speed-amplifier", "spawn-distance", "name", "particle", "sound", "display")));

    private static final List<String> PARTICLE_KEYS = List.of("particle", "hit-particle");
    private static final List<String> SOUND_KEYS = List.of("sound", "hit-sound", "miss-sound");
    private static final List<String> POTION_KEYS = List.of("slow-type");

    /**
     * 单次伤害超过这个值就提示"疑似过高"。
     * 基准是穿全套铁甲的玩家（减伤约 65%），所以配置值 30 对铁甲实际只有约 10.5 点，
     * 阈值定在 40 是为了拦住"多写了一位"这类真正的手滑。
     */
    private static final double DAMAGE_WARN_THRESHOLD = 40.0D;

    private final TaketoriPlugin plugin;

    public ConfigValidator(TaketoriPlugin plugin) {
        this.plugin = plugin;
    }

    public List<String> validate() {
        List<String> problems = new ArrayList<>();
        for (WeaponDef weapon : plugin.config().weapons().all()) {
            validateWeapon(weapon, problems);
        }
        for (var character : plugin.config().characters().all()) {
            for (String weaponId : character.weapons()) {
                if (!plugin.config().weapons().has(weaponId)) {
                    problems.add("characters.yml: 角色 " + character.id() + " 引用了不存在的武器 " + weaponId);
                }
            }
        }
        validateConfigKeys(problems);
        return problems;
    }

    /**
     * config.yml 里"写错就静默用默认值"的语义项（多世界范围、菜单时钟、基地数量）。
     *
     * <p>{@link #ALLOWED_PARAMS} 只管 weapons.yml 的技能参数；这些是 config.yml 的枚举 / 数值项，
     * 写错既不报错也不崩，只会在运行时表现成"怎么改都没效果"，所以在启动与 reload 时点名。</p>
     */
    private void validateConfigKeys(List<String> problems) {
        var cfg = plugin.getConfig();

        String scope = cfg.getString("worlds.broadcast-scope", "world");
        if (scope != null && !scope.isBlank()
                && !"world".equalsIgnoreCase(scope.trim()) && !"all".equalsIgnoreCase(scope.trim())) {
            problems.add("config.yml: worlds.broadcast-scope 只能是 world 或 all（当前 " + scope + "，将按 world 处理）");
        }

        for (String world : cfg.getStringList("lobby.takeover-worlds")) {
            if (world == null || world.isBlank() || "*".equals(world.trim())) {
                continue;
            }
            if (org.bukkit.Bukkit.getWorld(world.trim()) == null) {
                problems.add("config.yml: lobby.takeover-worlds 里的世界不存在或还没加载：" + world);
            }
        }

        String material = cfg.getString("menu-clock.material", "CLOCK");
        if (material != null && !material.isBlank()
                && Material.matchMaterial(material.trim().toUpperCase(Locale.ROOT)) == null) {
            problems.add("config.yml: menu-clock.material 无法解析：" + material + "（将回退到 CLOCK）");
        }

        String baseCount = cfg.getString("base.count-per-team", "3");
        if (baseCount != null && !baseCount.isBlank() && !"auto".equalsIgnoreCase(baseCount.trim())) {
            try {
                int value = Integer.parseInt(baseCount.trim());
                if (value < 1 || value > 16) {
                    problems.add("config.yml: base.count-per-team 要在 1~16 之间，或写 auto（当前 " + baseCount + "）");
                }
            } catch (NumberFormatException ex) {
                problems.add("config.yml: base.count-per-team 要写正整数（1~16）或 auto（当前 " + baseCount + "）");
            }
        }
    }

    private void validateWeapon(WeaponDef weapon, List<String> problems) {
        String where = "weapons.yml [" + weapon.id() + "]";

        if (Material.matchMaterial(weapon.materialName()) == null) {
            problems.add(where + " material 无法解析: " + weapon.materialName());
        }
        for (String attribute : weapon.attributes().keySet()) {
            String mapped = attribute.replace('-', '_');
            if (plugin.versions().attribute(mapped) == null) {
                problems.add(where + " attributes 里的 " + attribute + " 无法解析为属性名");
            }
        }

        Set<String> modes = new LinkedHashSet<>();
        modes.add(weapon.defaultMode());
        modes.addAll(weapon.modes().keySet());
        for (String mode : modes) {
            String label = (mode == null || mode.isBlank()) ? where : where + " mode=" + mode;
            for (SkillSlot slot : SkillSlot.values()) {
                validateSkill(weapon.skill(slot, mode), label + " " + slot.key(), problems);
            }
        }
    }

    private void validateSkill(SkillDef def, String where, List<String> problems) {
        if (def == null || !def.isPresent()) {
            return;
        }
        if (!plugin.registry().has(def.type())) {
            problems.add(where + " 的技能类型未注册: " + def.type());
            return;
        }
        Set<String> allowed = ALLOWED_PARAMS.getOrDefault(def.type().toLowerCase(Locale.ROOT), Set.of());
        for (String key : def.params().keySet()) {
            if (!allowed.contains(key)) {
                problems.add(where + " 出现未知参数 " + key + "（该技能支持: " + String.join(", ", allowed) + "）");
            }
        }
        for (String key : PARTICLE_KEYS) {
            if (def.has(key) && plugin.versions().particle(def.str(key, "")) == null) {
                problems.add(where + " 粒子名无法解析: " + def.str(key, ""));
            }
        }
        for (String key : SOUND_KEYS) {
            if (def.has(key) && plugin.versions().sound(def.str(key, "")) == null) {
                problems.add(where + " 音效名无法解析: " + def.str(key, ""));
            }
        }
        for (String key : POTION_KEYS) {
            if (def.has(key) && plugin.versions().potionEffect(def.str(key, "")) == null) {
                problems.add(where + " 药水效果名无法解析: " + def.str(key, ""));
            }
        }

        switch (def.type().toLowerCase(Locale.ROOT)) {
            case "projectile" -> {
                String name = def.str("projectile", "SNOWBALL");
                if (resolveEntityType(name) == null) {
                    problems.add(where + " 弹体类型无法解析: " + name);
                }
                warnIfSuspiciousDamage(def, where, problems);
            }
            case "melee_smash", "mirror_burst" -> warnIfSuspiciousDamage(def, where, problems);
            case "equip_switch" -> {
                String target = def.str("target", "");
                if (target.isBlank() || !plugin.config().weapons().has(target)) {
                    problems.add(where + " equip_switch 的 target 不存在: " + target);
                }
            }
            default -> {
                // 其余类型没有额外校验
            }
        }

        if (def.cooldownSeconds() > 120.0D) {
            problems.add(where + " 冷却时间过长（" + def.cooldownSeconds() + "s），确认是否写错单位（单位是秒）");
        }
    }

    private void warnIfSuspiciousDamage(SkillDef def, String where, List<String> problems) {
        double damage = def.dbl("damage", 0.0D);
        if (damage > DAMAGE_WARN_THRESHOLD) {
            problems.add(where + " 伤害 " + damage + " 偏高（玩家满血只有 20），确认是否写错单位或小数位");
        }
        if (damage < 0.0D) {
            problems.add(where + " 伤害为负数: " + damage);
        }
    }

    private EntityType resolveEntityType(String name) {
        if (name == null || name.isBlank()) {
            return EntityType.SNOWBALL;
        }
        try {
            return EntityType.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            try {
                return EntityType.valueOf(name.toUpperCase(Locale.ROOT).replace('.', '_'));
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
    }
}
