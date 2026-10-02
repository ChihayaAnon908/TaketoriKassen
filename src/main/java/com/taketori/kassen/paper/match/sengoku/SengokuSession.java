package com.taketori.kassen.paper.match.sengoku;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.core.match.TeamId;
import com.taketori.kassen.core.match.sengoku.RoundResult;
import com.taketori.kassen.core.match.sengoku.SengokuRules;
import com.taketori.kassen.core.match.sengoku.SengokuScore;
import com.taketori.kassen.paper.match.room.GameRoom;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

/**
 * 战国 3v3 的小局编排：三局两胜、逐局重置、整场判定。
 *
 * <p><b>为什么单独一层而不是改进 {@code GameRoom}</b>：{@code GameRoom} 的
 * {@code Phase}（WAITING/STARTING/CAGED/PLAYING/ENDING）描述的是<b>一个小局</b>的生命周期，
 * 现有 PVP / PVE 也按这个语义跑。多局制是新模式专属需求，独立成编排层后那两条线一行都不用改，
 * 将来要给 PVP 也加多局制，可以复用同一套抽象。</p>
 *
 * <p>与 {@code GameRoom} 的接口只有四个，刻意压到最少：</p>
 * <ul>
 *   <li>{@link GameRoom#startPlay} 里调 {@link #onRoundStart()}；</li>
 *   <li>本类在小局结束时调 {@link GameRoom#restartRound()}（还有下一局）或
 *       {@link GameRoom#finish(TeamId)}（整场结束）；</li>
 *   <li>重置战场走 {@link GameRoom#resetSengokuBattlefield()}。</li>
 * </ul>
 */
public final class SengokuSession {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final GameRoom room;
    private final TaketoriPlugin plugin;

    /** 小局比分与整场判定（纯逻辑，见 core 层 SengokuScore）。 */
    private final SengokuScore score = new SengokuScore();

    private BukkitTask timer;
    private long roundStartedAt;
    /** 当前小局序号（1 起；平局重开时不推进，见 {@link SengokuScore#record}）。 */
    private int currentRound;
    /** 整场是否已结束（防止 ENDING 期间再来一次收尾）。 */
    private boolean finished;

    public SengokuSession(GameRoom room) {
        this.room = room;
        this.plugin = room.plugin();
    }

    private SengokuRules rules() {
        return plugin.config().sengokuRules();
    }

    // ---------------------------------------------------------------- 小局生命周期

    /** 一小局开始：起小局计时、播报。由 {@code GameRoom.startPlay} 调用。 */
    public void onRoundStart() {
        currentRound++;
        roundStartedAt = System.currentTimeMillis();
        finished = false;
        startTimer();
        if (plugin.config().debug()) {
            plugin.getLogger().info("[sengoku] 房间 " + room.id() + " 第 " + currentRound
                    + " 小局开始（" + score.display() + "），时限 "
                    + rules().timeLimitSeconds() + " 秒");
        }
        room.broadcast("<yellow>第 <white>" + currentRound + "</white> 小局开始"
                + " <gray>（当前比分 <white>" + score.display() + "</white>"
                + "，三局两胜）");
        showTitle("<yellow>第 " + currentRound + " 小局", "比分 " + score.display());
    }

