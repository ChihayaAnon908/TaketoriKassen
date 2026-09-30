package com.taketori.kassen.paper.match.room;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.paper.match.ArenaDef;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 房间注册表（多房间匹配的唯一权威）：
 *
 * <ul>
 *   <li>插件启用 / reload 时为每个<b>启用场地</b>创建一个 {@link GameRoom}；</li>
 *   <li>维护全工程<b>唯一</b>的 {@code 玩家 → 房间} 映射，其他类不得自行保存归属；</li>
 *   <li>提供快速加入 {@link #quickJoin}、指定加入 {@link #joinRoom}、退出 {@link #leave}、
 *       归属查询 {@link #roomOf} / {@link #roomOfEntity}；</li>
 *   <li>每秒 {@link #tickAll()} 驱动各房间计时。</li>
 * </ul>
 *
 * <p>等待中允许换房：先离开旧房再加入新房。进行中的房间不能加入（只能旁观，Task 10）。</p>
 */
public final class RoomManager {

    /** 加入/快速加入的结果（调用方据此映射 messages.yml 文案）。 */
    public enum JoinOutcome {
        /** 成功加入（原本不在任何房间）。 */
        SUCCESS,
        /** 从另一个等待中房间换房成功。 */
        SWITCHED,
        /** 玩家已经在目标房间 / 已在等待房间里重复匹配。 */
        ALREADY_IN,
        /** 玩家已经在进行中的房间，不能再加入别的房间。 */
        IN_GAME,
        /** 目标房间满员。 */
        FULL,
        /** 目标房间已开局。 */
        STARTED,
        /** 场地 id 不存在。 */
        NOT_FOUND,
        /** 场地未就绪（缺出生点/基地/刷新区/等待点）。 */
        NOT_READY,
        /** 没有任何可加入的房间（快速加入用）。 */
        NO_ROOM
    }

    /** 加入结果：结果类型 + 目标/当前房间（可能为 null）。 */
    public record JoinResult(JoinOutcome outcome, GameRoom room) {
        public boolean ok() {
            return outcome == JoinOutcome.SUCCESS || outcome == JoinOutcome.SWITCHED;
        }
    }

    private final TaketoriPlugin plugin;
    /** 场地 id → 房间（保持场地注册顺序）。 */
    private final Map<String, GameRoom> rooms = new LinkedHashMap<>();
    /** 玩家 → 房间：全工程唯一权威归属映射。 */
    private final Map<UUID, GameRoom> playerRooms = new ConcurrentHashMap<>();
    /** 房间创建序号（快速加入平局时优先先创建者）。 */
    private final AtomicLong sequence = new AtomicLong();

    /** 断线背包暂存的淘汰期限：超过这个时间未取回的备份，随下一次暂存一起清掉（重启本身也会清空）。 */
    private static final long OFFLINE_BACKUP_TTL_MILLIS = 48L * 60L * 60L * 1000L;

    /** 一条断线背包暂存：背包快照 + 暂存时间（用于淘汰长期未取回的备份）。 */
    private record OfflineBackup(org.bukkit.inventory.ItemStack[][] backup, long storedAtMillis) {
    }

    /**
     * 对局中断线玩家的背包暂存：玩家在对局中退出时，把该房间为其备份的原背包
     * 转到这里（房间对象可能在结算时丢弃备份），等其重连后原样返还。
     * 玩家不再回归时不能永远持有整套装备快照：每次暂存前按 TTL 淘汰过期条目。
     */
    private final Map<UUID, OfflineBackup> offlineBackups = new ConcurrentHashMap<>();

    public RoomManager(TaketoriPlugin plugin) {
        this.plugin = plugin;
    }

    // ---------------------------------------------------------------- 注册表

    /** 按当前已启用场地创建/重建全部房间。reload 时先停止旧房间再重建。 */
    public void rebuild() {
        // reload 语义：所有对局作废——旧房间的参赛者/等待者先送回大厅，死亡临时旁观/观众身份清掉，再停房间
        // （首次 build 时 rooms 为空，lobby/spectator 可能尚未装配，整块跳过）
        if (!rooms.isEmpty()) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                // 参赛者/等待者在 playerRooms 里；从大厅旁观的观众不在其中（clearAll 只复位游戏模式
                // 不会传送），两类人都要在旧房间销毁前送回大厅，否则观众会留在已被拆掉的场地里
                if (playerRooms.containsKey(player.getUniqueId())
                        || plugin.spectator().isAudience(player)) {
                    plugin.lobby().sendToLobby(player);
                }
            }
            stopAll();
            plugin.spectator().clearAll();
        }
        rooms.clear();
        sequence.set(0L);
        for (ArenaDef def : plugin.arena().enabledArenas().values()) {
            rooms.put(def.id(), new GameRoom(plugin, this, def, sequence.getAndIncrement()));
        }
        plugin.getLogger().info("已为 " + rooms.size() + " 个启用场地创建游戏房间");
    }

    /** 重建的语义化别名（首次启用时调用）。 */
    public void build() {
        rebuild();
    }

    /**
     * 停止全部房间（reload / 插件禁用）：取消延时任务、还原玻璃笼、停组件、清实体。
     * 玩家映射一并清空（重载后所有对局作废，回到全 WAITING）。
     */
    public void stopAll() {
        for (GameRoom room : rooms.values()) {
            room.shutdown();
        }
        playerRooms.clear();
    }

    /**
     * 结算收尾用：解除所有"当前指向该房间"的玩家映射（人已回大厅，可重新快速加入）。
     * 只移除指向目标房间的条目，不影响其他并发房间。
     */
    public void detachRoom(GameRoom target) {
        playerRooms.entrySet().removeIf(entry -> entry.getValue() == target);
    }

    public List<GameRoom> rooms() {
        return List.copyOf(rooms.values());
    }

    public GameRoom room(String id) {
        return id == null ? null : rooms.get(id);
    }

    // ---------------------------------------------------------------- 玩家归属

    public GameRoom roomOf(UUID uuid) {
        return uuid == null ? null : playerRooms.get(uuid);
    }

    public GameRoom roomOf(Player player) {
        return player == null ? null : playerRooms.get(player.getUniqueId());
    }

    /**
     * 实体归属：遍历各房间月人刷怪器的归属集合（典型房间数 1~10，O(房间数) 可接受）。
     * 找不到返回 null（野生怪 / 其他玩法实体）。Task 5 起刷怪器真正按房间隔离。
     */
    public GameRoom roomOfEntity(Entity entity) {
        if (entity == null) {
            return null;
        }
        for (GameRoom room : rooms.values()) {
            if (room.minions().isMinion(entity)) {
                return room;
            }
        }
        return null;
    }

    /**
     * 场地房间是否正忙（非 WAITING）。场地删除拦截用：
     * STARTING/CAGED/PLAYING/ENDING 的场地不允许删除。
     */
    public boolean isBusy(String arenaId) {
        GameRoom room = room(arenaId);
        return room != null && room.phase() != GameRoom.Phase.WAITING;
    }

    // ---------------------------------------------------------------- 快速加入

    /**
     * 快速加入：自动选择"WAITING/STARTING、场地就绪、未满"中等待人数最多的房间，
     * 平局取先创建者；已经在等待房间的玩家返回 ALREADY_IN，不重复加入。
     */
    public JoinResult quickJoin(Player player) {
        if (player == null) {
            return new JoinResult(JoinOutcome.NO_ROOM, null);
        }
        GameRoom current = playerRooms.get(player.getUniqueId());
        if (current != null) {
            return current.isJoinable()
                    ? new JoinResult(JoinOutcome.ALREADY_IN, current)
                    : new JoinResult(JoinOutcome.IN_GAME, current);
        }
        GameRoom best = rooms.values().stream()
                .filter(room -> room.arena().isReady())
                .filter(GameRoom::isJoinable)
                // 等待人数最多；order 反向比较 → 平局时 order 小（先创建）者在 max 中胜出，
                // 把人聚到同一房而不是摊薄到多个新房
                .max(Comparator.comparingInt(GameRoom::waitingCount)
                        .thenComparing(Comparator.comparingLong(GameRoom::order).reversed()))
                .orElse(null);
        if (best == null) {
            return new JoinResult(JoinOutcome.NO_ROOM, null);
        }
        return joinRoom(player, best.id());
    }

    /**
     * 加入指定房间（房间列表 GUI / 管理指令用）。等待中允许换房：
     * 先静默离开旧房，再加入新房；已在进行中对局的玩家不能再加入。
     */
    public JoinResult joinRoom(Player player, String roomId) {
        if (player == null) {
            return new JoinResult(JoinOutcome.NOT_FOUND, null);
        }
        GameRoom target = rooms.get(roomId);
        if (target == null) {
            return new JoinResult(JoinOutcome.NOT_FOUND, null);
        }
        if (!target.arena().isReady()) {
            return new JoinResult(JoinOutcome.NOT_READY, target);
        }
        GameRoom current = playerRooms.get(player.getUniqueId());
        if (current == target) {
            return new JoinResult(JoinOutcome.ALREADY_IN, target);
        }
        if (current != null && !current.isJoinable()) {
            return new JoinResult(JoinOutcome.IN_GAME, current);
        }
        if (target.phase() != GameRoom.Phase.WAITING && target.phase() != GameRoom.Phase.STARTING) {
            return new JoinResult(JoinOutcome.STARTED, target);
        }
        if (target.waitingCount() >= target.maxPlayers()) {
            return new JoinResult(JoinOutcome.FULL, target);
        }

        boolean switched = false;
        if (current != null) {
            // 等待中换房：先离开旧房（不发退房文案、不回大厅，直接进新房等待区）
            current.removeWaiting(player.getUniqueId());
            current.scoreboard().hide(player);
            switched = true;
        }

        target.addWaiting(player);
        playerRooms.put(player.getUniqueId(), target);

        // 传送到中立等待出生点（开局才分队，此刻所有人都是中立等待者）
        Location waitSpawn = target.arena().waitSpawn();
        if (waitSpawn != null) {
            player.teleport(waitSpawn);
        }
        // 进房强制选装备：还没绑定角色的玩家立刻弹出角色选择菜单，
        // 否则开局时手里没武器（角色武器在 bindCharacter 时才发放）
        var profile = plugin.config().characters().profileOrNull(player.getUniqueId());
        if (profile == null || !profile.hasCharacter()) {
            plugin.scheduler().runLater(() -> {
                player.sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                        .deserialize("<gold>请先选择角色与装备<gray>（右侧弹出菜单，点击立即生效）"));
                plugin.characterMenu().open(player);
            }, 5L);
        }
        if (plugin.config().debug()) {
            plugin.getLogger().info("[room] " + player.getName() + (switched ? " 换房加入 " : " 加入 ")
                    + target.id() + "（" + target.waitingCount() + "/" + target.maxPlayers() + "）");
        }
        return new JoinResult(switched ? JoinOutcome.SWITCHED : JoinOutcome.SUCCESS, target);
    }

    /**
     * 玩家离开房间：移除权威映射。WAITING/STARTING 阶段释放等待名额；
     * CAGED/PLAYING/ENDING 沿用"队伍位置保留"的旧规则（teams 条目保留，
     * 只隐藏记分板），房间倒计时/空房判定只数在线者。
     *
     * @return 离开的房间；玩家本来就不在任何房间时返回 null
     */
    public GameRoom leave(UUID uuid) {
        if (uuid == null) {
            return null;
        }
        GameRoom room = playerRooms.remove(uuid);
        if (room == null) {
            return null;
        }
        room.removeWaiting(uuid);
        GameRoom.Phase phase = room.phase();
        if (phase == GameRoom.Phase.WAITING || phase == GameRoom.Phase.STARTING) {
            // 等待阶段退出：若已被提前分队（管理指令），释放队伍槽位
            if (room.teamOf(uuid) != null) {
                room.leave(uuid);
            } else {
                room.scoreboard().hide(Bukkit.getPlayer(uuid));
            }
        } else {
            // 对局中退出：保留队伍位置，只撤掉记分板
            room.scoreboard().hide(Bukkit.getPlayer(uuid));
        }
        return room;
    }

    // ---------------------------------------------------------------- 断线背包暂存

    /**
     * 玩家退出服务器时调用：若其所在房间为其存了开局前背包备份，把备份转移到
     * 全局暂存，避免房间结算时因玩家不在线而丢弃原物品。
     */
    public void stashOfflineBackup(UUID uuid) {
        if (uuid == null) {
            return;
        }
        GameRoom room = playerRooms.get(uuid);
        if (room == null) {
            return;
        }
        org.bukkit.inventory.ItemStack[][] backup = room.extractBackup(uuid);
        if (backup != null) {
            purgeStaleOfflineBackups();
            offlineBackups.put(uuid, new OfflineBackup(backup, System.currentTimeMillis()));
        }
    }

    /** 淘汰超过 TTL 未取回的断线背包暂存（玩家不再回归时不能一直持有整套装备快照）。 */
    private void purgeStaleOfflineBackups() {
        long cutoff = System.currentTimeMillis() - OFFLINE_BACKUP_TTL_MILLIS;
        offlineBackups.values().removeIf(entry -> entry.storedAtMillis() < cutoff);
    }

    /**
     * 玩家重连时调用：若有断线暂存的背包，清掉当前背包并原样返还。
     * 没有暂存则什么都不做。
     */
    public void restoreOfflineBackup(Player player) {
        if (player == null) {
            return;
        }
        OfflineBackup entry = offlineBackups.remove(player.getUniqueId());
        if (entry == null) {
            return;
        }
        org.bukkit.inventory.ItemStack[][] backup = entry.backup();
        var inv = player.getInventory();
        inv.clear();
        if (backup[0] != null) {
            inv.setStorageContents(backup[0]);
        }
        if (backup[1] != null) {
            inv.setArmorContents(backup[1]);
        }
        if (backup[2] != null && backup[2].length > 0) {
            inv.setItemInOffHand(backup[2][0]);
        }
    }

    // ---------------------------------------------------------------- 驱动

    /** 由主类每秒调用：观战提醒（全局一份）+ 各房间计时。 */
    public void tickAll() {
        plugin.spectator().tickReminders();
        for (GameRoom room : new ArrayList<>(rooms.values())) {
            room.tick();
        }
    }
}
