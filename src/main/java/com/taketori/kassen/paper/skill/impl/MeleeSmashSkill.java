package com.taketori.kassen.paper.skill.impl;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.core.match.TeamId;
import com.taketori.kassen.paper.skill.Skill;
import com.taketori.kassen.paper.skill.SkillContext;
import com.taketori.kassen.paper.skill.SkillResult;
import com.taketori.kassen.version.VersionAdapter;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;

/**
 * 近战范围攻击：对视线前方扇形内的目标造成伤害、击退，可选减速、定身或连击叠伤。
 *
 * <p><b>攻击节奏由原版攻击冷却控制</b>（武器的 attack-speed 属性）：冷却未满不结算伤害，
 * 这是防止连点无限刷伤害的关键。</p>
 *
 * <p>技能伤害就是配置里的 {@code damage}；近战的原版伤害已在 CombatListener 里取消，
 * 所以不存在"原版 + 技能"双重结算。</p>
 */
public final class MeleeSmashSkill implements Skill {

    private final TaketoriPlugin plugin;

    public MeleeSmashSkill(TaketoriPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String type() {
        return "melee_smash";
    }

    @Override
    public SkillResult execute(SkillContext context) {
        Player player = context.player();

        // 攻击冷却未就绪 → 本次挥击不结算伤害（空挥也不结算，与原版一致）
        float charge = player.getAttackCooldown();
        double minCharge = context.dbl("min-charge", 0.9D);
        if (charge < minCharge) {
            return SkillResult.NO_TARGET;
        }

        double damage = context.dbl("damage", 6.0D);
        // 连击：短时间内的连续命中让伤害递增（彩叶的剑靠这个吃贴脸）
        double comboBonus = context.dbl("combo-bonus", 0.0D);
        if (comboBonus > 0.0D) {
            int stacks = plugin.states().bumpChain(player.getUniqueId(), context.weapon().id(),
                    context.integer("combo-window", 60));
            damage *= 1.0D + stacks * comboBonus;
            // 上限：叠到一定程度就不再加（0 = 不封顶），避免贴脸连打把人一秒秒掉
            double cap = context.dbl("combo-cap", 0.0D);
            if (cap > 0.0D && damage > cap) {
                damage = cap;
            }
            if (plugin.config().debug() && stacks > 0) {
                plugin.getLogger().info("[combat] melee 连击第 " + (stacks + 1) + " 段，伤害 "
                        + String.format("%.2f", damage)
                        + (cap > 0.0D ? "（上限 " + String.format("%.0f", cap) + "）" : ""));
            }
            // 连击可视化（C11）：叠上第 2 段起在动作栏报层数
            if (stacks > 0) {
                plugin.fx().actionBar(player, net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                        .deserialize("<yellow>连击 <white>×" + (stacks + 1) + "</white> <gray>伤害提升"));
            }
        }
        double range = context.dbl("range", 3.0D);
        double knockback = context.dbl("knockback", 0.35D);
        double halfAngle = Math.toRadians(context.dbl("angle", 55.0D));
        int slowDuration = context.integer("slow-duration", 0);
        int freezeTicks = context.integer("freeze-ticks", 0);

        Location eye = player.getEyeLocation();
        Vector direction = eye.getDirection().normalize();

        List<LivingEntity> targets = new ArrayList<>();
        for (Entity entity : player.getWorld().getNearbyEntities(eye, range, range, range)) {
            if (!(entity instanceof LivingEntity living) || entity.equals(player) || living.isDead()) {
                continue;
            }
            if (entity instanceof Player other) {
                if (other.getGameMode().name().equals("SPECTATOR")
                        || isProtectedTeammate(player, other)) {
                    continue;
                }
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

        for (LivingEntity target : targets) {
            target.damage(damage, player);
            plugin.damageNumbers().hit(player, target, damage);   // 伤害数字（A1）
            if (knockback > 0.0D) {
                Vector push = target.getLocation().toVector().subtract(player.getLocation().toVector());
                push.setY(0.0D);
                if (push.lengthSquared() > 0.0001D) {
                    push.normalize().multiply(knockback).setY(0.2D);
                    target.setVelocity(target.getVelocity().add(push));
                }
            }
            if (slowDuration > 0) {
                applySlow(context, target, slowDuration);
            }
            if (freezeTicks > 0) {
                applyFreeze(target, freezeTicks);
            }
        }

        Location impact = eye.clone().add(direction.clone().multiply(Math.min(range, 2.0D)));
        plugin.fx().particle(context.str("particle", "SWEEP_ATTACK"), impact, targets.isEmpty() ? 1 : 3, 0.3D);
        if (!targets.isEmpty()) {
            plugin.fx().particle(context.str("hit-particle", "CRIT"), impact, 5, 0.3D);
            plugin.fx().sound(context.str("sound", "ENTITY_PLAYER_ATTACK_STRONG"), player, 0.8F, 1.0F);
        } else {
            plugin.fx().sound(context.str("miss-sound", "ENTITY_PLAYER_ATTACK_WEAK"), player, 0.4F, 1.2F);
        }
        return SkillResult.SUCCESS;
    }

    /**
     * 对方是不是"受友伤保护的同队队友"：是则整个目标跳过——
     * 伤害事件会被友伤处理器取消，但击退 / 减速 / 定身等控制效果不会，必须在此拦截。
     */
    private boolean isProtectedTeammate(Player caster, Player other) {
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

    private void applySlow(SkillContext context, LivingEntity target, int durationTicks) {
        VersionAdapter versions = plugin.versions();
        PotionEffectType type = versions.potionEffect(context.str("slow-type", "SLOWNESS"));
        if (type == null) {
            return;
        }
        int amplifier = Math.max(0, context.integer("slow-amplifier", 0));
        target.addPotionEffect(new PotionEffect(type, durationTicks, amplifier, false, true, true));
    }

    /** 定身：缓慢拉满 + 负跳跃（纯原版药水效果，跨版本安全）。 */
    private void applyFreeze(LivingEntity target, int ticks) {
        PotionEffectType slow = plugin.versions().potionEffect("SLOWNESS");
        if (slow != null) {
            target.addPotionEffect(new PotionEffect(slow, ticks, 250, false, true, true));
        }
        PotionEffectType jump = plugin.versions().potionEffect("JUMP_BOOST");
        if (jump != null) {
            target.addPotionEffect(new PotionEffect(jump, ticks, 128, false, false, true));
        }
    }
}
