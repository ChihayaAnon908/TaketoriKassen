package com.taketori.kassen.paper.match;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.core.match.TeamId;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单个场地的数据定义（多房间架构后，一个服务器可以配置多个场地，每个场地对应一个房间）。
 *
 * <p>持有：月人刷新区（可多个）、道具刷新点（可多个）、双方各 N 个基地、双方出生点、
 * PVE 据点、<b>等待出生点</b>（BedWars 式匹配：开局前玩家在此中立等待），以及启停状态。</p>
 *
 * <p>一个"能开局的场地"至少需要：**至少 1 个月人刷新区** + 2×N 个基地 + 2 个出生点
 * + 1 个等待出生点；道具刷新点可选。本类只负责数据与就绪判定，运行时状态在 GameRoom。</p>
 */
public final class ArenaDef {

    /** 每队基地数量的默认值（config.yml 的 base.count-per-team 没写或写错时用它）。 */
    public static final int BASES_PER_TEAM = 3;
    /** 每队基地编号的硬上限：防止把编号写成几百之后遍历与提示失控。 */
    public static final int MAX_BASES_PER_TEAM = 16;

    private final TaketoriPlugin plugin;
    private final String id;

    private String display;
    private boolean enabled = true;

    /** 月人刷新区标签：只刷新普通月人。 */
    public static final String REGION_KIND_NORMAL = "normal";
    /** 月人刷新区标签：普通与精英都刷新（旧配置没有标签的区域也按此处理）。 */
    public static final String REGION_KIND_MIXED = "mixed";

    /** 月人刷新区：编号 → 区域（1 起）。 */
    private final Map<Integer, CuboidRegion> minionRegions = new LinkedHashMap<>();
    /** 月人刷新区标签：编号 → normal / mixed（同一条 setminion 指令注册，标签可在 arenas.yml 修改）。 */
    private final Map<Integer, String> minionRegionKinds = new LinkedHashMap<>();
    /** 道具刷新点：编号 → 区域（1 起）。 */
    private final Map<Integer, CuboidRegion> lootRegions = new LinkedHashMap<>();

    // ---- 本场地月人刷新节奏（arenas.yml 的 minion-spawn 段；null = 用 config.yml 的 minion 全局默认值）----
    private Integer spawnIntervalSeconds;
    private Integer spawnPerSpawn;
    private Integer spawnMaxAlive;
    private final Map<TeamId, Map<Integer, CuboidRegion>> bases = new EnumMap<>(TeamId.class);
    private final Map<TeamId, Location> spawns = new EnumMap<>(TeamId.class);

    /** PVE 保卫据点的位置（雪傀儡站在这里）；没设置时用第一个月人刷新区中心。 */
    private Location outpost;
    /** 中立等待出生点：匹配后玩家在此集结等待倒计时（开局才分队）。 */
    private Location waitSpawn;

    public ArenaDef(TaketoriPlugin plugin, String id) {
        this.plugin = plugin;
        this.id = id;
        this.display = id;
        for (TeamId team : TeamId.values()) {
            bases.put(team, new LinkedHashMap<>());
        }
    }

    public String id() {
        return id;
    }

    public String display() {
        return display == null || display.isBlank() ? id : display;
    }

    public void setDisplay(String display) {
        this.display = display;
    }

    public boolean enabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    // ---------------------------------------------------------------- 月人刷新区

    /** 下一个还没配置的月人刷新区编号。 */
    public int nextFreeMinionIndex() {
        for (int i = 1; i <= 32; i++) {
            if (!minionRegions.containsKey(i)) {
                return i;
            }
        }
        return -1;
    }

    /** 设置第 index 个月人刷新区（1 起），标签默认 mixed。 */
    public void setMinionRegion(int index, CuboidRegion region) {
        setMinionRegion(index, region, REGION_KIND_MIXED);
    }

