package com.bizzan.bitrade.socket.ws;


import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.bizzan.bitrade.engine.ContractCoinMatchFactory;
import com.bizzan.bitrade.entity.*;
import com.bizzan.bitrade.job.ExchangePushJob;
import com.bizzan.bitrade.service.ContractCoinService;
import com.bizzan.bitrade.service.ContractMarketService;
import com.bizzan.bitrade.util.DateUtil;
import com.bizzan.bitrade.util.JSONUtils;
import com.bizzan.bitrade.util.WebSocketConnectionManage;
import com.bizzan.bitrade.util.ZipUtils;
import com.mashape.unirest.http.HttpResponse;
import com.mashape.unirest.http.JsonNode;
import com.mashape.unirest.http.Unirest;
import com.mashape.unirest.http.exceptions.UnirestException;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.enums.ReadyState;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class WebSocketBinance extends WebSocketClient {

    private Logger logger = LoggerFactory.getLogger(WebSocketClient.class);
    private ArrayList<String> subCoinList = new ArrayList<String>();

    private ContractCoinMatchFactory matchFactory;
    private ContractMarketService marketService;
    private ExchangePushJob exchangePushJob;
    private ContractCoinService contractCoinService;

    public static String DEPTH = "%s@depth20@100ms"; // 深度
    public static String KLINE = "%s@kline_%s"; // K线
    public static String DETAIL = "%s@ticker"; // 市场概要（最新价格、成交量等）
    public static String TRADE = "%s@aggTrade"; // 成交明细
    public static String FOUNDRATE = "%s@markPrice@1s"; // 资金费率



    private double VOLUME_PERCENT = 0.13; // 火币成交量的百分比

    public static String PERIOD[] = { "1m", "5m", "15m", "30m", "1h","4h", "1d", "1w", "1M" };

    public WebSocketBinance(URI uri, ContractCoinMatchFactory matchFactory, ContractMarketService service, ExchangePushJob pushJob, ContractCoinService contractCoinService) {
        super(uri);
        this.uri = uri;
        this.matchFactory = matchFactory;
        this.marketService = service;
        this.exchangePushJob = pushJob;
        this.contractCoinService = contractCoinService;
    }

    @Override
    public void onOpen(ServerHandshake shake) {
        logger.info("[WebSocketBinance] 开启价格 Websocket 监听...");
        if (null != this.matchFactory.getMatchMap() && this.matchFactory.getMatchMap().size() > 0) {
            for(String symbol : this.matchFactory.getMatchMap().keySet()) {
                if(!subCoinList.contains(symbol)){
                    subCoinList.add(symbol);
                }

                // 同步k线数据
                syncKline(symbol.replace("/", "").toLowerCase());

                List<String> params = new ArrayList<>();
                // 订阅深度
                String depthTopic = String.format(DEPTH, symbol.replace("/", "").toLowerCase());
//                logger.info("[WebSocketBinance][" + symbol + "] 深度订阅: " + depthTopic);
//                sendWS("SUBSCRIBE", depthTopic);
                params.add(depthTopic);

                // 订阅市场概要
                String detailTopic = String.format(DETAIL, symbol.replace("/", "").toLowerCase());
//                logger.info("[WebSocketBinance][" + symbol + "] 概要订阅: " + detailTopic);
//                sendWS("SUBSCRIBE", detailTopic);
                params.add(detailTopic);

                // 订阅成交明细
                String tradeTopic = String.format(TRADE, symbol.replace("/", "").toLowerCase());
//                logger.info("[WebSocketBinance][" + symbol + "] 成交明细订阅: " + tradeTopic);
//                sendWS("SUBSCRIBE", tradeTopic);
                params.add(tradeTopic);

                // 订阅资金费率
                String foundTopic = String.format(FOUNDRATE, symbol.replace("/", "").toLowerCase());
//                logger.info("[WebSocketBinance][" + symbol + "] 资金费率订阅: " + foundTopic);
//                sendWS("SUBSCRIBE", foundTopic);
                params.add(foundTopic);

                // 订阅实时K线
                for(String period : PERIOD) {
                    String klineTopic = String.format(KLINE, symbol.replace("/", "").toLowerCase(), period);
//                    logger.info("[WebSocketBinance][" + symbol + "] 实时K线订阅: " + klineTopic);
//                    sendWS("SUBSCRIBE", klineTopic);
                    params.add(klineTopic);
                }
                sendWS("SUBSCRIBE", params);
                logger.info("[WebSocketBinance][" + symbol + "] 订阅: " + params);
            }
        }
    }

    /**
     * 订阅新币种行情信息
     * @param symbol
     */
    public void subNewCoin(String symbol) {
        if(!subCoinList.contains(symbol)){
            subCoinList.add(symbol);

            // 同步k线数据
            syncKline(symbol.replace("/", "").toLowerCase());

            List<String> params = new ArrayList<>();
            String detailTopic = String.format(DETAIL, symbol.replace("/", "").toLowerCase());
//            logger.info("[WebSocketBinance][" + symbol + "] 概要订阅: " + detailTopic);
//            sendWS("SUBSCRIBE", detailTopic);
            params.add(detailTopic);

            String tradeTopic = String.format(TRADE, symbol.replace("/", "").toLowerCase());
//            logger.info("[WebSocketBinance][" + symbol + "] 成交明细订阅: " + tradeTopic);
//            sendWS("SUBSCRIBE", tradeTopic);
            params.add(tradeTopic);

            String depthTopic = String.format(DEPTH, symbol.replace("/", "").toLowerCase());
//            logger.info("[WebSocketBinance][" + symbol + "] 深度订阅: " + depthTopic);
//            sendWS("SUBSCRIBE", depthTopic);
            params.add(depthTopic);

            // 订阅资金费率
            String foundTopic = String.format(FOUNDRATE, symbol.replace("/", "").toLowerCase());
//            logger.info("[WebSocketBinance][" + symbol + "] 资金费率订阅: " + foundTopic);
//            sendWS("SUBSCRIBE", foundTopic);
            params.add(foundTopic);

            // 订阅实时K线
            for(String period : PERIOD) {
                String klineTopic = String.format(KLINE, symbol.replace("/", "").toLowerCase(), period);
//                logger.info("[WebSocketBinance][" + symbol + "] 实时K线订阅: " + klineTopic);
//                sendWS("SUBSCRIBE", klineTopic);
                params.add(klineTopic);
            }
            sendWS("SUBSCRIBE", params);
            logger.info("[WebSocketBinance][" + symbol + "] 订阅: " + params);
        }
    }

    // 同步K线
    public void reqKLineList(String symbol, String period, long from, long to) {

    }

    @Override
    public void onMessage(ByteBuffer bytes) {
        if (bytes != null) {
            try {
                String message = new String(ZipUtils.decompress(bytes.array()), "UTF-8");
                logger.info("[WebSocketBinance] receive bytes message: {}", message);
            } catch (Exception e) {
                e.printStackTrace();
                logger.error("[WebSocketBinance] websocket exception: {}", e.getMessage());
            }
        }
    }

    @Override
    public void onError(Exception arg0) {
        logger.error("[WebSocketBinance] has error ,the message is :: {}", arg0.getMessage());
        arg0.printStackTrace();
        String message = "";
        try {
            message = new String(arg0.getMessage().getBytes(), "UTF-8");
        } catch (Exception e) {
            e.printStackTrace();
            logger.error("[WebSocketBinance] has error ,the message is :: {}", message);
        }
    }

    @Override
    public void onClose(int arg0, String arg1, boolean arg2) {
        logger.info("[WebSocketBinance] connection close: {} - {} - {}", arg0, arg1, arg2);
        int tryTimes = 0;
        // 尝试20次
        logger.info("[WebSocketBinance] 尝试重新连接，第 " + tryTimes + "次");
        if(this.getReadyState().equals(ReadyState.NOT_YET_CONNECTED) || this.getReadyState().equals(ReadyState.CLOSED) || this.getReadyState().equals(ReadyState.CLOSING)) {

            Runnable sendable = new Runnable() {
                @Override
                public void run() {
                    logger.info("[WebSocketBinance] 开启重新连接");
                    reconnect();
                }
            };
            new Thread(sendable).start();
        }
    }

    @Override
    public void onMessage(String message) {
        try {
            JSONObject jsonObject = JSONObject.parseObject(message);
            if (!"".equals(message)) {
                String id = "";
                if(jsonObject.containsKey("e")) {
                    id = jsonObject.getString("e");
                }
                if(id.equals("")) {
                    return;
                }

                StringBuilder sb = new StringBuilder(jsonObject.getString("s"));
                String symbol = sb.insert(sb.indexOf("USDT"), "/").toString().toUpperCase();

                String type = id;

                if(type.equals("kline")) {
                    JSONObject klineObj = jsonObject.getJSONObject("k");

                    String period = klineObj.getString("i");
                    BigDecimal open = klineObj.getBigDecimal("o"); // 收盘价
                    BigDecimal close = klineObj.getBigDecimal("c"); // 收盘价
                    BigDecimal high = klineObj.getBigDecimal("h"); // 收盘价
                    BigDecimal low = klineObj.getBigDecimal("l"); // 收盘价
                    BigDecimal amount = klineObj.getBigDecimal("q"); // 收盘价
                    BigDecimal vol = klineObj.getBigDecimal("v"); // 收盘价
                    int count = klineObj.getIntValue("n"); // 收盘价
                    long time = klineObj.getLongValue("E");

                    KLine kline = new KLine(convert(period));
                    kline.setClosePrice(close);
                    kline.setCount(count);
                    kline.setHighestPrice(high);
                    kline.setLowestPrice(low);
                    kline.setOpenPrice(open);
                    kline.setTime(time);
                    kline.setTurnover(amount.multiply(BigDecimal.valueOf(VOLUME_PERCENT)));
                    kline.setVolume(vol.multiply(BigDecimal.valueOf(VOLUME_PERCENT)));
                    marketService.saveKLine(symbol, kline);

                    // 推送K线(如果只有一条，说明是最新的K线，需要推送到前端K线)
                    if(klineObj.getBoolean("x")) {
                        logger.info("[WebSocketBinance] K线推送：{} {}", symbol, kline.getPeriod());
                        exchangePushJob.pushTickKline(symbol, kline);
                    }
                }else if(type.equals("depthUpdate")){
                    // 买盘深度
                    JSONArray bids = jsonObject.getJSONArray("b");
                    List<TradePlateItem> buyItems = new ArrayList<>();
                    for(int i = 0; i < bids.size(); i++) {
                        TradePlateItem item = new TradePlateItem();
                        JSONArray itemObj = bids.getJSONArray(i);
                        item.setPrice(itemObj.getBigDecimal(0));
                        item.setAmount(itemObj.getBigDecimal(1));
                        buyItems.add(item);
                    }

                    // 卖盘深度
                    JSONArray asks = jsonObject.getJSONArray("a");
                    List<TradePlateItem> sellItems = new ArrayList<>();
                    for(int i = 0; i < asks.size(); i++) {
                        TradePlateItem item = new TradePlateItem();
                        JSONArray itemObj = asks.getJSONArray(i);
                        item.setPrice(itemObj.getBigDecimal(0));
                        item.setAmount(itemObj.getBigDecimal(1));
                        sellItems.add(item);
                    }
                    // 刷新盘口数据
                    this.matchFactory.getContractCoinMatch(symbol).refreshPlate(buyItems, sellItems);
                    logger.info("[WebSocketBinance] 盘口更新：{} bids共 {} 条，asks共 {} 条", symbol, bids.size(), asks.size());
                }else if(type.equals("24hrTicker")){ // 市场行情概要
                    BigDecimal amount = jsonObject.getBigDecimal("q");
                    BigDecimal open = jsonObject.getBigDecimal("o");
                    BigDecimal close = jsonObject.getBigDecimal("c");
                    BigDecimal high = jsonObject.getBigDecimal("h");
                    BigDecimal low = jsonObject.getBigDecimal("l");
                    BigDecimal vol = jsonObject.getBigDecimal("v");

                    CoinThumb thumb = new CoinThumb();
                    thumb.setOpen(open);
                    thumb.setClose(close);
                    thumb.setHigh(high);
                    thumb.setLow(low);
                    thumb.setVolume(amount.multiply(BigDecimal.valueOf(VOLUME_PERCENT))); // 成交量
                    thumb.setTurnover(vol.multiply(BigDecimal.valueOf(VOLUME_PERCENT))); // 成交额
                    this.matchFactory.getContractCoinMatch(symbol).refreshThumb(thumb);
                    // 委托触发 or 爆仓
                    this.matchFactory.getContractCoinMatch(symbol).refreshPrice(close, this.matchFactory);
                    logger.info("[WebSocketBinance] 价格更新：{} {}", symbol, thumb);
                }else if(type.equals("aggTrade")) { // 成交明细
                    List<ContractTrade> tradeArrayList = new ArrayList<ContractTrade>();
                    BigDecimal amount = jsonObject.getBigDecimal("q");
                    BigDecimal price = jsonObject.getBigDecimal("p");
                    String direction = jsonObject.getString("m");
                    long time = jsonObject.getLongValue("E");
                    String tradeId = jsonObject.getString("a");
                    // 创建交易
                    ContractTrade trade = new ContractTrade();
                    trade.setAmount(amount);
                    trade.setPrice(price);
                    if(direction.equals("false")) {
                        trade.setDirection(ContractOrderDirection.BUY);
                        trade.setBuyOrderId(tradeId);
                        trade.setBuyTurnover(amount.multiply(price));
                    }else{
                        trade.setDirection(ContractOrderDirection.SELL);
                        trade.setSellOrderId(tradeId);
                        trade.setSellTurnover(amount.multiply(price));
                    }
                    trade.setSymbol(symbol);
                    trade.setTime(time);
                    tradeArrayList.add(trade);
                    // 刷新成交记录
                    this.matchFactory.getContractCoinMatch(symbol).refreshLastedTrade(tradeArrayList);
                    logger.info("[WebSocketBinance] 成交明细：{} {}", symbol, tradeArrayList.size());
                }else if(type.equals("markPriceUpdate")) { // 资金费率
                    BigDecimal fundingRate = jsonObject.getBigDecimal("r"); // 当期资金费率
                    ContractCoin coin = this.contractCoinService.findBySymbol(symbol);
                    coin.setFeePercent(fundingRate);
                    contractCoinService.save(coin);
                    logger.info("[WebSocketBinance] 资金费率：{} {}", symbol, fundingRate);
                } else {
                    logger.error("[WebSocketBinance] 未处理：" + message);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            logger.error("[WebSocketBinance] websocket exception: {}", e.getMessage());
        }
    }

    public void sendWS(String op, List<String> params) {
        JSONObject req = new JSONObject();
        req.put("method", op);
        req.put("params", params);
        send(req.toString());
    }

    public void syncKline(String symbol) {
        for(String period : PERIOD) {
            while (true) {
                long fromTime = marketService.findMaxTimestamp(symbol, convert(period));
                Long currentTime = DateUtil.getTimeMillis() / 1000;
                long timeGap = currentTime - fromTime;
                if(period.equals("1m") && timeGap < 60) { // 超出1分钟
                    break;
                }
                if(period.equals("5m") && timeGap < 60 * 5) { // 超出5分钟
                    break;
                }
                if(period.equals("15m") && timeGap < 60 * 15) { // 超出15分钟
                    break;
                }
                if(period.equals("30m") && timeGap < 60 * 30) { // 超出30分钟
                    break;
                }
                if(period.equals("1h") && timeGap < 60 * 60) { // 超出60分钟
                    break;
                }
                if(period.equals("4h") && timeGap < 60 * 60 * 4) { // 超出4小时
                    break;
                }
                if(period.equals("1d") && timeGap < 60 * 60 * 24) { // 超出24小时
                    break;
                }
                if(period.equals("1w") && timeGap < 60 * 60 * 24 * 7) { // 超出24小时
                    break;
                }
                if(period.equals("1M") && timeGap < 60 * 60 * 24 * 30 * 2) { // 超出24小时
                    break;
                }
                try {
                    Integer code = sendKline(symbol, period, fromTime*1000, currentTime*1000);
                    if (code == 429) {
                        TimeUnit.SECONDS.sleep(10);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private Integer sendKline(String symbol, String period, long from, long to) throws UnirestException {
        String url = String.format("https://fapi.binance.com/fapi/v1/klines?symbol=%s&interval=%s&startTime=%s&endTime=%s", symbol, period, from, to);
        HttpResponse<JsonNode> resp = Unirest.get(url).asJson();
        logger.info("sendKline {} {}", url, resp);
        if(resp.getStatus() == 200) { //正确返回
            JSONArray klineList = JSON.parseArray(resp.getBody().toString());
            for(int i = 0; i < klineList.size(); i++) {
                JSONArray klineObj = klineList.getJSONArray(i);

                BigDecimal open = klineObj.getBigDecimal(1); // 收盘价
                BigDecimal close = klineObj.getBigDecimal(4); // 收盘价
                BigDecimal high = klineObj.getBigDecimal(2); // 收盘价
                BigDecimal low = klineObj.getBigDecimal(3); // 收盘价
                BigDecimal amount = klineObj.getBigDecimal(7); // 收盘价
                BigDecimal vol = klineObj.getBigDecimal(5); // 收盘价
                int count = klineObj.getIntValue(8); // 收盘价
                long time = klineObj.getLongValue(0);


                KLine kline = new KLine(convert(period));
                kline.setClosePrice(close);
                kline.setCount(count);
                kline.setHighestPrice(high);
                kline.setLowestPrice(low);
                kline.setOpenPrice(open);
                kline.setTime(time);
                kline.setTurnover(amount.multiply(BigDecimal.valueOf(VOLUME_PERCENT)));
                kline.setVolume(vol.multiply(BigDecimal.valueOf(VOLUME_PERCENT)));
                marketService.saveKLine(symbol, kline);
            }
            logger.info("[WebSocketBinance] K线同步：" + period + " - " + symbol + " - " + klineList.size());
        }
        return resp.getStatus();
    }

    private String convert(String period) {
        String interval = "";
        if (period.equals("1m")){
            interval = "1min";
        } else if (period.equals("5m")){
            interval = "5min";
        } else if (period.equals("15m")){
            interval = "15min";
        } else if (period.equals("30m")){
            interval = "30min";
        } else if (period.equals("1h")){
            interval = "60min";
        } else if (period.equals("4h")){
            interval = "4hour";
        } else if (period.equals("1d")){
            interval = "1day";
        } else if (period.equals("1w")){
            interval = "1week";
        } else if (period.equals("1M")){
            interval = "1mon";
        }
        return interval;
    }
}
