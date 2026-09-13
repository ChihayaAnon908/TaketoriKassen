package com.taketori.kassen.paper.command;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.core.match.BaseArgParser;
import com.taketori.kassen.core.match.TeamId;
import com.taketori.kassen.paper.match.ArenaManager;
import com.taketori.kassen.paper.match.CuboidRegion;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

/**
 * 玩法层指令：对局控制、分队、场地划定、历史战绩。
 *
 * <pre>
 * /taketori match start|stop [原因]|status
 * /taketori team &lt;玩家&gt; &lt;red|blue|none&gt;
 * /taketori arena pos1|pos2                     —— 用当前位置设置选区两个角点
 * /taketori arena setminion                     —— 把选区设为小怪刷新区
 * /taketori arena setbase &lt;red|blue&gt; [1-3]    —— 把选区设为某队第 N 个基地（编号可省略）
 * /taketori arena setspawn &lt;red|blue&gt;           —— 用当前位置设置某队出生点
 * /taketori arena list                          —— 查看已配置的场地
 * /taketori stats [数量]                         —— 跨局历史排行榜
 * </pre>
 *
 * <p>队伍与编号都做了宽容解析：<code>setbase 1 red</code>（写反）、<code>setbase red</code>（省略编号）、
 * <code>setbase 红 2</code>（中文队伍）、全角数字都能用；参数确实不对时才报错，并且会回显收到的是什么。</p>
 */
public final class MatchCommand {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final TaketoriPlugin plugin;

    public MatchCommand(TaketoriPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean handle(CommandSender sender, String[] args) {
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "match" -> handleMatch(sender, args);
            case "team" -> handleTeam(sender, args);
            case "arena" -> handleArena(sender, args);
            case "lobby" -> handleLobby(sender, args);
            case "stats" -> handleStats(sender, args);
            default -> send(sender, "<red>未知子命令。");
        }
        return true;
    }

    // ---------------------------------------------------------------- match

