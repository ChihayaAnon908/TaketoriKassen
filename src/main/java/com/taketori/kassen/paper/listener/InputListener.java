package com.taketori.kassen.paper.listener;

import com.taketori.kassen.TaketoriPlugin;
import com.taketori.kassen.core.character.PlayerProfile;
import com.taketori.kassen.core.skill.SkillDef;
import com.taketori.kassen.core.skill.SkillSlot;
import com.taketori.kassen.core.weapon.WeaponDef;
import com.taketori.kassen.paper.item.ItemFactory;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * 统一输入层：把原版动作翻译成技能槽位。
 *
 * <p>核心规则（计划 §3.4 的载体护栏）：</p>
 * <ul>
 *   <li>手里拿着插件武器时，<b>永远不参与方块交互</b>（左键不挖方块）。</li>
 *   <li>某个槽位<b>绑定了技能</b>就取消原版行为并派发技能；<b>没有绑定就放行原版</b>——
 *       于是弓的原版蓄力、盾的原版举盾天然不受影响。</li>
 *   <li>左键的"打实体"与"空挥"是两个事件，用 tick 窗口去重。</li>
 * </ul>
 *
 * <p><b>调试</b>：开启 debug 后这里会打印<b>收到的每一个交互事件</b>，包括
 * "主手不是插件武器 → 忽略"这种被跳过的分支。这样"按了没反应"才能定位到是哪一步断的：
 * 事件没到（[input] 无输出）→ 识别失败（提示不是插件武器）→ 槽位未绑定 → 冷却中。</p>
 */
public final class InputListener implements Listener {

    private final TaketoriPlugin plugin;

    public InputListener(TaketoriPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        // 副手交互不参与派发，避免与"主手武器"判定混淆
        if (event.getHand() != null && event.getHand() != EquipmentSlot.HAND) {
            return;
        }

        Player player = event.getPlayer();
        ItemStack hand = player.getInventory().getItemInMainHand();
        ItemFactory.Identity identity = plugin.items().read(hand);
        Action action = event.getAction();

        if (identity == null) {
            debug(player, action, hand, null, "主手不是插件武器 → 忽略（若你确定拿的是插件武器，说明 PDC 读不到，请用 /taketori doctor）");
            return;
        }

        // 插件武器永不破坏方块
        if (action == Action.LEFT_CLICK_BLOCK) {
            event.setCancelled(true);
            debug(player, action, hand, identity, "插件武器不破坏方块 → 已取消");
            return;
        }

        WeaponDef weapon = plugin.config().weapons().get(identity.weaponId());
        if (weapon == null) {
            debug(player, action, hand, identity, "weapon_id=" + identity.weaponId() + " 在 weapons.yml 里不存在 → 忽略");
            return;
        }
        PlayerProfile profile = plugin.config().characters().profile(player.getUniqueId());
        String mode = profile.mode(weapon.id(), weapon.defaultMode());

        switch (action) {
            case RIGHT_CLICK_AIR, RIGHT_CLICK_BLOCK -> {
                SkillSlot slot = player.isSneaking() ? SkillSlot.SHIFT_RIGHT : SkillSlot.RIGHT;
                SkillDef def = weapon.skill(slot, mode);
                if (!def.isPresent()) {
                    debug(player, action, hand, identity, "槽位 " + slot.key() + " 未绑定技能 → 放行原版行为");
                    return;
                }
                event.setCancelled(true);
                debug(player, action, hand, identity, "派发 " + slot.key() + " → " + def.type());
                plugin.skills().dispatch(player, hand, slot, true);
            }
            case LEFT_CLICK_AIR -> {
                SkillDef def = weapon.skill(SkillSlot.LEFT, mode);
                if (!def.isPresent()) {
                    debug(player, action, hand, identity, "槽位 left 未绑定技能 → 放行原版行为");
                    return;
                }
                // 与"左键命中实体"共享同一次挥击窗口
                if (profile.markSwing(player.getWorld().getGameTime(), weapon.id())) {
                    debug(player, action, hand, identity, "同一 tick 窗口内已由命中事件处理 → 去重跳过");
                    return;
                }
                debug(player, action, hand, identity, "派发 left → " + def.type());
                plugin.skills().dispatch(player, hand, SkillSlot.LEFT, true);
            }
            default -> {
                // LEFT_CLICK_BLOCK 已在上方处理
            }
        }
    }

