package com.bizzan.bitrade.dao;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.querydsl.QueryDslPredicateExecutor;
import org.springframework.data.repository.query.Param;

import com.bizzan.bitrade.entity.ExchangeCoin;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

public interface ExchangeCoinRepository extends JpaRepository<ExchangeCoin, String>, JpaSpecificationExecutor<ExchangeCoin>, QueryDslPredicateExecutor<ExchangeCoin> {
    ExchangeCoin findBySymbol(String symbol);

    @Query("select distinct a.baseSymbol from  ExchangeCoin a where a.enable = 1")
    List<String> findBaseSymbol();

    @Query("select distinct a.coinSymbol from  ExchangeCoin a where a.enable = 1 and a.baseSymbol = :baseSymbol")
    List<String> findCoinSymbol(@Param("baseSymbol")String baseSymbol);

    @Query("select distinct a.coinSymbol from  ExchangeCoin a where a.enable = 1")
    List<String> findAllCoinSymbol();

    @Transactional(rollbackFor = Exception.class)
    @Modifying
    @Query("update ExchangeCoin coin set coin.totalBuy=coin.totalBuy+:amount where coin.symbol = :symbol")
    void increaseBuy(@Param("symbol")String symbol, @Param("amount") BigDecimal amount);
}
