package com.taketori.kassen.paper.listener;

import com.taketori.kassen.TaketoriPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * 玩家生命周期：恢复角色绑定，退出时清理状态。
 *
 * <p>清理很重要——冷却表、防御窗口、内部伤害标记如果随玩家退出而不释放，
 * 长时间运行的服务器会慢性泄漏。</p>
 */
public final class PlayerListener implements Listener {

    private final TaketoriPlugin plugin;

    public PlayerListener(TaketoriPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String characterId = plugin.dataStore().characterIdOf(player.getUniqueId());
        if (characterId != null && plugin.config().characters().has(characterId)) {
            plugin.config().characters().bind(player.getUniqueId(), characterId);
            if (plugin.config().autoGiveOnJoin()) {
                plugin.scheduler().runLater(() -> plugin.giveCharacterWeapons(player, characterId), 20L);
            }
        }
        // 进服统一送到大厅（可在 config.yml 的 lobby.teleport-on-join 关闭）
        if (plugin.config().lobbyTeleportOnJoin() && plugin.lobby().isConfigured()) {
            plugin.scheduler().runLater(() -> plugin.lobby().sendToLobby(player), 10L);
        }
        // 有管理权限的玩家自动发一把选区锄（可在 config.yml 的 setup-wand.give-on-join 关闭）
        if (plugin.config().setupWandGiveOnJoin() && player.hasPermission("taketori.admin")) {
            plugin.scheduler().runLater(() -> plugin.setupWand().wand().give(player), 30L);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        plugin.forgetPlayer(event.getPlayer());
    }
}
