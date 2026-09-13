package com.taketori.kassen.paper.match;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.core.match.MatchRules;
import com.taketori.kassen.core.match.TeamId;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

/**
 * 对局相关的事件监听：击杀计分、死亡旁观、复活、退出清理。
 *
 * <p>死亡流程（需求：死亡旁观）：</p>
 * <ol>
 *   <li>死亡时按规则计分、保留物品；</li>
 *   <li>下一 tick 自动跳过死亡界面（{@code spigot().respawn()}）；</li>
 *   <li>重生事件里把玩家切成<b>旁观</b>并看向战场；</li>
 *   <li>倒计时结束恢复生存并传送到己方出生点。</li>
 * </ol>
 */
public final class MatchListener implements Listener {

    private final TaketoriPlugin plugin;

    public MatchListener(TaketoriPlugin plugin) {
        this.plugin = plugin;
    }

    /** 小怪被玩家击杀 → 按配置加分。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMinionDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        if (!(entity instanceof org.bukkit.entity.Zombie)) {
            return;
        }
        if (!plugin.minions().isMinion(entity)) {
            return;
        }
        plugin.minions().onMinionDeath(entity);

        Player killer = entity.getKiller();
        if (killer == null || !plugin.match().isRunning()) {
            return;
        }
        MatchRules rules = plugin.match().rules();
        plugin.match().addScore(killer, rules.minionKillScore(), "击杀月人");
        double healed = healOnKill(killer, rules.minionKillHeal());
        plugin.matchBoard().actionBar(killer, "<green>+<white>" + rules.minionKillScore()
                + " <gray>击杀月人" + healText(healed));
    }

    /** 玩家死亡：计分、保留物品、安排跳过死亡界面。 */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        MatchRules rules = plugin.match().rules();

        if (!plugin.match().isRunning()) {
            return;
        }
        if (rules.keepInventory()) {
            event.setKeepInventory(true);
            event.getDrops().clear();
            event.setKeepLevel(true);
            event.setDroppedExp(0);
        }
        Player killer = victim.getKiller();
        if (killer != null) {
            TeamId victimTeam = plugin.match().teamOf(victim.getUniqueId());
            TeamId killerTeam = plugin.match().teamOf(killer.getUniqueId());
            if (killerTeam != null && victimTeam != null && killerTeam != victimTeam) {
                plugin.match().addScore(killer, rules.playerKillScore(), "击杀 " + victim.getName());
                // 击杀回血（需求）：满血时不回、也不刷提示
                double healed = healOnKill(killer, rules.playerKillHeal());
                plugin.matchBoard().actionBar(killer, "<green>+<white>" + rules.playerKillScore()
                        + " <gray>击杀 " + victim.getName() + healText(healed));
                if (plugin.config().debug()) {
                    plugin.getLogger().info("[match] " + killer.getName() + " 击杀 " + victim.getName()
                            + "，回血 " + healed + " 点");
                }
            }
        }

