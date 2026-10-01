package com.taketori.kassen.paper.listener;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.core.character.PlayerProfile;
import com.taketori.kassen.core.skill.SkillDef;
import com.taketori.kassen.core.skill.SkillSlot;
import com.taketori.kassen.core.weapon.WeaponDef;
import com.taketori.kassen.paper.item.ItemFactory;
import com.taketori.kassen.paper.item.PDCKeys;
import com.taketori.kassen.paper.skill.impl.ProjectileSkill;
import com.taketori.kassen.paper.state.CombatStates;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 战斗结算层。
 *
 * <p>职责：近战改写、易伤标记、镜面反射、弹体原版伤害取消、防御反弹、摔落免疫，
 * 以及弓的特殊射击与三连射。</p>
 *
 * <p><b>弓的两个机制是解耦的</b>（踩过的坑）：</p>
 * <ul>
 *   <li>特殊射击：需要 shift-right 槽绑定了技能<b>且</b>开关已开；</li>
 *   <li>三连射：只取决于当前模式是不是 VOLLEY，<b>与 shift-right 是否绑定无关</b>。
 *       早先写成"shift-right 未绑定就 return"，会把三连射一起吃掉。</li>
 * </ul>
 */
public final class CombatListener implements Listener {

    private static final int VOLLEY_EXTRA_ARROWS = 2;

    private final TaketoriPlugin plugin;

    public CombatListener(TaketoriPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMeleeHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) {
            return;
        }
        if (!(event.getEntity() instanceof LivingEntity)) {
            return;
        }
        debug(String.format("%s 近战命中 %s（原版伤害 %.2f）", player.getName(), event.getEntityType(), event.getDamage()));

        if (plugin.isInternalDamage(player.getUniqueId())) {
            debug("└ 这是插件技能自己造成的伤害 → 放行，不改写");
            return;
        }

        if (event.getEntity() instanceof Player victim) {
            reflectMelee(victim, player, event);
        }

        ItemStack hand = player.getInventory().getItemInMainHand();
        ItemFactory.Identity identity = plugin.items().read(hand);
        if (identity == null) {
            debug("└ 主手不是插件武器 → 保留原版近战");
            return;
        }
        WeaponDef weapon = plugin.config().weapons().get(identity.weaponId());
        if (weapon == null) {
            debug("└ weapon_id=" + identity.weaponId() + " 未定义 → 保留原版近战");
            return;
        }
        PlayerProfile profile = plugin.config().characters().profile(player.getUniqueId());
        String mode = profile.mode(weapon.id(), weapon.defaultMode());
        SkillDef skill = weapon.skill(SkillSlot.LEFT, mode);
        if (!skill.isPresent()) {
            debug("└ " + weapon.id() + " 的 left 槽未绑定技能 → 保留原版近战");
            return;
        }

        // 蓄力门控（C10）：不满蓄的挥击保留原版轻击伤害，不再"挥了完全没反应"——奖励攻击节奏
        float attackCharge = player.getAttackCooldown();
        double chargeGate = plugin.config().meleeChargeGate();
        if (chargeGate > 0.0D && attackCharge < chargeGate) {
            debug(String.format("└ 蓄力 %.2f < %.2f → 保留原版轻击", attackCharge, chargeGate));
            return;
        }

