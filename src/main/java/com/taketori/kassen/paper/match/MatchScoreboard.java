package com.taketori.kassen.paper.match;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.core.match.PveSettings;
import com.taketori.kassen.core.match.TeamId;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 对局记分板（侧边栏）：实时显示双方比分与自己的得分。
 *
 * <p>每个玩家一份独立记分板 —— 因为"你的得分"是因人而异的。
 * 3v3 只有 6 人，这点开销可以忽略；玩家离线或对局结束时恢复主记分板。</p>
 */
public final class MatchScoreboard {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private static final String LINE_RED = "红队";
    private static final String LINE_BLUE = "蓝队";
    private static final String LINE_TOTAL = "总分";
    private static final String LINE_SEPARATOR = "─────────";
    private static final String LINE_MINE = "你的得分";
    private static final String LINE_KILLS = "本局击杀";
    private static final String LINE_WAVE = "波次";
    private static final String LINE_TIME = "剩余(分钟)";
    /** PVE：据点耐久百分比（0-100）。 */
    private static final String LINE_OUTPOST = "据点(%)";

    private final TaketoriPlugin plugin;
    private final Map<UUID, Scoreboard> boards = new HashMap<>();
    private final Map<UUID, Objective> objectives = new HashMap<>();

    public MatchScoreboard(TaketoriPlugin plugin) {
        this.plugin = plugin;
    }

    public void showTo(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        Scoreboard board = boards.get(player.getUniqueId());
        if (board == null) {
            board = Bukkit.getScoreboardManager().getNewScoreboard();
            Objective objective = board.registerNewObjective("taketori", Criteria.DUMMY,
                    MINI.deserialize("<gold><bold>竹取合战 3v3</bold>"));
            objective.setDisplaySlot(DisplaySlot.SIDEBAR);
            boards.put(player.getUniqueId(), board);
            objectives.put(player.getUniqueId(), objective);
        }
        player.setScoreboard(board);
        update(player);
    }

    public void hide(Player player) {
        if (player == null) {
            return;
        }
        boards.remove(player.getUniqueId());
        objectives.remove(player.getUniqueId());
        if (player.isOnline()) {
            player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        }
    }

    /** 刷新所有在线玩家的记分板。 */
    public void updateAll() {
        for (UUID uuid : List.copyOf(boards.keySet())) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) {
                boards.remove(uuid);
                objectives.remove(uuid);
                continue;
            }
            update(player);
        }
    }

    public void clearAll() {
        for (UUID uuid : List.copyOf(boards.keySet())) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
            }
        }
        boards.clear();
        objectives.clear();
    }

    private void update(Player player) {
        Objective objective = objectives.get(player.getUniqueId());
        if (objective == null) {
            return;
        }
        var match = plugin.match();
        long minutes = (match.remainingMillis() + 59_999L) / 60_000L;

        // 清掉旧条目，避免分数变化后残留旧行
        for (String entry : objective.getScoreboard().getEntries()) {
            objective.getScoreboard().resetScores(entry);
        }

        if (match.isPve()) {
            // PVE：只有一个总分（目标分写在标题里），击杀数按全队合计
            objective.displayName(MINI.deserialize("<gold><bold>竹取合战 PVE</bold> <gray>目标 "
                    + match.rules().scoreToWin()));
            objective.getScore(LINE_TOTAL).setScore(match.teamScore(TeamId.RED));
            objective.getScore(LINE_SEPARATOR).setScore(999);
            // 保卫据点：显示耐久百分比（据点被拆掉就直接判负，所以放在显眼的位置）
            OutpostManager outpost = plugin.outpost();
            if (outpost.isActive()) {
                objective.getScore(LINE_OUTPOST).setScore((int) Math.round(outpost.healthRatio() * 100.0D));
            }
            objective.getScore(LINE_MINE).setScore(match.playerScore(player.getUniqueId()));
            objective.getScore(LINE_KILLS).setScore(match.teamTotalKills(TeamId.RED));
            // 大波次：显示"当前 / 总数"，让玩家知道还剩几波
            PveSettings pve = plugin.pveSettings();
            if (pve.bigWavesEnabled() && pve.bigWaveCount() > 0) {
                objective.getScore("大波次/" + pve.bigWaveCount()).setScore(plugin.minions().bigWave());
            }
            objective.getScore(LINE_WAVE).setScore(plugin.minions().wave());
            objective.getScore(LINE_TIME).setScore((int) Math.min(999, minutes));
            return;
        }

        objective.displayName(MINI.deserialize("<gold><bold>竹取合战 3v3</bold>"));
        objective.getScore(LINE_RED).setScore(match.teamScore(TeamId.RED));
        objective.getScore(LINE_BLUE).setScore(match.teamScore(TeamId.BLUE));
        objective.getScore(LINE_SEPARATOR).setScore(999);
        objective.getScore(LINE_MINE).setScore(match.playerScore(player.getUniqueId()));
        objective.getScore(LINE_KILLS).setScore(match.totalKillsOf(player.getUniqueId()));
        objective.getScore(LINE_WAVE).setScore(plugin.minions().wave());
        objective.getScore(LINE_TIME).setScore((int) Math.min(999, minutes));
    }

    /** 基地占点进度等临时提示走 ActionBar，不占用记分板。 */
    public void actionBar(Player player, String miniMessage) {
        if (player != null && player.isOnline()) {
            player.sendActionBar(MINI.deserialize(miniMessage));
        }
    }
}