    /** Q 键：原版是"丢弃物品"，这里统一拦截并按配置转成模式 / 装备切换。 */
    // ignoreCancelled = false：别的插件即使先取消了丢弃事件，Q 键也要照常工作
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onDrop(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        ItemStack dropped = event.getItemDrop().getItemStack();
        ItemFactory.Identity identity = plugin.items().read(dropped);
        if (identity == null) {
            logDrop(player, dropped, null, "丢弃的物品不是插件武器 → 不处理（Q 只在你手持插件武器时才接管）");
            return;
        }

        if (plugin.config().allowDrop()) {
            logDrop(player, dropped, identity, "allow-drop=true → 允许丢弃，不触发 Q 技能");
            return;
        }
        event.setCancelled(true);

        String qMode = plugin.config().qMode();
        logDrop(player, dropped, identity, "Q 键（丢弃已被拦截）q-mode=" + qMode);
        if ("none".equalsIgnoreCase(qMode)) {
            return;
        }
        if ("held-slot".equalsIgnoreCase(qMode)) {
            // 同样延后：此刻主手是空的，切槽扫描会漏掉被丢的那一格
            plugin.scheduler().runLater(() -> switchToNextWeapon(player), 1L);
            return;
        }

        // 关键：PlayerDropItemEvent 触发时物品已经从物品栏移除（取消丢弃后会在本 tick 末恢复），
        // 所以此刻 getItemInMainHand() 已经不是这把武器 —— 直接拿它派发只会得到
        // "主手物品没有 weapon_id"。延后 1 tick 执行，届时主手已恢复；
        // 万一没恢复就退回事件里的物品快照（身份一定是正确的）。
        plugin.scheduler().runLater(() -> {
            if (!player.isOnline()) {
                return;
            }
            ItemStack hand = player.getInventory().getItemInMainHand();
            ItemStack source = plugin.items().read(hand) != null ? hand : dropped;
            plugin.skills().dispatch(player, source, SkillSlot.Q, true);
        }, 1L);
    }

    /**
     * Q 键专用日志：动作名写成 DROP。
     * 复用交互日志会把动作显示成 RIGHT_CLICK_AIR，看起来像是右键触发的，容易误判。
     */
    private void logDrop(Player player, ItemStack stack, ItemFactory.Identity identity, String note) {
        if (!plugin.config().debug()) {
            return;
        }
        plugin.getLogger().info(String.format("[input] %s DROP 手持=%s%s → %s",
                player.getName(),
                stack == null ? "?" : stack.getType(),
                identity == null ? "" : " weapon=" + identity.weaponId(),
                note));
    }

    /** F 键（副手交换）：不允许把武器换到副手，避免出现第二条技能通路。 */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        boolean mainIsWeapon = plugin.items().isPluginWeapon(event.getMainHandItem());
        boolean offIsWeapon = plugin.items().isPluginWeapon(event.getOffHandItem());
        if (mainIsWeapon || offIsWeapon) {
            event.setCancelled(true);
            if (plugin.config().debug()) {
                plugin.getLogger().info("[input] " + event.getPlayer().getName()
                        + " F 键副手交换被取消（主手武器=" + mainIsWeapon + " 副手武器=" + offIsWeapon + "）");
            }
        }
    }

    /** 把选中槽切到下一件本角色武器（q-mode: held-slot）。 */
    private void switchToNextWeapon(Player player) {
        PlayerInventory inventory = player.getInventory();
        int current = inventory.getHeldItemSlot();
        for (int offset = 1; offset <= 9; offset++) {
            int slot = (current + offset) % 9;
            ItemStack stack = inventory.getItem(slot);
            ItemFactory.Identity identity = plugin.items().read(stack);
            if (identity != null && plugin.items().usableBy(stack, player)) {
                inventory.setHeldItemSlot(slot);
                WeaponDef weapon = plugin.config().weapons().get(identity.weaponId());
                if (weapon != null) {
                    plugin.fx().actionBar(player, plugin.config().messages().get("input.equip-switched",
                            "weapon", weapon.display()));
                }
                return;
            }
        }
    }

    private void debug(Player player, Action action, ItemStack hand, ItemFactory.Identity identity, String note) {
        if (!plugin.config().debug()) {
            return;
        }
        plugin.getLogger().info(String.format("[input] %s %s 主手=%s%s → %s",
                player.getName(),
                action,
                hand == null ? "?" : hand.getType(),
                identity == null ? "" : " weapon=" + identity.weaponId(),
                note));
    }
}
