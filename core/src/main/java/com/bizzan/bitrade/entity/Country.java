package com.bizzan.bitrade.entity;

import lombok.Data;

import javax.persistence.Entity;
import javax.persistence.Id;

/**
 * 国家
 *
 * @author Jammy
 * @date 2019年02月10日
 */
@Data
@Entity
public class Country {
    /**
     * 中文名称
     */
    @Id
    private String zhName;

    /**
     * 英文名称
     */
    private String enName;

    /**
     * 区号
     */
    private String areaCode;
    /**
     * 语言
     */
    private String language;

    /**
     * 当地货币缩写
     */
    private String localCurrency;

    private int sort;

    /**
     * 是否支持注册（主要用在注册页面）
     * 0：不支持
     * 1：支持
     */
    private int isSupportReg = 0;
}
