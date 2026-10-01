package com.taketori.kassen.paper.state;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 短时战斗状态：防御窗口、免摔窗口、反射窗口、标记、连击层数。
 *
 * <p>放在 paper 层而不是 core，是因为它只在事件回调里被读写；计时用毫秒。</p>
 */
public final class CombatStates {

    /** 一个防御窗口（强化防御 / 镜面展开的护盾部分）。 */
    public record Defense(long untilMillis, double reflectRatio, double absorption, String source) {

        public boolean expired() {
            return System.currentTimeMillis() >= untilMillis;
        }

        public double remainingSeconds() {
            return Math.max(0.0D, (untilMillis - System.currentTimeMillis()) / 1000.0D);
        }
    }

    /** 反射窗口：期间把来袭的飞行物弹回发射者。 */
    public record Reflection(long untilMillis, double speedMultiplier, String source) {

        public boolean expired() {
            return System.currentTimeMillis() >= untilMillis;
        }

        public double remainingSeconds() {
            return Math.max(0.0D, (untilMillis - System.currentTimeMillis()) / 1000.0D);
        }
    }

    /** 被标记：受到的伤害提升。 */
    public record Mark(long untilMillis, double bonus) {

        public boolean expired() {
            return System.currentTimeMillis() >= untilMillis;
        }
    }

    private static final class ChainState {
        private String weaponId;
        private int stacks;
        private long expiresAt;
    }

    private final Map<UUID, Defense> defenses = new ConcurrentHashMap<>();
    private final Map<UUID, Long> fallImmunities = new ConcurrentHashMap<>();
    private final Map<UUID, Reflection> reflections = new ConcurrentHashMap<>();
    private final Map<UUID, Mark> marks = new ConcurrentHashMap<>();
    private final Map<UUID, ChainState> chains = new ConcurrentHashMap<>();

    // ---------------------------------------------------------------- 防御窗口

    public void setDefense(UUID uuid, int durationTicks, double reflectRatio, double absorption, String source) {
        if (uuid == null || durationTicks <= 0) {
            return;
        }
        defenses.put(uuid, new Defense(System.currentTimeMillis() + durationTicks * 50L, reflectRatio, absorption, source));
    }

    public Defense defense(UUID uuid) {
        if (uuid == null) {
            return null;
        }
        Defense defense = defenses.get(uuid);
        if (defense == null) {
            return null;
        }
        if (defense.expired()) {
            defenses.remove(uuid);
            return null;
        }
        return defense;
    }

    // ---------------------------------------------------------------- 免摔窗口

    /**
     * 推进类技能的落地保护。刻意<b>不使用缓降药水</b>——那会让玩家下落变慢（看起来像飘），
     * 与推进手感冲突；这里直接给一个免掉摔落伤害的窗口。
     */
    public void setFallImmunity(UUID uuid, int ticks) {
        if (uuid == null || ticks <= 0) {
            return;
        }
        fallImmunities.put(uuid, System.currentTimeMillis() + ticks * 50L);
    }

    public boolean isFallImmune(UUID uuid) {
        if (uuid == null) {
            return false;
        }
        Long until = fallImmunities.get(uuid);
        if (until == null) {
            return false;
        }
        if (System.currentTimeMillis() >= until) {
            fallImmunities.remove(uuid);
            return false;
        }
        return true;
    }

    // ---------------------------------------------------------------- 反射窗口（月镜）

    public void setReflection(UUID uuid, int durationTicks, double speedMultiplier, String source) {
        if (uuid == null || durationTicks <= 0) {
            return;
        }
        reflections.put(uuid, new Reflection(
                System.currentTimeMillis() + durationTicks * 50L,
                Math.max(0.2D, speedMultiplier),
                source));
    }

    public Reflection reflection(UUID uuid) {
        if (uuid == null) {
            return null;
        }
        Reflection reflection = reflections.get(uuid);
        if (reflection == null) {
            return null;
        }
        if (reflection.expired()) {
            reflections.remove(uuid);
            return null;
        }
        return reflection;
    }

    // ---------------------------------------------------------------- 标记（乃依）

    public void mark(UUID uuid, int ticks, double bonus) {
        if (uuid == null || ticks <= 0 || bonus <= 0.0D) {
            return;
        }
        marks.put(uuid, new Mark(System.currentTimeMillis() + ticks * 50L, bonus));
    }

    /** 被标记时返回伤害加成比例（0 = 没被标记）。 */
    public double markBonus(UUID uuid) {
        if (uuid == null) {
            return 0.0D;
        }
        Mark mark = marks.get(uuid);
        if (mark == null) {
            return 0.0D;
        }
        if (mark.expired()) {
            marks.remove(uuid);
            return 0.0D;
        }
        return mark.bonus();
    }

    // ---------------------------------------------------------------- 连击层数（彩叶的剑）

    /**
     * 记一次命中，返回"本次之前已有的层数"（第一次命中返回 0）。
     * 超过窗口或换了武器就重新计数。
     */
    public int bumpChain(UUID uuid, String weaponId, int windowTicks) {
        if (uuid == null || weaponId == null) {
            return 0;
        }
        long now = System.currentTimeMillis();
        long window = Math.max(1, windowTicks) * 50L;
        ChainState state = chains.get(uuid);
        if (state == null || !weaponId.equals(state.weaponId) || now >= state.expiresAt) {
            state = new ChainState();
            state.weaponId = weaponId;
            state.stacks = 0;
        }
        int previous = state.stacks;
        state.stacks = previous + 1;
        state.expiresAt = now + window;
        chains.put(uuid, state);
        return previous;
    }

    public int chainStacks(UUID uuid, String weaponId) {
        ChainState state = uuid == null ? null : chains.get(uuid);
        if (state == null || !state.weaponId.equals(weaponId) || System.currentTimeMillis() >= state.expiresAt) {
            return 0;
        }
        return state.stacks;
    }

    // ---------------------------------------------------------------- 清理

    public void clear(UUID uuid) {
        if (uuid == null) {
            return;
        }
        defenses.remove(uuid);
        fallImmunities.remove(uuid);
        reflections.remove(uuid);
        marks.remove(uuid);
        chains.remove(uuid);
    }

    /**
     * 主动清扫全部过期条目（含已消失生物留下的 mark / defense 等）。
     * 惰性清理只在同一 UUID 再次被访问时触发——被标记的月人若直接消失，
     * 条目永远无人访问而残留，故需要一个周期驱动点。
     */
    public void sweepExpired() {
        long now = System.currentTimeMillis();
        defenses.entrySet().removeIf(entry -> now >= entry.getValue().untilMillis());
        reflections.entrySet().removeIf(entry -> now >= entry.getValue().untilMillis());
        marks.entrySet().removeIf(entry -> now >= entry.getValue().untilMillis());
        fallImmunities.entrySet().removeIf(entry -> now >= entry.getValue());
        chains.entrySet().removeIf(entry -> now >= entry.getValue().expiresAt);
    }

    public void clearAll() {
        defenses.clear();
        fallImmunities.clear();
        reflections.clear();
        marks.clear();
        chains.clear();
    }

    public int size() {
        return defenses.size() + fallImmunities.size() + reflections.size() + marks.size() + chains.size();
    }
}