    private void handleMatch(CommandSender sender, String[] args) {
        if (!require(sender, "taketori.admin")) {
            return;
        }
        String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "status";
        switch (action) {
            case "start" -> startMatch(sender, args.length > 2 && isForceFlag(args[2]));
            case "force" -> startMatch(sender, true);
            case "stop" -> {
                String reason = args.length > 2 ? String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length)) : "管理员结束";
                plugin.match().stop(reason);
                send(sender, "<yellow>已结束对局。");
            }
            case "status" -> sendStatus(sender);
            case "mode" -> setMode(sender, args);
            default -> send(sender, "<gray>用法：/taketori match start [force] | force | stop | status | mode <pvp|pve>");
        }
    }

    /** /taketori match mode <pvp|pve>：切换对局模式并写回 config.yml。 */
    private void setMode(CommandSender sender, String[] args) {
        if (args.length < 3) {
            send(sender, "<gray>当前模式：<white>" + (plugin.match().isPve() ? "pve" : "pvp")
                    + "</white> <dark_gray>（用法：/taketori match mode <pvp|pve>）");
            return;
        }
        String mode = args[2].toLowerCase(Locale.ROOT);
        if (!"pvp".equals(mode) && !"pve".equals(mode)) {
            send(sender, "<red>模式只能是 pvp 或 pve。");
            return;
        }
        String error = plugin.match().setMode(mode);
        if (error != null) {
            send(sender, "<red>" + error);
            return;
        }
        send(sender, "<green>已切换为 <white>" + mode.toUpperCase(Locale.ROOT)
                + "</white> <gray>模式（已写入 config.yml，下一局生效）。");
        if ("pve".equals(mode)) {
            send(sender, "<gray>PVE：所有人同一队打月人，不占点；友伤保护按 <white>auto</white> 会关闭。");
        } else {
            send(sender, "<gray>PVP：红蓝对抗；友伤保护按 <white>auto</white> 会开启（同队互免伤害）。");
        }
    }

    private boolean isForceFlag(String text) {
        return "force".equalsIgnoreCase(text) || "-f".equalsIgnoreCase(text) || "true".equalsIgnoreCase(text);
    }

    /**
     * 开局。force = true 时先把大厅里的人拉进队列，再无视人数阈值直接开始
     * （单人测试、人数差一个、有人掉线都能用）。
     */
    private void startMatch(CommandSender sender, boolean force) {
        Player starter = sender instanceof Player player ? player : null;
        int pulled = 0;
        if (force) {
            pulled = plugin.lobby().pullLobbyPlayers();
        }
        String error = plugin.match().start(starter, force);
        if (error != null) {
            send(sender, "<red>无法开始：" + error);
            if (!force) {
                send(sender, "<gray>人数不够也想开：<white>/taketori match force</white>"
                        + " <dark_gray>（会把大厅里的人自动分队后开始）");
            }
            return;
        }
        send(sender, "<green>对局已开始。" + (force
                ? " <gray>（强制开局，自动拉入 <white>" + pulled + "</white> 人）"
                : ""));
    }

    private void sendStatus(CommandSender sender) {
        var match = plugin.match();
        send(sender, "<gold>===== 对局状态 =====");
        send(sender, "<gray>阶段：<white>" + match.phase()
                + "<gray>  剩余：<white>" + match.remainingText());
        send(sender, "<gray>模式：<white>" + (match.isPve() ? "PVE（合作打月人）" : "PVP（红蓝对抗）")
                + "<gray>  友伤保护：<white>" + (match.isFriendlyFireProtected() ? "开" : "关"));
        send(sender, "<gray>比分：<red>" + match.teamScore(TeamId.RED)
                + " <gray>: <blue>" + match.teamScore(TeamId.BLUE)
                + " <gray>（目标 <white>" + match.rules().scoreToWin() + "<gray>）");
        send(sender, "<gray>红队：<white>" + names(TeamId.RED));
        send(sender, "<gray>蓝队：<white>" + names(TeamId.BLUE));
        for (TeamId team : TeamId.values()) {
            send(sender, "<gray>" + team.display() + " 基地：<white>已拆 "
                    + plugin.baseCapture().capturedCount(team) + "/" + ArenaManager.BASES_PER_TEAM);
        }
        send(sender, "<gray>场上小怪：<white>" + plugin.minions().aliveCount()
                + " <gray>最后得分：<white>" + match.nameOf(match.lastScorer())
                + " <gray>(+" + match.lastScoreAmount() + " " + match.lastScoreReason() + ")");
        send(sender, "<gray>队列：<white>" + plugin.lobby().queuedCount() + " 人"
                + " <gray>观众：<white>" + plugin.spectator().audienceCount() + " 人"
                + " <dark_gray>（/taketori match force 可无视人数强制开局）");
    }

    /**
     * /taketori leave —— 玩家自助退出（聊天栏的「退出观战」按钮也是执行这条指令）。
     *
     * <ul>
     *   <li>观众 → 退出观战，回到大厅；</li>
     *   <li>排队中 / 已分队但未开局 → 退出队列与队伍；</li>
     *   <li>阵亡旁观 → 不动，等自动复活（否则会把对局流程搞乱）；</li>
     *   <li>对局中的参赛者 → 拒绝，避免队伍人数被悄悄改掉。</li>
     * </ul>
     */
    public void handleLeave(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            send(sender, "<red>该指令需要玩家执行。");
            return;
        }
        if (!player.hasPermission("taketori.play")) {
            send(sender, "<red>你没有 taketori.play 权限。");
            return;
        }
        if (plugin.spectator().leaveAudience(player)) {
            return;   // 已经是观众，退出完成（消息在 leaveAudience 里发）
        }
        if (plugin.spectator().isSpectator(player)) {
            send(sender, "<gray>你正在等待复活（阵亡旁观），稍后会自动回到战场，不需要退出。");
            return;
        }
        if (plugin.match().isRunning() && plugin.match().teamOf(player.getUniqueId()) != null) {
            send(sender, "<red>对局进行中，参赛者不能单独退出。"
                    + "<gray>要结束整局请找管理员执行 <white>/taketori match stop");
            return;
        }
        boolean wasQueued = plugin.lobby().isQueued(player.getUniqueId());
        TeamId team = plugin.match().teamOf(player.getUniqueId());
        if (wasQueued) {
            plugin.lobby().dequeue(player);
        } else if (team != null) {
            plugin.match().leave(player.getUniqueId());
            send(sender, "<yellow>已离开 " + team.display() + "。");
        } else {
            send(sender, "<gray>你现在不在队列或队伍里。");
        }
        if (plugin.lobby().isConfigured()) {
            plugin.lobby().sendToLobby(player);
        }
    }

    private String names(TeamId team) {
        List<Player> players = plugin.match().teamPlayers(team);
        if (players.isEmpty()) {
            return "（空）";
        }
        StringBuilder builder = new StringBuilder();
        players.forEach(player -> builder.append(builder.isEmpty() ? "" : "、").append(player.getName()));
        return builder.toString();
    }

    // ---------------------------------------------------------------- team

    private void handleTeam(CommandSender sender, String[] args) {
        if (!require(sender, "taketori.admin")) {
            return;
        }
        if (args.length < 3) {
            send(sender, "<gray>用法：/taketori team <玩家> <red|blue|none>");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            send(sender, "<red>找不到玩家：" + args[1]);
            return;
        }
        if ("none".equalsIgnoreCase(args[2]) || "leave".equalsIgnoreCase(args[2])) {
            plugin.match().leave(target.getUniqueId());
            send(sender, "<yellow>" + target.getName() + " 已退出队伍。");
            return;
        }
        TeamId team = TeamId.byName(args[2]);
        if (team == null) {
            send(sender, "<red>无效队伍，可选：red / blue / none");
            return;
        }
        plugin.match().join(target, team);
        send(sender, "<green>" + target.getName() + " 加入 " + team.display() + "。");
        send(target, "<gold>你被分到 " + team.display() + " <gray>（记分板已显示双方比分）");
    }

    // ---------------------------------------------------------------- arena

    private void handleArena(CommandSender sender, String[] args) {
        if (!require(sender, "taketori.admin")) {
            return;
        }
        if (!(sender instanceof Player player)) {
            send(sender, "<red>场地划定需要玩家执行（要用到你的位置）。");
            return;
        }
        if (args.length < 2) {
            sendArenaUsage(sender);
            return;
        }
        ArenaManager arena = plugin.arena();
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "pos1" -> {
                arena.setPos1(player.getUniqueId(), player.getLocation());
                send(sender, "<green>角点 1 已设置：" + describe(player.getLocation()));
            }
            case "pos2" -> {
                arena.setPos2(player.getUniqueId(), player.getLocation());
                send(sender, "<green>角点 2 已设置：" + describe(player.getLocation()));
            }
            case "setminion" -> {
                CuboidRegion selection = arena.selection(player.getUniqueId());
                if (selection == null) {
                    send(sender, "<red>选区不完整：<white>" + arena.selectionStatus(player.getUniqueId()));
                    send(sender, "<gray>站在一角 → <white>/taketori arena pos1</white>；走到对角 → <white>/taketori arena pos2</white>；再执行本条指令。");
                    return;
                }
                arena.setMinionRegion(selection);
                arena.save();
                arena.clearSelection(player.getUniqueId());
                send(sender, "<green>小怪刷新区已设置：" + selection.describe());
            }
            case "setbase" -> {
                if (args.length < 3) {
                    send(sender, "<gray>用法：/taketori arena setbase <red|blue> [编号 1-"
                            + ArenaManager.BASES_PER_TEAM + "]");
                    send(sender, "<dark_gray>  省略编号时会自动用该队下一个空位；队伍可写 red / blue，也可写 红 / 蓝。");
                    return;
                }
                String first = args[2];
                String second = args.length >= 4 ? args[3] : null;

                // 宽容解析（纯逻辑在 core/match/BaseArgParser，tools/BaseArgParserTest.java 可离线回归）：
                // 顺序写反、省略编号、中文队伍名、全角数字都能识别
                BaseArgParser.Result parsed = BaseArgParser.parse(first, second);
                TeamId team = parsed.team();
                int index = parsed.index();
                if (team == null) {
                    send(sender, "<red>队伍无效：<white>"
                            + (second == null ? first : first + " " + second)
                            + "</white> <gray>—— 队伍要写 <white>red</white> 或 <white>blue</white>（也接受 红 / 蓝）。");
                    send(sender, "<gray>正确写法：<white>/taketori arena setbase red 1");
                    return;
                }
                if (second != null && index <= 0) {
                    send(sender, "<red>编号无效：<white>" + second + "</white> <gray>—— 编号要写 <white>1~"
                            + ArenaManager.BASES_PER_TEAM + "</white> 的数字（全角数字会自动转换）。");
                    return;
                }
                if (index <= 0) {
                    index = arena.nextFreeBaseIndex(team);
                    if (index <= 0) {
                        send(sender, "<red>" + team.display() + " 的 " + ArenaManager.BASES_PER_TEAM
                                + " 个基地都已配置；要覆盖请写明编号，例如 <white>/taketori arena setbase "
                                + team.key() + " 1");
                        return;
                    }
                    send(sender, "<gray>未指定编号，自动使用 " + team.display() + " 基地 #" + index + "。");
                }
                if (index > ArenaManager.BASES_PER_TEAM) {
                    send(sender, "<red>编号 " + index + " 超出范围：当前每队只启用 <white>"
                            + ArenaManager.BASES_PER_TEAM + "</white> 个基地（可用 1~"
                            + ArenaManager.BASES_PER_TEAM + "）。");
                    return;
                }
                CuboidRegion selection = arena.selection(player.getUniqueId());
                if (selection == null) {
                    send(sender, "<red>选区不完整：<white>" + arena.selectionStatus(player.getUniqueId()));
                    send(sender, "<gray>站在一角 → <white>/taketori arena pos1</white>；走到对角 → <white>/taketori arena pos2</white>；再执行本条指令。");
                    return;
                }
                arena.setBase(team, index, selection);
                arena.save();
                arena.clearSelection(player.getUniqueId());
                send(sender, "<green>已设置 " + team.display() + " 基地 #" + index + "：" + selection.describe());
                send(sender, "<dark_gray>选区已清空，划下一个基地请重新 pos1 + pos2。");
            }
            case "setspawn" -> {
                if (args.length < 3) {
                    send(sender, "<gray>用法：/taketori arena setspawn <red|blue>");
                    return;
                }
                TeamId team = TeamId.byName(args[2]);
                if (team == null) {
                    send(sender, "<red>队伍无效：<white>" + args[2]
                            + "</white> <gray>—— 可选 <white>red</white> / <white>blue</white>（也接受 红 / 蓝）");
                    return;
                }
                arena.setSpawn(team, player.getLocation());
                arena.save();
                send(sender, "<green>已设置 " + team.display() + " 出生点：" + describe(player.getLocation()));
            }
            case "wand" -> {
                plugin.setupWand().wand().give(player);
            }
            case "list" -> {
                send(sender, "<gold>===== 场地配置 =====");
                send(sender, "<gray>小怪区：<white>"
                        + (arena.minionRegion() == null ? "未设置" : arena.minionRegion().describe()));
                for (TeamId team : TeamId.values()) {
                    send(sender, "<gray>" + team.display() + " 出生点：<white>"
                            + (arena.spawn(team) == null ? "未设置" : describe(arena.spawn(team))));
                    for (int i = 1; i <= ArenaManager.BASES_PER_TEAM; i++) {
                        CuboidRegion base = arena.base(team, i);
                        if (base != null) {
                            send(sender, "<dark_gray>  #" + i + " " + base.describe());
                        }
                    }
                }
                send(sender, "<gray>是否可开局：<white>" + (arena.isReady() ? "是" : "否（还缺：" + arena.missingHint() + "）"));
            }
            default -> sendArenaUsage(sender);
        }
    }

    private void sendArenaUsage(CommandSender sender) {
        send(sender, "<yellow>场地指令：");
        send(sender, "<gray>/taketori arena pos1 | pos2  <dark_gray>— 用当前位置设置选区角点");
        send(sender, "<gray>/taketori arena setminion  <dark_gray>— 选区设为小怪刷新区");
        send(sender, "<gray>/taketori arena setbase <red|blue> [编号 1-" + ArenaManager.BASES_PER_TEAM
                + "]  <dark_gray>— 编号可省略（自动用下一个空位）");
        send(sender, "<gray>/taketori arena setspawn <red|blue>");
        send(sender, "<gray>/taketori arena wand  <dark_gray>— 领一把选区锄（左键 = 角点 1，右键 = 角点 2）");
        send(sender, "<gray>/taketori arena list");
    }

    private String describe(org.bukkit.Location location) {
        if (location == null || location.getWorld() == null) {
            return "（世界不存在）";
        }
        return String.format("%s %.0f,%.0f,%.0f",
                location.getWorld().getName(), location.getX(), location.getY(), location.getZ());
    }

    // ---------------------------------------------------------------- lobby

    /**
     * 大厅指令。join / leave / spectate 对普通玩家开放（等价于点告示牌），
     * 其余配置类操作需要 taketori.admin。
     */
    private void handleLobby(CommandSender sender, String[] args) {
        var lobby = plugin.lobby();
        String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "list";

        // 玩家自助操作
        switch (action) {
            case "join" -> {
                if (sender instanceof Player player) {
                    lobby.queue(player);
                } else {
                    send(sender, "<red>该操作需要玩家执行。");
                }
                return;
            }
            case "leave" -> {
                if (sender instanceof Player player) {
                    plugin.lobby().dequeue(player);
                    // 是观众就走观众退出（会清掉观众标记）；否则按普通旁观恢复
                    if (!plugin.spectator().leaveAudience(player)) {
                        plugin.spectator().leave(player, lobby.spawn());
                    }
                } else {
                    send(sender, "<red>该操作需要玩家执行。");
                }
                return;
            }
            case "spectate" -> {
                if (sender instanceof Player player) {
                    plugin.spectator().enterAudience(player);
                } else {
                    send(sender, "<red>该操作需要玩家执行。");
                }
                return;
            }
            default -> {
                // 下面是配置类操作，继续走权限检查
            }
        }

        if (!require(sender, "taketori.admin")) {
            return;
        }
        if (!(sender instanceof Player player)) {
            send(sender, "<red>该操作需要玩家执行（要用到你的位置/视线）。");
            return;
        }

        switch (action) {
            case "setspawn" -> {
                lobby.setSpawn(player.getLocation());
                lobby.save();
                send(sender, "<green>大厅出生点已设置：" + describe(player.getLocation()));
            }
            case "pos1" -> {
                plugin.arena().setPos1(player.getUniqueId(), player.getLocation());
                send(sender, "<green>选区角点 1 已设置：" + describe(player.getLocation()));
            }
            case "pos2" -> {
                plugin.arena().setPos2(player.getUniqueId(), player.getLocation());
                send(sender, "<green>选区角点 2 已设置：" + describe(player.getLocation()));
            }
            case "setregion" -> {
                CuboidRegion selection = plugin.arena().selection(player.getUniqueId());
                if (selection == null) {
                    send(sender, "<red>选区不完整（先 lobby pos1 + pos2）。");
                    return;
                }
                lobby.setRegion(selection);
                lobby.save();
                plugin.arena().clearSelection(player.getUniqueId());
                send(sender, "<green>大厅区域已设置：" + selection.describe());
            }
            case "addsign" -> {
                if (args.length < 3) {
                    send(sender, "<gray>用法：/taketori lobby addsign <join|leave|spectate|character|character:角色id|lobby>");
                    send(sender, "<gray>把准星对准告示牌（6 格内）再执行。");
                    return;
                }
                org.bukkit.block.Block target = player.getTargetBlockExact(6);
                if (target == null) {
                    send(sender, "<red>没有瞄准到方块（请看向要绑定的告示牌，6 格内）。");
                    return;
                }
                String signAction = args[2].toLowerCase(Locale.ROOT);
                var sign = lobby.addSignAndSave(target.getWorld().getName(),
                        target.getX(), target.getY(), target.getZ(), signAction);
                send(sender, "<green>已绑定告示牌：" + sign.describe());
            }
            case "removesign" -> {
                org.bukkit.block.Block target = player.getTargetBlockExact(6);
                if (target == null) {
                    send(sender, "<red>没有瞄准到方块。");
                    return;
                }
                if (lobby.removeSignAt(target)) {
                    lobby.save();
                    send(sender, "<green>已移除该告示牌的绑定。");
                } else {
                    send(sender, "<red>这个方块没有绑定告示牌动作。");
                }
            }
            case "list" -> {
                send(sender, "<gold>===== 大厅配置 =====");
                send(sender, "<gray>出生点：<white>" + (lobby.spawn() == null ? "未设置" : describe(lobby.spawn())));
                send(sender, "<gray>区域：<white>" + (lobby.region() == null ? "未设置（不限定范围）" : lobby.region().describe()));
                send(sender, "<gray>告示牌：<white>" + lobby.signs().size() + " 个");
                lobby.signs().forEach(sign -> send(sender, "<dark_gray>  " + sign.describe()));
                send(sender, "<gray>当前队列：<white>" + lobby.queuedCount() + " 人"
                        + " <gray>（阈值 " + plugin.config().lobbyAutoStartPlayers() + " 自动开局）");
                send(sender, "<gray>是否可用：<white>" + (lobby.isConfigured() ? "是" : "否（先设置出生点）"));
            }
            default -> {
                send(sender, "<yellow>大厅指令：");
                send(sender, "<gray>/taketori lobby setspawn | pos1 | pos2 | setregion");
                send(sender, "<gray>/taketori lobby addsign &lt;动作&gt; | removesign | list");
                send(sender, "<gray>/taketori lobby join | leave | spectate  <dark_gray>— 玩家自助");
            }
        }
    }

    // ---------------------------------------------------------------- stats

    private void handleStats(CommandSender sender, String[] args) {
        int limit = 10;
        if (args.length > 1) {
            try {
                limit = Math.max(1, Math.min(50, Integer.parseInt(args[1])));
            } catch (NumberFormatException ignored) {
                // 用默认值
            }
        }
        List<String> lines = plugin.stats().leaderboard(limit);
        send(sender, "<gold>===== 历史战绩（跨局累计）=====");
        if (lines.isEmpty()) {
            send(sender, "<gray>还没有记录。");
            return;
        }
        lines.forEach(line -> send(sender, "<gray>" + line));
    }

    // ---------------------------------------------------------------- util

    private boolean require(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) {
            return true;
        }
        send(sender, "<red>你没有权限执行该指令。");
        return false;
    }

    private void send(CommandSender sender, String miniMessage) {
        sender.sendMessage(MINI.deserialize(miniMessage));
    }
}
