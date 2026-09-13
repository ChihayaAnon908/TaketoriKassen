package com.taketori.kassen.data;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * YAML 实现：data/players.yml，结构为 uuid → characterId。
 *
 * <p>写入采用"内存缓存 + 定期/退出时落盘"，避免每次绑定都做磁盘 IO。</p>
 */
public final class YamlPlayerDataStore implements PlayerDataStore {

    private final Plugin plugin;
    private final File file;
    private final Map<UUID, String> bindings = new ConcurrentHashMap<>();

    public YamlPlayerDataStore(Plugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "data/players.yml");
    }

    @Override
    public void loadAll() {
        bindings.clear();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("players");
        if (root == null) {
            return;
        }
        for (String key : root.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                String characterId = root.getString(key);
                if (characterId != null && !characterId.isBlank()) {
                    bindings.put(uuid, characterId);
                }
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("players.yml 中存在非法 UUID: " + key);
            }
        }
        plugin.getLogger().info("已载入 " + bindings.size() + " 条角色绑定记录");
    }

    @Override
    public String characterIdOf(UUID uuid) {
        return uuid == null ? null : bindings.get(uuid);
    }

    @Override
    public void setCharacterId(UUID uuid, String characterId) {
        if (uuid == null) {
            return;
        }
        if (characterId == null || characterId.isBlank()) {
            bindings.remove(uuid);
        } else {
            bindings.put(uuid, characterId);
        }
    }

    @Override
    public void remove(UUID uuid) {
        if (uuid != null) {
            bindings.remove(uuid);
        }
    }

    @Override
    public Map<UUID, String> snapshot() {
        return new LinkedHashMap<>(bindings);
    }

    @Override
    public void saveAll() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, String> entry : bindings.entrySet()) {
            yaml.set("players." + entry.getKey(), entry.getValue());
        }
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                plugin.getLogger().warning("无法创建数据目录: " + parent);
            }
            yaml.save(file);
        } catch (Exception ex) {
            plugin.getLogger().log(Level.SEVERE, "保存 players.yml 失败", ex);
        }
    }
}