    /** 设置第 index 个月人刷新区（1 起）并指定标签（normal / mixed，其它值一律按 mixed）。 */
    public void setMinionRegion(int index, CuboidRegion region, String kind) {
        int safeIndex = Math.max(1, index);
        minionRegions.put(safeIndex, region);
        minionRegionKinds.put(safeIndex, normalizeKind(kind));
    }

    private static String normalizeKind(String raw) {
        return raw != null && REGION_KIND_NORMAL.equalsIgnoreCase(raw.trim())
                ? REGION_KIND_NORMAL : REGION_KIND_MIXED;
    }

    /** 某刷新区的标签（未记录时按 mixed，兼容旧 arenas.yml）。 */
    public String minionRegionKind(int index) {
        return minionRegionKinds.getOrDefault(index, REGION_KIND_MIXED);
    }

    /** mixed 标签刷新区数量（精英月人只落在这些区）。 */
    public int mixedMinionRegionCount() {
        int count = 0;
        for (Integer index : minionRegions.keySet()) {
            if (REGION_KIND_MIXED.equals(minionRegionKind(index))) {
                count++;
            }
        }
        return count;
    }

    /** 删除某个月人刷新区；返回是否真的删掉了（编号本来就没配 → false）。 */
    public boolean clearMinionRegion(int index) {
        minionRegionKinds.remove(index);
        return minionRegions.remove(index) != null;
    }

    public Map<Integer, CuboidRegion> minionRegions() {
        return Map.copyOf(minionRegions);
    }

    /** 第 1 个刷新区（兼容只划了一个区域的场地）。 */
    public CuboidRegion minionRegion() {
        return minionRegions.get(1);
    }

    public CuboidRegion minionRegion(int index) {
        return minionRegions.get(index);
    }

    /**
     * 全部月人刷新区（按编号升序）。普通月人在这些区之间<b>轮转均分</b>：
     * 刷怪器按列表顺序依次取区，长期统计下各区刷新数量严格相等。
     */
    public List<CuboidRegion> minionRegionList() {
        List<Integer> indices = new ArrayList<>(minionRegions.keySet());
        indices.sort(Integer::compareTo);
        List<CuboidRegion> regions = new ArrayList<>(indices.size());
        for (Integer index : indices) {
            regions.add(minionRegions.get(index));
        }
        return List.copyOf(regions);
    }

    /**
     * mixed 标签的月人刷新区（按编号升序）。精英月人（精英波 / PVE 大波次）只在这些区之间
     * 轮转均分；列表为空说明全部区都是 normal，这一批精英不刷新。
     */
    public List<CuboidRegion> mixedMinionRegionList() {
        List<Integer> indices = new ArrayList<>(minionRegions.keySet());
        indices.sort(Integer::compareTo);
        List<CuboidRegion> regions = new ArrayList<>(indices.size());
        for (Integer index : indices) {
            if (REGION_KIND_MIXED.equals(minionRegionKind(index))) {
                regions.add(minionRegions.get(index));
            }
        }
        return List.copyOf(regions);
    }

    public int minionRegionCount() {
        return minionRegions.size();
    }

    // ---------------------------------------------------------------- 道具刷新点

    /** 下一个还没配置的道具点编号。 */
    public int nextFreeLootIndex() {
        for (int i = 1; i <= 32; i++) {
            if (!lootRegions.containsKey(i)) {
                return i;
            }
        }
        return -1;
    }

    public void setLootRegion(int index, CuboidRegion region) {
        lootRegions.put(Math.max(1, index), region);
    }

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

    public boolean clearBase(TeamId team, int index) {
        Map<Integer, CuboidRegion> map = bases.get(team);
        return map != null && map.remove(index) != null;
    }

    public void setSpawn(TeamId team, Location location) {
        spawns.put(team, location == null ? null : location.clone());
    }

    public CuboidRegion base(TeamId team, int index) {
        Map<Integer, CuboidRegion> map = bases.get(team);
        return map == null ? null : map.get(index);
    }

