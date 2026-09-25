package com.taketori.kassen.paper.match;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.core.match.TeamId;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

/**
 * 场地管理：月人刷新区（可多个）、道具刷新点（可多个）、双方各 3 个基地、双方出生点，以及划定时的临时选区。
 *
 * <p>全部通过指令或两种选区工具划定，保存到 <code>arena.yml</code>，重启后仍然有效。
 * 一个"能开局的场地"至少需要：**至少 1 个月人刷新区** + 2×3 个基地 + 2 个出生点；
 * 道具刷新点是可选的。</p>
 *
 * <p>刷新区与道具点的编号从 1 开始，各自独立：<code>/taketori arena setminion 2</code>、
 * <code>/taketori arena setloot 1</code>。旧版单数键 <code>minion-region</code> 会被当作第 1 个区域读入。</p>
 */
public final class ArenaManager {

    /** 每队基地数量的默认值（config.yml 的 base.count-per-team 没写或写错时用它）。 */
    public static final int BASES_PER_TEAM = 3;
    /** 每队基地编号的硬上限：防止把编号写成几百之后遍历与提示失控。 */
    public static final int MAX_BASES_PER_TEAM = 16;

    private final TaketoriPlugin plugin;
    private final File file;

    /** 月人刷新区：编号 → 区域（1 起）。 */
    private final Map<Integer, CuboidRegion> minionRegions = new LinkedHashMap<>();
    /** 道具刷新点：编号 → 区域（1 起）。 */
    private final Map<Integer, CuboidRegion> lootRegions = new LinkedHashMap<>();
    private final Map<TeamId, Map<Integer, CuboidRegion>> bases = new EnumMap<>(TeamId.class);
    private final Map<TeamId, Location> spawns = new EnumMap<>(TeamId.class);

    /** PVE 保卫据点的位置（雪傀儡站在这里）；没设置时用第一个月人刷新区中心。 */
    private Location outpost;

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

    /** 角点 1（选区工具显示标记用）。 */
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
     * 而不是只丢一句"选区不完整"让人猜（划场地最常见的失败原因）。
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

