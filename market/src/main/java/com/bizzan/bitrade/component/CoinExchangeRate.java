package com.bizzan.bitrade.component;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.bizzan.bitrade.entity.Coin;
import com.bizzan.bitrade.entity.CoinThumb;
import com.bizzan.bitrade.processor.CoinProcessor;
import com.bizzan.bitrade.processor.CoinProcessorFactory;
import com.bizzan.bitrade.service.CoinService;
import com.bizzan.bitrade.service.ExchangeCoinService;
import com.mashape.unirest.http.HttpResponse;
import com.mashape.unirest.http.JsonNode;
import com.mashape.unirest.http.Unirest;
import com.mashape.unirest.http.exceptions.UnirestException;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;

/**
 * 币种汇率管理
 */
@Component
@Slf4j
@ToString
public class CoinExchangeRate {
    @Getter
    @Setter
    private BigDecimal usdCnyRate = new BigDecimal("6.90");
    
    @Getter
    @Setter
    private BigDecimal usdtCnyRate = new BigDecimal("7.00");
    
    @Getter
    @Setter
    private BigDecimal usdJpyRate = new BigDecimal("110.02");

    @Getter
    @Setter
    private BigDecimal usdHkdRate = new BigDecimal("7.8491");

    @Getter
    @Setter
    private BigDecimal sgdCnyRate = new BigDecimal("5.08");

    @Getter
    @Setter
    private Map<String, BigDecimal> currencyMap = new HashMap<String, BigDecimal>();

    @Setter
    private CoinProcessorFactory coinProcessorFactory;

    @Autowired
    private CoinService coinService;
    @Autowired
    private ExchangeCoinService exCoinService;

    CoinExchangeRate(){
        // 这里是默认的汇率，定时任务会刷新该汇率
        // 默认汇率日期：2021/01/13 18:37
        currencyMap.put("USD", BigDecimal.ONE);     // 美元/美元
        currencyMap.put("CNY", BigDecimal.valueOf(6.470));     // 美元/人民币
        currencyMap.put("GBP", BigDecimal.valueOf(0.7309));     // 美元/英镑
        currencyMap.put("JPY", BigDecimal.valueOf(103.8));     // 美元/日元
        currencyMap.put("EUR", BigDecimal.valueOf(0.820));     // 美元/欧元
        currencyMap.put("KRW", BigDecimal.valueOf(1097.3));     // 美元/韩国元
        currencyMap.put("HKD", BigDecimal.valueOf(7.7533));     // 美元/港币
        currencyMap.put("RUB", BigDecimal.valueOf(73.8505));     // 美元/俄罗斯卢布
        currencyMap.put("TWD", BigDecimal.valueOf(28.01));     // 美元/台币
        currencyMap.put("SGD", BigDecimal.valueOf(1.3252));     // 美元/新加坡元
        currencyMap.put("INR", BigDecimal.valueOf(73.17));     // 美元/印度卢比
        currencyMap.put("AED", BigDecimal.valueOf(3.672));     // 美元/阿联酋迪拉姆
        currencyMap.put("AFN", BigDecimal.valueOf(77));     // 美元/阿富汗尼
        currencyMap.put("ANG", BigDecimal.valueOf(1.78));     // 美元/荷兰盾
        currencyMap.put("ARS", BigDecimal.valueOf(85.4));     // 美元/阿根廷披索
        currencyMap.put("AUD", BigDecimal.valueOf(1.2901));     // 美元/澳大利亚元
        currencyMap.put("BBD", BigDecimal.valueOf(2));     // 美元/巴巴多斯元
        currencyMap.put("BDT", BigDecimal.valueOf(84.57));     // 美元/孟加拉国塔卡
        currencyMap.put("BGN", BigDecimal.valueOf(1.6082));     // 美元/保加利亚列瓦
        currencyMap.put("BMD", BigDecimal.valueOf(1));     // 美元/百慕大元
        currencyMap.put("BND", BigDecimal.valueOf(1.325));     // 美元/文莱元
        currencyMap.put("BRL", BigDecimal.valueOf(5.323));     // 美元/巴西雷亚尔
        currencyMap.put("BYR", BigDecimal.valueOf(20020));     // 美元/白俄罗斯卢布
        currencyMap.put("CAD", BigDecimal.valueOf(1.2736));     // 美元/加拿大元
        currencyMap.put("CHF", BigDecimal.valueOf(0.8874));     // 美元/瑞士法郎
        currencyMap.put("CLP", BigDecimal.valueOf(724.7));     // 美元/智利比索
        currencyMap.put("COP", BigDecimal.valueOf(3474.7));     // 美元/哥伦比亚比索
        currencyMap.put("CUP", BigDecimal.valueOf(24.999));     // 美元/古巴比索
        currencyMap.put("CZK", BigDecimal.valueOf(21.48));     // 美元/捷克克朗
        currencyMap.put("DKK", BigDecimal.valueOf(6.1056));     // 美元/丹麦克朗
        currencyMap.put("EGP", BigDecimal.valueOf(15.6));     // 美元/埃及镑
        currencyMap.put("ETB", BigDecimal.valueOf(39.1));     // 美元/埃塞俄比亚比尔
        currencyMap.put("IDR", BigDecimal.valueOf(14062.18));     // 美元/印度尼西亚盾
        currencyMap.put("ILS", BigDecimal.valueOf(3.133));     // 美元/以色列谢克尔
        currencyMap.put("ISK", BigDecimal.valueOf(128));     // 美元/冰岛克朗
        currencyMap.put("KES", BigDecimal.valueOf(109.7));     // 美元/肯尼亚先令
        currencyMap.put("KPW", BigDecimal.valueOf(900));     // 美元/朝鲜元
        currencyMap.put("KZT", BigDecimal.valueOf(418.7));     // 美元/哈萨克斯坦坚戈
        currencyMap.put("LAK", BigDecimal.valueOf(9290));     // 美元/老挝基普
        currencyMap.put("MMK", BigDecimal.valueOf(1327));     // 美元/缅元:
        currencyMap.put("MOP", BigDecimal.valueOf(7.985));     // 美元/澳门币
        currencyMap.put("MYR", BigDecimal.valueOf(4.04));     // 美元/马来西亚林吉特
        currencyMap.put("NOK", BigDecimal.valueOf(8.459));     // 美元/挪威克朗
        currencyMap.put("NZD", BigDecimal.valueOf(1.389));     // 美元/新西兰元
        currencyMap.put("PHP", BigDecimal.valueOf(48.04));     // 美元/菲律宾比索
        currencyMap.put("PKR", BigDecimal.valueOf(160.2));     // 美元/巴基斯坦卢比
        currencyMap.put("SAR", BigDecimal.valueOf(3.751));     // 美元/沙特阿拉伯里亚尔
        currencyMap.put("SEK", BigDecimal.valueOf(8.2852));     // 美元/瑞典克朗
        currencyMap.put("TRY", BigDecimal.valueOf(7.434));     // 美元/土耳其里拉
        currencyMap.put("UAH", BigDecimal.valueOf(28.1));     // 美元/乌克兰格里夫纳
        currencyMap.put("VEF", BigDecimal.valueOf(248209));     // 美元/委内瑞拉玻利瓦尔
        currencyMap.put("VND", BigDecimal.valueOf(23176));     // 美元/越南盾
        currencyMap.put("XAF", BigDecimal.valueOf(537.7));     // 美元/中非法郎
        currencyMap.put("ZWL", BigDecimal.valueOf(321));     // 美元/津巴布韦元
    }

