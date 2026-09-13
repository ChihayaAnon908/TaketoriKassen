package com.taketori.kassen.paper.lobby;

import com.taketori.kassen.TaketoriPlugin;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEvent;

import java.util.Locale;

/**
 * 大厅交互：告示牌点击 + 大厅保护。
 *
 * <p>告示牌动作与 {@link LobbySign} 对应，全部走 {@link LobbyManager} 的公开方法，
 * 所以其他插件不依赖告示牌也能触发同一套流程。</p>
 */
public final class LobbyListener implements Listener {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final TaketoriPlugin plugin;

    public LobbyListener(TaketoriPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }
        LobbySign sign = plugin.lobby().signAt(block);
        if (sign == null) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        String action = sign.action().toLowerCase(Locale.ROOT);

        // 直接选角色：character:<id>
        if (action.startsWith("character:")) {
            String characterId = action.substring("character:".length());
            var character = plugin.config().characters().get(characterId);
            if (character == null) {
                player.sendMessage(MINI.deserialize("<red>该角色不存在：" + characterId));
                return;
            }
            plugin.bindCharacter(player, characterId);
            player.sendMessage(MINI.deserialize("<green>已选择角色 " + character.display()));
            return;
        }

        switch (action) {
            case "join" -> plugin.lobby().queue(player);
            case "leave" -> {
                plugin.lobby().dequeue(player);
                // 观众走观众退出（清掉观众标记），其他人按普通旁观恢复
                if (!plugin.spectator().leaveAudience(player)) {
                    plugin.spectator().leave(player, plugin.lobby().spawn());
                }
            }
            case "spectate" -> plugin.spectator().enterAudience(player);
            case "character" -> plugin.characterMenu().open(player);
            case "lobby" -> plugin.lobby().sendToLobby(player);
            default -> player.sendMessage(MINI.deserialize("<red>未知的告示牌动作：" + action));
        }
    }

    /** 大厅保护：大厅内不掉血（默认开启，可在 config.yml 关闭）。 */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!plugin.config().lobbyProtect()) {
            return;
        }
        if (event.getEntity() instanceof Player player && plugin.lobby().isInLobby(player)) {
            event.setCancelled(true);
        }
    }
}