    /**
     * 一小局结束：计分 → 决定"还有下一局"还是"整场结束"。
     *
     * <p>由击破器读条完成（P2）、小局超时（本类计时器）或管理员强制判定调用。</p>
     */
    public void onRoundEnd(RoundResult result) {
        if (result == null || finished) {
            return;
        }
        stopTimer();
        boolean recorded = score.record(result);
        long seconds = (System.currentTimeMillis() - roundStartedAt) / 1000L;

        if (result.isDraw()) {
            room.broadcast("<gray>第 <white>" + currentRound + "</white> 小局无人获胜"
                    + "（<white>" + result.reason().display() + "</white>）<gray>——本局重开");
            showTitle("<gray>第 " + currentRound + " 小局平局", "本局重开");
        } else {
            room.broadcast("<green>" + result.winner().display() + " 拿下第 <white>"
                    + currentRound + "</white> 小局"
                    + " <gray>（<white>" + result.reason().display() + "</white>，用时 "
                    + seconds + " 秒）<gray>　比分 <white>" + score.display() + "</white>");
            showTitle("<green>" + result.winner().display() + " 拿下第 " + currentRound + " 小局",
                    "比分 " + score.display());
        }
        if (plugin.config().debug()) {
            plugin.getLogger().info("[sengoku] 房间 " + room.id() + " 第 " + currentRound
                    + " 小局结束：" + result.reason().display()
                    + (recorded ? "，胜方 " + result.winner().key() : "，平局") + "，比分 " + score.display());
        }

        TeamId matchWinner = score.matchWinner(rules().bestOf());
        if (matchWinner != null || score.isFinished(rules().bestOf())) {
            // 整场结束：交回 GameRoom 走原有收尾（战绩、回大厅、世界回收）
            finished = true;
            room.broadcast("<dark_gray>========================================");
            room.broadcast(matchWinner == null
                    ? "<yellow>整场结束：<gray>双方战平"
                    : "<green>" + matchWinner.display() + " 以 <white>" + score.display()
                            + "</white> 赢得整场！");
            showTitle(matchWinner == null ? "<yellow>整场战平"
                            : "<green>" + matchWinner.display() + " 赢得整场",
                    "最终比分 " + score.display());
            room.finish(matchWinner);
            return;
        }

        // 还有下一局：清战场 → 重新进笼开局（队伍保留）
        room.resetSengokuBattlefield();
        String failure = room.restartRound();
        if (failure != null) {
            // 重开失败（人跑光了、场地被改坏）：别把房间卡在 ENDING，直接收尾
            plugin.getLogger().warning("[sengoku] 房间 " + room.id() + " 小局重开失败：" + failure);
            room.broadcast("<yellow>下一小局无法开始（<white>" + failure + "</white>），本场提前结束");
            room.finish(matchWinner);
        }
    }

    /** 小局计时器：到点按配置判定（箭楼数 / 平局）。 */
    private void startTimer() {
        stopTimer();
        timer = plugin.scheduler().runTimerTask(this::tick, 20L, 20L);
    }

    private void stopTimer() {
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
    }

    private void tick() {
        if (finished || !room.isRunning()) {
            return;
        }
        long limit = rules().timeLimitSeconds();
        if (limit <= 0L) {
            return;   // 0 = 不限时
        }
        long elapsed = (System.currentTimeMillis() - roundStartedAt) / 1000L;
        if (elapsed < limit) {
            return;
        }
        onRoundEnd(RoundResult.fromTimeout(rules().timeoutWinner(),
                redTowers(), blueTowers(), elapsed));
    }

    /** 红队当前占领的箭楼数（超时判定用）。 */
    private int redTowers() {
        return room.towers().countOf(TeamId.RED);
    }

    private int blueTowers() {
        return room.towers().countOf(TeamId.BLUE);
    }

    // ---------------------------------------------------------------- 对外查询

    public int currentRound() {
        return currentRound;
    }

    public int wins(TeamId team) {
        return score.wins(team);
    }

    /** 形如 {@code "1-0"}。 */
    public String display() {
        return score.display();
    }

    /** 本小局剩余秒数（不限时返回 -1）。 */
    public long remainingSeconds() {
        long limit = rules().timeLimitSeconds();
        if (limit <= 0L || roundStartedAt <= 0L) {
            return -1L;
        }
        long elapsed = (System.currentTimeMillis() - roundStartedAt) / 1000L;
        return Math.max(0L, limit - elapsed);
    }

    public boolean isFinished() {
        return finished;
    }

    /** 房间结束/销毁时停掉计时器。 */
    public void stop() {
        stopTimer();
    }

    /**
     * 给全体参赛者放一个 Title。
     *
     * <p>小局开始 / 结束这种"整场级的节点"用 Title 比聊天栏更合适：聊天栏会被
     * 战斗播报冲掉，而 Title 一定在屏幕正中。只发给参赛者，旁观者不受打扰。</p>
     */
    private void showTitle(String title, String subtitle) {
        var titleComponent = MINI.deserialize(title);
        var subtitleComponent = MINI.deserialize(subtitle);
        var times = net.kyori.adventure.title.Title.Times.times(
                java.time.Duration.ofMillis(200L),
                java.time.Duration.ofMillis(1600L),
                java.time.Duration.ofMillis(400L));
        var shown = net.kyori.adventure.title.Title.title(titleComponent, subtitleComponent, times);
        for (TeamId team : TeamId.values()) {
            for (Player player : room.teamPlayers(team)) {
                player.showTitle(shown);
            }
        }
    }
}
