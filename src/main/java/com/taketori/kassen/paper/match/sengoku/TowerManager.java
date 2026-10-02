package com.taketori.kassen.paper.match.sengoku;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.core.match.TeamId;
import com.taketori.kassen.core.match.sengoku.TowerRules;
import com.taketori.kassen.paper.item.PDCKeys;
import com.taketori.kassen.paper.match.ArenaDef;
import com.taketori.kassen.paper.match.room.GameRoom;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 箭楼：归属、守卫刷新、"清空守卫才能占领"的判定。
 *
 * <p>箭楼与现有基地最关键的区别是<b>可反复易手</b>：基地拆一次就永久消失，
 * 箭楼是双方反复争夺的据点。因此这里的归属是一张随时可写的表，而不是"已拆除"集合。</p>
 *
 * <p>占领的<b>读条</b>不在这里，在 {@link TowerCaptureManager}——那里要处理"双方同时读条
 * 互相锁死"的规则，与守卫这套逻辑正交。</p>
 *
 * <p>守卫的归属靠 PDC（{@link PDCKeys#towerGuardIndex()}）而不是内存表：守卫所在的区块可能
 * 被卸载再加载，内存里的实体引用会失效，PDC 让它们跨卸载仍然认得出自己属于哪座箭楼。</p>
 */
public final class TowerManager {

    /** 一座箭楼的运行时状态。 */
    private static final class TowerState {
        /** 当前归属；{@code null} = 中立。 */
        private TeamId owner;
        /** 存活守卫的 UUID。 */
        private final Set<UUID> guards = ConcurrentHashMap.newKeySet();
        /** 守卫全灭后安排的重刷时刻（毫秒）；0 = 无需重刷。 */
        private long nextRespawnAt;
    }

    private final GameRoom room;
    private final TaketoriPlugin plugin;
    private final Map<Integer, TowerState> towers = new LinkedHashMap<>();
    private BukkitTask task;

    public TowerManager(GameRoom room) {
        this.room = room;
        this.plugin = room.plugin();
    }

    private TowerRules rules() {
        return plugin.config().towerRules();
    }

    // ---------------------------------------------------------------- 生命周期

    /** 开战：建表、刷守卫、起 tick。 */
    public void start() {
        stop();
        int count = rules().safeCount();
        for (int index = 1; index <= count; index++) {
            TowerState state = new TowerState();
            towers.put(index, state);
            spawnGuards(index, state);
        }
        task = plugin.scheduler().runTimerTask(this::tick, 20L, 20L);
        if (plugin.config().debug()) {
            plugin.getLogger().info("[sengoku] 房间 " + room.id() + " 箭楼已启动："
                    + towers.size() + " 座");
        }
    }

    /** 收尾 / 小局重置：停 tick 并清掉全部守卫。 */
    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (TowerState state : towers.values()) {
            removeGuards(state);
        }
        towers.clear();
    }

    // ---------------------------------------------------------------- 归属查询

    /** 箭楼总数（按配置）。 */
    public int towerCount() {
        return rules().safeCount();
    }

    /** 某座箭楼的归属；{@code null} = 中立。 */
    public TeamId ownerOf(int index) {
        TowerState state = towers.get(index);
        return state == null ? null : state.owner;
    }

    /** 某队当前占领的箭楼数（超时判定与小局播报用）。 */
    public int countOf(TeamId team) {
        if (team == null) {
            return 0;
        }
        int count = 0;
        for (TowerState state : towers.values()) {
            if (state.owner == team) {
                count++;
            }
        }
        return count;
    }

    /** 是否所有箭楼都还没被占领（开局状态）。 */
    public boolean allNeutral() {
        for (TowerState state : towers.values()) {
            if (state.owner != null) {
                return false;
            }
        }
        return true;
    }

    /** 形如 {@code "红 1 / 蓝 0"}，用于 BossBar 与调试。 */
    public String describe() {
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<Integer, TowerState> entry : towers.entrySet()) {
            if (builder.length() > 0) {
                builder.append('、');
            }
            TowerState state = entry.getValue();
            builder.append('#').append(entry.getKey()).append('=')
                    .append(state.owner == null ? "中立" : state.owner.display());
        }
        return builder.toString();
    }

    // ---------------------------------------------------------------- 守卫与占领判定

    /** 某座箭楼是否还有守卫活着。 */
    public boolean hasGuards(int index) {
        TowerState state = towers.get(index);
        return state != null && !state.guards.isEmpty();
    }

    /** 存活守卫数量（提示用）。 */
    public int guardCount(int index) {
        TowerState state = towers.get(index);
        return state == null ? 0 : state.guards.size();
    }

    /**
     * 现在能不能占领这座箭楼。
     *
     * <p>需求第 7 条：必须<b>先清空守卫</b>再敲钟。箭楼不存在（配置数量与点位对不上）时也不可占领。</p>
     */
    public boolean canCapture(int index) {
        TowerState state = towers.get(index);
        if (state == null) {
            return false;
        }
        return state.guards.isEmpty();
    }

    /**
     * 把箭楼判给某队（占领成功后由 {@link TowerCaptureManager} 调用）。
     *
     * <p>归属没变时也照常播报——"又占了一次"本身就是有效信息（需求允许反复易手）。
     * 占领后守卫<b>不会</b>立刻重刷：留一个"占领窗口"，到点再由 tick 补上，
     * 这样下一次争夺需要重新清守卫。</p>
     */
    public void capture(int index, TeamId team) {
        TowerState state = towers.get(index);
        if (state == null || team == null) {
            return;
        }
        TeamId previous = state.owner;
        state.owner = team;
        // 占领后重新安排守卫：下一次争夺仍要先清守卫
        state.nextRespawnAt = System.currentTimeMillis() + Math.max(1, rules().guardRespawnSeconds()) * 1000L;
        room.broadcast("<yellow>⚑ " + team.display() + " 占领了箭楼 #" + index
                + "</yellow>" + (previous == null ? "" : " <gray>（原属 " + previous.display() + "）"));
        if (plugin.config().debug()) {
            plugin.getLogger().info("[sengoku] 房间 " + room.id() + " 箭楼 #" + index
                    + " 归属 " + (previous == null ? "中立" : previous.key()) + " → " + team.key());
        }
        // P2 的击破器与跳跃台在这里接：占领方获得进攻件与机动件
        onCaptured(index, team);
    }

    /**
     * 占领后的连锁效果。
     *
     * <p>P2 会在这里生成敌方的「大将击破器」与己方的「跳跃台」。当前只留钩子，
     * 避免先把逻辑写进 P1 再拆出来。</p>
     */
    private void onCaptured(int index, TeamId team) {
        var session = room.isSengoku() ? room.sengoku() : null;
        if (session != null && session.isFinished()) {
            return;   // 整场已结束，不再产生新的攻城件
        }
    }

    // ---------------------------------------------------------------- tick

    private void tick() {
        if (!room.isRunning()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Map.Entry<Integer, TowerState> entry : towers.entrySet()) {
            int index = entry.getKey();
            TowerState state = entry.getValue();

            // ① 摘掉已经死亡 / 失效 / 被清场的守卫
            state.guards.removeIf(id -> {
                Entity entity = Bukkit.getEntity(id);
                return entity == null || entity.isDead() || !entity.isValid();
            });

            // ② 全灭的瞬间安排重刷，并给两侧播报（"可以去敲钟了"是关键信息）
            if (state.guards.isEmpty() && state.nextRespawnAt == 0L) {
                state.nextRespawnAt = now + Math.max(1, rules().guardRespawnSeconds()) * 1000L;
                room.broadcast("<green>箭楼 #" + index + " 的守卫已被肃清"
                        + "<gray>——" + Math.max(1, rules().guardRespawnSeconds()) + " 秒内敲响铜钟即可占领");
                if (plugin.config().debug()) {
                    plugin.getLogger().info("[sengoku] 箭楼 #" + index + " 守卫已清空");
                }
            }

            // ③ 到点重刷（下一次争夺重新需要清守卫）
            if (state.nextRespawnAt > 0L && now >= state.nextRespawnAt) {
                state.nextRespawnAt = 0L;
                spawnGuards(index, state);
            }
        }
    }

    // ---------------------------------------------------------------- 守卫刷新

    private void spawnGuards(int index, TowerState state) {
        removeGuards(state);
        ArenaDef.Point spawn = guardSpawnPoint(index);
        if (spawn == null) {
            // 点位没划：不刷守卫也不报错——doctor 会点名，这里只留调试日志
            if (plugin.config().debug()) {
                plugin.getLogger().info("[sengoku] 箭楼 #" + index + " 没有守卫刷新点，跳过刷怪");
            }
            return;
        }
        Location center = spawn.toBukkitLocation();
        if (center == null || center.getWorld() == null) {
            return;
        }
        TowerRules towerRules = rules();
        spawnGuardGroup(index, state, center, towerRules.oxDemon());
        spawnGuardGroup(index, state, center, towerRules.shrimpCrab());
    }

    private void spawnGuardGroup(int index, TowerState state, Location center, TowerRules.GuardSpec spec) {
        if (spec == null || spec.count() <= 0) {
            return;
        }
        EntityType type = parseEntity(spec.entity());
        if (type == null) {
            return;
        }
        for (int i = 0; i < spec.count(); i++) {
            Location spot = center.clone().add(
                    (Math.random() - 0.5D) * 4.0D, 0.0D, (Math.random() - 0.5D) * 4.0D);
            Entity spawned = center.getWorld().spawnEntity(spot, type);
            if (!(spawned instanceof LivingEntity living)) {
                spawned.remove();
                continue;
            }
            living.getPersistentDataContainer().set(PDCKeys.towerGuardIndex(),
                    PersistentDataType.INTEGER, index);
            living.customName(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                    .deserialize(spec.display()));
            living.setCustomNameVisible(false);
            living.setRemoveWhenFarAway(false);
            living.setPersistent(false);   // 小局结束就清场，不需要写进世界存档
            applyAttribute(living, "max_health", spec.health());
            applyAttribute(living, "attack_damage", spec.damage());
            if (living.getHealth() > living.getMaxHealth()) {
                living.setHealth(living.getMaxHealth());
            }
            PotionEffectType speed = plugin.versions().potionEffect("SPEED");
            if (speed != null && spec.speedAmplifier() > 0) {
                living.addPotionEffect(new PotionEffect(speed, Integer.MAX_VALUE,
                        spec.speedAmplifier(), false, false, false));
            }
            if (living instanceof Mob mob) {
                mob.setTarget(null);
            }
            state.guards.add(living.getUniqueId());
        }
    }

    /** 用 VersionAdapter 解析属性名（不同版本名字不同），解析不到就静默跳过。 */
    private void applyAttribute(LivingEntity living, String configuredName, double value) {
        Attribute attribute = plugin.versions().attribute(configuredName);
        if (attribute == null) {
            return;
        }
        AttributeInstance instance = living.getAttribute(attribute);
        if (instance != null) {
            instance.setBaseValue(value);
        }
    }

    private EntityType parseEntity(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return EntityType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning("[sengoku] 箭楼守卫的实体名无法解析：" + raw);
            return null;
        }
    }

    private void removeGuards(TowerState state) {
        if (state.guards.isEmpty()) {
            return;
        }
        for (UUID id : new ArrayList<>(state.guards)) {
            Entity entity = Bukkit.getEntity(id);
            if (entity != null) {
                entity.remove();
            }
        }
        state.guards.clear();
    }

    private ArenaDef.Point guardSpawnPoint(int index) {
        var arena = room.arena();
        if (arena == null) {
            return null;
        }
        return arena.sengoku().guardSpawn(index);
    }

    /** 当前每座箭楼的守卫数（调试与 doctor 用）。 */
    public Map<Integer, Integer> guardCounts() {
        Map<Integer, Integer> result = new HashMap<>();
        for (Map.Entry<Integer, TowerState> entry : towers.entrySet()) {
            result.put(entry.getKey(), entry.getValue().guards.size());
        }
        return result;
    }

    /** 某座箭楼的守卫实体列表（调试用）。 */
    public List<LivingEntity> guardEntities(int index) {
        TowerState state = towers.get(index);
        if (state == null) {
            return List.of();
        }
        List<LivingEntity> result = new ArrayList<>();
        for (UUID id : state.guards) {
            Entity entity = Bukkit.getEntity(id);
            if (entity instanceof LivingEntity living) {
                result.add(living);
            }
        }
        return result;
    }
}
