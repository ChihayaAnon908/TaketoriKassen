package com.taketori.kassen.paper.match;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.core.match.TeamId;
import com.taketori.kassen.paper.match.room.GameRoom;
import org.bukkit.entity.Player;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 断线重连：对局中（CAGED/PLAYING）掉线的参赛者在时限内重连，直接回到原房原队。
 *
 * <p>参考 BedWars 的 RejoinSession / rejoin-time，并复用本项目已有的掉线兜底：</p>
 * <ul>
 *   <li>断线时沿用既有流程（playerRooms 移除、teams 条目保留、背包转全局暂存），
 *       这里只<b>追加登记</b>一条重连会话；</li>
 *   <li>重连时若会话未过期且房间还在 → 自动恢复：重新绑定房间映射、传送回本队出生点、
 *       还原背包、重新显示记分板并广播回归；</li>
 *   <li>过期 / 房间已回收 → 什么都不做，由 PlayerListener 既有逻辑返还背包；</li>
 *   <li>时限配置 {@code room.rejoin-seconds}（默认 300；0 = 关闭重连）。</li>
 * </ul>
 */
public final class RejoinManager {

    /** 一条重连会话。 */
    public record RejoinSession(String roomId, TeamId team, long expiresAtMillis) {
    }

    private final TaketoriPlugin plugin;
    /** 玩家 → 重连会话（主线程读写；ConcurrentHashMap 防御异步清理路径）。 */
    private final Map<UUID, RejoinSession> sessions = new ConcurrentHashMap<>();

    public RejoinManager(TaketoriPlugin plugin) {
        this.plugin = plugin;
    }

    /** 重连时限（秒）；0 = 关闭。 */
    public int rejoinSeconds() {
        return Math.max(0, plugin.getConfig().getInt("room.rejoin-seconds", 300));
    }

    /**
     * 断线时登记重连会话（只对 CAGED/PLAYING 的参赛者有意义）。
     * 房间不存在 / 玩家不在队伍里 / 时限为 0 时静默跳过。
     */
    public void register(UUID playerId, GameRoom room, TeamId team) {
        if (playerId == null || room == null || team == null) {
            return;
        }
        int seconds = rejoinSeconds();
        if (seconds <= 0) {
            return;
        }
        sessions.put(playerId, new RejoinSession(room.id(), team,
                System.currentTimeMillis() + seconds * 1000L));
    }

    /**
     * 玩家上线 / 执行 rejoin：尝试恢复到原房原队。
     *
     * @return true = 已恢复（调用方跳过其余加入流程）；false = 无可用会话
     *         （过期会话已清理，由既有 restoreOfflineBackup 兜底返还背包）
     */
    public boolean tryRejoin(Player player) {
        if (player == null || !player.isOnline()) {
            return false;
        }
        UUID id = player.getUniqueId();
        RejoinSession session = sessions.get(id);
        if (session == null) {
            return false;
        }
        sessions.remove(id);
        if (session.expiresAtMillis() < System.currentTimeMillis()) {
            player.sendMessage(plugin.config().messages().get("rejoin.expired"));
            return false;
        }
        GameRoom room = plugin.rooms().room(session.roomId());
        if (room == null || !session.team().equals(room.teamOf(id)) || !room.isRunning()) {
            player.sendMessage(plugin.config().messages().get("rejoin.gone"));
            return false;
        }
        // 恢复房间映射与现场：队伍条目断线时本来就保留，这里补回映射与传送
        plugin.rooms().attachAfterRejoin(player, room);
        ArenaDef.Point spawnPoint = room.arena().spawn(session.team());
        if (spawnPoint != null) {
            player.teleport(spawnPoint.toLocation(room.world()));
        }
        // 背包还原：断线时快照可能已从房间转移到全局暂存（stashOfflineBackup），
        // 两边都试一遍 —— 快照只会在其中一处，restoreTo 会先清空当前背包再写回
        room.restoreInventoryIfAny(player);
        plugin.rooms().restoreOfflineBackup(player);
        room.scoreboard().showTo(player);
        room.broadcastMessage("rejoin.back", "player", player.getName(),
                "room", room.display());
        player.sendMessage(plugin.config().messages().get("rejoin.welcome",
                "room", room.display()));
        return true;
    }

    /** 房间销毁时清掉指向它的全部会话（避免悬挂 roomId）。 */
    public void forgetRoom(String roomId) {
        if (roomId == null) {
            return;
        }
        Iterator<Map.Entry<UUID, RejoinSession>> iterator = sessions.entrySet().iterator();
        while (iterator.hasNext()) {
            if (roomId.equals(iterator.next().getValue().roomId())) {
                iterator.remove();
            }
        }
    }

    /** 由房间 tick 顺带清理过期会话（防长期累积）。 */
    public void purgeExpired() {
        long now = System.currentTimeMillis();
        sessions.values().removeIf(session -> session.expiresAtMillis() < now);
    }

    /** 插件卸载时清空。 */
    public void clearAll() {
        sessions.clear();
    }

    /** 调试用：当前会话数。 */
    public int size() {
        return sessions.size();
    }
}
