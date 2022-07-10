package com.bizzan.bitrade.job;

import com.bizzan.bitrade.entity.*;
import com.bizzan.bitrade.util.GeneratorUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.bizzan.bitrade.handler.NettyHandler;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

@Component
public class ExchangePushJob {
    @Autowired
    private SimpMessagingTemplate messagingTemplate;
    @Autowired
    private NettyHandler nettyHandler;
    private Map<String,List<ExchangeTrade>> tradesQueue = new HashMap<>();
    private Map<String,List<TradePlate>> plateQueue = new HashMap<>();
    private Map<String,List<CoinThumb>> thumbQueue = new HashMap<>();

    private Random rand = new Random();
    private HashMap<String, TradePlate> plateLastBuy = new HashMap<>(); // 最后一次推送的盘口，仅仅是为了虚拟推送二设立的
    private HashMap<String, TradePlate> plateLastSell = new HashMap<>(); // 最后一次推送的盘口，仅仅是为了虚拟推送二设立的
    private HashMap<String, TradePlate> plateLastBuyOrigin = new HashMap<>(); // 最后一次推送的盘口，仅仅是为了虚拟推送二设立的
    private HashMap<String, TradePlate> plateLastSellOrigin = new HashMap<>(); // 最后一次推送的盘口，仅仅是为了虚拟推送二设立的
    private BigDecimal lastBuyHeightPrice = BigDecimal.ZERO;
    private BigDecimal lastSellLowPrice = BigDecimal.ZERO;
    private Map<String, CoinThumb> lastPushThumb = new HashMap<>(); // 最后一次推送的Thumb

    public void addTrades(String symbol, List<ExchangeTrade> trades){
        List<ExchangeTrade> list = tradesQueue.get(symbol);
        if(list == null){
            list = new ArrayList<>();
            tradesQueue.put(symbol,list);
        }
        synchronized (list) {
            list.addAll(trades);
        }
    }

    public void addPlates(String symbol, TradePlate plate){
        List<TradePlate> list = plateQueue.get(symbol);
        if(list == null){
            list = new ArrayList<>();
            plateQueue.put(symbol,list);
        }
        synchronized (list) {
            list.add(plate);
        }

        if(plate.getDirection() == ExchangeOrderDirection.BUY) {
            // 更新最新盘口
            synchronized (plateLastBuy) {
                plateLastBuy.put(symbol, plate);
                plateLastBuyOrigin = (HashMap<String, TradePlate>) plateLastBuy.clone();
                lastBuyHeightPrice = plate.getHighestPrice();
            }
        }
        if(plate.getDirection() == ExchangeOrderDirection.SELL) {
            // 更新最新盘口
            synchronized (plateLastSell) {
                plateLastSell.put(symbol, plate);
                plateLastSellOrigin = (HashMap<String, TradePlate>) plateLastSell.clone();
                lastSellLowPrice = plate.getLowestPrice();
            }
        }
    }

    public void addThumb(String symbol, CoinThumb thumb){
        List<CoinThumb> list = thumbQueue.get(symbol);
        if(list == null){
            list = new ArrayList<>();
            thumbQueue.put(symbol,list);
        }
        synchronized (list) {
            list.add(thumb);
        }
    }


    @Scheduled(fixedRate = 800)
    public void pushTrade(){
        Iterator<Map.Entry<String,List<ExchangeTrade>>> entryIterator = tradesQueue.entrySet().iterator();
        while (entryIterator.hasNext()){
            Map.Entry<String,List<ExchangeTrade>> entry =  entryIterator.next();
            String symbol = entry.getKey();
            List<ExchangeTrade> trades = entry.getValue();
            if(trades.size() > 0){
                synchronized (trades) {
                    messagingTemplate.convertAndSend("/topic/market/trade/" + symbol, trades);
                    trades.clear();
                }
            }
        }
    }

