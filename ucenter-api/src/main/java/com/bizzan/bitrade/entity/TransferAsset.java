package com.bizzan.bitrade.entity;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class TransferAsset {
    private String currency;
    private String transferType;
    private String type;
    private BigDecimal fromProfit;
}
