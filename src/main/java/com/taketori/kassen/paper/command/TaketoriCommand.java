package com.taketori.kassen.paper.command;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.core.skill.SkillSlot;
import com.taketori.kassen.paper.match.ArenaManager;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * /taketori 命令组。
 *
 * <p>排查用：</p>
 * <ul>
 *   <li>{@code /taketori debug on|off} —— 运行时开关调试日志（本次运行有效）</li>
 *   <li>{@code /taketori doctor} —— 自检：识别、绑定、Q 键行为、名字解析能力</li>
 *   <li>{@code /taketori mode} —— 手动触发当前武器的 Q 槽技能（模式/装备切换），
 *       Q 键万一被别的插件吞掉时用它验证</li>
 *   <li>{@code /taketori qmode <drop|held-slot|none>} —— 运行时切换 Q 键行为，不用改文件</li>
 * </ul>
 */
public final class TaketoriCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUB_COMMANDS = List.of(
            "reload", "give", "character", "debug", "doctor", "mode", "qmode", "skills",
            "match", "team", "arena", "lobby", "stats", "play", "leave", "editor");

    private static final List<String> Q_MODES = List.of("drop", "held-slot", "none");

    private final TaketoriPlugin plugin;

    public TaketoriCommand(TaketoriPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(plugin.config().messages().prefixed("command.unknown-sub"));
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> {
                if (!require(sender, "taketori.admin")) {
                    return true;
                }
                if (plugin.reloadAll(sender)) {
                    sender.sendMessage(plugin.config().messages().prefixed("command.reloaded",
                            "weapons", plugin.config().weapons().size(),
                            "characters", plugin.config().characters().size()));
                }
            }
            case "give" -> handleGive(sender, args);
            case "character" -> handleCharacter(sender, args);
            case "debug" -> handleDebug(sender, args);
            case "doctor" -> handleDoctor(sender);
            case "mode" -> handleMode(sender);
            case "qmode" -> handleQMode(sender, args);
            case "skills" -> handleSkills(sender);
            // 玩法层子命令交给 MatchCommand（同包，无需 import）
            case "match", "team", "arena", "lobby", "stats" -> new MatchCommand(plugin).handle(sender, args);
            // 玩家自助退出：退出观战 / 退出队列（聊天栏的「退出观战」按钮执行的就是它）
            case "leave" -> new MatchCommand(plugin).handleLeave(sender);
            // 武器数据编辑 GUI（管理员）
            case "editor" -> handleEditor(sender, args);
            case "play" -> handlePlay(sender);
            default -> sender.sendMessage(plugin.config().messages().prefixed("command.unknown-sub"));
        }
        return true;
    }

    private void handleGive(CommandSender sender, String[] args) {
        if (!require(sender, "taketori.admin")) {
            return;
        }
        if (args.length < 3) {
            sender.sendMessage(plugin.config().messages().prefixed("command.usage-give"));
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(plugin.config().messages().prefixed("command.player-not-found", "name", args[1]));
            return;
        }
        var weapon = plugin.config().weapons().get(args[2]);
        if (weapon == null) {
            sender.sendMessage(plugin.config().messages().prefixed("command.weapon-not-found", "id", args[2]));
            return;
        }
        target.getInventory().addItem(plugin.items().create(weapon, target));
        sender.sendMessage(plugin.config().messages().prefixed("command.given",
                "player", target.getName(), "weapon", weapon.display()));
    }

    private void handleCharacter(CommandSender sender, String[] args) {
        if (!require(sender, "taketori.admin")) {
            return;
        }
        if (args.length < 3) {
            sender.sendMessage(plugin.config().messages().prefixed("command.usage-character"));
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(plugin.config().messages().prefixed("command.player-not-found", "name", args[1]));
            return;
        }
        String characterId = args[2];
        if ("none".equalsIgnoreCase(characterId)) {
            plugin.bindCharacter(target, null);
            sender.sendMessage(plugin.config().messages().prefixed("command.character-cleared", "player", target.getName()));
            return;
        }
        var character = plugin.config().characters().get(characterId);
        if (character == null) {
            sender.sendMessage(plugin.config().messages().prefixed("command.character-not-found", "id", characterId));
            return;
        }
        plugin.bindCharacter(target, characterId);
        sender.sendMessage(plugin.config().messages().prefixed("command.character-set",
                "player", target.getName(), "character", character.display()));
    }

    /**
     * /taketori debug [on|off]
     * 不带参数 = 查看当前状态；带参数 = 运行时开关（不落盘，重启后回到 config.yml 的值）。
     */
    private void handleDebug(CommandSender sender, String[] args) {
        if (!require(sender, "taketori.admin")) {
            return;
        }
        var messages = plugin.config().messages();
        if (args.length >= 2) {
            String toggle = args[1].toLowerCase(Locale.ROOT);
            if ("on".equals(toggle) || "true".equals(toggle)) {
                plugin.config().setDebug(true);
                sender.sendMessage(messages.prefixed("command.debug-runtime-on"));
                sender.sendMessage(messages.prefixed("command.debug-persist"));
                return;
            }
            if ("off".equals(toggle) || "false".equals(toggle)) {
                plugin.config().setDebug(false);
                sender.sendMessage(messages.prefixed("command.debug-runtime-off"));
                return;
            }
        }
        if (!plugin.config().debug()) {
            sender.sendMessage(messages.prefixed("command.debug-locked"));
            return;
        }
        if (sender instanceof Player player) {
            sender.sendMessage(messages.prefixed("command.debug-header", "info", plugin.describe(player)));
        } else {
            for (Player player : Bukkit.getOnlinePlayers()) {
                sender.sendMessage(messages.prefixed("command.debug-header",
                        "info", player.getName() + " " + plugin.describe(player)));
            }
        }
    }

    /** /taketori doctor：任何人都可以用，普通玩家只看自己那一部分。 */
    private void handleDoctor(CommandSender sender) {
        if (!require(sender, "taketori.play")) {
            return;
        }
        var messages = plugin.config().messages();
        Player player = sender instanceof Player p ? p : null;
        boolean includeProblems = sender.hasPermission("taketori.admin");
        sender.sendMessage(messages.get("command.doctor-header"));
        for (String line : plugin.doctorReport(player, includeProblems)) {
            sender.sendMessage(messages.get("command.doctor-line", "line", line));
        }
        sender.sendMessage(messages.get("command.doctor-footer"));
    }

    /**
     * /taketori mode：手动触发当前手持武器的 Q 槽技能。
     * 用途：Q 键依赖"丢弃物品"事件，若被其他插件吞掉，用这条命令可以照常切换并确认技能本身没问题。
     */
    private void handleMode(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.config().messages().prefixed("command.player-only"));
            return;
        }
        if (!require(sender, "taketori.play")) {
            return;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        var identity = plugin.items().read(hand);
        if (identity == null) {
            sender.sendMessage(plugin.config().messages().prefixed("command.mode-not-weapon"));
            return;
        }
        var weapon = plugin.config().weapons().get(identity.weaponId());
        String mode = plugin.config().characters().profile(player.getUniqueId())
                .mode(identity.weaponId(), weapon == null ? "?" : weapon.defaultMode());
        var result = plugin.skills().dispatch(player, hand, SkillSlot.Q, true);
        sender.sendMessage(plugin.config().messages().prefixed("command.mode-result",
                "result", result.name()));
        sender.sendMessage(plugin.config().messages().prefixed("command.mode-detail",
                "weapon", identity.weaponId(),
                "mode", mode,
                "hasModes", weapon != null && weapon.hasModes()));
    }

    /** /taketori qmode <drop|held-slot|none>：运行时切换 Q 键行为。 */
    private void handleQMode(CommandSender sender, String[] args) {
        if (!require(sender, "taketori.admin")) {
            return;
        }
        var messages = plugin.config().messages();
        if (args.length < 2) {
            sender.sendMessage(messages.prefixed("command.qmode-usage", "current", plugin.config().qMode()));
            return;
        }
        String mode = args[1].toLowerCase(Locale.ROOT);
        if (!Q_MODES.contains(mode)) {
            sender.sendMessage(messages.prefixed("command.qmode-invalid"));
            return;
        }
        plugin.config().setQMode(mode);
        sender.sendMessage(messages.prefixed("command.qmode-set", "mode", mode));
        sender.sendMessage(messages.prefixed("command.qmode-persist"));
    }

    /** /taketori skills：列出当前手持武器的四个技能与冷却状态。 */
    private void handleSkills(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.config().messages().prefixed("command.player-only"));
            return;
        }
        if (!require(sender, "taketori.play")) {
            return;
        }
        var messages = plugin.config().messages();
        ItemStack hand = player.getInventory().getItemInMainHand();
        var identity = plugin.items().read(hand);
        if (identity == null) {
            sender.sendMessage(messages.prefixed("command.skills-empty"));
            return;
        }
        var weapon = plugin.config().weapons().get(identity.weaponId());
        if (weapon == null) {
            sender.sendMessage(messages.prefixed("command.weapon-not-found", "id", identity.weaponId()));
            return;
        }
        String mode = plugin.config().characters().profile(player.getUniqueId())
                .mode(identity.weaponId(), weapon.defaultMode());

        sender.sendMessage(messages.get("command.skills-header"));
        sender.sendMessage(messages.get("command.skills-line",
                "slot", "武器",
                "name", weapon.display(),
                "state", "<gray>模式 <white>" + mode + "</white>"
                        + (weapon.hasModes() ? " <dark_gray>（Q 可切换：" + String.join(" / ", weapon.modes().keySet()) + "）" : "")));

        for (SkillSlot slot : SkillSlot.values()) {
            var def = weapon.skill(slot, mode);
            String name;
            String state;
            if (!def.isPresent()) {
                name = "—";
                state = "<dark_gray>未绑定（走原版行为）";
            } else {
                name = def.str("display", def.type());
                double remaining = plugin.skills().remainingSeconds(player, weapon.id(), slot);
                state = remaining > 0.0D
                        ? "<red>" + plugin.skills().progressBar(remaining, def.cooldownSeconds()) + " "
                                + com.taketori.kassen.paper.skill.SkillManager.formatSeconds(remaining) + "s"
                        : "<green>就绪 <dark_gray>(" + def.type() + ")";
            }
            sender.sendMessage(messages.get("command.skills-line",
                    "slot", slot.key(), "name", name, "state", state));
        }
        sender.sendMessage(messages.get("command.skills-footer"));
    }

    private void handlePlay(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.config().messages().prefixed("command.player-only"));
            return;
        }
        var profile = plugin.config().characters().profile(player.getUniqueId());
        if (!profile.hasCharacter()) {
            sender.sendMessage(plugin.config().messages().prefixed("input.no-character"));
            return;
        }
        var character = plugin.config().characters().get(profile.characterId());
        sender.sendMessage(plugin.config().messages().prefixed("command.play-hint",
                "character", character == null ? profile.characterId() : character.display(),
                "weapons", character == null ? "-" : String.join(", ", character.weapons())));
    }

    private boolean require(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) {
            return true;
        }
        sender.sendMessage(plugin.config().messages().prefixed("command.no-permission"));
        return false;
    }

    /** /taketori editor [武器id] —— 打开武器数据编辑 GUI（改数值 → 写回 weapons.yml → 自动 reload）。 */
    private void handleEditor(CommandSender sender, String[] args) {
        if (!require(sender, "taketori.admin")) {
            return;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                    .deserialize("<red>该指令需要玩家执行（要打开菜单）。"));
            return;
        }
        if (args.length >= 2) {
            plugin.editor().openWeapon(player, args[1].toLowerCase(Locale.ROOT));
        } else {
            plugin.editor().open(player);
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> result = new ArrayList<>();
        if (args.length == 1) {
            for (String option : SUB_COMMANDS) {
                if (option.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    result.add(option);
                }
            }
            return result;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("editor")) {
            for (String id : plugin.config().weapons().ids()) {
                if (id.startsWith(args[1].toLowerCase(Locale.ROOT))) {
                    result.add(id);
                }
            }
            return result;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("debug")) {
            for (String option : List.of("on", "off")) {
                if (option.startsWith(args[1].toLowerCase(Locale.ROOT))) {
                    result.add(option);
                }
            }
            return result;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("qmode")) {
            for (String option : Q_MODES) {
                if (option.startsWith(args[1].toLowerCase(Locale.ROOT))) {
                    result.add(option);
                }
            }
            return result;
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("give") || args[0].equalsIgnoreCase("character"))) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getName().toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))) {
                    result.add(player.getName());
                }
            }
            return result;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            for (String id : plugin.config().weapons().ids()) {
                if (id.startsWith(args[2].toLowerCase(Locale.ROOT))) {
                    result.add(id);
                }
            }
            return result;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("character")) {
            for (String id : plugin.config().characters().ids()) {
                if (id.startsWith(args[2].toLowerCase(Locale.ROOT))) {
                    result.add(id);
                }
            }
            result.add("none");
            return result;
        }
        // 玩法层补全：避免手打错参数（"队伍或编号无效"最常见的来源）
        if (args[0].equalsIgnoreCase("match")) {
            if (args.length == 2) {
                return startsWith(List.of("start", "force", "stop", "status", "mode"), args[1]);
            }
            if (args.length == 3 && args[1].equalsIgnoreCase("start")) {
                return startsWith(List.of("force"), args[2]);
            }
            if (args.length == 3 && args[1].equalsIgnoreCase("mode")) {
                return startsWith(List.of("pvp", "pve"), args[2]);
            }
            return result;
        }
        if (args[0].equalsIgnoreCase("team")) {
            if (args.length == 2) {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (player.getName().toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))) {
                        result.add(player.getName());
                    }
                }
            } else if (args.length == 3) {
                return startsWith(List.of("red", "blue", "none"), args[2]);
            }
            return result;
        }
        if (args[0].equalsIgnoreCase("arena")) {
            if (args.length == 2) {
                return startsWith(List.of("pos1", "pos2", "setminion", "setbase", "setspawn", "wand", "list"), args[1]);
            }
            if (args.length == 3 && (args[1].equalsIgnoreCase("setbase") || args[1].equalsIgnoreCase("setspawn"))) {
                return startsWith(List.of("red", "blue"), args[2]);
            }
            if (args.length == 4 && args[1].equalsIgnoreCase("setbase")) {
                List<String> numbers = new ArrayList<>();
                for (int i = 1; i <= ArenaManager.BASES_PER_TEAM; i++) {
                    numbers.add(Integer.toString(i));
                }
                return startsWith(numbers, args[3]);
            }
            return result;
        }
        if (args[0].equalsIgnoreCase("lobby")) {
            if (args.length == 2) {
                return startsWith(List.of("setspawn", "pos1", "pos2", "setregion", "addsign", "removesign",
                        "list", "join", "leave", "spectate"), args[1]);
            }
            if (args.length == 3 && (args[1].equalsIgnoreCase("addsign") || args[1].equalsIgnoreCase("removesign"))) {
                return startsWith(List.of("join", "leave", "spectate", "character", "lobby"), args[2]);
            }
            return result;
        }
        return result;
    }

    /** 前缀过滤的补全辅助（大小写不敏感）。 */
    private List<String> startsWith(List<String> options, String prefix) {
        String lowered = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(lowered)) {
                result.add(option);
            }
        }
        return result;
    }
}
