package com.taketori.kassen.paper.match;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.core.match.MatchRules;
import com.taketori.kassen.core.match.TeamId;
import com.taketori.kassen.core.worlds.WorldScope;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 对局管理：3v3 积分赛。
 *
 * <p>规则（来自 matches.yml，默认值即需求）：先到 600 分获胜；击杀小怪 +3、击杀对方玩家 +10、
 * 拆除对方基地 +50；20 分钟超时按分高者胜；死亡 5 秒后在己方出生点复活。</p>
 *
 * <p>同时记录需求里要的两项结算信息：<b>最后一位得分玩家</b>与<b>本局得分最多的玩家</b>。</p>
 */
public final class MatchManager {

    public enum Phase {
        IDLE, RUNNING, ENDED
    }

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final TaketoriPlugin plugin;
    private final Map<TeamId, Integer> teamScores = new EnumMap<>(TeamId.class);
    private final Map<UUID, Integer> playerScores = new HashMap<>();
    private final Map<UUID, String> playerNames = new HashMap<>();
    private final Map<UUID, TeamId> teams = new HashMap<>();
    /** 本局每个玩家的击杀数：玩家与月人分开记，记分板显示两者合计。 */
    private final Map<UUID, Integer> playerKills = new HashMap<>();
    private final Map<UUID, Integer> minionKills = new HashMap<>();

    private Phase phase = Phase.IDLE;
    private long startedAt;
    private long endedAt;
    private TeamId winner;

    private UUID lastScorer;
    private String lastScoreReason = "-";
    private int lastScoreAmount;

    public MatchManager(TaketoriPlugin plugin) {
        this.plugin = plugin;
        resetScores();
    }

    private MatchRules rules = MatchRules.defaults();

    /**
     * 从 config.yml 读取对局规则（启动与 /taketori reload 时调用）。
     * 默认值即需求：600 分、20 分钟、5 秒复活、3/10/50 分、40 血铁甲僵尸、基地读条 10 秒。
     */
    public void loadRules() {
        var cfg = plugin.getConfig();
        rules = new MatchRules(
                cfg.getInt("match.score-to-win", 600),
                Math.max(1, cfg.getInt("match.time-limit-minutes", 20)) * 60,
                Math.max(0, cfg.getInt("match.respawn-delay-seconds", 5)),
                cfg.getInt("scoring.minion-kill", 3),
                cfg.getInt("scoring.player-kill", 10),
                cfg.getInt("scoring.base-capture", 50),
                Math.max(1, cfg.getInt("minion.health", 40)),
                cfg.getBoolean("minion.iron-armor", true),
                Math.max(1, cfg.getInt("minion.interval-seconds", 9)),
                Math.max(1, cfg.getInt("minion.per-spawn", 3)),
                Math.max(1, cfg.getInt("minion.max-alive", 15)),
                Math.max(0.1D, cfg.getDouble("base.capture-seconds", 10.0D)),
                Math.max(0.0D, cfg.getDouble("base.capture-delay-seconds", 60.0D)),
                Math.max(0.0D, cfg.getDouble("base.decay-per-second", 0.5D)),
                cfg.getBoolean("base.multi-player-bonus", true),
                cfg.getBoolean("match.keep-inventory", true),
                Math.max(0.0D, cfg.getDouble("combat.kill-heal", 6.0D)),
                Math.max(0.0D, cfg.getDouble("combat.minion-kill-heal", 0.0D)),
                "pve".equalsIgnoreCase(cfg.getString("match.mode", "pvp")),
                cfg.getString("combat.friendly-fire-protection", "auto"));
    }

    public MatchRules rules() {
        return rules;
    }

    public Phase phase() {
        return phase;
    }

    public boolean isRunning() {
        return phase == Phase.RUNNING;
    }

    /** 当前是不是 PVE 模式（所有人一队打月人：玩家之间无伤害、不做基地占点）。 */
    public boolean isPve() {
        return rules().pve();
    }