    public BigDecimal getUsdRate(String symbol) {
        log.info("CoinExchangeRate getUsdRate unit = " + symbol);
        if ("USDT".equalsIgnoreCase(symbol)) {
            log.info("CoinExchangeRate getUsdRate unit = USDT  ,result = ONE");
            return BigDecimal.ONE;
        } else if ("CNY".equalsIgnoreCase(symbol)) {
            log.info("CoinExchangeRate getUsdRate unit = CNY  ,result : 1 divide {}", this.usdtCnyRate);
            BigDecimal bigDecimal = BigDecimal.ONE.divide(usdtCnyRate, 4,BigDecimal.ROUND_DOWN).setScale(4, BigDecimal.ROUND_DOWN);
            return bigDecimal;
        }else if ("BITCNY".equalsIgnoreCase(symbol)) {
            BigDecimal bigDecimal = BigDecimal.ONE.divide(usdCnyRate, 4,BigDecimal.ROUND_DOWN).setScale(4, BigDecimal.ROUND_DOWN);
            return bigDecimal;
        } else if ("ET".equalsIgnoreCase(symbol)) {
            BigDecimal bigDecimal = BigDecimal.ONE.divide(usdCnyRate, 4,BigDecimal.ROUND_DOWN).setScale(4, BigDecimal.ROUND_DOWN);
            return bigDecimal;
        } else if ("JPY".equalsIgnoreCase(symbol)) {
            BigDecimal bigDecimal = BigDecimal.ONE.divide(usdJpyRate, 4,BigDecimal.ROUND_DOWN).setScale(4, BigDecimal.ROUND_DOWN);
            return bigDecimal;
        }else if ("HKD".equalsIgnoreCase(symbol)) {
            BigDecimal bigDecimal = BigDecimal.ONE.divide(usdHkdRate, 4,BigDecimal.ROUND_DOWN).setScale(4, BigDecimal.ROUND_DOWN);
            return bigDecimal;
        }
        String usdtSymbol = symbol.toUpperCase() + "/USDT";
        String btcSymbol = symbol.toUpperCase() + "/BTC";
        String ethSymbol = symbol.toUpperCase() + "/ETH";

        if (coinProcessorFactory != null) {
            if (coinProcessorFactory.containsProcessor(usdtSymbol)) {
                log.info("Support exchange coin = {}", usdtSymbol);
                CoinProcessor processor = coinProcessorFactory.getProcessor(usdtSymbol);
                if(processor == null) {
                	return BigDecimal.ZERO;
                }
                CoinThumb thumb = processor.getThumb();
                if(thumb == null) {
                	log.info("Support exchange coin thumb is null", thumb);
                	return BigDecimal.ZERO;
                }
                return thumb.getUsdRate();
            } else if (coinProcessorFactory.containsProcessor(btcSymbol)) {
                log.info("Support exchange coin = {}/BTC", btcSymbol);
                CoinProcessor processor = coinProcessorFactory.getProcessor(btcSymbol);
                if(processor == null) {
                	return BigDecimal.ZERO; 
                }
                CoinThumb thumb = processor.getThumb();
                if(thumb == null) {
                	log.info("Support exchange coin thumb is null", thumb);
                	return BigDecimal.ZERO;
                }
                return thumb.getUsdRate();
            } else if (coinProcessorFactory.containsProcessor(ethSymbol)) {
                log.info("Support exchange coin = {}/ETH", ethSymbol);
                CoinProcessor processor = coinProcessorFactory.getProcessor(ethSymbol);
                if(processor == null) {
                	return BigDecimal.ZERO; 
                }
                CoinThumb thumb = processor.getThumb();
                if(thumb == null) {
                	log.info("Support exchange coin thumb is null", thumb);
                	return BigDecimal.ZERO;
                }
                return thumb.getUsdRate();
            } else {
                return getDefaultUsdRate(symbol);
            }
        } else {
            return getDefaultUsdRate(symbol);
        }
    }

