package com.taketori.kassen.paper.lobby;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.core.match.TeamId;
import com.taketori.kassen.paper.match.CuboidRegion;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;

/**
 * 大厅：玩家集结点、加入队列的入口、角色选择与告示牌注册。
 *
 * <p>流程：玩家进服（可选）传送到大厅 → 点「加入对局」告示牌 → <b>随机分队</b>并进入队列
 * → 人数达到阈值自动开局或管理员手动开局 → 开局时统一传送到各自出生点。</p>
 *
 * <p>所有状态都可通过 {@link #queue(Player)}、{@link #dequeue(Player)} 等公开方法调用，
 * 其他插件不必依赖告示牌也能驱动同一套流程（预留接口）。</p>
 */
public final class LobbyManager {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final TaketoriPlugin plugin;
    private final File file;

    private CuboidRegion region;
    private Location spawn;
    private final List<LobbySign> signs = new CopyOnWriteArrayList<>();
    private final Set<UUID> queued = ConcurrentHashMap.newKeySet();

    private BukkitTask autoStartTask;

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

    // ---------------------------------------------------------------- 队列

    public boolean isQueued(UUID uuid) {
        return queued.contains(uuid);
    }

    public int queuedCount() {
        return queued.size();
    }

    public Set<UUID> queuedView() {
        return Set.copyOf(queued);
    }

    /**
     * 加入对局队列：随机分队（人数少的一队优先，人数相同随机）。
     * 返回分配到的队伍；无法加入（对局已开始 / 已满）时返回 null。
     */
    public TeamId queue(Player player) {
        if (player == null || !player.isOnline()) {
            return null;
        }
        if (plugin.match().isRunning() && plugin.match().teamOf(player.getUniqueId()) == null) {
            send(player, "<red>对局已经开始，无法中途加入。<gray>可以点「旁观」告示牌观看。");
            return null;
        }
        TeamId existing = plugin.match().teamOf(player.getUniqueId());
        TeamId team = existing != null ? existing : plugin.match().joinRandom(player);
        if (team == null) {
            send(player, "<red>队伍已满，无法加入。");
            return null;
        }
        queued.add(player.getUniqueId());
        send(player, "<green>已加入对局队列，你被分到 " + team.display() + " <gray>（等待开局…）");
        plugin.match().broadcast("<gray>" + player.getName() + " 加入队列（" + team.display() + "）<dark_gray>当前 "
                + queued.size() + " 人");
        checkAutoStart();
        return team;
    }

    /** 退出队列并离开队伍。 */
    public void dequeue(Player player) {
        if (player == null) {
            return;
        }
        queued.remove(player.getUniqueId());
        plugin.match().leave(player.getUniqueId());
        send(player, "<yellow>已退出对局队列。");
    }

    public void clearQueue() {
        queued.clear();
    }

    /** 队列人数达到配置阈值时自动开局。 */
    private void checkAutoStart() {
        int threshold = plugin.config().lobbyAutoStartPlayers();
        if (threshold <= 0 || plugin.match().isRunning() || queued.size() < threshold) {
            return;
        }
        String error = plugin.match().start(null);
        if (error != null) {
            plugin.match().broadcast("<red>自动开局失败：" + error);
        }
    }

    /**
     * 强制开局用：把"已在大厅范围内"或"已排队"但还没分队的玩家随机分队并加入队列。
     *
     * <p>注意只在配了大厅区域（<code>/taketori lobby setregion</code>）时才能识别
     * "人在大厅"，否则只处理已经点过告示牌排队的人。</p>
     *
     * @return 被拉进来的人数
     */
    public int pullLobbyPlayers() {
        int pulled = 0;
        int maxPerTeam = Math.max(1, plugin.config().matchTeamSize());
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            if (plugin.match().teamOf(uuid) != null) {
                continue;   // 已经分好队了
            }
            if (plugin.spectator().isSpectator(player)) {
                continue;   // 观众不拉
            }
            if (!queued.contains(uuid) && !isInLobby(player)) {
                continue;   // 既没排队也不在大厅里：不动他
            }
            TeamId team = plugin.match().joinRandom(player);
            if (team == null) {
                send(player, "<red>队伍已满（每队上限 " + maxPerTeam + " 人），本局无法加入。");
                continue;
            }
            queued.add(uuid);
            send(player, "<green>管理员开始了对局，你被分到 " + team.display() + "。");
            pulled++;
        }
        return pulled;
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
        plugin.matchBoard().hide(player);
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

    private void send(Player player, String miniMessage) {
        player.sendMessage(MINI.deserialize(miniMessage));
    }
}