        event.setCancelled(true);
        profile.markSwing(player.getWorld().getGameTime(), weapon.id());
        debug("└ 已取消原版伤害，改由技能 " + skill.type() + " 结算（mode=" + mode + "）");
        plugin.skills().dispatch(player, hand, SkillSlot.LEFT, true);
    }

    /** 易伤标记：被标记的目标，受到的所有来源伤害都提高。 */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMarkedDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof LivingEntity victim)) {
            return;
        }
        double bonus = plugin.states().markBonus(victim.getUniqueId());
        if (bonus <= 0.0D) {
            return;
        }
        event.setDamage(event.getDamage() * (1.0D + bonus));
        debug(String.format("%s 身上有易伤标记 → 本次伤害 ×%.2f", victim.getName(), 1.0D + bonus));
    }

    /** 镜面反射：把打到你的飞行物弹回去。对近战无效（设计取舍）。 */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onReflectProjectile(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        if (!(event.getDamager() instanceof Projectile projectile)) {
            return;
        }
        CombatStates.Reflection reflection = plugin.states().reflection(victim.getUniqueId());
        if (reflection == null) {
            return;
        }
        event.setCancelled(true);
        projectile.setVelocity(projectile.getVelocity().multiply(-reflection.speedMultiplier()));
        projectile.setShooter(victim);
        plugin.fx().particle("END_ROD", victim.getLocation().add(0.0D, 1.0D, 0.0D), 20, 0.4D);
        plugin.fx().sound("BLOCK_GLASS_BREAK", victim, 0.8F, 1.8F);
        debug(victim.getName() + " 的镜面把 " + projectile.getType() + " 弹了回去");
    }

    /** 插件弹体的原版撞击/爆炸伤害一律取消，只由命中监听器结算一次。 */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onSkillProjectileDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Projectile projectile)) {
            return;
        }
        ProjectileSkill skill = plugin.projectileSkill();
        if (skill != null && skill.isSkillProjectile(projectile)) {
            event.setCancelled(true);
            debug("取消插件弹体（" + projectile.getType() + "）的原版伤害，改由命中结算");
        }
    }

    /** 推进技能的落地保护：命中摔落伤害时直接免掉（而不是给缓降药水）。 */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFallDamage(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.FALL) {
            return;
        }
        if (event.getEntity() instanceof Player player
                && plugin.states().isFallImmune(player.getUniqueId())) {
            event.setCancelled(true);
            debug(player.getName() + " 的摔落伤害被免伤窗口取消");
        }
    }

    private void reflectMelee(Player victim, Player attacker, EntityDamageByEntityEvent event) {
        CombatStates.Defense defense = plugin.states().defense(victim.getUniqueId());
        if (defense == null || defense.reflectRatio() <= 0.0D) {
            return;
        }
        double reflected = event.getDamage() * Math.min(1.0D, defense.reflectRatio());
        if (reflected <= 0.0D) {
            return;
        }
        debug(String.format("%s 处于防御窗口（%s）→ 反弹 %.2f 给 %s",
                victim.getName(), defense.source(), reflected, attacker.getName()));
        plugin.fx().particle("ENCHANTED_HIT", victim.getLocation().add(0.0D, 1.0D, 0.0D), 10, 0.3D);
        plugin.fx().sound("BLOCK_ANVIL_LAND", victim, 0.6F, 1.6F);
        plugin.markInternalDamage(victim.getUniqueId());
        try {
            attacker.damage(reflected, victim);
        } finally {
            plugin.unmarkInternalDamage(victim.getUniqueId());
        }
    }

    /** 弓：特殊射击强化（含易伤标记与命中范围伤害）+ VOLLEY 模式连发。 */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onShootBow(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        WeaponDef weapon = pluginWeaponOf(event.getBow());
        if (weapon == null) {
            return;
        }
        double damageMultiplier = tagArrow(player, weapon, event.getProjectile());
        scheduleVolley(player, weapon, event.getProjectile().getVelocity().clone(), damageMultiplier);
    }

    /**
     * 没有箭也能射（乃依的弓靠它实现「无箭射击」）。
     *
     * <p>原版在背包里没有箭时<b>根本不会发射</b>——连 {@link EntityShootBowEvent} 都不触发，
     * 所以只能在右键这一刻接管：手持插件弓 + 手里没箭 → 手动放一支箭，并走与普通射击
     * 完全相同的标记与三连射逻辑；有箭时什么都不做，照旧交给原版。</p>
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBowWithoutArrow(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE) {
            return;   // 创造模式原版就能射，不必我们操心
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType() != Material.BOW) {
            return;
        }
        WeaponDef weapon = pluginWeaponOf(hand);
        if (weapon == null) {
            return;   // 只给插件弓这个待遇，普通弓照原版
        }
        if (hasAnyArrow(player)) {
            return;   // 有箭：走原版 EntityShootBowEvent
        }
        // 按满蓄力箭的速度补射（原版满蓄约 3.0），方向取视线
        Arrow arrow = player.launchProjectile(Arrow.class,
                player.getEyeLocation().getDirection().multiply(3.0D));
        double damageMultiplier = tagArrow(player, weapon, arrow);
        scheduleVolley(player, weapon, arrow.getVelocity().clone(), damageMultiplier);
        debug("└ 无箭射击：背包里没有箭，按插件弓规则补射一支");
    }

    /** 读出手里这把弓绑定的插件武器；不是插件武器返回 null。 */
    private WeaponDef pluginWeaponOf(ItemStack stack) {
        ItemFactory.Identity identity = plugin.items().read(stack);
        if (identity == null) {
            return null;
        }
        return plugin.config().weapons().get(identity.weaponId());
    }

    /** 背包（含副手）里有没有任何可用的箭。 */
    private boolean hasAnyArrow(Player player) {
        var inventory = player.getInventory();
        return inventory.contains(Material.ARROW)
                || inventory.contains(Material.SPECTRAL_ARROW)
                || inventory.contains(Material.TIPPED_ARROW);
    }

    /**
     * 把这一箭的附加效果写到箭上：命中减益（所有箭都吃）+ 特殊射击强化（速度 / 伤害 / 易伤 / 范围）。
     *
     * @return 伤害倍率，供三连射的后续箭矢继承
     */
    private double tagArrow(Player player, WeaponDef weapon, Entity projectile) {
        PlayerProfile profile = plugin.config().characters().profile(player.getUniqueId());
        String mode = profile.mode(weapon.id(), weapon.defaultMode());
        SkillDef def = weapon.skill(SkillSlot.SHIFT_RIGHT, mode);

        debug(String.format("%s 射箭 weapon=%s mode=%s 特殊射击开关=%s shift-right绑定=%s",
                player.getName(), weapon.id(), mode, profile.specialShot(), def.isPresent()));

        // ③ 命中附加效果：把"随机负面效果"的名单写到箭上，命中时才抽签（乃依的压制手段）
        //    这一条与特殊射击、三连射都无关，所以放在最前面，普通箭也吃得到
        tagArrowDebuffs(projectile, weapon);

        // ① 特殊射击：需要 shift-right 绑定技能 + 开关已开
        if (def.isPresent() && profile.specialShot()) {
            double speedMultiplier = def.dbl("speed-multiplier", 1.15D);
            double damageMultiplier = def.dbl("damage-multiplier", 1.5D);
            projectile.setVelocity(projectile.getVelocity().multiply(speedMultiplier));

            var pdc = projectile.getPersistentDataContainer();
            pdc.set(PDCKeys.arrowMultiplier(), PersistentDataType.DOUBLE, damageMultiplier);

            double markBonus = def.dbl("mark-bonus", 0.0D);
            if (markBonus > 0.0D) {
                pdc.set(PDCKeys.arrowMarkBonus(), PersistentDataType.DOUBLE, markBonus);
                pdc.set(PDCKeys.arrowMarkTicks(), PersistentDataType.INTEGER, def.integer("mark-ticks", 100));
            }
            double hitRadius = def.dbl("hit-radius", 0.0D);
            if (hitRadius > 0.0D) {
                pdc.set(PDCKeys.arrowHitRadius(), PersistentDataType.DOUBLE, hitRadius);
                pdc.set(PDCKeys.arrowHitRatio(), PersistentDataType.DOUBLE, def.dbl("hit-radius-ratio", 0.3D));
            }

            plugin.fx().particle(def.str("particle", "CRIT"), projectile.getLocation(), 20, 0.3D);
            plugin.fx().sound(def.str("sound", "BLOCK_BEACON_ACTIVATE"), player, 0.8F, 1.6F);
            // 飞行拖尾：让"这一箭被强化了"肉眼可辨（否则玩家感觉不到特殊射击生效）
            startTrail(projectile, def.str("trail-particle", "CRIT"));

            debug(String.format("└ 特殊射击生效：速度 ×%.2f 伤害 ×%.2f 易伤 +%.0f%% 命中半径 %.1f",
                    speedMultiplier, damageMultiplier, markBonus * 100.0D, hitRadius));
            return damageMultiplier;
        }
        return 1.0D;
    }

    /** ② 三连射：只看模式，与 shift-right 是否绑定无关。 */
    private void scheduleVolley(Player player, WeaponDef weapon, Vector base, double damageMultiplier) {
        PlayerProfile profile = plugin.config().characters().profile(player.getUniqueId());
        String mode = profile.mode(weapon.id(), weapon.defaultMode());
        int extraArrows = "VOLLEY".equalsIgnoreCase(mode) ? VOLLEY_EXTRA_ARROWS : 0;
        if (extraArrows <= 0) {
            return;
        }
        SkillDef def = weapon.skill(SkillSlot.SHIFT_RIGHT, mode);
        // 间隔连发 + 微小散布：同一 tick 同向发射的话三支箭完全重叠，看起来只有一支
        int interval = Math.max(0, def.isPresent() ? def.integer("volley-interval", 2) : 2);
        double spreadDegrees = Math.max(0.0D, def.isPresent() ? def.dbl("volley-spread", 1.5D) : 1.5D);
        double multiplier = damageMultiplier;

        for (int i = 1; i <= extraArrows; i++) {
            final int index = i;
            plugin.scheduler().runLater(() -> {
                if (!player.isOnline() || player.isDead()) {
                    return;
                }
                Vector direction = spreadDegrees <= 0.0D
                        ? base.clone()
                        : rotateAroundY(base, Math.toRadians(spreadDegrees * index) * (index % 2 == 0 ? -1.0D : 1.0D));
                Arrow arrow = player.launchProjectile(Arrow.class, direction);
                if (multiplier > 1.0D) {
                    arrow.getPersistentDataContainer()
                            .set(PDCKeys.arrowMultiplier(), PersistentDataType.DOUBLE, multiplier);
                }
                // 三连射的每一支箭同样带上命中附加效果
                tagArrowDebuffs(arrow, weapon);
                debug("└ 三连射第 " + index + " 支箭（延迟 " + (interval * index) + " tick，散布 " + spreadDegrees + "°）");
            }, (long) interval * i);
        }
        debug("└ 三连射：额外发射 " + extraArrows + " 支箭");
        plugin.fx().sound("ITEM_CROSSBOW_SHOOT", player, 0.8F, 1.1F);
    }

    /** 特殊射击的飞行拖尾：每 2 tick 在箭的位置生成粒子，箭消失后自行结束。 */
    private void startTrail(Entity projectile, String particle) {
        if (particle == null || particle.isBlank()) {
            return;
        }
        final BukkitTask[] holder = new BukkitTask[1];
        final int[] elapsed = {0};
        holder[0] = plugin.scheduler().runTimerTask(() -> {
            elapsed[0]++;
            if (elapsed[0] > 120 || !(projectile instanceof Arrow arrow) || arrow.isDead() || !arrow.isValid()) {
                if (holder[0] != null) {
                    holder[0].cancel();
                }
                return;
            }
            plugin.fx().particle(particle, arrow.getLocation(), 3, 0.08D);
        }, 0L, 2L);
    }

    /** 被强化的箭矢命中：按倍率结算伤害、打易伤标记、并对周围造成范围伤害。 */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onArrowDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Arrow arrow)) {
            return;
        }
        var pdc = arrow.getPersistentDataContainer();

        Double multiplier = pdc.get(PDCKeys.arrowMultiplier(), PersistentDataType.DOUBLE);
        double finalDamage = event.getDamage();
        if (multiplier != null && multiplier > 1.0D) {
            finalDamage = event.getDamage() * multiplier;
            event.setDamage(finalDamage);
            debug(String.format("强化箭命中 → 伤害 ×%.2f", multiplier));
            plugin.fx().particle("CRIT", event.getEntity().getLocation().add(0.0D, 1.0D, 0.0D), 12, 0.25D);
        }
        // 伤害数字（A1）：强化箭/普通箭命中都汇报给射手
        if (arrow.getShooter() instanceof Player owner
                && event.getEntity() instanceof LivingEntity victim) {
            plugin.damageNumbers().hit(owner, victim, finalDamage);
        }

        // 命中范围伤害：让特殊射击不只是"数字变大"，而是能打到一片
        Double hitRadius = pdc.get(PDCKeys.arrowHitRadius(), PersistentDataType.DOUBLE);
        if (hitRadius != null && hitRadius > 0.0D) {
            Double ratio = pdc.get(PDCKeys.arrowHitRatio(), PersistentDataType.DOUBLE);
            double splash = finalDamage * (ratio == null ? 0.3D : ratio);
            var center = event.getEntity().getLocation();
            Entity shooter = arrow.getShooter() instanceof Entity entity ? entity : null;
            int hit = 0;
            for (Entity nearby : center.getWorld().getNearbyEntities(center, hitRadius, hitRadius, hitRadius)) {
                if (!(nearby instanceof LivingEntity living) || nearby.equals(event.getEntity()) || living.isDead()) {
                    continue;
                }
                if (shooter != null && shooter.equals(nearby)) {
                    continue;
                }
                if (shooter instanceof Player owner) {
                    plugin.markInternalDamage(owner.getUniqueId());
                }
                try {
                    if (shooter != null) {
                        living.damage(splash, shooter);
                    } else {
                        living.damage(splash);
                    }
                    hit++;
                } finally {
                    if (shooter instanceof Player owner) {
                        plugin.unmarkInternalDamage(owner.getUniqueId());
                    }
                }
            }
            plugin.fx().particle("CRIT", center, 20, hitRadius * 0.4D);
            plugin.fx().sound("ENTITY_GENERIC_EXPLODE", center, 0.7F, 1.4F);
            debug(String.format("特殊射击命中范围：半径 %.1f 内额外命中 %d 个目标（每个 %.2f 伤害）",
                    hitRadius, hit, splash));
        }

        Double markBonus = pdc.get(PDCKeys.arrowMarkBonus(), PersistentDataType.DOUBLE);
        if (markBonus != null && markBonus > 0.0D && event.getEntity() instanceof LivingEntity victim) {
            Integer ticks = pdc.get(PDCKeys.arrowMarkTicks(), PersistentDataType.INTEGER);
            plugin.states().mark(victim.getUniqueId(), ticks == null ? 100 : ticks, markBonus);
            debug(String.format("标记 %s：易伤 +%.0f%%", victim.getName(), markBonus * 100.0D));
            plugin.fx().particle("ENCHANTED_HIT", victim.getLocation().add(0.0D, 1.5D, 0.0D), 14, 0.3D);
        }

        // 命中附加负面效果（乃依的箭：每次命中随机抽一种减益）
        applyArrowDebuffs(pdc, arrow, event.getEntity());
    }

    /** 把武器的 hit-effects 写进箭矢 PDC（命中时才抽签决定具体是哪种减益）。 */
    private void tagArrowDebuffs(Entity projectile, WeaponDef weapon) {
        if (projectile == null || weapon == null) {
            return;
        }
        List<String> pool = weapon.hitEffectList("arrow-debuffs");
        if (pool.isEmpty()) {
            return;
        }
        double chance = weapon.hitEffectDbl("arrow-debuff-chance", 1.0D);
        if (chance <= 0.0D) {
            return;
        }
        PersistentDataContainer pdc = projectile.getPersistentDataContainer();
        pdc.set(PDCKeys.arrowDebuffs(), PersistentDataType.STRING, String.join(",", pool));
        pdc.set(PDCKeys.arrowDebuffTicks(), PersistentDataType.INTEGER,
                Math.max(20, weapon.hitEffectInt("arrow-debuff-ticks", 80)));
        pdc.set(PDCKeys.arrowDebuffAmplifier(), PersistentDataType.INTEGER,
                Math.max(0, weapon.hitEffectInt("arrow-debuff-amplifier", 0)));
        pdc.set(PDCKeys.arrowDebuffChance(), PersistentDataType.DOUBLE, Math.min(1.0D, chance));
        debug("箭矢已带上附加效果名单：" + String.join("/", pool) + "（概率 " + chance + "）");
    }

    /**
     * 命中时从名单里随机抽一种负面效果施加。
     *
     * <p>队友免疫：命中同队玩家时不挂效果（否则乃依会不停坑队友）。
     * 效果名解析失败只记 debug 日志、不抛异常——玩家不该因为配置里写错一个药水名就看到报错。</p>
     */
    private void applyArrowDebuffs(PersistentDataContainer pdc, Arrow arrow, Entity hitEntity) {
        String list = pdc.get(PDCKeys.arrowDebuffs(), PersistentDataType.STRING);
        if (list == null || list.isBlank() || !(hitEntity instanceof LivingEntity victim) || victim.isDead()) {
            return;
        }
        Entity shooter = arrow.getShooter() instanceof Entity entity ? entity : null;
        if (victim instanceof Player other && shooter instanceof Player owner && !other.equals(owner)) {
            // 多房间：两人必须在同一房间，队友才免疫减益
            var victimRoom = plugin.rooms().roomOf(other);
            if (victimRoom != null && victimRoom == plugin.rooms().roomOf(owner)) {
                var shooterTeam = victimRoom.teamOf(owner.getUniqueId());
                var victimTeam = victimRoom.teamOf(other.getUniqueId());
                if (shooterTeam != null && shooterTeam == victimTeam) {
                    return;
                }
            }
        }
        Double chance = pdc.get(PDCKeys.arrowDebuffChance(), PersistentDataType.DOUBLE);
        if (chance != null && chance < 1.0D && ThreadLocalRandom.current().nextDouble() > chance) {
            debug("箭矢附加效果未触发（概率 " + chance + "）");
            return;
        }
        String[] pool = list.split(",");
        String picked = pool[ThreadLocalRandom.current().nextInt(pool.length)].trim();
        PotionEffectType type = plugin.versions().potionEffect(picked);
        if (type == null) {
            debug("箭矢附加效果名解析失败：" + picked + "（跳过；可用 /taketori doctor 检查名字解析）");
            return;
        }
        Integer ticks = pdc.get(PDCKeys.arrowDebuffTicks(), PersistentDataType.INTEGER);
        Integer amplifier = pdc.get(PDCKeys.arrowDebuffAmplifier(), PersistentDataType.INTEGER);
        int duration = ticks == null ? 80 : ticks;
        int level = amplifier == null ? 0 : amplifier;
        victim.addPotionEffect(new PotionEffect(type, duration, level, false, true, true));
        plugin.fx().particle("ENCHANTED_HIT", victim.getLocation().add(0.0D, 1.2D, 0.0D), 10, 0.3D);
        debug("箭矢附加效果生效：" + picked + "（" + duration + " tick，等级 " + (level + 1) + "）");
    }

    private Vector rotateAroundY(Vector vector, double radians) {
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        return new Vector(
                vector.getX() * cos - vector.getZ() * sin,
                vector.getY(),
                vector.getX() * sin + vector.getZ() * cos);
    }

    private void debug(String message) {
        if (plugin.config().debug()) {
            plugin.getLogger().info("[combat] " + message);
        }
    }
}