    /**
     * 友伤保护是否生效（同一队的玩家互相不造成伤害）。
     *
     * <p>策略来自 config.yml 的 <code>combat.friendly-fire-protection</code>：</p>
     * <ul>
     *   <li><code>auto</code>（默认）：<b>PVP 开启、PVE 关闭</b>；</li>
     *   <li><code>on</code>：两种模式都保护；</li>
     *   <li><code>off</code>：两种模式都不保护。</li>
     * </ul>
     */
    public boolean isFriendlyFireProtected() {
        String policy = rules().friendlyFire() == null
                ? "auto" : rules().friendlyFire().trim().toLowerCase(java.util.Locale.ROOT);
        return switch (policy) {
            case "on", "true", "always", "yes" -> true;
            case "off", "false", "never", "no" -> false;
            default -> !isPve();
        };
    }

    /**
     * 切换 PVP / PVE 并写回 config.yml（下一局生效）。
     *
     * @return 失败原因；成功返回 null
     */
    public String setMode(String mode) {
        if (phase == Phase.RUNNING) {
            return "对局进行中不能切换模式，先 /taketori match stop。";
        }
        String normalized = "pve".equalsIgnoreCase(mode) ? "pve" : "pvp";
        plugin.getConfig().set("match.mode", normalized);
        plugin.saveConfig();
        loadRules();
        // 已经在队列里的人也要跟着新模式走，否则会出现"人在队列里却没队伍"
        if (isPve()) {
            for (UUID uuid : new ArrayList<>(teams.keySet())) {
                teams.put(uuid, TeamId.RED);   // PVE：全部并到同一队
            }
        } else {
            clearTeams();                      // PVP：清空重分，避免全挤在红队
            plugin.lobby().clearQueue();
        }
        return null;
    }

    // ---------------------------------------------------------------- 队伍

    public TeamId teamOf(UUID uuid) {
        return teams.get(uuid);
    }

    public void join(Player player, TeamId team) {
        teams.put(player.getUniqueId(), team);
        playerNames.put(player.getUniqueId(), player.getName());
        playerScores.putIfAbsent(player.getUniqueId(), 0);
        plugin.matchBoard().showTo(player);
    }

    public void leave(UUID uuid) {
        teams.remove(uuid);
        Player player = Bukkit.getPlayer(uuid);
        if (player != null) {
            plugin.matchBoard().hide(player);
        }
    }

    /**
     * 玩家自助选择队伍（大厅 GUI 的「队伍选择」）。
     *
     * <p>对局进行中不允许换边；PVE 模式下所有人同队，选哪边都并到同一队；
     * PVP 下队伍满员会被拒绝。原有指令 <code>/taketori team</code> 不受影响。</p>
     *
     * @return 失败原因；成功返回 null
     */
    public String chooseTeam(Player player, TeamId team) {
        if (player == null || team == null) {
            return "队伍无效。";
        }
        if (phase == Phase.RUNNING) {
            return "对局进行中不能换队（观战请点「旁观」）。";
        }
        if (isPve()) {
            join(player, TeamId.RED);
            return null;
        }
        int maxPerTeam = Math.max(1, plugin.config().matchTeamSize());
        boolean sameTeam = teams.get(player.getUniqueId()) == team;
        if (!sameTeam && teamPlayers(team).size() >= maxPerTeam) {
            return team.display() + " 已满（每队上限 " + maxPerTeam + " 人）。";
        }
        join(player, team);
        return null;
    }

    // ---------------------------------------------------------------- 本局击杀计数

    /** 记录一次玩家击杀（本局记分板与结算用；跨局统计由 MatchListener 写入）。 */
    public void addPlayerKill(Player killer) {
        if (killer == null) {
            return;
        }
        playerKills.merge(killer.getUniqueId(), 1, Integer::sum);
        playerNames.put(killer.getUniqueId(), killer.getName());
    }

    /** 记录一次月人击杀。 */
    public void addMinionKill(Player killer) {
        if (killer == null) {
            return;
        }
        minionKills.merge(killer.getUniqueId(), 1, Integer::sum);
        playerNames.put(killer.getUniqueId(), killer.getName());
    }

