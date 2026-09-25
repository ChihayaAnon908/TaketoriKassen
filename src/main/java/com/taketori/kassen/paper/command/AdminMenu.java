package com.taketori.kassen.paper.command;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.core.lobby.LobbyAction;
import com.taketori.kassen.core.match.TeamId;
import com.taketori.kassen.paper.match.ArenaManager;
import com.taketori.kassen.paper.match.CuboidRegion;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理员菜单：把常用管理指令映射成按钮（对局控制 / 场地 / 大厅 / 维护），外加一个"删除已划区域"子页。
 *
 * <p>两类按钮：</p>
 * <ul>
 *   <li><b>直接执行</b>：无参数指令（start / force / stop / status / mode / list / reload / doctor / debug），
 *       点击后按管理员身份执行，等价于自己手敲；</li>
 *   <li><b>提示补参</b>：需要参数的指令（分基地、绑告示牌…）点击后给一条可点击的建议指令
 *       （<code>suggest_command</code>），补完参数回车即可。</li>
 * </ul>
 *
 * <p><b>删除页</b>（{@code Page.DELETE}）用"格子 → 目标"映射表记录每个按钮删的是什么，
 * 而不是用坐标反推编号 —— 基地编号允许跳号（例如只配了 #1 与 #5），反推会删错东西。</p>
 *
 * <p>打开方式：<code>/taketori admin</code>（需要 taketori.admin 权限），或在玩家菜单里点「管理员菜单」。</p>
 */
public final class AdminMenu implements Listener {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /** 菜单页码。 */
    private enum Page {
        MAIN,
        DELETE
    }

    /** 删除页一个格子对应要删的东西。 */
    private record DeleteTarget(String kind, TeamId team, int index) {
    }

    /** 菜单持有者：避免用标题匹配识别界面，并记住当前是哪一页与删除页的目标映射。 */
    private static final class Holder implements InventoryHolder {

        private final Page page;
        private final Map<Integer, DeleteTarget> targets = new HashMap<>();
        private Inventory inventory;

        Holder(Page page) {
            this.page = page;
        }

        void bind(Inventory inventory) {
            this.inventory = inventory;
        }

