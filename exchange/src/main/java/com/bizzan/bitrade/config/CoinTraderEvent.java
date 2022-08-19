package com.bizzan.bitrade.config;

import com.alibaba.fastjson.JSON;
import com.bizzan.bitrade.Trader.CoinTrader;
import com.bizzan.bitrade.Trader.CoinTraderFactory;
import com.bizzan.bitrade.entity.ExchangeOrder;
import com.bizzan.bitrade.entity.ExchangeOrderDetail;
import com.bizzan.bitrade.service.ExchangeOrderDetailService;
import com.bizzan.bitrade.service.ExchangeOrderService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

@Component
public class CoinTraderEvent implements CommandLineRunner {
    private Logger log = LoggerFactory.getLogger(CoinTraderEvent.class);
    @Autowired
    CoinTraderFactory coinTraderFactory;
    @Autowired
    private ExchangeOrderService exchangeOrderService;
    @Autowired
    private ExchangeOrderDetailService exchangeOrderDetailService;
    @Autowired
    private KafkaTemplate<String,String> kafkaTemplate;
    @Autowired
    Executor executor;

    @Override
    public void run(String... strings) throws Exception {
        executor.execute(()->{
            deal();
        });
    }


    private void deal(){
        Map<String,CoinTrader> traders = coinTraderFactory.getTraderMap();
        traders.forEach((symbol,trader) ->{
            log.info("======CoinTrader Process: " + symbol + "======");
            List<ExchangeOrder> orders = exchangeOrderService.findAllTradingOrderBySymbol(symbol);
            log.info("Initialize: find all trading orders, total count( " + orders.size() + ")");
            List<ExchangeOrder> tradingOrders = new ArrayList<>();
            List<ExchangeOrder> completedOrders = new ArrayList<>();
            orders.forEach(order -> {
                BigDecimal tradedAmount = BigDecimal.ZERO;
                BigDecimal turnover = BigDecimal.ZERO;
                List<ExchangeOrderDetail> details = exchangeOrderDetailService.findAllByOrderId(order.getOrderId());

                for(ExchangeOrderDetail trade:details){
                    tradedAmount = tradedAmount.add(trade.getAmount());
                    turnover = turnover.add(trade.getAmount().multiply(trade.getPrice()));
                }
                order.setTradedAmount(tradedAmount);
                order.setTurnover(turnover);
                if(!order.isCompleted()){
                    tradingOrders.add(order);
                }
                else{
                    completedOrders.add(order);
                }

                //间断一会再去查询 mongo
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                }
            });
            log.info("Initialize: tradingOrders total count( " + tradingOrders.size() + ")");
            try {
                trader.trade(tradingOrders);
            } catch (ParseException e) {
                e.printStackTrace();
                log.info("异常：trader.trade(tradingOrders);");
            }
            //判断已完成的订单发送消息通知
            if(completedOrders.size() > 0){
                log.info("Initialize: completedOrders total count( " + tradingOrders.size() + ")");
                kafkaTemplate.send("exchange-order-completed", JSON.toJSONString(completedOrders));
            }
            trader.setReady(true);
        });
    }
}