    /** 本局击杀的玩家数。 */
    public int playerKillsOf(UUID uuid) {
        return playerKills.getOrDefault(uuid, 0);
    }

    /** 本局击杀的月人数。 */
    public int minionKillsOf(UUID uuid) {
        return minionKills.getOrDefault(uuid, 0);
    }

    /** 本局击杀总计（玩家 + 月人），记分板显示的就是它。 */
    public int totalKillsOf(UUID uuid) {
        return playerKillsOf(uuid) + minionKillsOf(uuid);
    }

    /** 本局全队击杀总计（PVE 记分板用）。 */
    public int teamTotalKills(TeamId team) {
        int total = 0;
        for (Map.Entry<UUID, TeamId> entry : teams.entrySet()) {
            if (entry.getValue() == team) {
                total += totalKillsOf(entry.getKey());
            }
        }
        return total;
    }

    // ---------------------------------------------------------------- 角色分配（同队唯一 + 权重裁决）

    /** 角色分配结果。 */
    public record RoleDecision(boolean granted, String displaced, String reason) {

        public boolean displacedSomeone() {
            return displaced != null;
        }
    }

    /** 玩家的隐性标签权重（未设置标签按 0）。 */
    public int weightOf(UUID uuid) {
        var profile = plugin.config().characters().profileOrNull(uuid);
        String tag = profile == null ? null : profile.tag();
        return plugin.tags().weightOf(tag);
    }

    /**
     * 请求把某个角色分配给玩家。
     *
     * <p>规则：<b>同一个队伍里不允许出现相同角色</b>（不同队伍之间可以），冲突时按隐性标签权重裁决 ——
     * 权重高者拿到角色，权重低者被拒绝；权重相同则先到先得（已占用的保住）。
     * 玩家还没分队（不在任何队伍）时直接放行，等分队或开局时再统一裁决。</p>
     */
    public RoleDecision requestRole(Player player, String characterId) {
        if (player == null || characterId == null || characterId.isBlank()) {
            return new RoleDecision(false, null, "参数无效。");
        }
        TeamId team = teams.get(player.getUniqueId());
        if (team == null) {
            return new RoleDecision(true, null, null);
        }
        UUID holder = holderOf(team, characterId, player.getUniqueId());
        if (holder == null) {
            return new RoleDecision(true, null, null);
        }
        int mine = weightOf(player.getUniqueId());
        int theirs = weightOf(holder);
        String holderName = nameOf(holder);
        if (mine > theirs) {
            // 顶替：解除对方的角色（他需要重新选一个）
            Player holderPlayer = Bukkit.getPlayer(holder);
            if (holderPlayer != null) {
                plugin.bindCharacter(holderPlayer, null);
            } else {
                plugin.config().characters().unbind(holder);
                plugin.dataStore().setCharacterId(holder, null);
            }
            broadcast("<yellow>" + nameOf(player.getUniqueId()) + " 接管了 " + team.display()
                    + " 的 " + characterId + "：" + holderName + " 的隐性标签权重较低，角色已被解除。");
            return new RoleDecision(true, holderName, null);
        }
        return new RoleDecision(false, null, team.display() + " 已经有该角色（由 " + holderName
                + " 占用，权重 " + theirs + " ≥ 你的 " + mine + "）。");
    }