        // 死亡旁观：1 tick 后跳过死亡界面，交给 onRespawn 接管
        String victimName = victim.getName();
        plugin.scheduler().runLater(() -> {
            Player player = plugin.getServer().getPlayerExact(victimName);
            if (player != null && player.isOnline() && player.isDead()) {
                player.spigot().respawn();
            }
        }, 1L);
    }

    /** 重生：先进入死亡旁观，倒计时结束后复活到己方出生点。 */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (!plugin.match().isRunning()) {
            return;
        }
        TeamId team = plugin.match().teamOf(player.getUniqueId());
        if (team == null) {
            return;
        }
        Location spawn = plugin.arena().spawn(team);
        if (spawn != null) {
            event.setRespawnLocation(spawn);
        }

        // 观战视角：优先看中央刷怪区（战场热点）
        Location viewPoint = null;
        if (plugin.arena().minionRegion() != null) {
            viewPoint = plugin.arena().minionRegion().center();
        }
        plugin.spectator().enterTemporary(player, viewPoint);

        int delay = Math.max(1, plugin.match().rules().respawnDelaySeconds()) * 20;
        String name = player.getName();
        plugin.scheduler().runLater(() -> {
            Player target = plugin.getServer().getPlayerExact(name);
            if (target == null || !target.isOnline()) {
                return;
            }
            if (!plugin.match().isRunning()) {
                plugin.spectator().leave(target, plugin.lobby().spawn());
                return;
            }
            plugin.spectator().leave(target, spawn);
            plugin.matchBoard().showTo(target);
            plugin.matchBoard().actionBar(target, "<green>已复活，返回战场！");
        }, delay);
    }

    /**
     * 友伤保护：**同一队**的玩家互相不造成伤害（近战、箭矢、技能弹体都算）。
     *
     * <p>是否生效看 <code>combat.friendly-fire-protection</code>：
     * 默认 <code>auto</code> = <b>PVP 开启、PVE 关闭</b>；
     * 想让 PVE 里也不许互相打，把它设成 <code>on</code> 即可。</p>
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFriendlyFire(EntityDamageByEntityEvent event) {
        if (!plugin.match().isRunning() || !plugin.match().isFriendlyFireProtected()) {
            return;
        }
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        Player attacker = attackerOf(event.getDamager());
        if (attacker == null || attacker.equals(victim)) {
            return;   // 自伤（技能反噬之类）不拦
        }
        TeamId attackerTeam = plugin.match().teamOf(attacker.getUniqueId());
        TeamId victimTeam = plugin.match().teamOf(victim.getUniqueId());
        if (attackerTeam == null || victimTeam == null || attackerTeam != victimTeam) {
            return;   // 不同队、或有人不在比赛里 → 正常结算
        }
        event.setCancelled(true);
        if (plugin.config().debug()) {
            plugin.getLogger().info("[match] 友伤保护：已取消 " + attacker.getName()
                    + " 对队友 " + victim.getName() + " 的伤害");
        }
    }

    private Player attackerOf(Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) {
            return shooter;
        }
        return null;
    }

    /** 玩家退出：撤掉记分板；对局中的队伍保留（回来还能继续）。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        plugin.matchBoard().hide(player);
        if (plugin.config().debug() && plugin.match().isRunning()) {
            plugin.getLogger().info("[match] " + player.getName() + " 退出对局（队伍保留）");
        }
    }

    /**
     * 击杀回血：回复指定"生命点"（2 点 = 1 颗心），不会超过最大生命值。
     *
     * <p>用 {@code getMaxHealth()} 而不是写死 20，因为角色属性会改上限
     * （辉夜之类血量不同的角色也能正确回满）。</p>
     *
     * @return 实际回复的点数；0 表示没回复（配置为 0、已经满血、或击杀者已阵亡）
     */
    private double healOnKill(Player killer, double amount) {
        if (amount <= 0.0D || killer == null || !killer.isOnline() || killer.isDead()) {
            return 0.0D;
        }
        double max = killer.getMaxHealth();
        double before = killer.getHealth();
        double after = Math.min(max, before + amount);
        double healed = after - before;
        if (healed <= 0.0D) {
            return 0.0D;   // 满血：不刷提示、不放特效
        }
        killer.setHealth(after);
        plugin.fx().particle("HEART", killer.getLocation().add(0.0D, 1.2D, 0.0D), 6, 0.4D);
        plugin.fx().sound("ENTITY_PLAYER_LEVELUP", killer, 0.35F, 1.7F);
        return healed;
    }

    /** 回血提示片段（" · 回血 +3❤"）；没回血时是空串，直接拼在击杀提示后面。 */
    private String healText(double healed) {
        return healed <= 0.0D ? "" : " <green>· 回血 +" + formatHearts(healed) + "❤";
    }

    /** 生命点 → 颗心（整数不带小数）。 */
    private String formatHearts(double healthPoints) {
        double hearts = healthPoints / 2.0D;
        return hearts == Math.floor(hearts)
                ? Long.toString((long) hearts)
                : String.format(java.util.Locale.ROOT, "%.1f", hearts);
    }
}