    public Map<Integer, CuboidRegion> bases(TeamId team) {
        Map<Integer, CuboidRegion> map = bases.get(team);
        return map == null ? Map.of() : Map.copyOf(map);
    }

    /**
     * 场地全部已配置的立体区域（双方基地 + 月人刷新区 + 道具点）。
     * 房间结算时只在这些区域内清理掉落物/弹体：同一世界并存多个场地时，
     * 绝不能按世界一刀切，否则会清掉别的房间乃至大厅的实体。
     * 区域外的散落物交给原版自然消失。
     */
    public List<CuboidRegion> regions() {
        List<CuboidRegion> all = new ArrayList<>();
        for (Map<Integer, CuboidRegion> map : bases.values()) {
            all.addAll(map.values());
        }
        all.addAll(minionRegions.values());
        all.addAll(lootRegions.values());
        return all;
    }

    /** 位置是否落在本场地已配置的某个区域内。 */
    public boolean containsRegionLocation(Location location) {
        if (location == null) {
            return false;
        }
        for (CuboidRegion region : regions()) {
            if (region.contains(location)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 场地<b>对局活动范围</b>的外包矩形：把所有基地、月人刷新区、道具点合并成一个
     * 最小外接长方体，用于：①在四周立屏障墙（防止外人闯入/玩家跑出）；
     * ②对局中禁止破坏/放置这个范围内的方块。
     *
     * <p>所有区域必须在同一世界（选区工具已强制）；跨世界时返回 null。
     * 没有任何区域时也返回 null。</p>
     */
    public CuboidRegion playBounds() {
        List<CuboidRegion> all = regions();
        if (all.isEmpty()) {
            return null;
        }
        String world = all.get(0).worldName();
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
        for (CuboidRegion region : all) {
            if (!region.worldName().equals(world)) {
                return null;  // 跨世界不合法
            }
            minX = Math.min(minX, region.minX());
            minY = Math.min(minY, region.minY());
            minZ = Math.min(minZ, region.minZ());
            maxX = Math.max(maxX, region.maxX());
            maxY = Math.max(maxY, region.maxY());
            maxZ = Math.max(maxZ, region.maxZ());
        }
        return new CuboidRegion(world, minX, minY, minZ, maxX, maxY, maxZ);
    }

    public int baseCount(TeamId team) {
        Map<Integer, CuboidRegion> map = bases.get(team);
        return map == null ? 0 : map.size();
    }

    public Location spawn(TeamId team) {
        Location location = spawns.get(team);
        return location == null ? null : location.clone();
    }

    // ---------------------------------------------------------------- 据点（PVE）

    public void setOutpost(Location location) {
        this.outpost = location == null ? null : location.clone();
    }

    public void clearOutpost() {
        this.outpost = null;
    }

    /** 据点位置；没显式设置时回落到第一个月人刷新区中心（还没有区域就返回 null）。 */
    public Location outpost() {
        if (outpost != null && outpost.getWorld() != null) {
            return outpost.clone();
        }
        CuboidRegion region = minionRegion(1);
        return region == null ? null : region.center();
    }

    public boolean hasOutpost() {
        return outpost != null && outpost.getWorld() != null;
    }

    // ---------------------------------------------------------------- 等待出生点

    public void setWaitSpawn(Location location) {
        this.waitSpawn = location == null ? null : location.clone();
    }

    /** 中立等待出生点（未设置返回 null）。 */
    public Location waitSpawn() {
        return waitSpawn == null ? null : waitSpawn.clone();
    }

    public boolean hasWaitSpawn() {
        return waitSpawn != null && waitSpawn.getWorld() != null;
    }

    // ---------------------------------------------------------------- 月人刷新节奏（minion-spawn）

    /** 本场地刷新间隔（秒）；arenas.yml 的 minion-spawn 未配置时回落全局默认值。 */
    public int minionSpawnIntervalSeconds(int fallback) {
        return spawnIntervalSeconds == null ? fallback : Math.max(1, spawnIntervalSeconds);
    }

    /** 本场地每波刷新数量；未配置时回落全局默认值。 */
    public int minionSpawnPerSpawn(int fallback) {
        return spawnPerSpawn == null ? fallback : Math.max(1, spawnPerSpawn);
    }

    /** 本场地场上月人上限；未配置时回落全局默认值。 */
    public int minionSpawnMaxAlive(int fallback) {
        return spawnMaxAlive == null ? fallback : Math.max(1, spawnMaxAlive);
    }

    /** 是否配置了任一项场地级刷新节奏（arena list 展示用）。 */
    public boolean hasMinionSpawnSettings() {
        return spawnIntervalSeconds != null || spawnPerSpawn != null || spawnMaxAlive != null;
    }

    // ---------------------------------------------------------------- 基地数量规则

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
     * 每队基地数量上限；{@code 0} 表示 config.yml 里写了 {@code auto}（不设上限）。
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

    /** 开局时每队需要配满的基地数量（auto 模式下只要求至少 1 个）。 */
    public int requiredBasesPerTeam() {
        int limit = baseLimit();
        return limit > 0 ? limit : 1;
    }

    /**
     * 可用基地编号的上界（提示与校验用）：固定数量时就是那个数量；
     * auto 时取"当前实际配到的最大编号 + 1"。
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

    // ---------------------------------------------------------------- 就绪判定

    /** 场地是否已具备开局条件（等待出生点为多房间匹配的必备项）。 */
    public boolean isReady() {
        if (minionRegions.isEmpty() || !hasWaitSpawn()) {
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
        if (!hasWaitSpawn()) {
            builder.append("等待出生点 ");
        }
        return builder.isEmpty() ? "无" : builder.toString().trim();
    }

    // ---------------------------------------------------------------- 持久化

    /**
     * 从配置段读入单个场地。新格式的段是 {@code arenas.<id>}；
     * 旧版 arena.yml 的根节点结构与单场地段一致，迁移时直接把根节点传进来即可。
     */
    public void read(ConfigurationSection section) {
        minionRegions.clear();
        minionRegionKinds.clear();
        lootRegions.clear();
        bases.values().forEach(Map::clear);
        spawns.clear();
        outpost = null;
        waitSpawn = null;
        spawnIntervalSeconds = null;
        spawnPerSpawn = null;
        spawnMaxAlive = null;
        if (section == null) {
            return;
        }
        enabled = section.getBoolean("enabled", true);
        String displayText = section.getString("display");
        if (displayText != null && !displayText.isBlank()) {
            display = displayText;
        }

        readMinionRegions(section.getConfigurationSection("minion-regions"));
        // 旧版单键 minion-region 当作 1 号区域（没有 kind 键，按 mixed 处理）
        CuboidRegion legacy = CuboidRegion.read(section.getConfigurationSection("minion-region"));
        if (legacy != null && !minionRegions.containsKey(1)) {
            setMinionRegion(1, legacy);
        }
        readRegions(section.getConfigurationSection("loot-regions"), lootRegions);

        ConfigurationSection minionSpawnSection = section.getConfigurationSection("minion-spawn");
        if (minionSpawnSection != null) {
            if (minionSpawnSection.isInt("interval-seconds")) {
                spawnIntervalSeconds = minionSpawnSection.getInt("interval-seconds");
            }
            if (minionSpawnSection.isInt("per-spawn")) {
                spawnPerSpawn = minionSpawnSection.getInt("per-spawn");
            }
            if (minionSpawnSection.isInt("max-alive")) {
                spawnMaxAlive = minionSpawnSection.getInt("max-alive");
            }
        }

        ConfigurationSection baseSection = section.getConfigurationSection("bases");
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
                        plugin.getLogger().warning("场地 " + id + " 的 " + team.key()
                                + " 基地编号非法: " + key);
                    }
                }
            }
        }

        ConfigurationSection spawnSection = section.getConfigurationSection("spawns");
        if (spawnSection != null) {
            for (TeamId team : TeamId.values()) {
                Location location = readLocation(spawnSection.getConfigurationSection(team.key()));
                if (location != null) {
                    spawns.put(team, location);
                }
            }
        }
        outpost = readLocation(section.getConfigurationSection("outpost"));
        waitSpawn = readLocation(section.getConfigurationSection("wait-spawn"));
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
                plugin.getLogger().warning("场地 " + id + " 的区域编号非法: " + key);
            }
        }
    }

    /** 读入月人刷新区（区域 + kind 标签；旧文件没有 kind 时按 mixed 处理）。 */
    private void readMinionRegions(ConfigurationSection section) {
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            try {
                int index = Integer.parseInt(key);
                ConfigurationSection sub = section.getConfigurationSection(key);
                CuboidRegion region = CuboidRegion.read(sub);
                if (region != null) {
                    setMinionRegion(index, region, sub == null ? null : sub.getString("kind"));
                }
            } catch (NumberFormatException ignored) {
                plugin.getLogger().warning("场地 " + id + " 的区域编号非法: " + key);
            }
        }
    }

    /** 把本场地写入 {@code arenas.<id>} 段。 */
    public void write(ConfigurationSection section) {
        section.set("display", display());
        section.set("enabled", enabled);
        for (Map.Entry<Integer, CuboidRegion> entry : minionRegions.entrySet()) {
            ConfigurationSection regionSection = section.createSection("minion-regions." + entry.getKey());
            entry.getValue().write(regionSection);
            regionSection.set("kind", minionRegionKind(entry.getKey()));
        }
        for (Map.Entry<Integer, CuboidRegion> entry : lootRegions.entrySet()) {
            entry.getValue().write(section.createSection("loot-regions." + entry.getKey()));
        }
        for (TeamId team : TeamId.values()) {
            ConfigurationSection teamSection = section.createSection("bases." + team.key());
            for (Map.Entry<Integer, CuboidRegion> entry : bases(team).entrySet()) {
                entry.getValue().write(teamSection.createSection(String.valueOf(entry.getKey())));
            }
        }
        for (TeamId team : TeamId.values()) {
            Location spawn = spawns.get(team);
            if (spawn != null && spawn.getWorld() != null) {
                writeLocation(section.createSection("spawns." + team.key()), spawn);
            }
        }
        if (outpost != null && outpost.getWorld() != null) {
            writeLocation(section.createSection("outpost"), outpost);
        }
        if (waitSpawn != null && waitSpawn.getWorld() != null) {
            writeLocation(section.createSection("wait-spawn"), waitSpawn);
        }
        if (hasMinionSpawnSettings()) {
            ConfigurationSection spawnSection = section.createSection("minion-spawn");
            if (spawnIntervalSeconds != null) {
                spawnSection.set("interval-seconds", spawnIntervalSeconds);
            }
            if (spawnPerSpawn != null) {
                spawnSection.set("per-spawn", spawnPerSpawn);
            }
            if (spawnMaxAlive != null) {
                spawnSection.set("max-alive", spawnMaxAlive);
            }
        }
    }

    /** 读取一个位置段；世界不存在时告警并返回 null。 */
    static Location readLocation(ConfigurationSection section) {
        if (section == null || !section.isString("world")) {
            return null;
        }
        World world = Bukkit.getWorld(section.getString("world"));
        if (world == null) {
            return null;
        }
        return new Location(world,
                section.getDouble("x"), section.getDouble("y"), section.getDouble("z"),
                (float) section.getDouble("yaw"), (float) section.getDouble("pitch"));
    }

    static void writeLocation(ConfigurationSection section, Location location) {
        section.set("world", location.getWorld().getName());
        section.set("x", location.getX());
        section.set("y", location.getY());
        section.set("z", location.getZ());
        section.set("yaw", (double) location.getYaw());
        section.set("pitch", (double) location.getPitch());
    }
}