    /** 开局前的最终裁决：每队同一角色只保留权重最高的那个（其余解除角色待重选）。 */
    public void enforceUniqueRoles() {
        Map<String, UUID> taken = new HashMap<>();
        for (Map.Entry<UUID, TeamId> entry : new ArrayList<>(teams.entrySet())) {
            var profile = plugin.config().characters().profileOrNull(entry.getKey());
            if (profile == null || !profile.hasCharacter()) {
                continue;
            }
            String key = entry.getValue().key() + ":" + profile.characterId();
            UUID holder = taken.get(key);
            if (holder == null) {
                taken.put(key, entry.getKey());
                continue;
            }
            UUID keeper = weightOf(entry.getKey()) > weightOf(holder) ? entry.getKey() : holder;
            UUID loser = keeper.equals(holder) ? entry.getKey() : holder;
            taken.put(key, keeper);
            Player loserPlayer = Bukkit.getPlayer(loser);
            if (loserPlayer != null) {
                plugin.bindCharacter(loserPlayer, null);
            } else {
                plugin.config().characters().unbind(loser);
                plugin.dataStore().setCharacterId(loser, null);
            }
            broadcast("<yellow>" + nameOf(loser) + " 的角色被解除："
                    + entry.getValue().display() + " 不允许出现两个相同角色（权重更高者优先）。");
        }
    }