    /**
     * 获取币种设置里的默认价格
     *
     * @param symbol
     * @return
     */
    public BigDecimal getDefaultUsdRate(String symbol) {
        Coin coin = coinService.findByUnit(symbol);
        if (coin != null) {
            return new BigDecimal(coin.getUsdRate());
        } else {
            return BigDecimal.ZERO;
        }
    }

    public BigDecimal getCnyRate(String symbol) {
        if ("CNY".equalsIgnoreCase(symbol)) {
            return BigDecimal.ONE;
        } else if("ET".equalsIgnoreCase(symbol)){
            return BigDecimal.ONE;
        }
        return getUsdRate(symbol).multiply(usdtCnyRate).setScale(2, RoundingMode.DOWN);
    }

    public BigDecimal getJpyRate(String symbol) {
        if ("JPY".equalsIgnoreCase(symbol)) {
            return BigDecimal.ONE;
        }
        return getUsdRate(symbol).multiply(usdJpyRate).setScale(2, RoundingMode.DOWN);
    }

    public BigDecimal getHkdRate(String symbol) {
        if ("HKD".equalsIgnoreCase(symbol)) {
            return BigDecimal.ONE;
        }
        return getUsdRate(symbol).multiply(usdHkdRate).setScale(2, RoundingMode.DOWN);
    }

    public BigDecimal getUsdCurrencyRate(String currency) {
        if(StringUtils.isEmpty(currency)) {
            return BigDecimal.ONE;
        }
        BigDecimal curr = currencyMap.get(currency);
        if(curr != null) {
            return curr;
        }else{
            return BigDecimal.ONE;
        }
    }
    /**
     * 每5分钟同步一次价格
     *
     * @throws UnirestException
     */
    
