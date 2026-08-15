package com.pepperminecart.api;

/** 取下回调的处理结果：告知引擎是否继续执行默认取下流程。 */
public enum TakeOffOutcome {
    /** 引擎继续默认流程：还原普通矿车、清理数据、按策略返还物品。 */
    DEFAULT,
    /** 处理器已完整处理取下（含实体替换/物品交付），引擎不再动作。 */
    HANDLED
}