    /** 该队里已经占用某角色的玩家（可排除自己）。 */
    private UUID holderOf(TeamId team, String characterId, UUID exclude) {
        for (Map.Entry<UUID, TeamId> entry : teams.entrySet()) {
            if (entry.getValue() != team || entry.getKey().equals(exclude)) {
                continue;
            }
            var profile = plugin.config().characters().profileOrNull(entry.getKey());
            if (profile != null && characterId.equals(profile.characterId())) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * 出生增益：开局与复活共用，持续秒数与效果都来自 config.yml 的 <code>combat.spawn-buff</code>。
     *
     * <p>配置写 0 秒或清空 effects 即为关闭；效果名解析失败会跳过（不报错）。</p>
     */
    public void applySpawnBuff(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        int seconds = plugin.config().spawnBuffSeconds();
        if (seconds <= 0) {
            return;
        }
        int ticks = seconds * 20;
        for (Map<?, ?> raw : plugin.config().spawnBuffEffects()) {
            Object typeName = raw.get("type");
            if (typeName == null) {
                continue;
            }
            var type = plugin.versions().potionEffect(String.valueOf(typeName));
            if (type == null) {
                if (plugin.config().debug()) {
                    plugin.getLogger().info("[match] 出生增益的名字无法解析：" + typeName);
                }
                continue;
            }
            int amplifier = raw.get("amplifier") instanceof Number number ? number.intValue() : 0;
            player.addPotionEffect(new org.bukkit.potion.PotionEffect(type, ticks,
                    Math.max(0, amplifier), false, true, true));
        }
    }

    public List<Player> teamPlayers(TeamId team) {
        List<Player> result = new ArrayList<>();
        for (Map.Entry<UUID, TeamId> entry : teams.entrySet()) {
            if (entry.getValue() != team) {
                continue;
            }
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null && player.isOnline()) {
                result.add(player);
            }
        }
        return result;
    }

    /**
     * 随机分队：优先补人少的一队，人数相同则随机。
     * 队伍满员（每队 team-size 人）或对局已开始时返回 null。
     */
    public TeamId joinRandom(Player player) {
        if (player == null) {
            return null;
        }
        TeamId existing = teams.get(player.getUniqueId());
        if (existing != null) {
            return existing;
        }
        // PVE：所有人同一队（不限人数），没有"队伍已满"这回事
        if (isPve()) {
            join(player, TeamId.RED);
            return TeamId.RED;
        }
        int maxPerTeam = Math.max(1, plugin.config().matchTeamSize());
        int red = teamPlayers(TeamId.RED).size();
        int blue = teamPlayers(TeamId.BLUE).size();
        if (red >= maxPerTeam && blue >= maxPerTeam) {
            return null;
        }
        TeamId team;
        if (red < blue) {
            team = TeamId.RED;
        } else if (blue < red) {
            team = TeamId.BLUE;
        } else {
            team = java.util.concurrent.ThreadLocalRandom.current().nextBoolean() ? TeamId.RED : TeamId.BLUE;
        }
        join(player, team);
        return team;
    }

    /** 死亡旁观的观察点：优先看中央刷怪区。 */
    public Location spectatorViewPoint() {
        return plugin.arena().minionRegion() == null ? null : plugin.arena().minionRegion().center();
    }

    public Map<UUID, TeamId> teamsView() {
        return Map.copyOf(teams);
    }

    public void clearTeams() {
        teams.clear();
        playerNames.clear();
    }

    // ---------------------------------------------------------------- 计分

    private void resetScores() {
        teamScores.clear();
        for (TeamId team : TeamId.values()) {
            teamScores.put(team, 0);
        }
        playerScores.clear();
        playerKills.clear();
        minionKills.clear();
        lastScorer = null;
        lastScoreReason = "-";
        lastScoreAmount = 0;
        winner = null;
    }

    public int teamScore(TeamId team) {
        return teamScores.getOrDefault(team, 0);
    }

    public int playerScore(UUID uuid) {
        return playerScores.getOrDefault(uuid, 0);
    }

    public UUID lastScorer() {
        return lastScorer;
    }

    public String lastScoreReason() {
        return lastScoreReason;
    }

    public int lastScoreAmount() {
        return lastScoreAmount;
    }

    /** 给玩家记分（同时计入其队伍）。只在 RUNNING 阶段生效。 */
    public void addScore(Player player, int amount, String reason) {
        if (player == null || amount == 0) {
            return;
        }
        // 观众不计分
        if (plugin.spectator().isSpectator(player)) {
            return;
        }
        TeamId team = teams.get(player.getUniqueId());
        if (team == null) {
            return;
        }
        addScore(team, player.getUniqueId(), player.getName(), amount, reason);
    }

    public void addScore(TeamId team, UUID scorer, String scorerName, int amount, String reason) {
        if (phase != Phase.RUNNING || team == null) {
            return;
        }
        teamScores.merge(team, amount, Integer::sum);
        if (scorer != null) {
            playerScores.merge(scorer, amount, Integer::sum);
            playerNames.put(scorer, scorerName == null ? "?" : scorerName);
            lastScorer = scorer;
            lastScoreReason = reason;
            lastScoreAmount = amount;
        }
        if (plugin.config().debug()) {
            plugin.getLogger().info(String.format("[match] %s +%d（%s）→ 总分 %d:%d",
                    team.display(), amount, reason, teamScore(TeamId.RED), teamScore(TeamId.BLUE)));
        }
        // 实时更新记分板
        plugin.matchBoard().updateAll();
        // 胜负检查
        if (teamScore(team) >= rules().scoreToWin()) {
            finish(team);
        }
    }

    /** 本局得分最高的玩家（用于结算播报）。 */
    public Map.Entry<UUID, Integer> topScorer() {
        return playerScores.entrySet().stream()
                .filter(entry -> entry.getValue() > 0)
                .max(Comparator.comparingInt(Map.Entry::getValue))
                .orElse(null);
    }

    public String nameOf(UUID uuid) {
        if (uuid == null) {
            return "-";
        }
        String name = playerNames.get(uuid);
        if (name != null) {
            return name;
        }
        Player player = Bukkit.getPlayer(uuid);
        return player != null ? player.getName() : uuid.toString().substring(0, 8);
    }

    // ---------------------------------------------------------------- 生命周期

    /** 开始一局；返回失败原因，成功返回 null。 */
    public String start(Player starter) {
        return start(starter, false);
    }

    /**
     * 开始一局。
     *
     * @param starter 触发者（可为 null）
     * @param force   true = 强制开局：允许某一队暂时无人（只要场上至少有 1 名参赛者），
     *                用于人数不够时先开起来测试或救场
     * @return 失败原因；成功返回 null
     */
    public String start(Player starter, boolean force) {
        if (phase == Phase.RUNNING) {
            return "已经有一局在进行中。";
        }
        if (!plugin.arena().isReady()) {
            return "场地未就绪，还缺：" + plugin.arena().missingHint();
        }
        boolean red = !teamPlayers(TeamId.RED).isEmpty();
        boolean blue = !teamPlayers(TeamId.BLUE).isEmpty();
        if (!red && !blue) {
            return isPve()
                    ? "场上一个人都没有：先让玩家点「加入对局」告示牌。"
                    : "场上一个人都没有：先让玩家点「加入对局」告示牌，"
                    + "或用 /taketori team <玩家> <red|blue> 分队。";
        }
        if (!isPve() && (!red || !blue) && !force) {
            return "双方都需要至少一名玩家（人数不够也想开：/taketori match force）。";
        }

        resetScores();
        phase = Phase.RUNNING;
        startedAt = System.currentTimeMillis();
        endedAt = 0L;

        // 开局前最终裁决：每队同一角色只保留权重最高的那个（不同队伍之间不受影响）
        enforceUniqueRoles();

        // 开局时清掉旁观状态，保证参赛者都以生存模式进场
        plugin.spectator().clearAll();
        plugin.minions().start();
        plugin.loot().start();
        if (isPve()) {
            plugin.baseCapture().stop();   // PVE 没有敌方基地要拆
            // PVE：放出保卫据点（不会移动的雪傀儡），月人靠近就会拆它
            String outpostError = plugin.outpost().start(plugin.pveSettings());
            if (outpostError != null) {
                plugin.getLogger().warning("[pve] 据点未能生成：" + outpostError);
                broadcast("<yellow>据点未生成：<gray>" + outpostError);
            }
        } else {
            plugin.baseCapture().start();
            plugin.outpost().stop();
        }

        // 传送到出生点
        for (Map.Entry<UUID, TeamId> entry : teams.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) {
                continue;
            }
            var spawn = plugin.arena().spawn(entry.getValue());
            if (spawn != null) {
                player.teleport(spawn);
            }
            plugin.matchBoard().showTo(player);
            applySpawnBuff(player);   // 开局的出生增益
        }

        broadcast("<gold><bold>对局开始！</bold></gold> <gray>先到 <white>" + rules().scoreToWin()
                + "</white> 分获胜，时限 <white>" + (rules().timeLimitSeconds() / 60) + "</white> 分钟。");
        if (isPve()) {
            broadcast("<aqua>PVE 模式</aqua> <gray>所有人同一队，击杀月人得分：<white>+"
                    + rules().minionKillScore() + "</white> / 只；<gray>玩家之间不会互相造成伤害。");
            broadcast("<gray>死亡 <white>" + rules().respawnDelaySeconds()
                    + "</white> 秒后在出生点复活；本模式没有基地占点。");
        } else {
            broadcast("<gray>击杀月人 <white>+" + rules().minionKillScore()
                    + "</white> / 击杀玩家 <white>+" + rules().playerKillScore()
                    + "</white> / 拆除基地 <white>+" + rules().baseCaptureScore() + "</white>");
            long delay = (long) rules().baseCaptureDelaySeconds();
            if (delay > 0L) {
                broadcast("<gray>基地保护期 <white>" + delay + "</white> 秒，期间无法占点。");
            }
            if (!red || !blue) {
                TeamId empty = red ? TeamId.BLUE : TeamId.RED;
                broadcast("<yellow>强制开局：<white>" + empty.display()
                        + "</white> 暂时无人，之后可以用 <white>/taketori team <玩家> "
                        + empty.key() + "</white> 补人。");
            }
        }
        return null;
    }

