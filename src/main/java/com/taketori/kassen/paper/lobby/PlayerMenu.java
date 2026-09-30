package com.taketori.kassen.paper.lobby;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.core.match.TeamId;
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
import java.util.List;

/**
 * 玩家入口菜单：把"匹配 / 队伍选择 / 角色选择"做成图形入口。
 *
 * <p>原有的告示牌（join / spectate / character …）与指令全部保留，这里是多给一条更直观的路径：
 * 玩家执行 <code>/taketori menu</code>（或点大厅里绑定 <code>menu</code> 动作的告示牌）即可打开。</p>
 *
 * <p>对应关系：</p>
 * <ul>
 *   <li><b>匹配</b> —— 等价于点「加入对局」告示牌（{@code LobbyManager#queue}），再点一次退出队列；</li>
 *   <li><b>队伍选择</b> —— 玩家自助选红队 / 蓝队（等价于管理员执行 {@code /taketori team}）；</li>
 *   <li><b>角色选择</b> —— 打开现成的 {@link CharacterMenu}；</li>
 *   <li><b>排行榜</b> —— 打开总计排行榜。</li>
 * </ul>
 */
public final class PlayerMenu implements Listener {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private enum Page {
        ENTRY,
        TEAM
    }

    /** 菜单持有者：记录当前是哪一页，避免用标题匹配。 */
    private static final class Holder implements InventoryHolder {

        private final Page page;
        private Inventory inventory;

        Holder(Page page) {
            this.page = page;
        }