    @Scheduled(cron = "0 */5 * * * *")
    public void syncUsdtCnyPrice() throws UnirestException {
    	// 抹茶OTC接口
    	String url = "https://otc.mxc.com/api/coin/USDT/price";
        //如有报错 请自行官网申请获取汇率 或者默认写死
        HttpResponse<JsonNode> resp = Unirest.get(url)
                .asJson();
        if(resp.getStatus() == 200) { //正确返回
	        JSONObject ret = JSON.parseObject(resp.getBody().toString());
	        if(ret.getIntValue("code") == 0) {
	        	JSONObject result = ret.getJSONObject("result");
	        	setUsdtCnyRate(new BigDecimal(result.getDouble("buy")).setScale(2, RoundingMode.HALF_UP));
	        	return;
	        }
        }
        
        // Huobi Otc接口（如抹茶接口无效则走此路径）
        String url2 = "https://otc-api-hk.eiijo.cn/v1/data/trade-market?coinId=2&currency=1&tradeType=sell&currPage=1&payMethod=0&country=&blockType=general&online=1&range=0&amount=";
        HttpResponse<JsonNode> resp2 = Unirest.get(url2)
                .asJson();
        if(resp2.getStatus() == 200) { //正确返回
        	JSONObject ret2 = JSON.parseObject(resp2.getBody().toString());
	        if(ret2.getIntValue("code") == 200) {
	        	JSONArray arr = ret2.getJSONArray("data");
	        	if(arr.size() > 0) {
	        		JSONObject obj = arr.getJSONObject(0);
	        		setUsdtCnyRate(new BigDecimal(obj.getDouble("price")).setScale(2, RoundingMode.HALF_UP));
	        		return;
	        	}
	        }
        }
        
        // Okex Otc接口
        String url3 = "https://otc-api-hk.eiijo.cn/v1/data/trade-market?coinId=2&currency=1&tradeType=sell&currPage=1&payMethod=0&country=37&blockType=general&online=1&range=0&amount=";
        HttpResponse<JsonNode> resp3 = Unirest.get(url2)
                .asJson();
        if(resp3.getStatus() == 200) { //正确返回
        	JSONObject ret3 = JSON.parseObject(resp3.getBody().toString());
	        if(ret3.getIntValue("code") == 200) {
                JSONArray okArr = ret3.getJSONArray("data");
	        	if(okArr.size() > 0) {
	        		JSONObject okObj2 = okArr.getJSONObject(0);
	        		setUsdtCnyRate(new BigDecimal(okObj2.getDouble("price")).setScale(2, RoundingMode.HALF_UP));
	        		return;
	        	}
	        }
        }
    }
    
    /**
     * 每30分钟同步一次价格
     *
     * @throws UnirestException
     */
    
    @Scheduled(cron = "0 */30 * * * *")
    public void syncPrice() throws UnirestException {
        String url = "http://op.juhe.cn/onebox/exchange/query?key=4cc2de9a6f5d95cb412d8ee34950a1db";
        //如有报错 请自行官网申请获取汇率 或者默认写死
        HttpResponse<JsonNode> resp = Unirest.get(url)
                .queryString("key", "4cc2de9a6f5d95cb412d8ee34950a1db")
                .asJson();
        log.info("forex result:{}", resp.getBody());
        JSONObject ret = JSON.parseObject(resp.getBody().toString());


        if(ret.getIntValue("resultcode") == 200) {
	        JSONArray result = ret.getJSONArray("result");
	        result.forEach(json -> {
	            JSONObject obj = (JSONObject) json;
	            if ("USDCNY".equals(obj.getString("code"))) {
	                setUsdCnyRate(new BigDecimal(obj.getDouble("price")).setScale(2, RoundingMode.DOWN));
	                log.info(obj.toString());
	            } else if ("USDJPY".equals(obj.getString("code"))) {
	                setUsdJpyRate(new BigDecimal(obj.getDouble("price")).setScale(2, RoundingMode.DOWN));
	                log.info(obj.toString());
	            }
	        });
        }
    }

    @Scheduled(cron = "0 0 0 * * *")
    public void syncCurrency() throws UnirestException {
        String url = "http://api.k780.com/?app=finance.rate&scur=USD&tcur=CNY,GBP,JPY,EUR,KRW,HKD,RUB,TWD,SGD,INR,AED,AFN,ANG,ARS,AUD,BBD,BDT,BGN,BMD,BND,BRL,BYR,CAD,CHF,CLP,COP,CUP,CZK,DKK,EGP,ETB,IDR,ILS,ISK,KES,KPW,KZT,LAK,MMK,MOP,MYR,NOK,NZD,PHP,PKR,SAR,SEK,TRY,UAH,VEF,VND,XAF,ZWL&appkey=56894&sign=f6291daaf22d9a95b07fb301c1e5dff8";
        HttpResponse<JsonNode> resp = Unirest.get(url)
                .queryString("appkey", "56894")
                .queryString("sign", "f6291daaf22d9a95b07fb301c1e5dff8")
                .asJson();

        if(resp.getStatus() == 200) {
            JSONObject ret = JSON.parseObject(resp.getBody().toString());
            if (ret.getIntValue("success") == 1) {
                JSONObject resultObj = ret.getJSONObject("result");
                JSONArray curArr = resultObj.getJSONArray("lists");
                log.info("获取世界各国汇率，共计：{} 条。", curArr.size());
                for(int i = 0; i < curArr.size(); i++) {
                    JSONObject obj = curArr.getJSONObject(i);
                    String cSymbol = obj.getString("tcur");
                    BigDecimal cRate = obj.getBigDecimal("rate");

                    currencyMap.put(cSymbol, cRate);
                }
            }
        }
    }
}
