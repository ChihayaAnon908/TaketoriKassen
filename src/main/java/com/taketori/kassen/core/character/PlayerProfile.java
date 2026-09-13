package com.taketori.kassen.core.character;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 玩家运行时档案：角色绑定、每把武器的当前模式、弓的特殊射击开关、挥击去重窗口。
 *
 * <p>这里是 <b>mode 的真源</b>（计划 §3.3）：物品上的 PDC 只作为展示用冗余，
 * 避免每次切换模式都重写 ItemStack 造成物品栏闪烁与脏数据。</p>
 */
public final class PlayerProfile {

    private final UUID uuid;
    private String characterId;
    private final Map<String, String> weaponModes = new HashMap<>();
    private boolean specialShot;
    private long lastSwingTick = Long.MIN_VALUE;
    private String lastSwingWeapon;

    public PlayerProfile(UUID uuid, String characterId) {
        this.uuid = uuid;
        this.characterId = characterId;
    }

    public UUID uuid() {
        return uuid;
    }

    public String characterId() {
        return characterId;
    }

    public void setCharacterId(String characterId) {
        this.characterId = characterId;
    }

    public boolean hasCharacter() {
        return characterId != null && !characterId.isBlank();
    }

    public String mode(String weaponId, String defaultMode) {
        return weaponModes.getOrDefault(weaponId, defaultMode);
    }

    public void setMode(String weaponId, String mode) {
        if (weaponId != null && mode != null) {
            weaponModes.put(weaponId, mode);
        }
    }

    public Map<String, String> allModes() {
        return Map.copyOf(weaponModes);
    }

    public boolean specialShot() {
        return specialShot;
    }

    public boolean toggleSpecialShot() {
        specialShot = !specialShot;
        return specialShot;
    }

    public void setSpecialShot(boolean value) {
        this.specialShot = value;
    }

    /**
     * 左键的"打实体"与"空挥"是两个不同事件（计划 §3.1），用 tick 窗口去重，
     * 避免同一次挥击被结算两次伤害。
     */
    public boolean markSwing(long tick, String weaponId) {
        boolean duplicate = lastSwingWeapon != null
                && lastSwingWeapon.equals(weaponId)
                && tick - lastSwingTick <= 2L;
        lastSwingTick = tick;
        lastSwingWeapon = weaponId;
        return duplicate;
    }

    public void clearCombatState() {
        specialShot = false;
        lastSwingTick = Long.MIN_VALUE;
        lastSwingWeapon = null;
    }
}
