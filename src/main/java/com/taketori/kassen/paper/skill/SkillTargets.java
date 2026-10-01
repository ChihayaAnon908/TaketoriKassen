package com.taketori.kassen.paper.skill;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.core.match.TeamId;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 技能选目标的公共判定：友伤保护、圆形范围、视线扇形。
 *
 * <p>抽出来的原因：2.0 版新增的 {@code mark_apply} / {@code armor_break} 等技能都要用同一套
 * "谁算敌人"的规则，散在各类里复制粘贴迟早会走偏（例如漏掉旁观者、或把队友也算成目标）。
 * 判定口径与 {@code MeleeSmashSkill} 原有实现保持一致。</p>
 */
public final class SkillTargets {

    private SkillTargets() {
    }

    /**
     * 对方是不是"受友伤保护的同队队友"：是则整个目标跳过——
     * 伤害事件会被友伤处理器取消，但击退 / 减速 / 标记等控制效果不会，必须在这里拦截。
     */
    public static boolean isProtectedTeammate(TaketoriPlugin plugin, Player caster, Player other) {
        var room = plugin.rooms().roomOf(caster);
        if (room == null || !room.isFriendlyFireProtected()) {
            return false;
        }
        if (plugin.rooms().roomOf(other) != room) {
            return false;
        }
        TeamId casterTeam = room.teamOf(caster.getUniqueId());
        TeamId otherTeam = room.teamOf(other.getUniqueId());
        return casterTeam != null && casterTeam == otherTeam;
    }

    /** 旁观者（或非存活）跳过。 */
    public static boolean isUntargetable(Player player) {
        return player == null || player.isDead() || player.getGameMode().name().equals("SPECTATOR");
    }

    /** 以 {@code center} 为圆心、{@code radius} 格内的敌方生物（含月人等非玩家生物）。 */
    public static List<LivingEntity> enemiesInRadius(TaketoriPlugin plugin, Player caster,
                                                     Location center, double radius) {
        List<LivingEntity> targets = new ArrayList<>();
        if (center == null || center.getWorld() == null) {
            return targets;
        }
        for (Entity entity : center.getWorld().getNearbyEntities(center, radius, radius, radius)) {
            if (!(entity instanceof LivingEntity living) || entity.equals(caster) || living.isDead()) {
                continue;
            }
            if (entity instanceof Player other
                    && (isUntargetable(other) || isProtectedTeammate(plugin, caster, other))) {
                continue;
            }
            targets.add(living);
        }
        return targets;
    }

    /**
     * 视线前方、半径 {@code range} 内、与视线夹角不超过 {@code halfAngleDegrees} 的敌方生物。
     * 与 {@code MeleeSmashSkill} 的扇形筛选同口径。
     */
    public static List<LivingEntity> enemiesInCone(TaketoriPlugin plugin, Player caster,
                                                   double range, double halfAngleDegrees) {
        List<LivingEntity> targets = new ArrayList<>();
        Location eye = caster.getEyeLocation();
        Vector direction = eye.getDirection().normalize();
        double halfAngle = Math.toRadians(halfAngleDegrees);
        for (Entity entity : caster.getWorld().getNearbyEntities(eye, range, range, range)) {
            if (!(entity instanceof LivingEntity living) || entity.equals(caster) || living.isDead()) {
                continue;
            }
            if (entity instanceof Player other
                    && (isUntargetable(other) || isProtectedTeammate(plugin, caster, other))) {
                continue;
            }
            Vector toTarget = living.getLocation().add(0.0D, living.getHeight() * 0.5D, 0.0D)
                    .toVector().subtract(eye.toVector());
            if (toTarget.length() > range || toTarget.length() < 0.01D) {
                continue;
            }
            if (direction.angle(toTarget) > halfAngle) {
                continue;
            }
            targets.add(living);
        }
        return targets;
    }

    /**
     * 统一的"选目标"入口：{@code radius >= rayThreshold} 时走视线射线（远程角色用它做"瞄准射击"），
     * 否则走以自身为圆心的范围判定。
     */
    public static List<LivingEntity> select(TaketoriPlugin plugin, Player caster,
                                            double radius, double angleDegrees, double rayThreshold) {
        if (radius >= rayThreshold) {
            return enemiesInCone(plugin, caster, radius, angleDegrees);
        }
        return enemiesInRadius(plugin, caster, caster.getLocation(), radius);
    }

    /**
     * 目标身上是否有"可兑现"的前置状态：易伤标记 / 破甲 / 减速 / 冻结，四选一。
     *
     * <p>{@code echo_consume} 技能与弹体的 {@code echo-bonus} 共用这份判定——两条兑现链路
     * 必须对"什么算前置"有完全一致的理解，否则同一套连招换个武器就失效。</p>
     */
    public static boolean hasConsumableState(TaketoriPlugin plugin, LivingEntity target) {
        if (target == null) {
            return false;
        }
        UUID id = target.getUniqueId();
        if (plugin.states().markBonus(id) > 0.0D) {
            return true;
        }
        if (plugin.states().armorBreak(id) != null) {
            return true;
        }
        if (target.getFreezeTicks() > 0) {
            return true;
        }
        PotionEffectType slow = plugin.versions().potionEffect("SLOWNESS");
        return slow != null && target.hasPotionEffect(slow);
    }
}
