package com.taketori.kassen.paper.skill.impl;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.paper.item.PDCKeys;
import com.taketori.kassen.paper.skill.Skill;
import com.taketori.kassen.paper.skill.SkillContext;
import com.taketori.kassen.paper.skill.SkillResult;
import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Fireball;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

import java.util.Locale;
import java.util.Optional;

/**
 * 弹体技能：火箭弹、金棒远程、冰冻弹。
 *
 * <p>只负责"发射"，并把伤害/半径/衰减/点燃/减速等参数写进弹体 PDC；
 * 命中结算由 {@code ProjectileListener} 统一处理，原版弹体伤害会被取消，
 * 因此不会出现"原版伤害 + 技能伤害"双重结算。</p>
 *
 * <p>关于点燃：<b>不使用火球的原版点燃</b>（那会连地形一起点着，把地图烧了），
 * 而是记录 {@code ignite-ticks}，命中时只点燃被打到的实体。</p>
 */
public final class ProjectileSkill implements Skill {

    private final TaketoriPlugin plugin;

    public ProjectileSkill(TaketoriPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String type() {
        return "projectile";
    }

    @Override
    public SkillResult execute(SkillContext context) {
        Player player = context.player();
        EntityType entityType = resolveType(context.str("projectile", "SNOWBALL"));
        if (entityType == null) {
            plugin.getLogger().warning("无法解析弹体类型: " + context.str("projectile", "?"));
            return SkillResult.FAILED;
        }

        double speed = context.dbl("speed", 1.6D);
        double spawnOffset = context.dbl("spawn-offset", 0.7D);
        Location eye = player.getEyeLocation();
        Location spawn = eye.clone().add(eye.getDirection().normalize().multiply(spawnOffset));
        Vector velocity = eye.getDirection().normalize().multiply(speed);

        var entity = player.getWorld().spawnEntity(spawn, entityType);
        if (!(entity instanceof Projectile projectile)) {
            entity.remove();
            plugin.getLogger().warning("弹体类型不是投掷物: " + entityType);
            return SkillResult.FAILED;
        }
        projectile.setShooter(player);
        projectile.setVelocity(velocity);
        if (context.has("gravity")) {
            projectile.setGravity(context.bool("gravity", true));
        }
        if (projectile instanceof Fireball fireball) {
            // 原版点燃与爆炸破坏一律关闭，全部交给技能参数控制
            fireball.setIsIncendiary(false);
            fireball.setYield(0.0F);
        }

        var pdc = projectile.getPersistentDataContainer();
        pdc.set(PDCKeys.projDamage(), PersistentDataType.DOUBLE, context.dbl("damage", 6.0D));
        pdc.set(PDCKeys.projRadius(), PersistentDataType.DOUBLE, context.dbl("radius", 2.0D));
        pdc.set(PDCKeys.projIgnite(), PersistentDataType.BYTE, (byte) (context.bool("ignite", false) ? 1 : 0));
        pdc.set(PDCKeys.projIgniteTicks(), PersistentDataType.INTEGER, context.integer("ignite-ticks", 60));
        pdc.set(PDCKeys.projSlowDuration(), PersistentDataType.INTEGER, context.integer("slow-duration", 0));
        pdc.set(PDCKeys.projSlowAmplifier(), PersistentDataType.INTEGER, context.integer("slow-amplifier", 0));
        pdc.set(PDCKeys.projKnockback(), PersistentDataType.DOUBLE, context.dbl("knockback", 0.0D));
        pdc.set(PDCKeys.projMinFalloff(), PersistentDataType.DOUBLE, context.dbl("min-falloff", 0.5D));
        pdc.set(PDCKeys.projHitSound(), PersistentDataType.STRING, context.str("hit-sound", "ENTITY_GENERIC_EXPLODE"));
        pdc.set(PDCKeys.projHitParticle(), PersistentDataType.STRING, context.str("hit-particle", context.str("particle", "CRIT")));
        pdc.set(PDCKeys.projShooter(), PersistentDataType.STRING, player.getUniqueId().toString());
        pdc.set(PDCKeys.projFreezeTicks(), PersistentDataType.INTEGER, context.integer("freeze-ticks", 0));

        plugin.fx().sound(context.str("sound", "ENTITY_SNOWBALL_THROW"), player, 0.8F, 1.0F);
        plugin.fx().particle(context.str("particle", "CRIT"), spawn, 5, 0.12D);
        return SkillResult.SUCCESS;
    }

    private EntityType resolveType(String name) {
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

    public Optional<Double> damageOf(Projectile projectile) {
        return Optional.ofNullable(projectile.getPersistentDataContainer().get(PDCKeys.projDamage(), PersistentDataType.DOUBLE));
    }

    public double radiusOf(Projectile projectile, double fallback) {
        Double value = projectile.getPersistentDataContainer().get(PDCKeys.projRadius(), PersistentDataType.DOUBLE);
        return value == null ? fallback : value;
    }

    public boolean igniteOf(Projectile projectile) {
        Byte value = projectile.getPersistentDataContainer().get(PDCKeys.projIgnite(), PersistentDataType.BYTE);
        return value != null && value == 1;
    }

    public int igniteTicksOf(Projectile projectile) {
        Integer value = projectile.getPersistentDataContainer().get(PDCKeys.projIgniteTicks(), PersistentDataType.INTEGER);
        return value == null ? 60 : value;
    }

    public int slowDurationOf(Projectile projectile) {
        Integer value = projectile.getPersistentDataContainer().get(PDCKeys.projSlowDuration(), PersistentDataType.INTEGER);
        return value == null ? 0 : value;
    }

    public int slowAmplifierOf(Projectile projectile) {
        Integer value = projectile.getPersistentDataContainer().get(PDCKeys.projSlowAmplifier(), PersistentDataType.INTEGER);
        return value == null ? 0 : value;
    }

    public double knockbackOf(Projectile projectile) {
        Double value = projectile.getPersistentDataContainer().get(PDCKeys.projKnockback(), PersistentDataType.DOUBLE);
        return value == null ? 0.0D : value;
    }

    public double minFalloffOf(Projectile projectile) {
        Double value = projectile.getPersistentDataContainer().get(PDCKeys.projMinFalloff(), PersistentDataType.DOUBLE);
        return value == null ? 0.5D : Math.max(0.0D, Math.min(1.0D, value));
    }

    /** 命中后定身多少 tick（0 = 不定身）。 */
    public int freezeTicksOf(Projectile projectile) {
        Integer value = projectile.getPersistentDataContainer().get(PDCKeys.projFreezeTicks(), PersistentDataType.INTEGER);
        return value == null ? 0 : value;
    }

    public String hitSoundOf(Projectile projectile) {
        String value = projectile.getPersistentDataContainer().get(PDCKeys.projHitSound(), PersistentDataType.STRING);
        return value == null ? "ENTITY_GENERIC_EXPLODE" : value;
    }

    public String hitParticleOf(Projectile projectile) {
        String value = projectile.getPersistentDataContainer().get(PDCKeys.projHitParticle(), PersistentDataType.STRING);
        return value == null ? "CRIT" : value;
    }

    /** 该弹体是否由插件技能发射。 */
    public boolean isSkillProjectile(Projectile projectile) {
        return damageOf(projectile).isPresent();
    }
}
