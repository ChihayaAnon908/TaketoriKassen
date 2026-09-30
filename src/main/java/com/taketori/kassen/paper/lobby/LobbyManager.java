package com.taketori.kassen.paper.lobby;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.core.worlds.WorldScope;
import com.taketori.kassen.paper.match.CuboidRegion;
import com.taketori.kassen.paper.match.room.GameRoom;
import com.taketori.kassen.paper.match.room.RoomManager;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;

/**
 * 大厅（Hub）：玩家集结点、快速匹配的入口、角色选择与告示牌注册。
 *
 * <p>BedWars 式流程：玩家进服传送到大厅 → 点「加入对局」告示牌 / 菜单按钮 /
 * 指令发起 {@link #quickJoin} → 自动进入人最多的等待房间（等待区可退出、可换房）
 * → 房间自己倒计时、开局分队、结算后把人送回大厅。大厅不再维护队列与提前分队，
 * "玩家属于哪个房间"的唯一权威在 {@link RoomManager}。</p>
 */
public final class LobbyManager {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final TaketoriPlugin plugin;
    private final File file;

    private CuboidRegion region;
    private Location spawn;
    private final List<LobbySign> signs = new CopyOnWriteArrayList<>();

    public LobbyManager(TaketoriPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "lobby.yml");
    }

    // ---------------------------------------------------------------- 配置

    public void load() {
        signs.clear();
        region = null;
        spawn = null;
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        region = CuboidRegion.read(yaml.getConfigurationSection("region"));

        ConfigurationSection spawnSection = yaml.getConfigurationSection("spawn");
        if (spawnSection != null && spawnSection.isString("world")) {
            var world = org.bukkit.Bukkit.getWorld(spawnSection.getString("world"));
            if (world != null) {
                spawn = new Location(world,
                        spawnSection.getDouble("x"), spawnSection.getDouble("y"), spawnSection.getDouble("z"),
                        (float) spawnSection.getDouble("yaw"), (float) spawnSection.getDouble("pitch"));
            }
        }

        ConfigurationSection signSection = yaml.getConfigurationSection("signs");
        if (signSection != null) {
            for (String key : signSection.getKeys(false)) {
                LobbySign sign = LobbySign.read(signSection.getConfigurationSection(key));
                if (sign != null) {
                    signs.add(sign);
                }
            }
        }
        plugin.getLogger().info("已载入大厅：出生点 " + (spawn == null ? "未设置" : "已设置")
                + " / 告示牌 " + signs.size() + " 个");
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        if (region != null) {
            region.write(yaml.createSection("region"));
        }
        if (spawn != null && spawn.getWorld() != null) {
            ConfigurationSection section = yaml.createSection("spawn");
            section.set("world", spawn.getWorld().getName());
            section.set("x", spawn.getX());
            section.set("y", spawn.getY());
            section.set("z", spawn.getZ());
            section.set("yaw", (double) spawn.getYaw());
            section.set("pitch", (double) spawn.getPitch());
        }
        int index = 0;
        for (LobbySign sign : signs) {
            sign.write(yaml.createSection("signs." + (index++)));
        }
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                plugin.getLogger().warning("无法创建数据目录: " + parent);
            }
            yaml.save(file);
        } catch (Exception ex) {
            plugin.getLogger().log(Level.SEVERE, "保存 lobby.yml 失败", ex);
        }
    }

    public CuboidRegion region() {
        return region;
    }

    public void setRegion(CuboidRegion region) {
        this.region = region;
    }

    public Location spawn() {
        return spawn == null ? null : spawn.clone();
    }

    public void setSpawn(Location location) {
        this.spawn = location == null ? null : location.clone();
    }

    public boolean isConfigured() {
        return spawn != null;
    }

    // ---------------------------------------------------------------- 告示牌

    public List<LobbySign> signs() {
        return List.copyOf(signs);
    }

    public LobbySign signAt(Block block) {
        for (LobbySign sign : signs) {
            if (sign.matches(block)) {
                return sign;
            }
        }
        return null;
    }

    public void addSign(LobbySign sign) {
        // 同一坐标重复添加时覆盖旧动作
        signs.removeIf(existing -> existing.world().equals(sign.world())
                && existing.x() == sign.x() && existing.y() == sign.y() && existing.z() == sign.z());
        signs.add(sign);
    }

    /** 注册告示牌并立即保存（指令路径用；返回新注册的告示牌）。 */
    public LobbySign addSignAndSave(String world, int x, int y, int z, String action) {
        LobbySign sign = new LobbySign(world, x, y, z, action);
        addSign(sign);
        save();
        return sign;
    }

    public boolean removeSignAt(Block block) {
        return signs.removeIf(sign -> sign.matches(block));
    }

    // ---------------------------------------------------------------- 快速加入 / 离开房间

    /**
     * 快速加入：告示牌 {@code join} / 玩家菜单匹配按钮 / {@code /taketori lobby join}
     * 的<b>唯一入口</b>。自动进入等待人数最多的可加入房间；加入/失败文案与房间播报统一处理。
     */
    public void quickJoin(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        handleJoinResult(player, plugin.rooms().quickJoin(player), null);
    }

    /** 加入指定房间（房间列表 GUI / 管理指令用）；等待中允许自动换房。 */
    public void joinRoom(Player player, String roomId) {
        if (player == null || !player.isOnline()) {
            return;
        }
        handleJoinResult(player, plugin.rooms().joinRoom(player, roomId), roomId);
    }

    private void handleJoinResult(Player player, RoomManager.JoinResult result, String requestedId) {
        GameRoom room = result.room();
        switch (result.outcome()) {
            case SUCCESS -> {
                message(player, "room.join-success", "room", room.display());
                room.broadcastMessage("room.join-broadcast", "player", player.getName(),
                        "count", room.waitingCount(), "max", room.maxPlayers());
            }
            case SWITCHED -> {
                message(player, "room.switch-room", "room", room.display());
                room.broadcastMessage("room.join-broadcast", "player", player.getName(),
                        "count", room.waitingCount(), "max", room.maxPlayers());
            }
            case ALREADY_IN, IN_GAME -> message(player, "room.already-in", "room", room.display());
            case FULL -> message(player, "room.room-full", "room", room.display());
            case STARTED -> message(player, "room.room-started");
            case NOT_READY -> message(player, "room.list-not-ready",
                    "missing", room == null ? "" : room.arena().missingHint());
            case NOT_FOUND -> message(player, "room.arena-not-found",
                    "id", requestedId == null ? "?" : requestedId);
            case NO_ROOM -> message(player, "room.no-room");
        }
    }

    /**
     * 主动离开房间回大厅（{@code /taketori leave}、告示牌 leave、菜单按钮的统一入口）：
     * <ul>
     *   <li>WAITING/STARTING：释放等待名额（房间倒计时自动重算），房间内播报，传送回大厅；</li>
     *   <li>CAGED/PLAYING 的参赛者：拒绝中途退出（要结束整局找管理员 stop）；</li>
     *   <li>ENDING / 无房间：直接送大厅。</li>
     * </ul>
     * 观众身份请先由 SpectatorManager.leaveAudience 处理（指令里在本方法之前判断）。
     */
    public void returnToLobby(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        GameRoom room = plugin.rooms().roomOf(player);
        if (room == null) {
            message(player, "room.not-in-room");
            sendToLobby(player);
            return;
        }
        if ((room.phase() == GameRoom.Phase.CAGED || room.phase() == GameRoom.Phase.PLAYING)
                && room.teamOf(player.getUniqueId()) != null) {
            player.sendMessage(MINI.deserialize("<red>对局进行中，参赛者不能单独退出。"
                    + "<gray>要结束整局请找管理员执行 <white>/taketori match stop"));
            return;
        }
        boolean waitingRoom = room.phase() == GameRoom.Phase.WAITING
                || room.phase() == GameRoom.Phase.STARTING;
        String name = player.getName();
        plugin.rooms().leave(player.getUniqueId());
        if (waitingRoom) {
            room.broadcastMessage("room.leave-broadcast", "player", name,
                    "count", room.waitingCount(), "max", room.maxPlayers());
        }
        message(player, "room.leave");
        sendToLobby(player);
    }

    // ---------------------------------------------------------------- 播报

    /**
     * 大厅播报：默认只发给<b>大厅世界</b>里的玩家（{@code worlds.broadcast-scope: world}）。
     *
     * <p>多世界服务器上，排队与开局的消息不该刷到其它世界的玩家；把 scope 配成 {@code all}
     * 就恢复旧的全服广播。大厅出生点还没配（拿不到世界）时同样按全服处理，避免消息静默消失。</p>
     */
    public void broadcastLobby(String miniMessage) {
        String scope = plugin.config().broadcastScope();
        Location target = spawn();
        String world = target == null || target.getWorld() == null ? null : target.getWorld().getName();
        if (world == null || "all".equalsIgnoreCase(scope)) {
            plugin.getServer().sendMessage(MINI.deserialize(miniMessage));
            return;
        }
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (WorldScope.shouldReceive(scope, Set.of(world), player.getWorld().getName())) {
                player.sendMessage(MINI.deserialize(miniMessage));
            }
        }
    }

    // ---------------------------------------------------------------- 传送

    /** 把玩家送到大厅，并清掉技能残留状态。 */
    public void sendToLobby(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        clearTransient(player);
        Location target = spawn();
        if (target != null) {
            player.teleport(target);
        }
        if (player.getGameMode() == GameMode.SPECTATOR) {
            player.setGameMode(GameMode.SURVIVAL);
        }
        // 回大厅：按所属房间（参赛者或观众）找到对应房间记分板并隐藏；都没有就直接还原主记分板
        var room = plugin.rooms().roomOf(player);
        if (room == null) {
            room = plugin.spectator().audienceRoom(player.getUniqueId());
        }
        if (room != null) {
            room.scoreboard().hide(player);
        } else if (player.isOnline()) {
            player.setScoreboard(org.bukkit.Bukkit.getScoreboardManager().getMainScoreboard());
        }
    }

    /** 清掉药水效果与技能相关状态（进大厅 / 出对局时用）。 */
    public void clearTransient(Player player) {
        for (PotionEffect effect : new ArrayList<>(player.getActivePotionEffects())) {
            player.removePotionEffect(effect.getType());
        }
        player.setAbsorptionAmount(0.0D);
        player.setFireTicks(0);
        player.setFallDistance(0.0F);
    }

    public boolean isInLobby(Player player) {
        if (player == null || region == null) {
            return false;
        }
        return region.contains(player.getLocation());
    }

    /** 发送一条 messages.yml 文案（占位符按 key/value 成对传入）。 */
    private void message(Player player, String key, Object... placeholders) {
        player.sendMessage(plugin.config().messages().get(key, placeholders));
    }
}