    /** 手动结束（管理指令）。 */
    public void stop(String reason) {
        if (phase == Phase.IDLE) {
            return;
        }
        phase = Phase.ENDED;
        endedAt = System.currentTimeMillis();
        plugin.minions().stop();
        plugin.outpost().stop();
        plugin.loot().stop();
        plugin.baseCapture().stop();
        broadcast("<yellow>对局被结束：" + reason);
        endOfMatchCleanup();
    }

    /** 由主类每秒调用：观众提示 + 时限检查。 */
    public void tick() {
        // 观战中的玩家需要定期再看到「退出观战」按钮（聊天栏刷过去就找不到了）
        plugin.spectator().tickReminders();
        if (phase != Phase.RUNNING) {
            return;
        }
        if (remainingMillis() <= 0L) {
            TeamId red = TeamId.RED;
            TeamId blue = TeamId.BLUE;
            int redScore = teamScore(red);
            int blueScore = teamScore(blue);
            if (redScore == blueScore) {
                finish(null);
            } else {
                finish(redScore > blueScore ? red : blue);
            }
        }
    }

    public long remainingMillis() {
        if (phase == Phase.IDLE) {
            return rules().timeLimitMillis();
        }
        long base = phase == Phase.RUNNING ? System.currentTimeMillis() : endedAt;
        return Math.max(0L, rules().timeLimitMillis() - (base - startedAt));
    }