        Map<Integer, DeleteTarget> targets() {
            return targets;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final TaketoriPlugin plugin;

    public AdminMenu(TaketoriPlugin plugin) {
        this.plugin = plugin;
    }

    // ---------------------------------------------------------------- 主菜单

    public void open(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        if (!player.hasPermission("taketori.admin")) {
            player.sendMessage(MINI.deserialize("<red>需要 taketori.admin 权限。"));
            return;
        }
        Holder holder = new Holder(Page.MAIN);
        Inventory inventory = Bukkit.createInventory(holder, 54,
                MINI.deserialize("<dark_red>竹取合战 <dark_gray>· <white>管理员菜单"));
        holder.bind(inventory);

        inventory.setItem(4, button(Material.NETHER_STAR, "<gold>管理员菜单",
                "<gray>点按钮 = 执行对应指令",
                "<gray>需要参数的会给出可点击的建议指令",
                "<dark_gray>对局：<white>" + plugin.match().phase()
                        + " <dark_gray>模式：<white>" + (plugin.match().isPve() ? "PVE" : "PVP")));

        // ---- 对局控制 ----
        inventory.setItem(10, run(Material.LIME_DYE, "<green>开始对局",
                "<dark_gray>/taketori match start", "taketori match start"));
        inventory.setItem(11, run(Material.LIME_CONCRETE, "<green>强制开局（人数不够也开）",
                "<dark_gray>/taketori match force（会把大厅里的人自动分队）", "taketori match force"));
        inventory.setItem(12, run(Material.RED_CONCRETE, "<red><bold>强制结束对局",
                "<dark_gray>/taketori match stop",
                "<gray>立刻结算当前对局并把所有人恢复为生存", "taketori match stop 管理员通过菜单结束"));
        inventory.setItem(13, run(Material.BOOK, "<white>对局状态",
                "<dark_gray>/taketori match status", "taketori match status"));
        inventory.setItem(14, run(Material.IRON_SWORD, "<aqua>切换到 PVE",
                "<dark_gray>/taketori match mode pve", "taketori match mode pve"));
        inventory.setItem(15, run(Material.SHIELD, "<red>切换到 PVP",
                "<dark_gray>/taketori match mode pvp", "taketori match mode pvp"));

        // ---- 场地 ----
        inventory.setItem(19, run(Material.NETHERITE_HOE, "<yellow>领选区锄",
                "<dark_gray>/taketori arena wand", "taketori arena wand"));
        inventory.setItem(20, run(Material.MAP, "<white>场地列表",
                "<dark_gray>/taketori arena list", "taketori arena list"));
        inventory.setItem(21, suggest(Material.RED_BANNER, "<white>划基地 / 出生点",
                "<dark_gray>点一下填入指令，补完坐标参数回车",
                "<gray>例如 /taketori arena setbase red 1",
                "/taketori arena setbase red 1"));
        inventory.setItem(22, suggest(Material.ROTTEN_FLESH, "<white>划月人刷新区",
                "<gray>先用锄头点两个角，再执行",
                "/taketori arena setminion"));
        inventory.setItem(23, suggest(Material.CHEST, "<white>划道具刷新点",
                "<dark_gray>先用「道具点工具」点两个角，再执行",
                "/taketori arena setloot 1"));
        inventory.setItem(24, run(Material.NAME_TAG, "<light_purple>隐性标签",
                "<dark_gray>给玩家设置权重标签（同队角色冲突时高者优先）",
                "<gray>权重在 config.yml 的 tags 段配置",
                "taketori tags"));
        inventory.setItem(25, button(Material.TNT, "<red>删除已划区域",
                "<gray>基地 / 月人刷新区 / 道具点 / PVE 据点",
                "<gray>点进子页后<white>点条目即删除</white>",
                "<dark_gray>等价命令：/taketori arena delbase | delminion | delloot | deloutpost"));
        inventory.setItem(26, run(Material.GOLDEN_HOE, "<white>清空我的选区",
                "<dark_gray>清掉 pos1 / pos2，重新划",
                "taketori arena clearselection"));

        // ---- 大厅与维护 ----
        inventory.setItem(28, run(Material.OAK_SIGN, "<white>大厅配置",
                "<dark_gray>/taketori lobby list", "taketori lobby list"));
        inventory.setItem(29, suggest(Material.BIRCH_SIGN, "<white>绑定告示牌",
                "<gray>准星对准牌子后执行；动作：",
                "<gray>" + LobbyAction.keys(),
                "/taketori lobby addsign join"));
        inventory.setItem(30, run(Material.ANVIL, "<yellow>武器数据编辑",
                "<dark_gray>/taketori editor", "taketori editor"));
        inventory.setItem(31, run(Material.COMMAND_BLOCK, "<white>热重载配置",
                "<dark_gray>/taketori reload", "taketori reload"));
        inventory.setItem(32, run(Material.KNOWLEDGE_BOOK, "<white>自检",
                "<dark_gray>/taketori doctor（识别链路与名字解析）", "taketori doctor"));
        inventory.setItem(33, run(Material.GLOWSTONE_DUST, "<white>切换调试日志",
                "<dark_gray>/taketori debug on|off",
                "taketori debug " + (plugin.config().debug() ? "off" : "on")));

        inventory.setItem(49, button(Material.BARRIER, "<red>关闭", "<gray>点一下关闭菜单"));
        player.openInventory(inventory);
    }

    // ---------------------------------------------------------------- 删除页

    /** 删除页：动态列出已配置的基地 / 刷新区 / 道具点 / 据点，点条目即删除。 */
    public void openDelete(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        if (!player.hasPermission("taketori.admin")) {
            player.sendMessage(MINI.deserialize("<red>需要 taketori.admin 权限。"));
            return;
        }
        Holder holder = new Holder(Page.DELETE);
        Inventory inventory = Bukkit.createInventory(holder, 54,
                MINI.deserialize("<dark_red>删除区域 <dark_gray>· <white>点条目即删除"));
        holder.bind(inventory);

        ArenaManager arena = plugin.arena();
        inventory.setItem(4, button(Material.BARRIER, "<red>删除已划定的区域",
                "<gray>点某一条就<white>立刻删除</white>它（不可撤销）",
                "<gray>删除后 <white>/taketori arena list</white> 立即反映",
                "<dark_gray>基地删多了会导致「无法开局」，补划回来即可"));

        fillBases(inventory, holder, arena, TeamId.RED, 0);
        fillBases(inventory, holder, arena, TeamId.BLUE, 9);

        int minionFilled = fillRegions(inventory, holder, arena.minionRegions(), 18,
                "月人刷新区", Material.ROTTEN_FLESH, "minion", "delminion");
        int lootFilled = fillRegions(inventory, holder, arena.lootRegions(), 27,
                "道具刷新点", Material.CHEST, "loot", "delloot");
        if (minionFilled == 0) {
            inventory.setItem(18, button(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "<dark_gray>月人刷新区",
                    "<dark_gray>还没配置"));
        }
        if (lootFilled == 0) {
            inventory.setItem(27, button(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "<dark_gray>道具刷新点",
                    "<dark_gray>还没配置"));
        }

        if (arena.hasOutpost()) {
            int slot = 36;
            holder.targets().put(slot, new DeleteTarget("outpost", null, 0));
            inventory.setItem(slot, button(Material.SNOWBALL, "<white>PVE 据点",
                    "<gray>位置：" + describe(arena.outpost()),
                    "<red>点击删除据点位置",
                    "<dark_gray>删除后 PVE 会回落到第一个月人刷新区中心"));
        } else {
            inventory.setItem(36, button(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "<dark_gray>PVE 据点",
                    "<dark_gray>未设置"));
        }

        int selectionSlot = 38;
        holder.targets().put(selectionSlot, new DeleteTarget("selection", null, 0));
        inventory.setItem(selectionSlot, button(Material.GOLDEN_HOE, "<white>清空我的选区",
                "<gray>当前：" + arena.selectionStatus(player.getUniqueId()),
                "<yellow>点击清空（重新划区域用）"));

        inventory.setItem(49, button(Material.ARROW, "<yellow>返回管理员菜单", "<gray>回到上一页"));
        inventory.setItem(53, button(Material.BARRIER, "<red>关闭", "<gray>点一下关闭菜单"));
        player.openInventory(inventory);
    }

    /** 一行 9 格铺开某队的基地 #1..#9（未配置的用灰玻璃占位，不可点）。 */
    private void fillBases(Inventory inventory, Holder holder, ArenaManager arena, TeamId team, int startSlot) {
        for (int index = 1; index <= 9; index++) {
            int slot = startSlot + index - 1;
            CuboidRegion region = arena.base(team, index);
            if (region == null) {
                inventory.setItem(slot, button(Material.LIGHT_GRAY_STAINED_GLASS_PANE,
                        "<dark_gray>" + team.display() + " 基地 #" + index, "<dark_gray>未配置"));
                continue;
            }
            holder.targets().put(slot, new DeleteTarget("base", team, index));
            inventory.setItem(slot, button(Material.RED_BANNER, "<white>" + team.display() + " 基地 #" + index,
                    "<gray>" + region.describe(),
                    "<red>点击删除",
                    "<dark_gray>等价命令：/taketori arena delbase " + team.key() + " " + index));
        }
    }

    /**
     * 按"实际存在的编号"铺开区域按钮（不是 1..9 顺序猜）。
     *
     * @return 实际铺了几个
     */
    private int fillRegions(Inventory inventory, Holder holder, Map<Integer, CuboidRegion> regions,
                            int startSlot, String label, Material material, String kind, String command) {
        int filled = 0;
        for (Map.Entry<Integer, CuboidRegion> entry : regions.entrySet()) {
            if (filled >= 9) {
                break;   // 一行只有 9 格；超出部分用命令删除
            }
            int slot = startSlot + filled;
            holder.targets().put(slot, new DeleteTarget(kind, null, entry.getKey()));
            inventory.setItem(slot, button(material, "<white>" + label + " #" + entry.getKey(),
                    "<gray>" + entry.getValue().describe(),
                    "<red>点击删除",
                    "<dark_gray>等价命令：/taketori arena " + command + " " + entry.getKey()));
            filled++;
        }
        if (regions.size() > filled) {
            int slot = startSlot + 8;
            inventory.setItem(slot, button(Material.PAPER, "<yellow>还有 " + (regions.size() - filled) + " 个未列出",
                    "<gray>" + label + " 超过 9 个时只显示前 9 个",
                    "<gray>其余请用命令删除：<white>/taketori arena " + command + " <编号>"));
        }
        // 空位补一层黑玻璃，避免看起来像"还能点"
        for (int slot = startSlot + filled; slot < startSlot + 9; slot++) {
            if (inventory.getItem(slot) == null) {
                inventory.setItem(slot, button(Material.BLACK_STAINED_GLASS_PANE, "<dark_gray>—"));
            }
        }
        return filled;
    }

    // ---------------------------------------------------------------- 按钮工厂

    /**
     * 直接执行型按钮：最后一行的文字被当作要执行的指令，其余行进 Lore。
     * 例如 {@code run(Material.BOOK, "对局状态", "<dark_gray>/taketori match status", "taketori match status")}。
     */
    private ItemStack run(Material material, String name, String... lines) {
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> {
            meta.displayName(MINI.deserialize(name));
            List<Component> lore = new ArrayList<>();
            for (int i = 0; i < lines.length - 1; i++) {
                lore.add(MINI.deserialize(lines[i]));
            }
            lore.add(MINI.deserialize("<yellow>▶ 点击执行"));
            meta.lore(lore);
        });
        return item;
    }

