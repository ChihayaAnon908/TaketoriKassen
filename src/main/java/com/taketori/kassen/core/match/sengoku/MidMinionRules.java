package com.taketori.kassen.core.match.sengoku;

import java.util.Locale;

/**
 * 中地小兵规则（来自 {@code sengoku-minions.yml}）。纯数据。
 *
 * <p>刻意不复用 PVE 的 {@code MinionSpawner}：那边绑着波次、精英潮、难度档这些
 * <b>PVE 专属</b>机制，而这里要的是"场地中央持续平刷、可以被绕过"的压力源，
 * 复用反而要把那些特性逐条关掉。</p>
 *
 * @param entity         小兵实体名（默认直接用月人的载体）
 * @param display        显示名（MiniMessage）；留空则不设名字
 * @param intervalSeconds 刷新间隔（秒）
 * @param perSpawn       每次刷新几只
 * @param maxAlive       场上上限，达到就停止刷新（性能兜底）
 * @param shardTick      分片：每 tick 只处理 1/N 个刷新区，避免一次性全刷造成卡顿
 * @param aiSimplify     是否简化 AI（关闭部分寻路），大量实体时的性能开关
 * @param health         血量上限
 * @param damage         攻击力
 * @param scoreOnKill    击杀给的对局积分；<b>默认 0</b>——刻意不计分，
 *                       否则"刷小兵赢比赛"会变成有效战术
 */
public record MidMinionRules(String entity,
                             String display,
                             int intervalSeconds,
                             int perSpawn,
                             int maxAlive,
                             int shardTick,
                             boolean aiSimplify,
                             double health,
                             double damage,
                             int scoreOnKill) {

    /** 默认：与月人同载体、每 6 秒 4 只、上限 40、分片 4、不计分。 */
    public static MidMinionRules defaults() {
        return new MidMinionRules("ZOMBIE", "<gray>中地小兵</gray>",
                6, 4, 40, 4, true, 20.0D, 4.0D, 0);
    }

    /** 刷新间隔（tick），至少 1 tick。 */
    public long intervalTicks() {
        return Math.max(1, intervalSeconds) * 20L;
    }

    /** 每次刷新数量，至少 1。 */
    public int safePerSpawn() {
        return Math.max(1, perSpawn);
    }

    /** 上限，至少 1（写成 0 会导致永远刷不出兵）。 */
    public int safeMaxAlive() {
        return Math.max(1, maxAlive);
    }

    /**
     * 分片数：夹在 1..{@code regionCount} 之间。
     *
     * <p>分片的意义是"把 N 个刷新区的处理摊到 N 个 tick 上"，所以分片数超过区域数没有意义。</p>
     */
    public int safeShardTick(int regionCount) {
        int shards = Math.max(1, shardTick);
        return Math.min(shards, Math.max(1, regionCount));
    }

    /** 是否要设显示名。 */
    public boolean hasDisplay() {
        return display != null && !display.isBlank();
    }

    /** 规范化实体名，便于比较与日志。 */
    public String normalizedEntity() {
        return entity == null ? "" : entity.trim().toUpperCase(Locale.ROOT);
    }
}