    @Scheduled(fixedDelay = 500)
    public void pushPlate(){
        Iterator<Map.Entry<String,List<TradePlate>>> entryIterator = plateQueue.entrySet().iterator();
        while (entryIterator.hasNext()){
            Map.Entry<String,List<TradePlate>> entry =  entryIterator.next();
            String symbol = entry.getKey();
            List<TradePlate> plates = entry.getValue();
            if(plates.size() > 0){
                boolean hasPushAskPlate = false;
                boolean hasPushBidPlate = false;
                synchronized (plates) {
                    for(TradePlate plate:plates) {
                        if(plate.getDirection() == ExchangeOrderDirection.BUY && !hasPushBidPlate) {
                            hasPushBidPlate = true;
                        }
                        else if(plate.getDirection() == ExchangeOrderDirection.SELL && !hasPushAskPlate){
                            hasPushAskPlate = true;
                        }
                        else {
                            continue;
                        }
                        //websocket推送盘口信息
                        messagingTemplate.convertAndSend("/topic/market/trade-plate/" + symbol, plate.toJSON(24));
                        //websocket推送深度信息
                        messagingTemplate.convertAndSend("/topic/market/trade-depth/" + symbol, plate.toJSON(50));
                        //netty推送
                        nettyHandler.handlePlate(symbol, plate);
                    }
                    plates.clear();
                }
            }else{
                // 不管盘口有没有变化，都推送一下数据，显得盘口交易很活跃的样子(这里获取到的盘口有可能是买盘，也可能是卖盘)
                // 50%的概率重置盘口

                TradePlate plateBuy = plateLastBuy.get(symbol);
                TradePlate plateSell = plateLastSell.get(symbol);
                if(rand.nextInt(100) > 50) {
                    if(plateBuy != null) {
                        plateLastBuy = (HashMap<String, TradePlate>) plateLastBuyOrigin.clone();
                        plateBuy = plateLastBuy.get(symbol);
                    }
                }
                if(rand.nextInt(100) > 50) {
                    if(plateSell != null) {
                        plateLastSell = (HashMap<String, TradePlate>) plateLastSellOrigin.clone();
                        plateSell = plateLastSell.get(symbol);
                    }
                }
                // 50%的概率生成交易与行情
                if(rand.nextInt(100) > 50) {
                    // 买卖盘口都不为空，则生成一个虚拟的交易和行情
                    // 获取最后一次Thumb的推送
                    CoinThumb lastThumb = lastPushThumb.get(symbol);
                    if (plateSell != null && plateBuy != null && lastThumb != null) {

                        BigDecimal buyFistPrice = plateBuy.getHighestPrice();
                        BigDecimal sellLastPrice = plateSell.getLowestPrice();
                        if (sellLastPrice.subtract(buyFistPrice).compareTo(BigDecimal.valueOf(0.0001)) >= 0) {
                            ExchangeOrderDirection direction = rand.nextInt(100) > 50 ? ExchangeOrderDirection.BUY : ExchangeOrderDirection.SELL;
                            // 获取中间随机价
                            int seed = rand.nextInt(10);
                            BigDecimal randThumbPrice = BigDecimal.ZERO;
                            BigDecimal randThumbAmount = plateBuy.getMinAmount().multiply(BigDecimal.valueOf(0.85)).setScale(8, RoundingMode.HALF_UP);
                            if (direction == ExchangeOrderDirection.BUY) {
                                randThumbPrice = sellLastPrice.subtract(sellLastPrice.subtract(buyFistPrice).multiply(BigDecimal.valueOf(seed).divide(BigDecimal.valueOf(1000)))).setScale(6, RoundingMode.HALF_UP);
                            } else {
                                randThumbPrice = buyFistPrice.add(sellLastPrice.subtract(buyFistPrice).multiply(BigDecimal.valueOf(seed).divide(BigDecimal.valueOf(1000)))).setScale(6, RoundingMode.HALF_UP);
                            }
                            if (randThumbPrice.compareTo(sellLastPrice) >= 0) {
                                randThumbPrice = buyFistPrice;
                            }
                            if (!symbol.equalsIgnoreCase("BTC/USDT") && randThumbPrice.compareTo(BigDecimal.ZERO) <= 0) {
                                plateLastBuy = (HashMap<String, TradePlate>) plateLastBuyOrigin.clone();
                                plateBuy = plateLastBuy.get(symbol);
                                plateLastSell = (HashMap<String, TradePlate>) plateLastSellOrigin.clone();
                                plateSell = plateLastSell.get(symbol);
                                randThumbPrice = buyFistPrice;
                            }
                            if (!symbol.equalsIgnoreCase("BTC/USDT") && randThumbAmount.compareTo(BigDecimal.valueOf(0.0001)) <= 0) {
                                plateLastBuy = (HashMap<String, TradePlate>) plateLastBuyOrigin.clone();
                                plateBuy = plateLastBuy.get(symbol);
                                plateLastSell = (HashMap<String, TradePlate>) plateLastSellOrigin.clone();
                                plateSell = plateLastSell.get(symbol);
                                randThumbAmount = plateBuy.getMinAmount();
                            }
                            if (symbol.equalsIgnoreCase("BTC/USDT") && randThumbAmount.compareTo(BigDecimal.valueOf(0.000001)) <= 0) {
                                plateLastBuy = (HashMap<String, TradePlate>) plateLastBuyOrigin.clone();
                                plateBuy = plateLastBuy.get(symbol);
                                plateLastSell = (HashMap<String, TradePlate>) plateLastSellOrigin.clone();
                                plateSell = plateLastSell.get(symbol);
                                randThumbAmount = plateBuy.getMinAmount();
                            }
                            // 虚拟一个交易出来并且推送
                            ExchangeTrade randTrade = new ExchangeTrade();
                            randTrade.setPrice(randThumbPrice); // 虚拟成交价
                            randTrade.setAmount(randThumbAmount); // 成交量为最小档位的15%
                            randTrade.setBuyOrderId(GeneratorUtil.getOrderId("E"));
                            randTrade.setSellOrderId(GeneratorUtil.getOrderId("E"));
                            randTrade.setDirection(direction);
                            randTrade.setSymbol(symbol);
                            randTrade.setTime(Calendar.getInstance().getTimeInMillis());
                            boolean sendOrder = true;
                            if (randTrade.getDirection() == ExchangeOrderDirection.SELL) {
                                if (randThumbPrice.subtract(buyFistPrice).compareTo(BigDecimal.valueOf(0.0001)) <= 0) {
                                    sendOrder = false;
                                }
                            } else {
                                if (sellLastPrice.subtract(randThumbPrice).compareTo(BigDecimal.valueOf(0.0001)) <= 0) {
                                    sendOrder = false;
                                }
                            }
                            if (sendOrder) {
                                List<ExchangeTrade> virtualTrades = new ArrayList<>();
                                virtualTrades.add(randTrade);
                                messagingTemplate.convertAndSend("/topic/market/trade/" + symbol, virtualTrades);
                                if (lastThumb != null) {
                                    // 虚拟更新一下行情Thumb，并推送
                                    lastThumb.setClose(randTrade.getPrice());
                                    lastThumb.setHigh(randThumbPrice.max(lastThumb.getHigh()));
                                    lastThumb.setLow(randThumbPrice.min(lastThumb.getLow()));
                                    lastThumb.setVolume(lastThumb.getVolume().add(randTrade.getAmount()));
                                    lastThumb.setTurnover(lastThumb.getTurnover().add(randTrade.getAmount().multiply(randTrade.getPrice())));
                                    lastThumb.setChange(lastThumb.getClose().subtract(lastThumb.getOpen()));

                                    try {
                                        lastThumb.setChg(lastThumb.getChange().divide(lastThumb.getOpen(), 4, BigDecimal.ROUND_UP));
                                    } catch (Exception e) {
                                        //屏蔽除 0 异常
                                    }

                                    lastThumb.setUsdRate(randTrade.getPrice());
                                    messagingTemplate.convertAndSend("/topic/market/thumb", lastThumb);
                                    nettyHandler.handleTrade(symbol, randTrade, lastThumb);
                                }

                                // 盘口增加一个随机盘口订单
                                if (randTrade.getDirection() == ExchangeOrderDirection.BUY) {
                                    // 交易为买单，则构建卖单盘口
                                    ExchangeOrder sellOrder = new ExchangeOrder();
                                    sellOrder.setType(ExchangeOrderType.LIMIT_PRICE);
                                    sellOrder.setDirection(ExchangeOrderDirection.SELL);
                                    sellOrder.setAmount(randTrade.getAmount().multiply(BigDecimal.valueOf(1.1)));
                                    sellOrder.setPrice(randTrade.getPrice());
                                    sellOrder.setTradedAmount(BigDecimal.ZERO);
                                    //plateSell.add(sellOrder);
                                } else {
                                    // 交易为卖单，则构建买单盘口
                                    ExchangeOrder buyOrder = new ExchangeOrder();
                                    buyOrder.setType(ExchangeOrderType.LIMIT_PRICE);
                                    buyOrder.setDirection(ExchangeOrderDirection.BUY);
                                    buyOrder.setAmount(randTrade.getAmount().multiply(BigDecimal.valueOf(1.1)));
                                    buyOrder.setPrice(randTrade.getPrice());
                                    buyOrder.setTradedAmount(BigDecimal.ZERO);
                                    plateBuy.add(buyOrder);
                                }
                            }
                        }
                    }
                }

                if(plateBuy != null) {
                    // 随机修改盘口数据，然后推送
                    List<TradePlateItem> list = plateBuy.getItems();
                    if(list.size() > 9) { // 只要大于随机数的种子就行，这里的4是随意设置的
                        int randInt = rand.nextInt(9);
                        list.get(randInt).setAmount(list.get(randInt).getAmount().multiply(BigDecimal.valueOf(1.5))); // 随机挑选一个，让盘口订单数量+50%
                        if(randInt > 0 && randInt < list.size() - 1) { // 如果在中间，就可以变动价格
                            // 价格取前后价格居中的价格
                            // list.get(randInt).setPrice(list.get(randInt - 1).getPrice().add(list.get(randInt + 1).getPrice()).divide(BigDecimal.valueOf(2), 8, RoundingMode.HALF_DOWN));
                        }
                        //websocket推送盘口信息
                        messagingTemplate.convertAndSend("/topic/market/trade-plate/" + symbol, plateBuy.toJSON(24));
                        //websocket推送深度信息
                        messagingTemplate.convertAndSend("/topic/market/trade-depth/" + symbol, plateBuy.toJSON(50));
                        //netty推送
                        nettyHandler.handlePlate(symbol, plateBuy);
                    }
                }
                if(plateSell != null) {
                    // 随机修改盘口数据，然后推送
                    List<TradePlateItem> list = plateSell.getItems();
                    if(list.size() > 9) { // 只要大于随机数的种子就行，这里的4是随意设置的
                        int randInt = rand.nextInt(9);
                        list.get(randInt).setAmount(list.get(randInt).getAmount().multiply(BigDecimal.valueOf(1.5))); // 随机挑选一个，让盘口订单数量+50%
                        if(randInt > 0 && randInt < list.size() - 1) { // 如果在中间，就可以变动价格
                            // 价格取前后价格居中的价格
                            // list.get(randInt).setPrice(list.get(randInt - 1).getPrice().add(list.get(randInt + 1).getPrice()).divide(BigDecimal.valueOf(2), 8, RoundingMode.HALF_DOWN));
                        }
                        //websocket推送盘口信息
                        messagingTemplate.convertAndSend("/topic/market/trade-plate/" + symbol, plateSell.toJSON(24));
                        //websocket推送深度信息
                        messagingTemplate.convertAndSend("/topic/market/trade-depth/" + symbol, plateSell.toJSON(50));
                        //netty推送
                        nettyHandler.handlePlate(symbol, plateSell);
                    }
                }

            }
        }
    }

    @Scheduled(fixedRate = 1000)
    public void pushThumb(){
        Iterator<Map.Entry<String,List<CoinThumb>>> entryIterator = thumbQueue.entrySet().iterator();
        while (entryIterator.hasNext()){
            Map.Entry<String,List<CoinThumb>> entry =  entryIterator.next();
            String symbol = entry.getKey();
            List<CoinThumb> thumbs = entry.getValue();
            if(thumbs.size() > 0){
                synchronized (thumbs) {
                    // 跟虚拟的对比一下，如果发现成交量比较小，就加一下
                    CoinThumb pushThumb = thumbs.get(thumbs.size() - 1);
                    if(lastPushThumb.get(symbol) != null && lastPushThumb.get(symbol).getVolume().compareTo(pushThumb.getVolume()) > 0){
                        pushThumb.setVolume(lastPushThumb.get(symbol).getVolume());
                    }
                    messagingTemplate.convertAndSend("/topic/market/thumb", thumbs.get(thumbs.size() - 1));
                    // 保存最后一次推送的行情
                    lastPushThumb.put(symbol, thumbs.get(thumbs.size() - 1));
                    thumbs.clear();
                }
            }
        }
    }
}
