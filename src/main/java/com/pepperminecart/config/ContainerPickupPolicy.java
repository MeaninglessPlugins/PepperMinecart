package com.pepperminecart.config;

import java.util.Locale;

/** 容器类矿车取下策略（木桶/投掷器/通用容器，不含潜影盒）。 */
public enum ContainerPickupPolicy {
    /** 直接取下，内容物随容器物品（NBT）带走 */
    PICKUP,
    /** 内容物全部掉落到地面，容器物品本身按 take-off-mode 处理 */
    DROP,
    /** 禁止取下容器类方块 */
    FORBIDDEN;

    public static ContainerPickupPolicy parse(String value) {
        if (value == null) {
            return null;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