    public String remainingText() {
        long seconds = remainingMillis() / 1000L;
        return String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
    }

    /** 开局后已经过的秒数（基地保护期判定用）。 */
    public long elapsedSeconds() {
        if (phase == Phase.IDLE) {
            return 0L;
        }
        long base = phase == Phase.RUNNING ? System.currentTimeMillis() : endedAt;
        return Math.max(0L, (base - startedAt) / 1000L);
    }

    /** 基地是否已过保护期、可以开始占点。 */
    public boolean isBaseCaptureOpen() {
        return phase == Phase.RUNNING && elapsedSeconds() >= (long) rules().baseCaptureDelaySeconds();
    }

    /** 保护期剩余秒数（0 = 已开放占点）。 */
    public long baseCaptureDelayRemaining() {
        if (phase != Phase.RUNNING) {
            return 0L;
        }
        return Math.max(0L, (long) rules().baseCaptureDelaySeconds() - elapsedSeconds());
    }

    // ---------------------------------------------------------------- 结算

    private void finish(TeamId winnerTeam) {
        if (phase == Phase.ENDED) {
            return;
        }
        phase = Phase.ENDED;
        endedAt = System.currentTimeMillis();
        winner = winnerTeam;
        plugin.minions().stop();
        plugin.outpost().stop();
        plugin.loot().stop();
        plugin.baseCapture().stop();
        plugin.matchBoard().updateAll();

        // 结算播报（需求：获胜玩家信息 + 最后一位得分玩家 + 累计得分最多玩家）
        Map.Entry<UUID, Integer> top = topScorer();
        if (isPve()) {
            // PVE：只有"达成目标分"才算挑战成功，时间到而没达标不叫成功
            boolean cleared = teamScore(TeamId.RED) >= rules().scoreToWin();
            broadcast("<dark_gray>========================================");
            broadcast(cleared
                    ? "<green><bold>挑战成功！</bold></green> <gray>总分 <white>" + teamScore(TeamId.RED)
                    + "</white> / " + rules().scoreToWin()
                    : "<yellow><bold>时间到</bold></yellow> <gray>未能达成目标：总分 <white>"
                    + teamScore(TeamId.RED) + "</white> / " + rules().scoreToWin());
            broadcast("<gray>参与成员：<white>" + memberNames(TeamId.RED));
            broadcast("<gray>最后得分：<white>" + nameOf(lastScorer) + " <gray>(+"
                    + lastScoreAmount + " " + lastScoreReason + ")");
            broadcast(top == null
                    ? "<gray>本局得分王：<white>无"
                    : "<gray>本局得分王：<white>" + nameOf(top.getKey())
                    + " <gray>(<white>" + top.getValue() + " 分<gray>)");
            broadcast("<dark_gray>========================================");
        } else {
            String resultLine = winnerTeam == null
                    ? "<yellow>平局！"
                    : winnerTeam.colorTag() + "<bold>" + winnerTeam.display() + "</bold></color> <green>获胜！";
            broadcast("<dark_gray>========================================");
            broadcast(resultLine + " <gray>比分 " + TeamId.RED.colorTag() + teamScore(TeamId.RED)
                    + " <gray>: " + TeamId.BLUE.colorTag() + teamScore(TeamId.BLUE));
            broadcast("<gray>获胜队伍成员：<white>" + memberNames(winnerTeam));
            broadcast("<gray>最后得分：<white>" + nameOf(lastScorer) + " <gray>(+"
                    + lastScoreAmount + " " + lastScoreReason + ")");
            broadcast(top == null
                    ? "<gray>本局得分王：<white>无"
                    : "<gray>本局得分王：<white>" + nameOf(top.getKey()) + " <gray>(<white>" + top.getValue() + " 分<gray>)");
            broadcast("<dark_gray>========================================");
        }

        plugin.stats().recordMatch(winnerTeam, teamScores, playerScores, playerNames);
        if (winnerTeam != null) {
            List<String> winners = new ArrayList<>();
            for (Map.Entry<UUID, TeamId> entry : teams.entrySet()) {
                if (entry.getValue() == winnerTeam) {
                    winners.add(nameOf(entry.getKey()));
                }
            }
            plugin.stats().recordWin(winners);
        }
        endOfMatchCleanup();
    }

