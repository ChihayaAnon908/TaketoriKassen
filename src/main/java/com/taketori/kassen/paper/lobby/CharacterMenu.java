package com.taketori.kassen.paper.lobby;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.core.character.CharacterDef;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

/**
 * 角色选择菜单：点大厅里的「选择角色」告示牌打开，选完立即绑定角色并发放武器。
 *
 * <p>菜单用箱子界面呈现，图标取该角色第一把武器的原版载体，角色 id 写在物品 PDC 上
 * （不依赖显示名，玩家改名也不影响识别）。</p>
 *
 * <p>角色数量变化时菜单会自动适配尺寸，不需要改代码。</p>
 */
public final class CharacterMenu implements Listener {

    /** 用于识别"这是我们开的菜单"，比匹配标题文本可靠。 */
    private static final class MenuHolder implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final TaketoriPlugin plugin;
    private final NamespacedKey characterKey;

    public CharacterMenu(TaketoriPlugin plugin) {
        this.plugin = plugin;
        this.characterKey = new NamespacedKey(plugin, "menu_character_id");
    }

    public void open(Player player) {
        List<CharacterDef> definitions = new ArrayList<>(plugin.config().characters().all());
        if (definitions.isEmpty()) {
            player.sendMessage(MINI.deserialize("<red>还没有配置任何角色。"));
            return;
        }
        int rows = Math.min(6, Math.max(1, (definitions.size() + 8) / 9));
        Inventory inventory = Bukkit.createInventory(new MenuHolder(), rows * 9,
                MINI.deserialize("<gold>选择角色</gold> <dark_gray>（点击立即生效）"));
        for (CharacterDef def : definitions) {
            inventory.addItem(icon(def));
        }
        player.openInventory(inventory);
    }

    private ItemStack icon(CharacterDef def) {
        Material material = Material.NETHER_STAR;
        if (!def.weapons().isEmpty()) {
            var weapon = plugin.config().weapons().get(def.weapons().get(0));
            if (weapon != null) {
                Material parsed = Material.matchMaterial(weapon.materialName());
                if (parsed != null && !parsed.isAir()) {
                    material = parsed;
                }
            }
        }
        ItemStack stack = new ItemStack(material);
        stack.editMeta(meta -> {
            meta.displayName(MINI.deserialize(def.display()));
            List<Component> lore = new ArrayList<>();
            lore.add(MINI.deserialize("<gray>武器：<white>" + String.join("、", def.weapons())));
            def.lore().forEach(line -> lore.add(MINI.deserialize(line)));
            lore.add(Component.empty());
            lore.add(MINI.deserialize("<yellow>▶ 点击选择该角色"));
            meta.lore(lore);
            meta.getPersistentDataContainer().set(characterKey, PersistentDataType.STRING, def.id());
        });
        return stack;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof MenuHolder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType().isAir() || !clicked.hasItemMeta()) {
            return;
        }
        String characterId = clicked.getItemMeta().getPersistentDataContainer()
                .get(characterKey, PersistentDataType.STRING);
        if (characterId == null) {
            return;
        }
        player.closeInventory();
        var character = plugin.config().characters().get(characterId);
        if (character == null) {
            player.sendMessage(MINI.deserialize("<red>该角色已不存在：" + characterId));
            return;
        }
        // 同队不允许出现相同角色：冲突时按隐性标签权重裁决（权重高者优先）
        var decision = plugin.match().requestRole(player, characterId);
        if (!decision.granted()) {
            player.sendMessage(MINI.deserialize("<red>无法选择该角色：" + decision.reason()));
            player.sendMessage(MINI.deserialize("<gray>可以换一个角色，或让管理员调高你的隐性标签权重。"));
            return;
        }
        plugin.bindCharacter(player, characterId);
        player.sendMessage(MINI.deserialize("<green>已选择角色 " + character.display()
                + "<gray>，武器已发放到快捷栏。"));
        if (decision.displacedSomeone()) {
            player.sendMessage(MINI.deserialize("<yellow>你的权重更高，已接管该角色（"
                    + decision.displaced() + " 的角色被解除）。"));
        }
    }
}
