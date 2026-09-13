package com.taketori.kassen.paper.lobby;

import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;

/**
 * 大厅里的交互告示牌：记录坐标与要执行的动作。
 *
 * <p>动作类型（可扩展，这就是"预留接口"的入口）：</p>
 * <ul>
 *   <li>{@code join} —— 加入对局队列（自动随机分队）</li>
 *   <li>{@code leave} —— 退出队列 / 退出对局</li>
 *   <li>{@code spectate} —— 以观众身份旁观当前对局</li>
 *   <li>{@code character} —— 打开角色选择菜单</li>
 *   <li>{@code character:&lt;角色id&gt;} —— 直接选择某个角色</li>
 *   <li>{@code lobby} —— 传送回大厅</li>
 * </ul>
 */
public record LobbySign(String world, int x, int y, int z, String action) {

    public LobbySign(String world, int x, int y, int z, String action) {
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.action = action == null ? "join" : action.toLowerCase(java.util.Locale.ROOT);
    }

    public boolean matches(Block block) {
        return block != null
                && block.getWorld() != null
                && block.getWorld().getName().equals(world)
                && block.getX() == x
                && block.getY() == y
                && block.getZ() == z;
    }

    public String describe() {
        return world + " " + x + "," + y + "," + z + " → " + action;
    }

    public void write(ConfigurationSection section) {
        section.set("world", world);
        section.set("x", x);
        section.set("y", y);
        section.set("z", z);
        section.set("action", action);
    }

    public static LobbySign read(ConfigurationSection section) {
        if (section == null || !section.isString("world") || !section.isString("action")) {
            return null;
        }
        return new LobbySign(
                section.getString("world"),
                section.getInt("x"),
                section.getInt("y"),
                section.getInt("z"),
                section.getString("action"));
    }
}
