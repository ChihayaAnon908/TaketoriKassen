package com.taketori.kassen.paper.skill.impl;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.paper.skill.Skill;
import com.taketori.kassen.paper.skill.SkillContext;
import com.taketori.kassen.paper.skill.SkillManager;
import com.taketori.kassen.paper.skill.SkillResult;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * 强化防御（雷）：给玩家一个时间窗，窗内减伤、吸收伤害，并按比例反弹近战伤害。
 *
 * <p>不新增按键，也不与举盾的原版行为冲突——举盾照旧由原版处理，
 * 这个技能只叠加一个增益窗口。</p>
 */
public final class ShieldGuardSkill implements Skill {

    private final TaketoriPlugin plugin;

    public ShieldGuardSkill(TaketoriPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String type() {
        return "shield_guard";
    }

    @Override
    public SkillResult execute(SkillContext context) {
        Player player = context.player();
        int duration = context.integer("duration-ticks", 100);
        double reflection = context.dbl("reflect-ratio", 0.3D);
        double absorption = context.dbl("absorption", 6.0D);
        int amplifier = Math.max(0, context.integer("resistance-amplifier", 1));

        plugin.states().setDefense(player.getUniqueId(), duration, reflection, absorption, context.weapon().id());

        // 壁垒模式的取舍：用机动换硬度（给自己挂缓慢）
        int selfSlow = Math.max(0, context.integer("self-slow-amplifier", 0));
        if (selfSlow > 0) {
            PotionEffectType slow = plugin.versions().potionEffect("SLOWNESS");
            if (slow != null) {
                player.addPotionEffect(new PotionEffect(slow,
                        context.integer("self-slow-ticks", duration), selfSlow, false, false, true));
            }
        }

        PotionEffectType resistance = plugin.versions().potionEffect("RESISTANCE");
        if (resistance != null) {
            player.addPotionEffect(new PotionEffect(resistance, duration, amplifier, false, true, true));
        }
        if (absorption > 0.0D) {
            player.setAbsorptionAmount(player.getAbsorptionAmount() + absorption);
        }

        plugin.fx().actionBar(player, plugin.config().messages().get("skill.guard-ready",
                "time", SkillManager.formatSeconds(duration / 20.0D)));
        plugin.fx().sound(context.str("sound", "BLOCK_ANVIL_LAND"), player, 0.8F, 1.2F);
        plugin.fx().particle(context.str("particle", "ENCHANTED_HIT"), player.getLocation().add(0.0D, 1.0D, 0.0D), 24, 0.5D);
        return SkillResult.SUCCESS;
    }
}
