package com.bizzan.bitrade.entity;

/**
 * @description: ExchangeOrderResource
 * @author: MrGao
 * @create: 2019/05/07 15:46
 */
public enum ExchangeRobotType {

    /**
     * 同步模式（如BTC/USDT、ETH/USDT这种行情依赖于外部的）
     */
    SYNC,
    /**
     * 自定义模式（如平台币这种平台独有的币种）
     */
    CUSTOM,
    /**
     * 其他（）
     */
    OTHER;
}
