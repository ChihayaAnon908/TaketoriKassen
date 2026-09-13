package com.taketori.kassen.paper.match;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.core.match.MatchRules;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Zombie;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 小怪刷新器：在中央刷怪区生成"40 血铁甲僵尸"，双方抢怪各得 3 分。
 *
 * <p>要点：</p>
 * <ul>
 *   <li>血量与装备都由 matches.yml 控制（默认 40 血 + 全套铁甲）；</li>
 *   <li>追踪小怪 UUID 集合，死亡时才能准确判断"这是小怪而不是玩家"；</li>
 *   <li>数量达到上限就不再刷，避免堆怪拖垮服务器；</li>
 *   <li>对局结束或插件卸载时会清掉场上剩余小怪。</li>
 * </ul>
 */
public final class MinionSpawner {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final TaketoriPlugin plugin;
    private final Set<UUID> minions = ConcurrentHashMap.newKeySet();
    private BukkitTask task;

    public MinionSpawner(TaketoriPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        stop();
        MatchRules rules = plugin.match().rules();
        long interval = Math.max(20L, rules.minionIntervalSeconds() * 20L);
        task = plugin.scheduler().runTimerTask(this::tick, interval, interval);
        if (plugin.config().debug()) {
            plugin.getLogger().info("[match] 小怪刷新启动：每 " + rules.minionIntervalSeconds()
                    + " 秒 " + rules.minionPerSpawn() + " 只，上限 " + rules.minionMaxAlive());
        }
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        removeAll();
    }

    /** 场上存活的小怪数量（顺带清理已失效的引用）。 */
    public int aliveCount() {
        int alive = 0;
        for (UUID uuid : new ArrayList<>(minions)) {
            Entity entity = plugin.getServer().getEntity(uuid);
            if (entity == null || entity.isDead() || !entity.isValid()) {
                minions.remove(uuid);
                continue;
            }
            alive++;
        }
        return alive;
    }

    public boolean isMinion(Entity entity) {
        return entity != null && minions.contains(entity.getUniqueId());
    }

    public void onMinionDeath(Entity entity) {
        if (entity != null) {
            minions.remove(entity.getUniqueId());
        }
    }

    private void tick() {
        if (!plugin.match().isRunning()) {
            return;
        }
        CuboidRegion region = plugin.arena().minionRegion();
        if (region == null || region.world() == null) {
            return;
        }
        MatchRules rules = plugin.match().rules();
        int alive = aliveCount();
        int budget = rules.minionMaxAlive() - alive;
        if (budget <= 0) {
            return;
        }
        int spawnCount = Math.min(budget, Math.max(1, rules.minionPerSpawn()));
        for (int i = 0; i < spawnCount; i++) {
            spawnOne(region, rules);
        }
    }

    private void spawnOne(CuboidRegion region, MatchRules rules) {
        Location location = region.randomLocation();
        if (location == null) {
            return;
        }
        Zombie zombie = region.world().spawn(location, Zombie.class, spawned -> {
            spawned.customName(MINI.deserialize("<red>月人</red>"));
            spawned.setCustomNameVisible(true);
            spawned.setPersistent(false);
            spawned.setRemoveWhenFarAway(true);
            spawned.setShouldBurnInDay(false);   // 白天不要被烧死

            Attribute maxHealth = plugin.versions().attribute("max_health");
            if (maxHealth != null) {
                AttributeInstance instance = spawned.getAttribute(maxHealth);
                if (instance != null) {
                    instance.setBaseValue(rules.minionHealth());
                }
            }
            spawned.setHealth(Math.min(rules.minionHealth(), maxHealth(rules)));

            if (rules.minionIronArmor()) {
                EntityEquipment equipment = spawned.getEquipment();
                if (equipment != null) {
                    equipment.setHelmet(new ItemStack(Material.IRON_HELMET));
                    equipment.setChestplate(new ItemStack(Material.IRON_CHESTPLATE));
                    equipment.setLeggings(new ItemStack(Material.IRON_LEGGINGS));
                    equipment.setBoots(new ItemStack(Material.IRON_BOOTS));
                    // 不掉装备，避免刷装备
                    equipment.setHelmetDropChance(0.0F);
                    equipment.setChestplateDropChance(0.0F);
                    equipment.setLeggingsDropChance(0.0F);
                    equipment.setBootsDropChance(0.0F);
                }
            }
        });
        minions.add(zombie.getUniqueId());
    }

    private double maxHealth(MatchRules rules) {
        return Math.max(1.0D, rules.minionHealth());
    }

    private void removeAll() {
        for (UUID uuid : new ArrayList<>(minions)) {
            Entity entity = plugin.getServer().getEntity(uuid);
            if (entity != null) {
                entity.remove();
            }
        }
        minions.clear();
    }
}