    /** 结算后的收尾：清旁观、清队列、可选把参赛者送回大厅并解除队伍。 */
    private void endOfMatchCleanup() {
        plugin.spectator().clearAll();
        plugin.lobby().clearQueue();
        if (plugin.config().lobbyReturnAfterMatch()) {
            for (UUID uuid : new ArrayList<>(teams.keySet())) {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null && player.isOnline()) {
                    plugin.lobby().sendToLobby(player);
                }
            }
        }
        clearTeams();
        plugin.matchBoard().updateAll();
    }

    private String memberNames(TeamId team) {
        if (team == null) {
            return "无";
        }
        List<Player> players = teamPlayers(team);
        if (players.isEmpty()) {
            return "（已离线）";
        }
        StringBuilder builder = new StringBuilder();
        for (Player player : players) {
            if (!builder.isEmpty()) {
                builder.append("、");
            }
            builder.append(player.getName());
        }
        return builder.toString();
    }

    public TeamId winner() {
        return winner;
    }

    /**
     * 本局涉及的世界集合（出生点 / 基地 / 月人刷新区 / PVE 据点）。
     *
     * <p>用途：把对局播报限制在"对局世界"内。多世界服务器上，别的世界的玩家
     * （比如在其它玩法世界里的人）不该被本对局的播报刷屏。</p>
     */
    public Set<String> matchWorlds() {
        Set<String> worlds = new LinkedHashSet<>();
        for (TeamId team : TeamId.values()) {
            Location spawn = plugin.arena().spawn(team);
            if (spawn != null && spawn.getWorld() != null) {
                worlds.add(spawn.getWorld().getName());
            }
            for (CuboidRegion region : plugin.arena().bases(team).values()) {
                if (region != null && region.worldName() != null) {
                    worlds.add(region.worldName());
                }
            }
        }
        for (CuboidRegion region : plugin.arena().minionRegions().values()) {
            if (region != null && region.worldName() != null) {
                worlds.add(region.worldName());
            }
        }
        Location outpost = plugin.arena().outpost();
        if (outpost != null && outpost.getWorld() != null) {
            worlds.add(outpost.getWorld().getName());
        }
        return worlds;
    }

    /**
     * 对局播报。
     *
     * <p>默认只发给<b>对局世界</b>里的玩家（{@code worlds.broadcast-scope: world}）；
     * 配成 {@code all} 时恢复旧的全服广播。若当前还没记录到任何对局世界
     * （场地未就绪、开局前），按全服发送 —— 宁可多播，也别让消息静默消失。</p>
     */
    public void broadcast(String miniMessage) {
        String scope = plugin.config().broadcastScope();
        Component component = MINI.deserialize(miniMessage);
        Set<String> worlds = matchWorlds();
        if ("all".equalsIgnoreCase(scope) || worlds.isEmpty()) {
            if (worlds.isEmpty() && plugin.config().debug()) {
                plugin.getLogger().info("[match] 还没有对局世界信息 → 本条播报按全服发送");
            }
            Bukkit.getServer().sendMessage(component);
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (WorldScope.shouldReceive(scope, worlds, player.getWorld().getName())) {
                player.sendMessage(component);
            }
        }
    }
}
