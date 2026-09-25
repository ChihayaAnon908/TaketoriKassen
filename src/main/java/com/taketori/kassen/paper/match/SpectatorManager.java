package com.taketori.kassen.paper.match;

import com.taketori.kassen.TaketoriPlugin;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 旁观状态管理：两种旁观。
 *
 * <ul>
 *   <li><b>死亡旁观</b>：对局中死亡后立刻切成旁观（而不是停在死亡界面），
 *       在复活倒计时结束前可以继续看战况，时间到了自动恢复生存并传送到己方出生点。</li>
 *   <li><b>观众</b>：不参赛的玩家以旁观身份观看整局，不参与计分、不刷怪不掉落。
 *       进入时会在聊天栏给出<b>可点击的「退出观战」按钮</b>，并每隔一段时间重发一次，
 *       所以玩家不需要记指令也能自己退出来。</li>
 * </ul>
 *
 * <p>离开旁观时统一恢复为生存模式；插件卸载或对局结束会清空所有旁观状态。</p>
 */
public final class SpectatorManager {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /** 聊天栏里的退出按钮：点一下就等于执行 /taketori leave。 */
    private static final String EXIT_BUTTON = "<click:run_command:'/taketori leave'>"
            + "<hover:show_text:'<gray>点击退出观战，回到大厅'>"
            + "<yellow><bold>[ 退出观战 ]</bold></yellow></hover></click>";

    /** 观众提示重发间隔（毫秒）：聊天栏刷过去之后还能再看到按钮。 */
    private static final long REMINDER_MILLIS = 45_000L;

    private final TaketoriPlugin plugin;
    private final Set<UUID> spectators = ConcurrentHashMap.newKeySet();
    /** 主动进入的观众（区别于死亡旁观：只有他们需要"退出观战"按钮）。 */
    private final Set<UUID> audience = ConcurrentHashMap.newKeySet();
    /** 进观众前的位置：没有配置大厅出生点时用来回退。 */
    private final Map<UUID, Location> returnPoints = new ConcurrentHashMap<>();
    private final Map<UUID, Long> nextReminderAt = new ConcurrentHashMap<>();

    public SpectatorManager(TaketoriPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isSpectator(Player player) {
        return player != null && spectators.contains(player.getUniqueId());
    }

    public boolean isSpectator(UUID uuid) {
        return uuid != null && spectators.contains(uuid);
    }

    /** 是否是"主动进入的观众"（死亡旁观不算）。 */
    public boolean isAudience(Player player) {
        return player != null && audience.contains(player.getUniqueId());
    }

    public Set<UUID> spectators() {
        return Set.copyOf(spectators);
    }

    public int audienceCount() {
        return audience.size();
    }

    /** 死亡旁观：切换到旁观模式并看向战场（可选指定观察点）。不会给退出按钮。 */
    public void enterTemporary(Player player, Location viewAt) {
        if (player == null || !player.isOnline()) {
            return;
        }
        spectators.add(player.getUniqueId());
        audience.remove(player.getUniqueId());
        nextReminderAt.remove(player.getUniqueId());
        player.setGameMode(GameMode.SPECTATOR);
        if (viewAt != null) {
            player.teleport(viewAt);
        }
        player.sendActionBar(MINI.deserialize("<gray>你已阵亡，正在旁观…"));
    }

    /** 观众：不参赛的旁观者，带聊天栏退出按钮。 */
    public void enterAudience(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        if (!spectators.contains(player.getUniqueId())) {
            returnPoints.put(player.getUniqueId(), player.getLocation().clone());
        }
        spectators.add(player.getUniqueId());
        audience.add(player.getUniqueId());
        player.setGameMode(GameMode.SPECTATOR);
        Location view = plugin.match().spectatorViewPoint();
        if (view != null) {
            player.teleport(view);
        }
        nextReminderAt.put(player.getUniqueId(), System.currentTimeMillis() + REMINDER_MILLIS);
        sendAudienceHint(player, "<gray>已进入观众视角。");
    }

    /** 每秒调用：给观战中的玩家定期重发一次退出按钮提示。 */
    public void tickReminders() {
        if (audience.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (UUID uuid : audience) {
            Player player = plugin.getServer().getPlayer(uuid);
            if (player == null || !player.isOnline()) {
                audience.remove(uuid);
                nextReminderAt.remove(uuid);
                continue;
            }
            Long next = nextReminderAt.get(uuid);
            if (next != null && now < next) {
                continue;
            }
            nextReminderAt.put(uuid, now + REMINDER_MILLIS);
            sendAudienceHint(player, "<gray>你正在观战。");
        }
    }

    /**
     * 退出观众：恢复生存并回到大厅（没有大厅就回到进观众前的位置）。
     *
     * @return true 表示原本确实是观众（已处理）；false 表示他不是观众，调用方自行决定后续
     */
    public boolean leaveAudience(Player player) {
        if (player == null || !player.isOnline()) {
            return false;
        }
        UUID uuid = player.getUniqueId();
        if (!audience.remove(uuid)) {
            return false;
        }
        spectators.remove(uuid);
        nextReminderAt.remove(uuid);
        player.setGameMode(GameMode.SURVIVAL);
        Location target = plugin.lobby().spawn();
        Location fallback = returnPoints.remove(uuid);
        if (target == null) {
            target = fallback;
        }
        if (target != null) {
            player.teleport(target);
        }
        player.setFallDistance(0.0F);
        plugin.lobby().clearTransient(player);
        player.sendMessage(MINI.deserialize("<green>已退出观战，回到大厅。"));
        return true;
    }

    /** 结束旁观：恢复生存模式（可选传送到指定位置）。死亡复活的正常路径。 */
    public void leave(Player player, Location destination) {
        if (player == null || !player.isOnline()) {
            return;
        }
        UUID uuid = player.getUniqueId();
        spectators.remove(uuid);
        audience.remove(uuid);
        nextReminderAt.remove(uuid);
        returnPoints.remove(uuid);
        player.setGameMode(GameMode.SURVIVAL);
        if (destination != null) {
            player.teleport(destination);
        }
        player.setFallDistance(0.0F);
    }

    /** 对局结束时把所有人拉回正常状态。 */
    public void clearAll() {
        for (UUID uuid : Set.copyOf(spectators)) {
            Player player = plugin.getServer().getPlayer(uuid);
            if (player != null && player.isOnline()) {
                player.setGameMode(GameMode.SURVIVAL);
            }
            spectators.remove(uuid);
        }
        audience.clear();
        returnPoints.clear();
        nextReminderAt.clear();
    }

    private void sendAudienceHint(Player player, String prefix) {
        player.sendMessage(MINI.deserialize(prefix + " <gray>想退出就点 " + EXIT_BUTTON
                + " <dark_gray>（也可以输入 /taketori leave）"));
    }
}
