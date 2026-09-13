package com.taketori.kassen.paper.match;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 长方体区域：刷怪区、基地范围都用它表示。
 *
 * <p>坐标取两点的最小/最大值，所以用指令划定时不必关心先后顺序。
 * 可序列化进 arena.yml，服务器重启后仍然有效。</p>
 */
public final class CuboidRegion {

    private final String worldName;
    private final double minX;
    private final double minY;
    private final double minZ;
    private final double maxX;
    private final double maxY;
    private final double maxZ;

    public CuboidRegion(String worldName,
                        double minX, double minY, double minZ,
                        double maxX, double maxY, double maxZ) {
        this.worldName = worldName;
        this.minX = Math.min(minX, maxX);
        this.minY = Math.min(minY, maxY);
        this.minZ = Math.min(minZ, maxZ);
        this.maxX = Math.max(minX, maxX);
        this.maxY = Math.max(minY, maxY);
        this.maxZ = Math.max(minZ, maxZ);
    }

    public static CuboidRegion of(Location a, Location b) {
        if (a == null || b == null || a.getWorld() == null || b.getWorld() == null) {
            return null;
        }
        if (!a.getWorld().getName().equals(b.getWorld().getName())) {
            return null;   // 跨世界选区不合法
        }
        return new CuboidRegion(a.getWorld().getName(),
                a.getX(), a.getY(), a.getZ(),
                b.getX(), b.getY(), b.getZ());
    }

    public String worldName() {
        return worldName;
    }

    public World world() {
        return Bukkit.getWorld(worldName);
    }

    public double minX() {
        return minX;
    }

    public double minY() {
        return minY;
    }

    public double minZ() {
        return minZ;
    }

    public double maxX() {
        return maxX;
    }

    public double maxY() {
        return maxY;
    }

    public double maxZ() {
        return maxZ;
    }

    public boolean contains(Location location) {
        if (location == null || location.getWorld() == null) {
            return false;
        }
        if (!location.getWorld().getName().equals(worldName)) {
            return false;
        }
        double x = location.getX();
        double y = location.getY();
        double z = location.getZ();
        return x >= minX - 0.5D && x <= maxX + 0.5D
                && y >= minY - 0.5D && y <= maxY + 1.5D
                && z >= minZ - 0.5D && z <= maxZ + 0.5D;
    }

    /** 区域内随机一点（用于生成小怪）。 */
    public Location randomLocation() {
        World world = world();
        if (world == null) {
            return null;
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        return new Location(world,
                random.nextDouble(minX, maxX + 0.999D) + 0.5D,
                minY + 1.0D,
                random.nextDouble(minZ, maxZ + 0.999D) + 0.5D);
    }

    /** 区域中心（用于复活点、提示）。 */
    public Location center() {
        World world = world();
        if (world == null) {
            return null;
        }
        return new Location(world, (minX + maxX) / 2.0D + 0.5D, minY + 1.0D, (minZ + maxZ) / 2.0D + 0.5D);
    }

    public double volume() {
        return (maxX - minX + 1.0D) * (maxY - minY + 1.0D) * (maxZ - minZ + 1.0D);
    }

    public String describe() {
        return String.format("%s [%.0f,%.0f,%.0f → %.0f,%.0f,%.0f]",
                worldName, minX, minY, minZ, maxX, maxY, maxZ);
    }

    public void write(ConfigurationSection section) {
        section.set("world", worldName);
        section.set("min.x", minX);
        section.set("min.y", minY);
        section.set("min.z", minZ);
        section.set("max.x", maxX);
        section.set("max.y", maxY);
        section.set("max.z", maxZ);
    }

    public static CuboidRegion read(ConfigurationSection section) {
        if (section == null || !section.isString("world")) {
            return null;
        }
        return new CuboidRegion(
                section.getString("world"),
                section.getDouble("min.x"), section.getDouble("min.y"), section.getDouble("min.z"),
                section.getDouble("max.x"), section.getDouble("max.y"), section.getDouble("max.z"));
    }
}
