package com.taketori.kassen.paper.match;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.core.match.TeamId;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * 跨局累计统计（stats.yml）：总得分、参赛局数、胜场、单局最高分。
 *
 * <p>满足需求里的"记录累计得分最多玩家" —— 单局内的得分王在 MatchManager 里播报，
 * 这里负责<b>跨局</b>的历史榜，服务器重启后仍然保留。</p>
 */
public final class StatsTracker {

    private static final class Entry {
        private long totalScore;
        private int matches;
        private int wins;
        private long bestScore;
    }

    private final TaketoriPlugin plugin;
    private final File file;
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    public StatsTracker(TaketoriPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "data/stats.yml");
    }

    public void load() {
        entries.clear();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("players");
        if (root == null) {
            return;
        }
        for (String name : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(name);
            if (section == null) {
                continue;
            }
            Entry entry = new Entry();
            entry.totalScore = section.getLong("total-score", 0L);
            entry.matches = section.getInt("matches", 0);
            entry.wins = section.getInt("wins", 0);
            entry.bestScore = section.getLong("best-score", 0L);
            entries.put(name, entry);
        }
        plugin.getLogger().info("已载入 " + entries.size() + " 名玩家的历史战绩");
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<String, Entry> mapEntry : entries.entrySet()) {
            String path = "players." + mapEntry.getKey();
            Entry entry = mapEntry.getValue();
            yaml.set(path + ".total-score", entry.totalScore);
            yaml.set(path + ".matches", entry.matches);
            yaml.set(path + ".wins", entry.wins);
            yaml.set(path + ".best-score", entry.bestScore);
        }
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                plugin.getLogger().warning("无法创建数据目录: " + parent);
            }
            yaml.save(file);
        } catch (Exception ex) {
            plugin.getLogger().log(Level.SEVERE, "保存 stats.yml 失败", ex);
        }
    }

    /** 一局结束时写入：每个参赛者累加本局得分与场次，胜方成员累加胜场。 */
    public void recordMatch(TeamId winner,
                            Map<TeamId, Integer> teamScores,
                            Map<UUID, Integer> playerScores,
                            Map<UUID, String> playerNames) {
        for (Map.Entry<UUID, String> mapEntry : playerNames.entrySet()) {
            String name = mapEntry.getValue();
            int score = playerScores.getOrDefault(mapEntry.getKey(), 0);
            Entry entry = entries.computeIfAbsent(name, key -> new Entry());
            entry.matches++;
            entry.totalScore += score;
            entry.bestScore = Math.max(entry.bestScore, score);
        }
        if (winner != null) {
            // 胜场按"该队成员"记；队友名单从 playerNames 里无法直接得知，这里按 teamScores 与得分归属近似处理
            // 简化：胜方所有有得分的玩家都算参赛者，胜场在 recordWin 里单独调用
            plugin.getLogger().fine("对局结束：胜方 " + winner.display() + "（胜场由 recordWin 记录）");
        }
        save();
    }

    /** 单独记录胜场（需要队伍成员名单，由 MatchManager 在结算时调用）。 */
    public void recordWin(List<String> winners) {
        for (String name : winners) {
            entries.computeIfAbsent(name, key -> new Entry()).wins++;
        }
        save();
    }

    /** 历史排行榜文本（按总得分降序），供指令展示。 */
    public List<String> leaderboard(int limit) {
        List<Map.Entry<String, Entry>> sorted = new ArrayList<>(entries.entrySet());
        sorted.sort(Comparator.comparingLong((Map.Entry<String, Entry> e) -> e.getValue().totalScore).reversed());
        List<String> lines = new ArrayList<>();
        int rank = 1;
        for (Map.Entry<String, Entry> mapEntry : sorted) {
            if (rank > limit) {
                break;
            }
            Entry entry = mapEntry.getValue();
            lines.add(String.format("%d. %s — 总得分 %d / 场次 %d / 胜场 %d / 单局最高 %d",
                    rank++, mapEntry.getKey(), entry.totalScore, entry.matches, entry.wins, entry.bestScore));
        }
        return lines;
    }

    public int size() {
        return entries.size();
    }
}