        void bind(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final TaketoriPlugin plugin;

    public PlayerMenu(TaketoriPlugin plugin) {
        this.plugin = plugin;
    }

    // ---------------------------------------------------------------- 入口页

    public void open(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        Holder holder = new Holder(Page.ENTRY);
        Inventory inventory = Bukkit.createInventory(holder, 27,
                MINI.deserialize("<dark_gray>竹取合战 <dark_gray>· <white>玩家菜单"));
        holder.bind(inventory);

        var joinedRoom = plugin.rooms().roomOf(player);
        boolean inWaitingRoom = joinedRoom != null
                && (joinedRoom.phase() == com.taketori.kassen.paper.match.room.GameRoom.Phase.WAITING
                || joinedRoom.phase() == com.taketori.kassen.paper.match.room.GameRoom.Phase.STARTING);
        boolean inLiveRoom = joinedRoom != null && !inWaitingRoom;
        TeamId team = joinedRoom == null ? null : joinedRoom.teamOf(player.getUniqueId());
        var profile = plugin.config().characters().profile(player.getUniqueId());
        String character = profile.hasCharacter() ? profile.characterId() : "未选择";

        inventory.setItem(10, button(inWaitingRoom ? Material.RED_DYE : Material.COMPASS,
                inWaitingRoom ? "<yellow>离开等待区（回大厅）"
                        : (inLiveRoom ? "<gray>对局进行中" : "<green>快速加入对局"),
                inWaitingRoom
                        ? "<gray>房间 <white>" + joinedRoom.display()
                                + " <gray>等待中 <white>(" + joinedRoom.waitingCount()
                                + "/" + joinedRoom.maxPlayers() + ")"
                        : (inLiveRoom
                                ? "<gray>你已在房间 <white>" + joinedRoom.display()
                                        + " <gray>中，参赛者不能中途退出"
                                : "<gray>自动加入等待人数最多的房间"),
                inWaitingRoom ? "<dark_gray>再点一次即退出房间、返回大厅"
                        : (inLiveRoom ? "<dark_gray>要结束整局请联系管理员"
                                : "<dark_gray>人数达标自动倒计时开局"),
                "<dark_gray>与点「加入对局」告示牌等价"));

        inventory.setItem(12, button(Material.SHIELD, "<white>队伍选择",
                "<gray>当前队伍：<white>" + (team == null ? "未分队" : team.display()),
                joinedRoom != null && joinedRoom.isPve()
                        ? "<dark_gray>该房 PVE 模式下所有人同一队"
                        : "<yellow>点击选择红队 / 蓝队",
                "<dark_gray>开局瞬间按等待名单自动均衡分队"));

        // 告示牌"旁观"与"回大厅"两个动作现在都指向本菜单，对应的按钮就在这里
        boolean audience = plugin.spectator().isAudience(player);
        inventory.setItem(11, button(audience ? Material.ENDER_EYE : Material.GLASS,
                audience ? "<yellow>退出观战" : "<gray>旁观对局",
                audience ? "<gray>你正在以观众身份观战"
                        : "<gray>以观众身份观看当前对局（不计分、不参与战局）",
                audience ? "<yellow>点击退出观战并回到大厅"
                        : "<yellow>点击进入观众模式",
                "<dark_gray>与点「旁观」告示牌 / /taketori leave 等价"));

        inventory.setItem(13, button(Material.ENDER_PEARL, "<white>回大厅",
                "<gray>传送回大厅出生点",
                "<dark_gray>与点「回大厅」告示牌等价"));

        inventory.setItem(14, button(Material.NETHER_STAR, "<white>角色选择",
                "<gray>当前角色：<white>" + character,
                "<yellow>点击打开角色菜单",
                "<dark_gray>选完立即绑定并发放武器"));

        inventory.setItem(15, button(Material.FILLED_MAP, "<white>房间列表",
                "<gray>查看所有房间：模式 / 阶段 / 人数 / 倒计时",
                "<yellow>点击打开列表",
                "<dark_gray>等待房可加入，游戏房可旁观",
                "<dark_gray>与点「房间列表」告示牌等价"));

        inventory.setItem(16, button(Material.GOLD_INGOT, "<white>总计排行榜",
                "<gray>总积分 / 击杀 / 拆家 / 对局数…",
                "<yellow>点击查看（跨局累计）"));

        inventory.setItem(22, button(Material.BARRIER, "<red>关闭", "<gray>点一下关闭菜单"));

        // 管理员额外看到一个入口（普通玩家看不到，点空位也不会执行任何东西）
        if (player.hasPermission("taketori.admin")) {
            inventory.setItem(20, button(Material.COMMAND_BLOCK, "<red>管理员菜单",
                    "<gray>对局控制 / 场地 / 大厅 / 维护",
                    "<gray>含一键<white>强制结束对局</white>",
                    "<yellow>点击打开"));
        }

        player.openInventory(inventory);
    }

    // ---------------------------------------------------------------- 队伍页

    public void openTeam(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        Holder holder = new Holder(Page.TEAM);
        Inventory inventory = Bukkit.createInventory(holder, 27,
                MINI.deserialize("<dark_gray>队伍选择"));
        holder.bind(inventory);

        var joinedRoom = plugin.rooms().roomOf(player);
        TeamId current = joinedRoom == null ? null : joinedRoom.teamOf(player.getUniqueId());
        boolean pve = joinedRoom != null && joinedRoom.isPve();
        int maxPerTeam = Math.max(1, plugin.config().matchTeamSize());

        inventory.setItem(11, teamButton(joinedRoom, TeamId.RED, Material.RED_WOOL, current, pve, maxPerTeam));
        inventory.setItem(15, teamButton(joinedRoom, TeamId.BLUE, Material.BLUE_WOOL, current, pve, maxPerTeam));

        var waitingRoom = plugin.rooms().roomOf(player);
        String roomState = waitingRoom == null ? "未加入"
                : waitingRoom.display() + " 等待中（开局时自动分队）";
        inventory.setItem(13, button(Material.PAPER, "<white>当前状态",
                "<gray>房间：<white>" + roomState,
                "<gray>模式：<white>" + (pve ? "PVE（所有人同队）" : "PVP（红队 vs 蓝队）"),
                "<dark_gray>队伍在开局瞬间按等待名单均衡分配",
                "<dark_gray>每队上限 " + maxPerTeam + " 人"));

        inventory.setItem(22, button(Material.ARROW, "<yellow>返回", "<gray>回到玩家菜单"));
        player.openInventory(inventory);
    }

    private ItemStack teamButton(com.taketori.kassen.paper.match.room.GameRoom room, TeamId team,
                               Material material, TeamId current, boolean pve, int maxPerTeam) {
        int size = room == null ? 0 : room.teamPlayers(team).size();
        boolean mine = current == team;
        boolean full = size >= maxPerTeam && !mine;
        ItemStack item = new ItemStack(pve || full ? Material.GRAY_DYE : material);
        List<Component> lore = new ArrayList<>();
        lore.add(MINI.deserialize("<gray>当前人数：<white>" + size + " / " + maxPerTeam));
        if (pve) {
            lore.add(MINI.deserialize("<dark_gray>PVE 模式下所有人都在同一队"));
            lore.add(MINI.deserialize("<yellow>点击仍然可以加入（会并到同一队）"));
        } else if (mine) {
            lore.add(MINI.deserialize("<green>你已在这个队伍"));
        } else if (full) {
            lore.add(MINI.deserialize("<red>该队已满"));
        } else {
            lore.add(MINI.deserialize("<yellow>点击加入 " + team.display()));
        }
        item.editMeta(meta -> {
            meta.displayName(MINI.deserialize(team.colorTag() + "<bold>" + team.display() + "</bold></color>"
                    + (mine ? " <green>（当前）" : "")));
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
        int slot = event.getRawSlot();
        if (holder.page == Page.ENTRY) {
            switch (slot) {
                case 10 -> toggleQueue(player);
                case 11 -> toggleAudience(player);
                case 12 -> openTeam(player);
                case 13 -> {
                    player.closeInventory();
                    plugin.lobby().sendToLobby(player);
                }
                case 14 -> {
                    player.closeInventory();
                    plugin.characterMenu().open(player);
                }
                case 15 -> {
                    player.closeInventory();
                    plugin.roomListMenu().open(player);
                }
                case 16 -> {
                    player.closeInventory();
                    plugin.statsMenu().open(player);
                }
                case 20 -> {
                    if (player.hasPermission("taketori.admin")) {
                        plugin.adminMenu().open(player);
                    }
                }
                case 22 -> player.closeInventory();
                default -> {
                }
            }
            return;
        }
        switch (slot) {
            case 11 -> choose(player, TeamId.RED);
            case 15 -> choose(player, TeamId.BLUE);
            case 22 -> open(player);
            default -> {
            }
        }
    }

    /** 匹配按钮：无房→快速加入；等待房→退房回大厅；对局中→拒绝（文案走 LobbyManager 统一入口）。 */
    private void toggleQueue(Player player) {
        var room = plugin.rooms().roomOf(player);
        if (room != null && (room.phase() == com.taketori.kassen.paper.match.room.GameRoom.Phase.WAITING
                || room.phase() == com.taketori.kassen.paper.match.room.GameRoom.Phase.STARTING)) {
            player.closeInventory();
            plugin.lobby().returnToLobby(player);
            return;
        }
        if (room != null) {
            player.sendMessage(MINI.deserialize("<red>对局进行中，参赛者不能单独退出。"
                    + "<gray>要结束整局请找管理员执行 <white>/taketori match stop"));
            return;
        }
        player.closeInventory();
        plugin.lobby().quickJoin(player);
    }

    /** 旁观 / 退出观战：自动选一个进行中的房间（与房间列表里选具体房间等价）。 */
    private void toggleAudience(Player player) {
        if (plugin.spectator().isAudience(player)) {
            player.closeInventory();
            plugin.spectator().leaveAudience(player);
            return;
        }
        var live = plugin.rooms().rooms().stream()
                .filter(room -> room.phase() == com.taketori.kassen.paper.match.room.GameRoom.Phase.CAGED
                        || room.phase() == com.taketori.kassen.paper.match.room.GameRoom.Phase.PLAYING)
                .findFirst().orElse(null);
        if (live == null) {
            player.sendMessage(MINI.deserialize("<red>当前没有进行中的对局，无法旁观。"));
            return;
        }
        player.closeInventory();
        plugin.spectator().enterAudience(player, live.spectatorViewPoint(), live);
        player.sendMessage(plugin.config().messages().get("room.spectating", "room", live.display()));
    }

    private void choose(Player player, TeamId team) {
        var room = plugin.rooms().roomOf(player);
        if (room == null) {
            player.sendMessage(MINI.deserialize("<red>你还没加入房间：先点「快速加入对局」或打开「房间列表」。"));
            return;
        }
        String error = room.chooseTeam(player, team);
        if (error != null) {
            player.sendMessage(MINI.deserialize("<red>" + error));
            return;
        }
        openTeam(player);
    }

    private ItemStack button(Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> {
            meta.displayName(MINI.deserialize(name));
            List<Component> lines = new ArrayList<>();
            for (String line : lore) {
                lines.add(MINI.deserialize(line));
            }
            meta.lore(lines);
        });
        return item;
    }
}
