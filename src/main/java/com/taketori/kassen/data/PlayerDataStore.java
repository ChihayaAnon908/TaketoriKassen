package com.taketori.kassen.data;

import java.util.Map;
import java.util.UUID;

/**
 * 玩家数据存储接口。
 *
 * <p>本期只需要保存"玩家 ↔ 角色"的绑定（战斗层无对局数据），
 * 所以默认实现是 YAML；第二期加入对局统计后再换 SQLite 只需替换实现。</p>
 */
public interface PlayerDataStore {

    void loadAll();

    String characterIdOf(UUID uuid);

    void setCharacterId(UUID uuid, String characterId);

    void remove(UUID uuid);

    void saveAll();

    Map<UUID, String> snapshot();
}
