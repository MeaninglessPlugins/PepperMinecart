package com.pepperminecart.api;

/** 取下策略 / 取下结果。 */
public enum TakeOffResult {
    /** 进玩家背包（放不下掉落地面） */
    INVENTORY,
    /** 掉落到地面 */
    DROP,
    /** 禁止取下 */
    DISABLED
}