    /** 提示补参型按钮（多行说明 + 建议指令）。 */
    private ItemStack suggest(Material material, String name, String... lines) {
        return button(material, name, lines);
    }

    /** 普通按钮：所有行都进 Lore。 */
    private ItemStack button(Material material, String name, String... lines) {
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> {
            meta.displayName(MINI.deserialize(name));
            List<Component> lore = new ArrayList<>();
            for (String line : lines) {
                lore.add(MINI.deserialize(line));
            }
            meta.lore(lore);
        });
        return item;
    }

    // ---------------------------------------------------------------- 点击

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Holder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!player.hasPermission("taketori.admin")) {
            player.closeInventory();
            return;
        }
        int slot = event.getRawSlot();

        if (holder.page == Page.DELETE) {
            if (slot == 49) {
                open(player);
                return;
            }
            if (slot == 53) {
                player.closeInventory();
                return;
            }
            DeleteTarget target = holder.targets().get(slot);
            if (target != null) {
                handleDelete(player, target);
            }
            return;
        }

        switch (slot) {
            case 10 -> execute(player, "taketori match start");
            case 11 -> execute(player, "taketori match force");
            case 12 -> execute(player, "taketori match stop 管理员通过菜单结束");
            case 13 -> execute(player, "taketori match status");
            case 14 -> execute(player, "taketori match mode pve");
            case 15 -> execute(player, "taketori match mode pvp");
            case 19 -> execute(player, "taketori arena wand");
            case 20 -> execute(player, "taketori arena list");
            case 21 -> suggest(player, "/taketori arena setbase red 1");
            case 22 -> suggest(player, "/taketori arena setminion");
            case 23 -> suggest(player, "/taketori arena setloot 1");
            case 24 -> execute(player, "taketori tags");
            case 25 -> openDelete(player);
            case 26 -> execute(player, "taketori arena clearselection");
            case 28 -> execute(player, "taketori lobby list");
            case 29 -> suggest(player, "/taketori lobby addsign join");
            case 30 -> execute(player, "taketori editor");
            case 31 -> execute(player, "taketori reload");
            case 32 -> execute(player, "taketori doctor");
            case 33 -> execute(player, "taketori debug " + (plugin.config().debug() ? "off" : "on"));
            case 49 -> player.closeInventory();
            default -> {
            }
        }
    }

    /** 删除页点击：按映射表执行删除，然后刷新本页（看到结果）。 */
    private void handleDelete(Player player, DeleteTarget target) {
        ArenaManager arena = plugin.arena();
        switch (target.kind()) {
            case "base" -> {
                if (!arena.clearBase(target.team(), target.index())) {
                    player.sendMessage(MINI.deserialize("<red>该基地已经不在了（页面可能过期，已刷新）。"));
                } else {
                    arena.save();
                    player.sendMessage(MINI.deserialize("<green>已删除 " + target.team().display()
                            + " 基地 #" + target.index() + "。 <gray>补划：<white>/taketori arena setbase "
                            + target.team().key() + " " + target.index()));
                }
            }
            case "minion" -> {
                if (arena.clearMinionRegion(target.index())) {
                    arena.save();
                    player.sendMessage(MINI.deserialize("<green>已删除月人刷新区 #" + target.index()
                            + "。 <gray>剩余 <white>" + arena.minionRegionCount() + "</white> 个"));
                }
            }
            case "loot" -> {
                if (arena.clearLootRegion(target.index())) {
                    arena.save();
                    player.sendMessage(MINI.deserialize("<green>已删除道具刷新点 #" + target.index()
                            + "。 <gray>剩余 <white>" + arena.lootRegionCount() + "</white> 个"));
                }
            }
            case "outpost" -> {
                arena.clearOutpost();
                arena.save();
                player.sendMessage(MINI.deserialize("<green>已删除 PVE 据点位置。"
                        + " <gray>PVE 时会回落到第一个月人刷新区的中心"));
            }
            case "selection" -> {
                arena.clearSelection(player.getUniqueId());
                player.sendMessage(MINI.deserialize("<green>已清空你的选区。"));
            }
            default -> {
                return;
            }
        }
        openDelete(player);
    }

    /** 按管理员身份执行一条指令（等价于自己手敲）。 */
    private void execute(Player player, String command) {
        player.closeInventory();
        if (plugin.config().debug()) {
            plugin.getLogger().info("[admin-menu] " + player.getName() + " → /" + command);
        }
        Bukkit.dispatchCommand(player, command);
    }

    /** 给一条可点击的建议指令，补完参数回车即可执行。 */
    private void suggest(Player player, String command) {
        player.closeInventory();
        player.sendMessage(MINI.deserialize("<gray>点这里填入指令：<click:suggest_command:'" + command
                + "'><yellow><u>" + command + "</u></yellow></click> <dark_gray>（补完参数后回车执行）"));
    }

    private String describe(org.bukkit.Location location) {
        if (location == null || location.getWorld() == null) {
            return "（世界不存在）";
        }
        return String.format("%s %.0f,%.0f,%.0f", location.getWorld().getName(),
                location.getX(), location.getY(), location.getZ());
    }
}
