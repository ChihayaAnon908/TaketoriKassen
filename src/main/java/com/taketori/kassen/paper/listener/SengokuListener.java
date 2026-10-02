package com.taketori.kassen.paper.listener;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.core.match.TeamId;
import com.taketori.kassen.paper.match.room.GameRoom;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 战国 3v3 的事件入口。
 *
 * <p>目前只做天守阁保护；后续阶段会在这里挂铜钟交互、击破器拾取与护栏、跳跃台触发。
 * 集中在一个监听器里而不是散着注册，是因为这些事件都要先做同一个前置判断
 * （"这个玩家 / 方块属于哪个战国房间"）。</p>
 */
public final class SengokuListener implements Listener {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    /** 提示节流间隔：天守阁本来就打不动，每次挖都发消息会刷屏。 */
    private static final long NOTICE_INTERVAL_MILLIS = 3000L;

    private final TaketoriPlugin plugin;
    private final Map<UUID, Long> lastNotice = new ConcurrentHashMap<>();

    public SengokuListener(TaketoriPlugin plugin) {
        this.plugin = plugin;
    }

    /** 天守阁：拦截玩家的方块破坏。 */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        GameRoom room = sengokuRoomOf(event.getPlayer());
        if (room == null || !room.keep().isProtectedBlock(event.getBlock().getLocation())) {
            return;
        }
        event.setCancelled(true);
        noticeKeepProtected(room, event.getPlayer());
    }

    /** 天守阁：拦截实体爆炸（TNT / 苦力怕）。 */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        filterExplosion(event.blockList(), event.getLocation().getWorld().getName());
    }

    /** 天守阁：拦截方块爆炸（床 / 重生锚）。 */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        filterExplosion(event.blockList(), event.getBlock().getWorld().getName());
    }

    /**
     * 把落在天守阁范围内的方块从爆炸列表里摘掉。
     *
     * <p>刻意<b>不整体取消爆炸</b>：那会顺带废掉这次爆炸在别处的效果（比如炸开敌方掩体），
     * 而需求只说天守阁本身不可破坏。</p>
     */
    private void filterExplosion(List<Block> blocks, String worldName) {
        GameRoom room = sengokuRoomOfWorld(worldName);
        if (room == null) {
            return;
        }
        blocks.removeIf(block -> room.keep().isProtectedBlock(block.getLocation()));
    }

    /**
     * 箭楼铜钟：右键敲钟，触发占领读条。
     *
     * <p>用<b>点位匹配</b>而不是"全图认材质"：地图上可能本来就摆着装饰用的钟，
     * 那些不该具备占领功能。</p>
     *
     * <p>取消原版交互是因为原版的钟会响一声并产生"钟鸣"效果，与这里的语义冲突——
     * 由插件统一播报"谁敲响了哪座箭楼"。</p>
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBellInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }
        GameRoom room = sengokuRoomOf(event.getPlayer());
        if (room == null || !room.isRunning()) {
            return;
        }
        int index = towerIndexAtBell(room, block);
        if (index < 0) {
            return;
        }
        event.setCancelled(true);
        TeamId team = room.teamOf(event.getPlayer().getUniqueId());
        if (team == null) {
            return;   // 旁观者 / 未分队
        }
        String failure = room.towerCapture().ringBell(index, team);
        if (failure != null) {
            event.getPlayer().sendActionBar(MINI.deserialize("<gray>" + failure));
        }
    }

    /** 这个方块是不是某座箭楼的铜钟；不是返回 -1。 */
    private int towerIndexAtBell(GameRoom room, Block block) {
        var arena = room.arena();
        if (arena == null) {
            return -1;
        }
        var map = arena.sengoku();
        String wanted = plugin.config().towerRules().bellMaterial();
        if (wanted != null && !wanted.isBlank()
                && !block.getType().name().equalsIgnoreCase(wanted.trim())) {
            return -1;
        }
        int count = plugin.config().towerRules().safeCount();
        for (int index = 1; index <= count; index++) {
            Location bell = map.bell(index) == null ? null : map.bell(index).toBukkitLocation();
            if (bell == null) {
                continue;
            }
            if (bell.getBlockX() == block.getX()
                    && bell.getBlockY() == block.getY()
                    && bell.getBlockZ() == block.getZ()) {
                return index;
            }
        }
        return -1;
    }

    private GameRoom sengokuRoomOf(Player player) {
        if (player == null) {
            return null;
        }
        GameRoom room = plugin.rooms().roomOf(player);
        return room != null && room.isSengoku() ? room : null;
    }

    /** 爆炸事件没有玩家可用，只能按世界反查房间。 */
    private GameRoom sengokuRoomOfWorld(String worldName) {
        if (worldName == null) {
            return null;
        }
        for (GameRoom room : plugin.rooms().rooms()) {
            if (room.isSengoku() && room.roomWorlds().contains(worldName)) {
                return room;
            }
        }
        return null;
    }

    private void noticeKeepProtected(GameRoom room, Player player) {
        long now = System.currentTimeMillis();
        Long last = lastNotice.get(player.getUniqueId());
        if (last != null && now - last < NOTICE_INTERVAL_MILLIS) {
            return;
        }
        lastNotice.put(player.getUniqueId(), now);
        // messages().get(...) 已经返回 Component，直接塞给 ActionBar
        player.sendActionBar(plugin.config().messages().get("sengoku.keep-protected"));
    }

    /** 玩家退出时清掉节流记录，避免长期运行攒下无用条目。 */
    public void forget(UUID uuid) {
        if (uuid != null) {
            lastNotice.remove(uuid);
        }
    }

    public int trackedPlayers() {
        return lastNotice.size();
    }
}
