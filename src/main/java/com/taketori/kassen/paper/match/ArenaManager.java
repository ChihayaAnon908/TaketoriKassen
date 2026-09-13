package com.taketori.kassen.paper.match;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.core.match.TeamId;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * 场地管理：刷怪区、双方各 3 个基地、双方出生点，以及划定时的临时选区。
 *
 * <p>全部通过指令划定（两个角点），保存到 <code>arena.yml</code>，重启后仍然有效。
 * 一个"能开局的场地"至少需要：1 个刷怪区 + 2×3 个基地 + 2 个出生点。</p>
 */
public final class ArenaManager {

    /** 每队基地数量（当前设定：双方各 3 个）。 */
    public static final int BASES_PER_TEAM = 3;

    private final TaketoriPlugin plugin;
    private final File file;

    private CuboidRegion minionRegion;
    private final Map<TeamId, Map<Integer, CuboidRegion>> bases = new EnumMap<>(TeamId.class);
    private final Map<TeamId, Location> spawns = new EnumMap<>(TeamId.class);

    private final Map<UUID, Location> pos1 = new HashMap<>();
    private final Map<UUID, Location> pos2 = new HashMap<>();

    public ArenaManager(TaketoriPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "arena.yml");
        for (TeamId team : TeamId.values()) {
            bases.put(team, new LinkedHashMap<>());
        }
    }

    // ---------------------------------------------------------------- 选区

    public void setPos1(UUID uuid, Location location) {
        pos1.put(uuid, location.clone());
    }

    public void setPos2(UUID uuid, Location location) {
        pos2.put(uuid, location.clone());
    }

    /** 角点 1（选区锄显示标记用）。 */
    public Location pos1(UUID uuid) {
        return pos1.get(uuid);
    }

    /** 角点 2。 */
    public Location pos2(UUID uuid) {
        return pos2.get(uuid);
    }

    /** 由当前两点构成的选区；不完整或跨世界返回 null。 */
    public CuboidRegion selection(UUID uuid) {
        Location a = pos1.get(uuid);
        Location b = pos2.get(uuid);
        if (a == null || b == null) {
            return null;
        }
        return CuboidRegion.of(a, b);
    }

    public void clearSelection(UUID uuid) {
        pos1.remove(uuid);
        pos2.remove(uuid);
    }

    /**
     * 选区当前缺什么。指令报错时用它说明具体原因，
     * 而不是只丢一句"选区不完整"让人猜（划基地最常见的失败原因）。
     */
    public String selectionStatus(UUID uuid) {
        Location a = pos1.get(uuid);
        Location b = pos2.get(uuid);
        if (a == null && b == null) {
            return "还没标记任何角点";
        }
        if (a == null) {
            return "只有角点 2，缺角点 1（站到一角执行 /taketori arena pos1）";
        }
        if (b == null) {
            return "只有角点 1，缺角点 2（走到对角执行 /taketori arena pos2）";
        }
        String worldA = a.getWorld() == null ? "?" : a.getWorld().getName();
        String worldB = b.getWorld() == null ? "?" : b.getWorld().getName();
        return "两个角点不在同一世界（当前是 " + worldA + " / " + worldB + "）";
    }

    /** 该队下一个还没配置的基地编号；1~BASES_PER_TEAM 都配满了返回 -1。 */
    public int nextFreeBaseIndex(TeamId team) {
        for (int i = 1; i <= BASES_PER_TEAM; i++) {
            if (base(team, i) == null) {
                return i;
            }
        }
        return -1;
    }

    // ---------------------------------------------------------------- 设置

    public void setMinionRegion(CuboidRegion region) {
        this.minionRegion = region;
    }

    public void setBase(TeamId team, int index, CuboidRegion region) {
        bases.computeIfAbsent(team, key -> new LinkedHashMap<>()).put(index, region);
    }

    public void setSpawn(TeamId team, Location location) {
        spawns.put(team, location.clone());
    }

    public CuboidRegion minionRegion() {
        return minionRegion;
    }

    public CuboidRegion base(TeamId team, int index) {
        Map<Integer, CuboidRegion> map = bases.get(team);
        return map == null ? null : map.get(index);
    }

    public Map<Integer, CuboidRegion> bases(TeamId team) {
        Map<Integer, CuboidRegion> map = bases.get(team);
        return map == null ? Map.of() : Map.copyOf(map);
    }

    public int baseCount(TeamId team) {
        Map<Integer, CuboidRegion> map = bases.get(team);
        return map == null ? 0 : map.size();
    }

    public Location spawn(TeamId team) {
        return spawns.get(team);
    }

    public int minionRegionCount() {
        return minionRegion == null ? 0 : 1;
    }

    /** 场地是否已具备开局条件。 */
    public boolean isReady() {
        if (minionRegion == null) {
            return false;
        }
        for (TeamId team : TeamId.values()) {
            if (spawns.get(team) == null || baseCount(team) < BASES_PER_TEAM) {
                return false;
            }
        }
        return true;
    }

    /** 还缺什么（给管理员的提示）。 */
    public String missingHint() {
        StringBuilder builder = new StringBuilder();
        if (minionRegion == null) {
            builder.append("刷怪区 ");
        }
        for (TeamId team : TeamId.values()) {
            if (spawns.get(team) == null) {
                builder.append(team.display()).append("出生点 ");
            }
            int count = baseCount(team);
            if (count < BASES_PER_TEAM) {
                builder.append(team.display()).append("基地(").append(count).append('/')
                        .append(BASES_PER_TEAM).append(") ");
            }
        }
        return builder.isEmpty() ? "无" : builder.toString().trim();
    }

    // ---------------------------------------------------------------- 持久化

    public void load() {
        bases.values().forEach(Map::clear);
        minionRegion = null;
        spawns.clear();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);

        minionRegion = CuboidRegion.read(yaml.getConfigurationSection("minion-region"));

        ConfigurationSection baseSection = yaml.getConfigurationSection("bases");
        if (baseSection != null) {
            for (TeamId team : TeamId.values()) {
                ConfigurationSection teamSection = baseSection.getConfigurationSection(team.key());
                if (teamSection == null) {
                    continue;
                }
                for (String key : teamSection.getKeys(false)) {
                    try {
                        int index = Integer.parseInt(key);
                        CuboidRegion region = CuboidRegion.read(teamSection.getConfigurationSection(key));
                        if (region != null) {
                            setBase(team, index, region);
                        }
                    } catch (NumberFormatException ignored) {
                        plugin.getLogger().warning("arena.yml 里 " + team.key() + " 的基地编号非法: " + key);
                    }
                }
            }
        }

        ConfigurationSection spawnSection = yaml.getConfigurationSection("spawns");
        if (spawnSection != null) {
            for (TeamId team : TeamId.values()) {
                ConfigurationSection teamSection = spawnSection.getConfigurationSection(team.key());
                if (teamSection == null) {
                    continue;
                }
                var world = org.bukkit.Bukkit.getWorld(teamSection.getString("world", "world"));
                if (world == null) {
                    plugin.getLogger().warning("出生点所在世界不存在: " + teamSection.getString("world"));
                    continue;
                }
                spawns.put(team, new Location(world,
                        teamSection.getDouble("x"), teamSection.getDouble("y"), teamSection.getDouble("z"),
                        (float) teamSection.getDouble("yaw"), (float) teamSection.getDouble("pitch")));
            }
        }

        plugin.getLogger().info("已载入场地：刷怪区 " + minionRegionCount() + " 个 / 基地 "
                + baseCount(TeamId.RED) + "+" + baseCount(TeamId.BLUE) + " 个 / 出生点 "
                + (spawns.size()) + " 个");
        for (TeamId team : TeamId.values()) {
            int count = baseCount(team);
            if (count > BASES_PER_TEAM) {
                plugin.getLogger().warning("arena.yml 里 " + team.display() + " 配了 " + count
                        + " 个基地，但当前每队只启用 " + BASES_PER_TEAM
                        + " 个；编号 " + (BASES_PER_TEAM + 1) + " 及以后会被忽略（配置会保留，便于改回来）。");
            }
        }
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        if (minionRegion != null) {
            minionRegion.write(yaml.createSection("minion-region"));
        }
        for (TeamId team : TeamId.values()) {
            ConfigurationSection teamSection = yaml.createSection("bases." + team.key());
            for (Map.Entry<Integer, CuboidRegion> entry : bases(team).entrySet()) {
                entry.getValue().write(teamSection.createSection(String.valueOf(entry.getKey())));
            }
        }
        for (TeamId team : TeamId.values()) {
            Location spawn = spawns.get(team);
            if (spawn == null || spawn.getWorld() == null) {
                continue;
            }
            ConfigurationSection section = yaml.createSection("spawns." + team.key());
            section.set("world", spawn.getWorld().getName());
            section.set("x", spawn.getX());
            section.set("y", spawn.getY());
            section.set("z", spawn.getZ());
            section.set("yaw", (double) spawn.getYaw());
            section.set("pitch", (double) spawn.getPitch());
        }
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                plugin.getLogger().warning("无法创建数据目录: " + parent);
            }
            yaml.save(file);
        } catch (Exception ex) {
            plugin.getLogger().log(Level.SEVERE, "保存 arena.yml 失败", ex);
        }
    }
}