    /** 该队下一个还没配置的基地编号；都配满了返回 -1。 */
    public int nextFreeBaseIndex(TeamId team) {
        for (int i = 1; i <= baseIndexCeiling(); i++) {
            if (base(team, i) == null) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 每队基地数量上限；{@code 0} 表示 config.yml 里写了 {@code auto}
     * （不设上限，以实际划定的为准）。
     */
    public int baseLimit() {
        String raw = plugin.config().baseCountPerTeam();
        if (raw == null || raw.isBlank() || "auto".equalsIgnoreCase(raw.trim())) {
            return 0;
        }
        try {
            return Math.max(1, Math.min(MAX_BASES_PER_TEAM, Integer.parseInt(raw.trim())));
        } catch (NumberFormatException ex) {
            plugin.getLogger().warning("base.count-per-team 无法解析：" + raw
                    + "（可写正整数或 auto）→ 回退到每队 " + BASES_PER_TEAM + " 个");
            return BASES_PER_TEAM;
        }
    }

    /** 是不是 auto（以实际划定的基地为准）。 */
    public boolean baseCountAuto() {
        return baseLimit() == 0;
    }

    /** 开局时每队需要配满的基地数量（auto 模式下只要求至少 1 个）。 */
    public int requiredBasesPerTeam() {
        int limit = baseLimit();
        return limit > 0 ? limit : 1;
    }

    /**
     * 可用基地编号的上界（提示与校验用）。
     *
     * <p>固定数量时就是那个数量；auto 时取"当前实际配到的最大编号 + 1"，
     * 这样第一次划基地会自动用 #1，划满之后再划就顺延到下一个编号。</p>
     */
    public int baseIndexCeiling() {
        int limit = baseLimit();
        if (limit > 0) {
            return limit;
        }
        int max = 0;
        for (TeamId team : TeamId.values()) {
            for (int index : bases(team).keySet()) {
                max = Math.max(max, index);
            }
        }
        return Math.max(1, Math.min(MAX_BASES_PER_TEAM, max + 1));
    }

    /** 下一个还没配置的月人刷新区编号。 */
    public int nextFreeMinionIndex() {
        for (int i = 1; i <= 32; i++) {
            if (!minionRegions.containsKey(i)) {
                return i;
            }
        }
        return -1;
    }

    /** 下一个还没配置的道具点编号。 */
    public int nextFreeLootIndex() {
        for (int i = 1; i <= 32; i++) {
            if (!lootRegions.containsKey(i)) {
                return i;
            }
        }
        return -1;
    }

    // ---------------------------------------------------------------- 月人刷新区（多个）

    /** 设置第 index 个月人刷新区（1 起）。 */
    public void setMinionRegion(int index, CuboidRegion region) {
        minionRegions.put(Math.max(1, index), region);
    }

    /** 兼容旧调用：设置第 1 个刷新区。 */
    public void setMinionRegion(CuboidRegion region) {
        setMinionRegion(1, region);
    }

    /** 删除某个月人刷新区；返回是否真的删掉了（编号本来就没配 → false）。 */
    public boolean clearMinionRegion(int index) {
        return minionRegions.remove(index) != null;
    }

    public Map<Integer, CuboidRegion> minionRegions() {
        return Map.copyOf(minionRegions);
    }

    /** 第 1 个刷新区（兼容旧调用与只划了一个区域的服务器）。 */
    public CuboidRegion minionRegion() {
        return minionRegions.get(1);
    }

    public CuboidRegion minionRegion(int index) {
        return minionRegions.get(index);
    }

    /** 随机挑一个刷新区（多个区域时月人会分散出现）。 */
    public CuboidRegion randomMinionRegion() {
        List<CuboidRegion> regions = new ArrayList<>(minionRegions.values());
        if (regions.isEmpty()) {
            return null;
        }
        if (regions.size() == 1) {
            return regions.get(0);
        }
        return regions.get(ThreadLocalRandom.current().nextInt(regions.size()));
    }

    public int minionRegionCount() {
        return minionRegions.size();
    }

    // ---------------------------------------------------------------- 道具刷新点（多个）

    public void setLootRegion(int index, CuboidRegion region) {
        lootRegions.put(Math.max(1, index), region);
    }

    /** 删除某个道具刷新点；返回是否真的删掉了。 */
    public boolean clearLootRegion(int index) {
        return lootRegions.remove(index) != null;
    }

    public Map<Integer, CuboidRegion> lootRegions() {
        return Map.copyOf(lootRegions);
    }

    public CuboidRegion lootRegion(int index) {
        return lootRegions.get(index);
    }

    public int lootRegionCount() {
        return lootRegions.size();
    }

    // ---------------------------------------------------------------- 基地与出生点

    public void setBase(TeamId team, int index, CuboidRegion region) {
        bases.computeIfAbsent(team, key -> new LinkedHashMap<>()).put(index, region);
    }

    /** 删除某队的某个基地；返回是否真的删掉了。 */
    public boolean clearBase(TeamId team, int index) {
        Map<Integer, CuboidRegion> map = bases.get(team);
        return map != null && map.remove(index) != null;
    }

    public void setSpawn(TeamId team, Location location) {
        spawns.put(team, location.clone());
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

    // ---------------------------------------------------------------- 据点（PVE）

    /** 设置 PVE 保卫据点的位置。 */
    public void setOutpost(Location location) {
        this.outpost = location == null ? null : location.clone();
    }

    public void clearOutpost() {
        this.outpost = null;
    }

    /** 据点位置；没显式设置时回落到第一个月人刷新区中心（还没有区域就返回 null）。 */
    public Location outpost() {
        if (outpost != null && outpost.getWorld() != null) {
            return outpost;
        }
        CuboidRegion region = minionRegion(1);
        return region == null ? null : region.center();
    }

    /** 是否显式设置过据点位置（用于命令提示）。 */
    public boolean hasOutpost() {
        return outpost != null && outpost.getWorld() != null;
    }

    /** 场地是否已具备开局条件。 */
    public boolean isReady() {
        if (minionRegions.isEmpty()) {
            return false;
        }
        int required = requiredBasesPerTeam();
        for (TeamId team : TeamId.values()) {
            if (spawns.get(team) == null || baseCount(team) < required) {
                return false;
            }
        }
        return true;
    }

    /** 还缺什么（给管理员的提示）。 */
    public String missingHint() {
        StringBuilder builder = new StringBuilder();
        if (minionRegions.isEmpty()) {
            builder.append("月人刷新区 ");
        }
        int required = requiredBasesPerTeam();
        for (TeamId team : TeamId.values()) {
            if (spawns.get(team) == null) {
                builder.append(team.display()).append("出生点 ");
            }
            int count = baseCount(team);
            if (count < required) {
                builder.append(team.display()).append("基地(").append(count).append('/')
                        .append(required).append(") ");
            }
        }
        return builder.isEmpty() ? "无" : builder.toString().trim();
    }

    // ---------------------------------------------------------------- 持久化

    public void load() {
        bases.values().forEach(Map::clear);
        minionRegions.clear();
        lootRegions.clear();
        spawns.clear();
        outpost = null;
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);

        // 新版：minion-regions.<编号>；旧版：minion-region（当作 1 号）
        readRegions(yaml.getConfigurationSection("minion-regions"), minionRegions);
        CuboidRegion legacy = CuboidRegion.read(yaml.getConfigurationSection("minion-region"));
        if (legacy != null && !minionRegions.containsKey(1)) {
            minionRegions.put(1, legacy);
        }
        readRegions(yaml.getConfigurationSection("loot-regions"), lootRegions);

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

        ConfigurationSection outpostSection = yaml.getConfigurationSection("outpost");
        if (outpostSection != null) {
            var world = org.bukkit.Bukkit.getWorld(outpostSection.getString("world", "world"));
            if (world == null) {
                plugin.getLogger().warning("据点所在世界不存在: " + outpostSection.getString("world"));
            } else {
                outpost = new Location(world,
                        outpostSection.getDouble("x"), outpostSection.getDouble("y"), outpostSection.getDouble("z"),
                        (float) outpostSection.getDouble("yaw"), (float) outpostSection.getDouble("pitch"));
            }
        }

        plugin.getLogger().info("已载入场地：月人刷新区 " + minionRegionCount() + " 个 / 道具点 "
                + lootRegionCount() + " 个 / 基地 " + baseCount(TeamId.RED) + "+" + baseCount(TeamId.BLUE)
                + " 个 / 出生点 " + spawns.size() + " 个");
    }

    private void readRegions(ConfigurationSection section, Map<Integer, CuboidRegion> target) {
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            try {
                int index = Integer.parseInt(key);
                CuboidRegion region = CuboidRegion.read(section.getConfigurationSection(key));
                if (region != null) {
                    target.put(index, region);
                }
            } catch (NumberFormatException ignored) {
                plugin.getLogger().warning("arena.yml 里的区域编号非法: " + key);
            }
        }
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<Integer, CuboidRegion> entry : minionRegions.entrySet()) {
            entry.getValue().write(yaml.createSection("minion-regions." + entry.getKey()));
        }
        for (Map.Entry<Integer, CuboidRegion> entry : lootRegions.entrySet()) {
            entry.getValue().write(yaml.createSection("loot-regions." + entry.getKey()));
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
        if (outpost != null && outpost.getWorld() != null) {
            ConfigurationSection section = yaml.createSection("outpost");
            section.set("world", outpost.getWorld().getName());
            section.set("x", outpost.getX());
            section.set("y", outpost.getY());
            section.set("z", outpost.getZ());
            section.set("yaw", (double) outpost.getYaw());
            section.set("pitch", (double) outpost.getPitch());
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
