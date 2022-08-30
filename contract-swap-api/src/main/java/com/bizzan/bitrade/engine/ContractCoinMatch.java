package com.bizzan.bitrade.engine;

import com.alibaba.fastjson.JSON;
import com.bizzan.bitrade.constant.TransactionType;
import com.bizzan.bitrade.entity.*;
import com.bizzan.bitrade.handler.MarketHandler;
import com.bizzan.bitrade.job.ExchangePushJob;
import com.bizzan.bitrade.service.ContractCoinService;
import com.bizzan.bitrade.service.ContractOrderEntrustService;
import com.bizzan.bitrade.service.MemberContractWalletService;
import com.bizzan.bitrade.service.MemberTransactionService;
import com.bizzan.bitrade.util.DateUtil;
import com.bizzan.bitrade.util.GeneratorUtil;
import com.bizzan.bitrade.util.MessageResult;
import com.mysql.cj.x.protobuf.MysqlxCrud;
import org.java_websocket.client.WebSocketClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.ParseException;
import java.util.*;

/**
 * 合约撮合引擎
 */
public class ContractCoinMatch {

    private Logger logger = LoggerFactory.getLogger(ContractCoinMatch.class);

    private String symbol;                                           // 交易对：BTC/USDT
    private String baseSymbol;                                       // 基币：USDT
    private String coinSymbol;                                       // 币种：BTC
    private CoinThumb thumb;                                         // 交易对行情
    private LinkedList<ContractTrade> lastedTradeList;               // 最新成交明细
    private int lastedTradeListSize = 50;

    private long lastUpdateTime = 0L;                                // 上次价格更新时间（主要用于控制价格刷新周期，因为websokcet获取的价格变化较快）
    private boolean isTriggerComplete = true;                        // 价格刷新是否完成，触发委托及爆仓
    private BigDecimal nowPrice = BigDecimal.ZERO;                   // 当前最新价格

    private ContractCoinService contractCoinService;                  // 合约币种服务
    private ContractOrderEntrustService contractOrderEntrustService;  // 合约委托单服务
    private MemberTransactionService memberTransactionService;
    private MemberContractWalletService memberContractWalletService;
    private ContractCoin contractCoin;

    private List<ContractOrderEntrust> contractOrderEntrustList = new ArrayList<>();      // 委托列表(计划委托)
    private List<MemberContractWallet> memberContractWalletList = new ArrayList<>();      // 用户仓位信息

    private List<MarketHandler> handlers;                             // 行情、概要等处理者
    private ExchangePushJob exchangePushJob;                          // 推送任务

    //卖盘盘口信息
    private TradePlate sellTradePlate;
    //买盘盘口信息
    private TradePlate buyTradePlate;

    private boolean isStarted = false;                                // 是否启动完成（用于初始化时，获取一些数据库未处理的订单的，如果没获取完，不允许处理）

    private LinkedList<ContractOrderEntrust> openOrderList = new LinkedList<ContractOrderEntrust>(); // 开仓订单
    private LinkedList<ContractOrderEntrust> closeOrderList = new LinkedList<ContractOrderEntrust>(); // 平仓订单

    private LinkedList<ContractOrderEntrust> openOrderSpotList = new LinkedList<ContractOrderEntrust>(); // 开仓止盈止损订单
    private LinkedList<ContractOrderEntrust> closeOrderSpotList = new LinkedList<ContractOrderEntrust>(); // 平仓止盈止损订单

    /**
     * 构造函数
     * @param symbol
     */
    public ContractCoinMatch(String symbol) {
        this.symbol = symbol;
        this.coinSymbol = symbol.split("/")[0];
        this.baseSymbol = symbol.split("/")[1];
        this.handlers = new ArrayList<>();
        this.lastedTradeList = new LinkedList<>();
        this.buyTradePlate = new TradePlate(symbol, ContractOrderDirection.BUY);
        this.sellTradePlate = new TradePlate(symbol, ContractOrderDirection.SELL);
        // 初始化行情
        this.initializeThumb();
    }

    public void trade(ContractOrderEntrust order) throws ParseException {
        if(!this.isStarted) return;
        if(!symbol.equalsIgnoreCase(order.getSymbol())){
            logger.info("unsupported symbol,coin={},base={}", order.getCoinSymbol(), order.getBaseSymbol());
            return ;
        }
        if(order.getVolume().compareTo(BigDecimal.ZERO) <=0 || order.getVolume().subtract(order.getTradedVolume()).compareTo(BigDecimal.ZERO)<=0){
            return ;
        }
        if(order.getEntrustType() == ContractOrderEntrustType.OPEN) { // 开仓
            if(order.getType() == ContractOrderType.MARKET_PRICE) { // 市价，直接成交
                this.dealOpenOrder(order);
            }else if(order.getType() == ContractOrderType.LIMIT_PRICE) { // 限价
                if(order.getDirection() == ContractOrderDirection.BUY && order.getEntrustPrice().compareTo(nowPrice) >= 0
                || order.getDirection() == ContractOrderDirection.SELL && order.getEntrustPrice().compareTo(nowPrice) <= 0){ // 出价高于当前价，直接成交
                    this.dealOpenOrder(order);
                }else{
                    // 放入列表中
                    synchronized (openOrderList) {
                        logger.info("开仓订单进入监控队列");
                        openOrderList.addLast(order);
                    }
                }
            }else if(order.getType() == ContractOrderType.SPOT_LIMIT) { // 计划委托
                synchronized (openOrderSpotList) {
                    logger.info("开仓计划委托订单进入监控队列");
                    openOrderSpotList.add(order);
                }
            }
        }else if(order.getEntrustType() == ContractOrderEntrustType.CLOSE) { // 平仓
            if(order.getType() == ContractOrderType.MARKET_PRICE) { // 市价，直接成交
                this.dealCloseOrder(order);
            }else if(order.getType() == ContractOrderType.LIMIT_PRICE) { // 限价
                if(order.getDirection() == ContractOrderDirection.BUY && order.getEntrustPrice().compareTo(nowPrice) > 0
                || order.getDirection() == ContractOrderDirection.SELL && order.getEntrustPrice().compareTo(nowPrice) < 0){ // 出价高于当前价，直接成交
                    this.dealCloseOrder(order);
                }else{
                    // 放入列表中
                    synchronized (closeOrderList) {
                        logger.info("平仓订单进入监控队列");
                        closeOrderList.addLast(order);
                    }
                }
            }else if(order.getType() == ContractOrderType.SPOT_LIMIT) { // 计划委托
                synchronized (closeOrderSpotList) {
                    logger.info("平仓计划委托订单进入监控队列");
                    closeOrderSpotList.add(order);
                }
            }
        }
    }

    /**
     * 成交开仓委托
     * @param order
     */
    public void dealOpenOrder(ContractOrderEntrust order){
        logger.info("成交开仓订单");
        MemberContractWallet memberContractWallet = memberContractWalletService.findByMemberIdAndContractCoin(order.getMemberId(), contractCoin);
        // 扣除手续费(冻结资产中扣除）
        memberContractWalletService.decreaseUsdtFrozen(memberContractWallet.getId(), order.getOpenFee());
        // 合约交易对手续费收入增加
        contractCoinService.increaseTotalOpenFee(contractCoin.getId(), order.getOpenFee());
        // 统一处理手续费
        handleFee(order.getMemberId(), order.getOpenFee());
        // 增加资产变更记录
        MemberTransaction memberTransaction = new MemberTransaction();
        memberTransaction.setFee(BigDecimal.ZERO);
        memberTransaction.setAmount(BigDecimal.ZERO.subtract(order.getOpenFee()));
        memberTransaction.setMemberId(order.getMemberId());
        memberTransaction.setSymbol(contractCoin.getSymbol().split("/")[1]);
        memberTransaction.setType(TransactionType.CONTRACT_FEE);
        memberTransaction.setCreateTime(DateUtil.getCurrentDate());
        memberTransaction.setRealFee("0");
        memberTransaction.setDiscountFee("0");
        memberTransaction = memberTransactionService.save(memberTransaction);

        // 做多：扣除保证金 到 多仓保证金账户（冻结余额也减少）
        if (order.getDirection() == ContractOrderDirection.BUY) {
            if (order.getMemberId() != 1) {
                contractCoinService.increaseTotalBuy(contractCoin.getId(), order.getVolume());
            }
            memberContractWalletService.increaseUsdtBuyPrincipalAmountWithFrozen(memberContractWallet.getId(), order.getPrincipalAmount());
        }
        // 做空：扣除保证金 到 空仓保证金账户（可用余额也减少）
        if (order.getDirection() == ContractOrderDirection.SELL) {
            if (order.getMemberId() != 1) {
                contractCoinService.increaseTotalSell(contractCoin.getId(), order.getVolume());
            }
            memberContractWalletService.increaseUsdtSellPrincipalAmountWithFrozen(memberContractWallet.getId(), order.getPrincipalAmount());
        }
        // 计算开仓价（滑点 > 市价用）
        BigDecimal openPrice = BigDecimal.ZERO;
        openPrice = nowPrice;
        if (order.getDirection() == ContractOrderDirection.BUY) { // 买入，滑点计算，做多，更高价格成交
            if (contractCoin.getSpreadType() == 1) { // 滑点类型：百分比
                logger.info("计算滑点价格（开多）：当前价（{}）, 滑点类型类型（百分比）, 滑点（{}）", nowPrice, contractCoin.getSpread());
                openPrice = nowPrice.add(nowPrice.multiply(contractCoin.getSpread())); // 已当前价成交（或滑点价成交）
            } else { // 滑点类型：固定额
                logger.info("计算滑点价格（开多）：当前价（{}）, 滑点类型类型（固定额）, 滑点（{}）", nowPrice, contractCoin.getSpread());
                openPrice = nowPrice.add(contractCoin.getSpread());
            }
        } else { // 卖出，滑点计算，做空，更低价格成交
            if (contractCoin.getSpreadType() == 1) { // 滑点类型：百分比
                logger.info("计算滑点价格（开空）：当前价（{}）, 滑点类型类型（百分比）, 滑点（{}）", nowPrice, contractCoin.getSpread());
                openPrice = nowPrice.subtract(nowPrice.multiply(contractCoin.getSpread())); // 已当前价成交（或滑点价成交）
            } else { // 滑点类型：固定额
                logger.info("计算滑点价格（开空）：当前价（{}）, 滑点类型类型（固定额）, 滑点（{}）", nowPrice, contractCoin.getSpread());
                openPrice = nowPrice.subtract(contractCoin.getSpread());
            }
        }

        // 计算平均开仓价  更新持仓均价
        // （当前持仓张数 * 持仓均价 + 委托张数 * 成交价格） / （当前持仓张数 + 委托张数）
        BigDecimal avaPrice = BigDecimal.ZERO;
        if (order.getDirection() == ContractOrderDirection.BUY) {
            logger.info("计算开仓均价（开多）：当前持仓张数（{}）,当前持仓均价（{}）, 委托张数（{}），成交价格价格（{}） 代币数量({})", memberContractWallet.getUsdtBuyPosition(), memberContractWallet.getUsdtBuyPrice(), order.getVolume(), openPrice, order.getQuantity());
            avaPrice = memberContractWallet.getUsdtBuyPosition().multiply(memberContractWallet.getUsdtBuyPrice()).add(order.getVolume().multiply(openPrice)).divide(memberContractWallet.getUsdtBuyPosition().add(order.getVolume()), 8, BigDecimal.ROUND_DOWN);
            // 更新持仓均价和持仓数量（多仓）
            memberContractWalletService.updateUsdtBuyPriceAndPosition(memberContractWallet.getId(), avaPrice, order.getVolume(), order.getQuantity());
        } else {
            logger.info("计算开仓均价（开多）：当前持仓张数（{}）,当前持仓均价（{}）, 委托张数（{}），成交价格价格（{}） 代币数量({})", memberContractWallet.getUsdtSellPosition(), memberContractWallet.getUsdtSellPrice(), order.getVolume(), openPrice, order.getQuantity());
            avaPrice = memberContractWallet.getUsdtSellPosition().multiply(memberContractWallet.getUsdtSellPrice()).add(order.getVolume().multiply(openPrice)).divide(memberContractWallet.getUsdtSellPosition().add(order.getVolume()), 8, BigDecimal.ROUND_DOWN);
            // 更新持仓均价和持仓数量（空仓）
            memberContractWalletService.updateUsdtSellPriceAndPosition(memberContractWallet.getId(), avaPrice, order.getVolume(), order.getQuantity());
        }

        if(memberContractWallet.getUsdtShareNumber().compareTo(order.getShareNumber()) != 0) {
            memberContractWalletService.updateShareNumber(memberContractWallet.getId(), order.getShareNumber());
        }
        order.setStatus(ContractOrderEntrustStatus.ENTRUST_SUCCESS); // 委托状态：已成交
        order.setTradedVolume(order.getVolume()); // 设置已交易数量
        order.setTradedPrice(openPrice);
        ContractOrderEntrust entrusyObj = contractOrderEntrustService.save(order);

        // 同步最新用户持仓数据
        memberWalletChange(memberContractWallet.getId());
        //syncMemberPosition();
    }

    /**
     * 成交平仓委托
     * @param order
     */
    public void dealCloseOrder(ContractOrderEntrust order) {
        logger.info("成交平仓委托");
        MemberContractWallet memberContractWallet = memberContractWalletService.findByMemberIdAndContractCoin(order.getMemberId(), contractCoin);
        // 计算滑点成交价（市价下单时用此价格）
        BigDecimal dealPrice = nowPrice;
        if (order.getDirection() == ContractOrderDirection.BUY) { // 买入平空，滑点计算，更低价格
            if (order.getMemberId() != 1) {
                contractCoinService.increaseTotalSell(contractCoin.getId(), order.getVolume().negate());
            }

            if (contractCoin.getSpreadType() == 1) { // 滑点类型：百分比
                dealPrice = nowPrice.add(nowPrice.multiply(contractCoin.getSpread())); // 已当前价成交（或滑点价成交）
            } else { // 滑点类型：固定额
                dealPrice = nowPrice.add(contractCoin.getSpread());
            }
        } else { // 卖出，滑点计算，做空，更低价格成交
            if (order.getMemberId() != 1) {
                contractCoinService.increaseTotalBuy(contractCoin.getId(), order.getVolume().negate());
            }

            if (contractCoin.getSpreadType() == 1) { // 滑点类型：百分比
                dealPrice = nowPrice.subtract(nowPrice.multiply(contractCoin.getSpread())); // 已当前价成交（或滑点价成交）
            } else { // 滑点类型：固定额
                dealPrice = nowPrice.subtract(contractCoin.getSpread());
            }
        }

        if (order.getDirection() == ContractOrderDirection.BUY) { // 买入平空
            // 平空 - 空仓收益计算方法：（1 - 当前价格 / 开仓均价）* （可用仓位 + 冻结仓位） * 合约面值
            // 计算盈亏
            BigDecimal pL = BigDecimal.ONE.subtract(dealPrice.divide(memberContractWallet.getUsdtSellPrice(), 8, BigDecimal.ROUND_DOWN)).multiply(order.getVolume()).multiply(memberContractWallet.getUsdtShareNumber());
            // 计算保证金该扣除多少(平仓数量 / 总持仓 * 保证金总量)
            BigDecimal principalAmount = order.getPrincipalAmount();
            // 计算平仓手续费
            BigDecimal closeFee = order.getVolume().multiply(memberContractWallet.getUsdtShareNumber()).multiply(contractCoin.getCloseFee());

            // 扣除用户空仓持仓冻结和相应的保证金
            memberContractWalletService.decreaseUsdtFrozenSellPositionAndPrincipalAmount(memberContractWallet.getId(), order.getVolume(), principalAmount, order.getQuantity());
            // 增加用户余额
            memberContractWalletService.increaseUsdtBalance(memberContractWallet.getId(), principalAmount.add(pL).subtract(closeFee));
            // 平台增加平仓手续费
            contractCoinService.increaseTotalCloseFee(contractCoin.getId(), closeFee);
            // 统一处理手续费
            handleFee(memberContractWallet.getMemberId(), closeFee);
            // 委托单设置状态
            order.setStatus(ContractOrderEntrustStatus.ENTRUST_SUCCESS);
            order.setTradedVolume(order.getVolume());
            order.setTradedPrice(dealPrice);
            order.setProfitAndLoss(pL);
            ContractOrderEntrust entrusyObj = contractOrderEntrustService.save(order);

            // 更新合约交易对平台盈亏 / 更新用户账户盈亏
            if (pL.compareTo(BigDecimal.ZERO) > 0){
                memberContractWalletService.increaseUsdtProfit(memberContractWallet.getId(), pL);
                contractCoinService.increaseTotalLoss(contractCoin.getId(), pL); // 用户赚钱，则平台亏损
            }
            if (pL.compareTo(BigDecimal.ZERO) < 0){
               memberContractWalletService.increaseUsdtLoss(memberContractWallet.getId(), BigDecimal.ZERO.subtract(pL));
               contractCoinService.increaseTotalProfit(contractCoin.getId(), BigDecimal.ZERO.subtract(pL)); // 用户亏损，则平台盈利
            }

            // 统一处理用户盈亏
            handlePl(memberContractWallet.getMemberId(), pL);

        } else { // 卖出平多
            // 平多 - 多仓收益计算方法：（当前价格 / 开仓均价 - 1）* （可用仓位 + 冻结仓位） * 合约面值
            // 计算盈亏
            BigDecimal pL = dealPrice.divide(memberContractWallet.getUsdtBuyPrice(), 8, BigDecimal.ROUND_DOWN).subtract(BigDecimal.ONE).multiply(order.getVolume()).multiply(memberContractWallet.getUsdtShareNumber());
            // 计算保证金该扣除多少(平仓数量 / 总持仓 * 保证金总量)
            BigDecimal principalAmount = order.getPrincipalAmount();
            // 计算平仓手续费
            BigDecimal closeFee = order.getVolume().multiply(memberContractWallet.getUsdtShareNumber()).multiply(contractCoin.getCloseFee());

            // 扣除用户多仓持仓 和 保证金
            memberContractWalletService.decreaseUsdtFrozenBuyPositionAndPrincipalAmount(memberContractWallet.getId(), order.getVolume(), principalAmount, order.getQuantity());
            // 增加用户余额
            memberContractWalletService.increaseUsdtBalance(memberContractWallet.getId(), principalAmount.add(pL).subtract(closeFee));
            // 平台增加平仓手续费
            contractCoinService.increaseTotalCloseFee(contractCoin.getId(), closeFee);
            // 统一处理手续费
            handleFee(memberContractWallet.getMemberId(), closeFee);

            if(memberContractWallet.getUsdtShareNumber().compareTo(order.getShareNumber()) != 0) {
                memberContractWalletService.updateShareNumber(memberContractWallet.getId(), order.getShareNumber());
            }

            // 委托单设置状态
            order.setStatus(ContractOrderEntrustStatus.ENTRUST_SUCCESS);
            order.setTradedVolume(order.getVolume());
            order.setTradedPrice(dealPrice);
            order.setProfitAndLoss(pL);
            ContractOrderEntrust entrusyObj = contractOrderEntrustService.save(order);

            // 更新合约交易对平台盈亏 / 更新用户账户盈亏
            if (pL.compareTo(BigDecimal.ZERO) > 0){
                memberContractWalletService.increaseUsdtProfit(memberContractWallet.getId(), pL);
                contractCoinService.increaseTotalLoss(contractCoin.getId(), pL); // 用户赚钱，则平台亏损
            }
            if (pL.compareTo(BigDecimal.ZERO) < 0){
                memberContractWalletService.increaseUsdtLoss(memberContractWallet.getId(), BigDecimal.ZERO.subtract(pL));
                contractCoinService.increaseTotalProfit(contractCoin.getId(), BigDecimal.ZERO.subtract(pL)); // 用户亏损，则平台盈利
            }

            // 统一处理用户盈亏
            handlePl(memberContractWallet.getMemberId(), pL);
        }

        // 同步最新用户持仓数据
        memberWalletChange(memberContractWallet.getId());
        // 同步最新用户持仓数据
        // syncMemberPosition();
    }

    /**
     * 启动引擎，加载未处理订单
     */
    public void run(){
        logger.info(symbol + " 合约引擎启动，加载数据库订单....");
        contractCoin = contractCoinService.findBySymbol(symbol);
        if(contractCoin == null) {
            logger.info(contractCoin.getSymbol() + "引擎启动失败，找不到合约交易对");
            return;
        }
        // 数据库查找订单，加载到列表中
        contractOrderEntrustList = contractOrderEntrustService.loadUnMatchOrders(contractCoin.getId());
        if(contractOrderEntrustList != null && contractOrderEntrustList.size() > 0) {
            logger.info(contractCoin.getSymbol() + "加载订单，共计 " + contractOrderEntrustList.size());
            for (ContractOrderEntrust item : contractOrderEntrustList) {
                if (item.getEntrustType() == ContractOrderEntrustType.OPEN) { // 开仓单
                    if (item.getType() == ContractOrderType.SPOT_LIMIT) { // 计划委托单（止盈止损单）
                        openOrderSpotList.add(item);
                    } else {
                        openOrderList.add(item);
                    }
                } else { // 平仓单
                    if (item.getType() == ContractOrderType.SPOT_LIMIT) { // 计划委托单（止盈止损单）
                        closeOrderSpotList.add(item);
                    } else {
                        closeOrderList.add(item);
                    }
                }
            }
        }
        // 加载用户持仓信息
        this.syncMemberPosition();
        this.isStarted = true;
    }

    /**
     * 更新盘口（买卖盘，火币Websocket获取到的是20条）
     * @param buyPlateItems
     * @param sellPlateItems
     */
    public void refreshPlate(List<TradePlateItem> buyPlateItems, List<TradePlateItem> sellPlateItems) {
        if(!this.isStarted) return;

        this.buyTradePlate.setItems(buyPlateItems);
        this.sellTradePlate.setItems(sellPlateItems);

        this.exchangePushJob.addPlates(symbol, sellTradePlate);
        this.exchangePushJob.addPlates(symbol, buyTradePlate);

//        logger.info("{} 盘口刷新：买盘大小-{}, 卖盘大小-{}", symbol, buyPlateItems.size(), sellPlateItems.size());
    }

    /**
     * 更新行情
     * @param thumb
     */
    public void refreshThumb(CoinThumb thumb) {
        if(!this.isStarted) return;

        this.thumb.setHigh(thumb.getHigh());
        this.thumb.setLow(thumb.getLow());
        this.thumb.setOpen(thumb.getClose());
        this.thumb.setClose(thumb.getClose());
        this.thumb.setTurnover(thumb.getTurnover());
        this.thumb.setVolume(thumb.getVolume());
        this.thumb.setUsdRate(thumb.getClose());
        // 计算变化（变化金额以及变化比例，其中变化比例会出现负数）
        this.thumb.setChange(thumb.getClose().subtract(thumb.getOpen()));
        if(thumb.getOpen().compareTo(BigDecimal.ZERO) > 0) {
            this.thumb.setChg(this.thumb.getChange().divide(this.thumb.getOpen(), 4, RoundingMode.UP));
        }

//        logger.info("{} 行情刷新：Hight-{}, Low-{}, Open-{}, Close-{}", symbol, thumb.getHigh(), thumb.getLow(), thumb.getOpen(), thumb.getClose());
        // 推送行情
        handleCoinThumb();
    }

    /**
     * 更新价格
     * 更新价格时，涉及到计划委托、止盈止损检测、爆仓检查，有一定耗时操作
     * @param newPrice
     */
    public void refreshPrice(BigDecimal newPrice, ContractCoinMatchFactory matchFactory) {
        // 尚未启动
        if(!this.isStarted) return;

        // 上一次任务尚未完成
        if(!isTriggerComplete) {
            return;
        }
        long currentTime = Calendar.getInstance().getTimeInMillis();
        // 控制1秒+更新一次
        if(currentTime - lastUpdateTime > 100) {
            lastUpdateTime = currentTime;

            synchronized (this.nowPrice) {
                // 价格未发生变化，无需继续操作
                if (this.nowPrice.compareTo(newPrice) == 0) {
                    return;
                }
                this.nowPrice = newPrice;
            }
            // 开始检查委托
            isTriggerComplete = false;
            this.process(newPrice, matchFactory);
        }
    }

    /**
     * 更新成交明细
     * @param tradeArrayList
     */
    public void refreshLastedTrade(List<ContractTrade> tradeArrayList) {
        synchronized (lastedTradeList) {
            for (ContractTrade trade : tradeArrayList) {
                if (lastedTradeList.size() > lastedTradeListSize) {
                    this.lastedTradeList.removeLast();
                    this.lastedTradeList.addFirst(trade);
                } else {
                    this.lastedTradeList.addFirst(trade);
                }
            }
//        logger.info("{} 盘面更新：大小-{}", symbol, tradeArrayList.size());
            // 添加成交明细
            this.exchangePushJob.addTrades(symbol, tradeArrayList);
        }
    }

    /**
     * 处理委托单
     * @param newPrice
     */
    public void process(BigDecimal newPrice, ContractCoinMatchFactory matchFactory) {
        long startTick = System.currentTimeMillis();
        this.processBlastCheck(newPrice, matchFactory);              // 1、爆仓处理
        this.processOpenSpotEntrustList(newPrice);     // 2、开仓计划委托处理
        this.processCloseSpotEntrustList(newPrice);    // 3、平仓计划委托处理
        this.processCloseEntrustList(newPrice);        // 4、平仓委托处理
        this.processOpenEntrustList(newPrice);         // 5、开仓委托处理
        this.isTriggerComplete = true;
        logger.info("委托处理耗时：{}", System.currentTimeMillis() - startTick);
    }

    /**
     * 处理开仓限价委托订单
     * @param newPrice
     */
    public void processOpenEntrustList(BigDecimal newPrice) {
        synchronized (openOrderList) {
            Iterator<ContractOrderEntrust> orderIterator = openOrderList.iterator();
            while ((orderIterator.hasNext())) {
                ContractOrderEntrust order = orderIterator.next();
                if(order.getDirection() == ContractOrderDirection.BUY) { // 多单
                    if(order.getEntrustPrice().compareTo(newPrice) >= 0) {
                        logger.info("[买入开多]触发成交开仓多单委托订单, 实时价/委托价：" + newPrice + "/" + order.getEntrustPrice() + ", Order: " + JSON.toJSONString(order));
                        dealOpenOrder(order);
                        orderIterator.remove();
                    }
                }else{ // 空单
                    if(order.getEntrustPrice().compareTo(newPrice) <= 0) {
                        logger.info("[卖出开空]触发成交开仓空单委托订单, 实时价/委托价：" + newPrice + "/" + order.getEntrustPrice() + ", Order: " + JSON.toJSONString(order));
                        dealOpenOrder(order);
                        orderIterator.remove();
                    }
                }
            }
        }
    }

    /**
     * 处理平仓限价委托订单
     * @param newPrice
     */
    public void processCloseEntrustList(BigDecimal newPrice) {
        synchronized (closeOrderList) {
            Iterator<ContractOrderEntrust> orderIterator = closeOrderList.iterator();
            while ((orderIterator.hasNext())) {
                ContractOrderEntrust order = orderIterator.next();
                if(order.getDirection() == ContractOrderDirection.BUY){
                    if(order.getEntrustPrice().compareTo(newPrice) >= 0) {
                        logger.info("[买入平空]触发成交平仓空单委托订单, 实时价/委托价：" + newPrice + "/" + order.getEntrustPrice() + ", Order: " + JSON.toJSONString(order));
                        dealCloseOrder(order);
                        orderIterator.remove();
                    }
                }else{
                    if(order.getEntrustPrice().compareTo(newPrice) <= 0) {
                        logger.info("[卖出平多]触发成交平仓空单委托订单, 实时价/委托价：" + newPrice + "/" + order.getEntrustPrice() + ", Order: " + JSON.toJSONString(order));
                        dealCloseOrder(order);
                        orderIterator.remove();
                    }
                }
            }
        }
    }

    /**
     * 处理计划委托开仓委托订单
     * @param newPrice
     */
    public void processOpenSpotEntrustList(BigDecimal newPrice) {
        synchronized (openOrderSpotList) {
            Iterator<ContractOrderEntrust> orderIterator = openOrderSpotList.iterator();
            while ((orderIterator.hasNext())) {
                ContractOrderEntrust order = orderIterator.next();
                // 这里分为两种可能性计划委托
                // 1、用户委托时，委托的触发价格大于当时的价格，到现在这个时候，触发价格小于行情价，说明价格涨到触发价，该触发了 （开多时相当于止盈，开空时相当于止损）
                // 2、用户委托时，委托的触发价格小于当时的价格，到现在这个时候，触发价格大于行情价，说明价格跌到触发价，该触发了 （开多时相当于止损，开空时相当于止盈）
                if((order.getTriggerPrice().compareTo(order.getCurrentPrice()) >= 0 && order.getTriggerPrice().compareTo(newPrice) <= 0)
                || (order.getTriggerPrice().compareTo(order.getCurrentPrice()) <= 0 && order.getTriggerPrice().compareTo(newPrice) >= 0)) {

                    MemberContractWallet wallet = memberContractWalletService.findByMemberIdAndContractCoin(order.getMemberId(), contractCoin);
                    BigDecimal leverage = order.getDirection() == ContractOrderDirection.BUY ? wallet.getUsdtBuyLeverage() : wallet.getUsdtSellLeverage();
                    // 检查保证金是否足够开单
                    // 0、计算当前开仓订单所需保证金
                    // 合约张数 * 合约面值 / 杠杆倍数 （该计算方式适合于金本位，即USDT作为保证金模式）
                    BigDecimal principalAmount = order.getVolume().multiply(contractCoin.getShareNumber().divide(leverage, 8, BigDecimal.ROUND_HALF_DOWN));

                    // 1、计算开仓手续费(合约张数 * 合约面值 * 开仓费率）
                    BigDecimal openFee = order.getVolume().multiply(contractCoin.getShareNumber()).multiply(contractCoin.getOpenFee());

                    // 当前账户为逐仓模式时，只需比较可用余额
                    if (wallet.getUsdtPattern() == ContractOrderPattern.FIXED) {
                        if (principalAmount.add(openFee).compareTo(wallet.getUsdtBalance()) > 0) {
                            logger.info("计划委托失败，余额不足：" + order.getMemberId() + " - " + order.getId() + " - " + order.getContractOrderEntrustId());
                            continue;
                        }
                    }
                    // 全仓模式，需要计算空仓和多仓总权益
                    if (wallet.getUsdtPattern() == ContractOrderPattern.CROSSED) {
                        // 计算金本位权益（多仓 + 空仓）
                        BigDecimal usdtTotalProfitAndLoss = BigDecimal.ZERO;
                        // 多仓计算方法：（当前价格 / 开仓均价 - 1）* （可用仓位 + 冻结仓位） * 合约面值
                        if (wallet.getUsdtBuyPrice().compareTo(BigDecimal.ZERO) > 0 && wallet.getUsdtBuyPosition().compareTo(BigDecimal.ZERO) > 0) {
                            usdtTotalProfitAndLoss = usdtTotalProfitAndLoss.add(newPrice.divide(wallet.getUsdtBuyPrice(), 8, BigDecimal.ROUND_HALF_DOWN).subtract(BigDecimal.ONE).multiply(wallet.getUsdtBuyPosition().add(wallet.getUsdtFrozenBuyPosition())).multiply(wallet.getUsdtShareNumber()));
                        }

                        // 空仓计算方法：（1 - 当前价格 / 开仓均价）* （可用仓位 + 冻结仓位） * 合约面值
                        if (wallet.getUsdtSellPrice().compareTo(BigDecimal.ZERO) > 0 && wallet.getUsdtSellPosition().compareTo(BigDecimal.ZERO) > 0) {
                            usdtTotalProfitAndLoss = usdtTotalProfitAndLoss.add(BigDecimal.ONE.subtract(newPrice.divide(wallet.getUsdtSellPrice(), 8, BigDecimal.ROUND_HALF_DOWN)).multiply(wallet.getUsdtSellPosition().add(wallet.getUsdtFrozenSellPosition())).multiply(wallet.getUsdtShareNumber()));
                        }

                        // 加上仓位保证金
                        usdtTotalProfitAndLoss = usdtTotalProfitAndLoss.add(wallet.getUsdtBuyPrincipalAmount());
                        // 经过上面的计算，可能会得到一个正值，也可能得到一个负值，如果是负值，因为是全仓模式，就需要用余额减去该数值，然后计算余额是否足够
                        if (usdtTotalProfitAndLoss.compareTo(BigDecimal.ZERO) < 0) {
                            if (principalAmount.add(openFee).compareTo(wallet.getUsdtBalance().add(usdtTotalProfitAndLoss)) > 0) {
                                logger.info("计划委托失败：" + order.getMemberId() + " - " + order.getId() + " - " + order.getContractOrderEntrustId());
                                continue;
                            }
                        } else { // 如果持仓权益是正值，则直接跟可用余额比较即可
                            if (principalAmount.add(openFee).compareTo(wallet.getUsdtBalance()) > 0) {
                                logger.info("计划委托失败：" + order.getMemberId() + " - " + order.getId() + " - " + order.getContractOrderEntrustId());
                                continue;
                            }
                        }
                    }
                    // 触发委托
                    if(order.getEntrustPrice().compareTo(BigDecimal.ZERO) == 0) { // 市价委托
                        // 更改委托单类型
                        order.setType(ContractOrderType.MARKET_PRICE);
                    }else{ // 限价委托
                        // 更改委托单类型
                        order.setType(ContractOrderType.LIMIT_PRICE);
                    }
                    contractOrderEntrustService.save(order);
                    memberContractWalletService.freezeUsdtBalance(wallet, principalAmount.add(openFee));
                    try {
                        this.trade(order);
                        orderIterator.remove();
                    } catch (ParseException e) {
                        logger.info("计划委托失败，请检查交易引擎");
                        e.printStackTrace();
                    }
                }
            }
        }
    }

    /**
     * 处理计划委托平仓委托订单
     * @param newPrice
     */
    public void processCloseSpotEntrustList(BigDecimal newPrice) {
        logger.info("计划委托平仓处理开始：{} {}", symbol, newPrice);
        synchronized (closeOrderSpotList) {
            Iterator<ContractOrderEntrust> orderIterator = closeOrderSpotList.iterator();

            logger.info("计划委托平仓处理订单列表, size: {}", closeOrderSpotList.size());
            while ((orderIterator.hasNext())) {
                ContractOrderEntrust order = orderIterator.next();

                MemberContractWallet wallet = memberContractWalletService.findByMemberIdAndContractCoin(order.getMemberId(), contractCoin);
                if (order.getDirection() == ContractOrderDirection.BUY) {
                   if (wallet.getUsdtSellPosition().compareTo(order.getVolume()) < 0) {
                       orderIterator.remove();
                       order.setStatus(ContractOrderEntrustStatus.ENTRUST_CANCEL);
                       contractOrderEntrustService.save(order);
                       continue;
                    }
                } else {
                    if (wallet.getUsdtBuyPosition().compareTo(order.getVolume()) < 0) {
                        orderIterator.remove();
                        order.setStatus(ContractOrderEntrustStatus.ENTRUST_CANCEL);
                        contractOrderEntrustService.save(order);
                        continue;
                    }
                }

                // 这里分为两种可能性计划委托
                // 1、用户委托时，委托的触发价格大于当时的价格，到现在这个时候，触发价格小于行情价，说明价格涨到触发价，该触发了 （买入平空时相当于止损，卖出平多时相当于止盈）
                // 2、用户委托时，委托的触发价格小于当时的价格，到现在这个时候，触发价格大于行情价，说明价格跌到触发价，该触发了 （买入平空时相当于止盈，卖出平多时相当于止损）
                logger.info("计划委托平仓判断处理. {} - {} - {}", order.getTriggerPrice(), order.getCurrentPrice(), newPrice);
                if ((order.getTriggerPrice().compareTo(order.getCurrentPrice()) >= 0 && order.getTriggerPrice().compareTo(newPrice) <= 0)
                 || (order.getTriggerPrice().compareTo(order.getCurrentPrice()) <= 0 && order.getTriggerPrice().compareTo(newPrice) >= 0)) {
                    logger.info("触发计划：计划委托平仓处理. {} - {} - {}", order.getTriggerPrice(), order.getCurrentPrice(), newPrice);
                    // MemberContractWallet wallet = memberContractWalletService.findByMemberIdAndContractCoin(order.getMemberId(), contractCoin);
                    // 触发委托
                    if (order.getDirection() == ContractOrderDirection.BUY) { // 买入平空，检查空单持仓量是否足够
                        // 检查空单持仓量是否足够
                        if(wallet.getUsdtSellPosition().compareTo(order.getVolume()) < 0) {
                            logger.info("计划委托失败,空单持仓不足：" + order.getMemberId() + " - " + order.getId() + " - " + order.getContractOrderEntrustId());
                            continue;
                        }else{
                            // 冻结空仓持仓
                            memberContractWalletService.freezeUsdtSellPosition(wallet.getId(), order.getVolume(), order.getQuantity());
                        }
                    } else { // 卖出平多
                        // 检查多单持仓量是否足够
                        if(wallet.getUsdtBuyPosition().compareTo(order.getVolume()) < 0) {
                            logger.info("计划委托失败,多单持仓不足：" + order.getMemberId() + " - " + order.getId() + " - " + order.getContractOrderEntrustId());
                            continue;
                        }else{
                            // 冻结空仓持仓
                            memberContractWalletService.freezeUsdtBuyPosition(wallet.getId(), order.getVolume(), order.getQuantity());
                        }
                    }
                    // 触发委托
                    if(order.getEntrustPrice().compareTo(BigDecimal.ZERO) == 0) { // 市价委托
                        order.setType(ContractOrderType.MARKET_PRICE);
                    }else{
                        order.setType(ContractOrderType.LIMIT_PRICE);
                    }
                    //计算平仓应该扣除多少保证金(平仓量/（可平仓量+冻结平仓量） *  保证金总量）
                        if (order.getDirection() == ContractOrderDirection.BUY) { // 买入平空
                            BigDecimal mPrinc = order.getVolume().divide(wallet.getUsdtSellPosition().add(wallet.getUsdtFrozenSellPosition()), 8, RoundingMode.HALF_UP).multiply(wallet.getUsdtSellPrincipalAmount());
                            order.setPrincipalAmount(mPrinc);
                        } else {
                            BigDecimal mPrinc = order.getVolume().divide(wallet.getUsdtBuyPosition().add(wallet.getUsdtFrozenBuyPosition()), 8, RoundingMode.HALF_UP).multiply(wallet.getUsdtBuyPrincipalAmount());
                            order.setPrincipalAmount(mPrinc);
                        }
                    contractOrderEntrustService.save(order);
                    try {
                        this.trade(order);
                        orderIterator.remove();
                    } catch (ParseException e) {
                        logger.info("计划委托失败，请检查交易引擎");
                        e.printStackTrace();
                    }
                }
            }
        }
    }

    /**
     * 同步仓位信息
     */
    public void syncMemberPosition() {
        // 同步最新用户仓位
        synchronized (memberContractWalletList) {
            memberContractWalletList = memberContractWalletService.findAllNeedSync(contractCoin);
            logger.info(contractCoin.getSymbol() + "=====>同步最新用户仓位信息, size: " + memberContractWalletList.size());
        }
    }

    /**
     * 爆仓检查
     */
    public void processBlastCheck(BigDecimal newPrice, ContractCoinMatchFactory matchFactory) {
        // 同步最新用户仓位
        synchronized (memberContractWalletList){
            Iterator<MemberContractWallet> walletIterator = memberContractWalletList.iterator();
            while(walletIterator.hasNext()) {
                MemberContractWallet wallet = walletIterator.next();
                if(wallet.getUsdtPattern() == ContractOrderPattern.FIXED) { // 逐仓
                    // 计算当前价格是否爆仓 - 多单计算
                    if(wallet.getUsdtBuyPrice().compareTo(BigDecimal.ZERO) > 0) {
                        BigDecimal buyPL = newPrice.divide(wallet.getUsdtBuyPrice(), 8, BigDecimal.ROUND_DOWN).subtract(BigDecimal.ONE).multiply(wallet.getUsdtBuyPosition().add(wallet.getUsdtFrozenBuyPosition())).multiply(wallet.getUsdtShareNumber());
                        // 保证金率低于最低保证金率，则爆仓
                        if (buyPL.compareTo(BigDecimal.ZERO) < 0) {
                            // 计算保证金率
                            // （合约收益 + 保证金数量 - 平仓手续费）/ （（可用持仓 + 冻结持仓）* 合约面值） < coin.maintenanceMarginRate
                            BigDecimal minNeedPrinc = wallet.getUsdtBuyPosition().add(wallet.getUsdtFrozenBuyPosition()).multiply(wallet.getUsdtShareNumber()).divide(wallet.getUsdtBuyLeverage(), 8, BigDecimal.ROUND_DOWN);
                            BigDecimal closeFee = wallet.getUsdtBuyPosition().add(wallet.getUsdtFrozenBuyPosition()).multiply(wallet.getUsdtShareNumber()).multiply(contractCoin.getCloseFee());

                            // 当前保证金率 = （合约收益 + 保证金 - 平仓手续费） / 合约最低需要保证金
                            BigDecimal curRate = buyPL.add(wallet.getUsdtBuyPrincipalAmount()).subtract(closeFee).divide(minNeedPrinc, 8, BigDecimal.ROUND_DOWN);
                            if (curRate.compareTo(contractCoin.getMaintenanceMarginRate()) <= 0) {
                                // 爆多单处理
                                blastBuy(wallet, newPrice);
                                // 更新一下用户钱包
                                MemberContractWallet queryWallet = memberContractWalletService.findOne(wallet.getId());
                                memberContractWalletList.set(memberContractWalletList.indexOf(wallet), queryWallet);
                            }
                        }
                    }

                    // 计算当前价格是否爆仓 - 空单计算
                    if(wallet.getUsdtSellPrice().compareTo(BigDecimal.ZERO) > 0) {
                        BigDecimal sellPL = BigDecimal.ONE.subtract(newPrice.divide(wallet.getUsdtSellPrice(), 8, BigDecimal.ROUND_DOWN)).multiply(wallet.getUsdtSellPosition().add(wallet.getUsdtFrozenSellPosition())).multiply(wallet.getUsdtShareNumber());
                        // 保证金率低于最低保证金率，则爆仓
                        if (sellPL.compareTo(BigDecimal.ZERO) < 0) {
                            // 计算保证金率
                            // （合约收益 + 保证金数量）/ （（可用持仓 + 冻结持仓）* 合约面值） < coin.maintenanceMarginRate
                            // 需要的保证金 = （可用空仓位 + 冻结空仓位 ）* 合约面值 / 杠杆倍数
                            BigDecimal minNeedPrinc = wallet.getUsdtSellPosition().add(wallet.getUsdtFrozenSellPosition()).multiply(wallet.getUsdtShareNumber()).divide(wallet.getUsdtSellLeverage(), 8, BigDecimal.ROUND_DOWN);
                            BigDecimal closeFee = wallet.getUsdtSellPosition().add(wallet.getUsdtFrozenSellPosition()).multiply(wallet.getUsdtShareNumber()).multiply(contractCoin.getCloseFee());
                            // 当前保证金率 = （合约收益 + 保证金 - 平仓手续费） / 合约最低需要保证金
                            BigDecimal curRate = sellPL.add(wallet.getUsdtSellPrincipalAmount()).subtract(closeFee).divide(minNeedPrinc, 8, BigDecimal.ROUND_DOWN);
                            if (curRate.compareTo(contractCoin.getMaintenanceMarginRate()) <= 0) {
                                // 爆空单处理
                                blastSell(wallet, newPrice);
                                // 更新一下用户钱包
                                MemberContractWallet queryWallet = memberContractWalletService.findOne(wallet.getId());
                                memberContractWalletList.set(memberContractWalletList.indexOf(wallet), queryWallet);
                            }
                        }
                    }
                }else{ // 全仓
                    BigDecimal allBuyPL = BigDecimal.ZERO;
                    BigDecimal allSellPL = BigDecimal.ZERO;
                    BigDecimal allBuyPositionValue = BigDecimal.ZERO;
                    BigDecimal allSellPositionValue = BigDecimal.ZERO;
                    BigDecimal allBuyPrincipalAmount = BigDecimal.ZERO;
                    BigDecimal allSellPrincipalAmount = BigDecimal.ZERO;
                    BigDecimal allBuyCloseFee = BigDecimal.ZERO;
                    BigDecimal allSellCloseFee = BigDecimal.ZERO;

                   List<MemberContractWallet> items = memberContractWalletService.findAllByMemberId(wallet.getMemberId());
                   for (MemberContractWallet item:items) {
                       ContractCoinMatch match = matchFactory.getContractCoinMatch(item.getContractCoin().getSymbol());
                       if (match == null) {
                           continue;
                       }
                       BigDecimal nowPrice = match.getNowPrice();
                       // 计算多单收益
                       BigDecimal buyPL = BigDecimal.ZERO;
                       if(item.getUsdtBuyPrice().compareTo(BigDecimal.ZERO) > 0){
                           buyPL = nowPrice.divide(item.getUsdtBuyPrice(), 8, BigDecimal.ROUND_DOWN).subtract(BigDecimal.ONE).multiply(item.getUsdtBuyPosition().add(item.getUsdtFrozenBuyPosition())).multiply(item.getUsdtShareNumber());
                           allBuyPL = allBuyPL.add(buyPL);
                       }
                       // 计算空单收益
                       BigDecimal sellPL = BigDecimal.ZERO;
                       if(item.getUsdtSellPrice().compareTo(BigDecimal.ZERO) > 0){
                           sellPL = BigDecimal.ONE.subtract(nowPrice.divide(item.getUsdtSellPrice(), 8, BigDecimal.ROUND_DOWN)).multiply(item.getUsdtSellPosition().add(item.getUsdtFrozenSellPosition())).multiply(item.getUsdtShareNumber());
                           allSellPL = allSellPL.add(sellPL);
                       }

                       BigDecimal buyPositionValue = item.getUsdtBuyPosition().add(item.getUsdtFrozenBuyPosition()).multiply(item.getUsdtShareNumber()).divide(item.getUsdtBuyLeverage(), 8, BigDecimal.ROUND_DOWN);
                       BigDecimal sellPositionValue = item.getUsdtSellPosition().add(item.getUsdtFrozenSellPosition()).multiply(item.getUsdtShareNumber()).divide(item.getUsdtSellLeverage(), 8, BigDecimal.ROUND_DOWN);

                       BigDecimal buyCloseFee = item.getUsdtBuyPosition().add(item.getUsdtFrozenBuyPosition()).multiply(item.getUsdtShareNumber()).multiply(contractCoin.getCloseFee());
                       BigDecimal sellCloseFee = item.getUsdtSellPosition().add(item.getUsdtFrozenSellPosition()).multiply(item.getUsdtShareNumber()).multiply(contractCoin.getCloseFee());

                       allBuyPositionValue = allBuyPositionValue.add(buyPositionValue);
                       allSellPositionValue = allSellPositionValue.add(sellPositionValue);
                       allBuyCloseFee = allBuyCloseFee.add(buyCloseFee);
                       allSellCloseFee = allSellCloseFee.add(sellCloseFee);
                       allBuyPrincipalAmount = allBuyPrincipalAmount.add(item.getUsdtBuyPrincipalAmount());
                       allSellPrincipalAmount = allSellPrincipalAmount.add(item.getUsdtSellPrincipalAmount());
                   }

                    // 计算当前价格是否爆仓
                    // 多单与空单收益之和
                    // 多空总收益为负数，并且多仓保证金 + 空仓保证金 + USDT余额都不足以支付亏损时爆仓
                    if(allBuyPL.add(allSellPL).compareTo(BigDecimal.ZERO) < 0) {
                        // 全仓保证金率 （多单收益 + 空单收益 + 多单保证金 + 空单保证金 + 账户可用USDT余额 + 账户冻结USDT金额 - 平多手续费 - 平空手续费）/ （多单最低保证金 + 空单最低保证金）
                        BigDecimal curRate = allBuyPL.add(allSellPL).add(allBuyPrincipalAmount).add(allSellPrincipalAmount).add(wallet.getUsdtBalance()).add(wallet.getUsdtFrozenBalance()).subtract(allBuyCloseFee).subtract(allSellCloseFee).divide(allBuyPositionValue.add(allSellPositionValue), 8, BigDecimal.ROUND_DOWN);
                        //logger.info("爆仓检查 - 保证金率："+curRate + "，维持保证金率：" + contractCoin.getMaintenanceMarginRate());
                        if(curRate.compareTo(contractCoin.getMaintenanceMarginRate()) <= 0) {
                            logger.info("爆仓检查 - 保证金率："+curRate);
                            // 爆 多单 和 空单
                            for (MemberContractWallet item:items) {
                                ContractCoinMatch match = matchFactory.getContractCoinMatch(item.getContractCoin().getSymbol());
                                if (match == null) {
                                    continue;
                                }
                                BigDecimal nowPrice = match.getNowPrice();
                                blastAll(item, nowPrice);
                            }
                            // 更新钱包
                            MemberContractWallet queryWallet = memberContractWalletService.findOne(wallet.getId());
                            memberContractWalletList.set(memberContractWalletList.indexOf(wallet), queryWallet);
                        }
                    }
                }
            }
        }
    }

    // 爆多单
    public void blastBuy(MemberContractWallet wallet, BigDecimal price) {
        if(wallet.getUsdtBuyPosition().add(wallet.getUsdtFrozenBuyPosition()).compareTo(BigDecimal.ZERO) > 0 && wallet.getUsdtBuyPrice().compareTo(BigDecimal.ZERO) > 0) {
            logger.info("多仓爆仓，用户ID：{}，爆仓执行价：{}", wallet.getMemberId(), price);
            // 计算收益
            BigDecimal buyPL = price.divide(wallet.getUsdtBuyPrice(), 8, BigDecimal.ROUND_DOWN).subtract(BigDecimal.ONE).multiply(wallet.getUsdtBuyPosition().add(wallet.getUsdtFrozenBuyPosition())).multiply(wallet.getUsdtShareNumber());
            BigDecimal closeFee = wallet.getUsdtBuyPosition().add(wallet.getUsdtFrozenBuyPosition()).multiply(wallet.getUsdtShareNumber()).multiply(contractCoin.getCloseFee());
            // 新建合约委托单
            ContractOrderEntrust orderEntrust = new ContractOrderEntrust();
            orderEntrust.setContractId(contractCoin.getId()); // 合约ID
            orderEntrust.setMemberId(wallet.getMemberId()); // 用户ID
            orderEntrust.setSymbol(contractCoin.getSymbol()); // 交易对符号
            orderEntrust.setBaseSymbol(contractCoin.getSymbol().split("/")[1]); // 基币/结算币
            orderEntrust.setCoinSymbol(contractCoin.getSymbol().split("/")[0]); // 币种符号
            orderEntrust.setDirection(ContractOrderDirection.SELL); // 平仓方向：平空/平多
            orderEntrust.setContractOrderEntrustId(GeneratorUtil.getOrderId("CE"));
            orderEntrust.setVolume(wallet.getUsdtBuyPosition().add(wallet.getUsdtFrozenBuyPosition())); // 平仓张数
            orderEntrust.setTradedVolume(wallet.getUsdtBuyPosition().add(wallet.getUsdtFrozenBuyPosition())); // 已交易数量
            orderEntrust.setTradedPrice(price); // 成交价格
            orderEntrust.setPrincipalUnit("USDT"); // 保证金单位
            orderEntrust.setPrincipalAmount(BigDecimal.ZERO); // 保证金数量
            orderEntrust.setCreateTime(DateUtil.getTimeMillis()); // 开仓时间
            orderEntrust.setType(ContractOrderType.MARKET_PRICE);
            orderEntrust.setTriggerPrice(BigDecimal.ZERO); // 触发价
            orderEntrust.setEntrustPrice(BigDecimal.ZERO); // 委托价格
            orderEntrust.setEntrustType(ContractOrderEntrustType.CLOSE); // 平仓
            orderEntrust.setTriggeringTime(0L); // 触发时间，暂时无效
            orderEntrust.setShareNumber(wallet.getUsdtShareNumber());
            orderEntrust.setProfitAndLoss(buyPL); // 盈亏（仅平仓计算）
            orderEntrust.setPatterns(wallet.getUsdtPattern()); // 仓位模式
            orderEntrust.setCloseFee(closeFee);
            orderEntrust.setCurrentPrice(price);
            orderEntrust.setIsBlast(1); // 是爆仓单
            orderEntrust.setStatus(ContractOrderEntrustStatus.ENTRUST_SUCCESS); // 委托状态：委托中
            ContractOrderEntrust retObj = contractOrderEntrustService.save(orderEntrust);
            if (retObj != null) {
                logger.info("多仓，清空仓位");

                // 更新总多仓位
                if (wallet.getMemberId() != 1) {
                    contractCoinService.increaseTotalBuy(contractCoin.getId(), orderEntrust.getVolume().negate());
                }

                // 更新平台收益
                contractCoinService.increaseTotalProfit(contractCoin.getId(), wallet.getUsdtBuyPrincipalAmount());

                // 统一处理用户盈亏
                handlePl(wallet.getMemberId(), BigDecimal.ZERO.subtract(wallet.getUsdtBuyPrincipalAmount()));

                // 更新平台平仓手续费
                contractCoinService.increaseTotalCloseFee(contractCoin.getId(), closeFee);
                // 统一处理手续费
                handleFee(wallet.getMemberId(), closeFee);
                // 多仓清空（可用仓位 + 冻结仓位），多仓保证金清空
                memberContractWalletService.blastBuy(wallet.getId());
                wallet.setUsdtBuyPosition(BigDecimal.ZERO);
                wallet.setUsdtFrozenBuyPosition(BigDecimal.ZERO);
                wallet.setUsdtBuyPrincipalAmount(BigDecimal.ZERO);
                // 撤销所有卖出平多单（包含计划委托）
                List<ContractOrderEntrust> closingList = contractOrderEntrustService.queryAllEntrustClosingOrdersByContractCoin(wallet.getMemberId(), contractCoin.getId(), ContractOrderDirection.SELL);
                for (ContractOrderEntrust item : closingList) {
                    cancelContractOrderEntrust(item, true);
                }
            }
        }
    }

    // 爆空单
    public void blastSell(MemberContractWallet wallet, BigDecimal price) {

        if(wallet.getUsdtSellPosition().add(wallet.getUsdtFrozenSellPosition()).compareTo(BigDecimal.ZERO) > 0 && wallet.getUsdtSellPrice().compareTo(BigDecimal.ZERO) > 0) {
            logger.info("空仓爆仓，用户ID：{}，爆仓执行价：{}", wallet.getMemberId(), price);
            BigDecimal sellPL = BigDecimal.ONE.subtract(price.divide(wallet.getUsdtSellPrice(), 8, BigDecimal.ROUND_DOWN)).multiply(wallet.getUsdtSellPosition().add(wallet.getUsdtFrozenSellPosition())).multiply(wallet.getUsdtShareNumber());
            BigDecimal closeFee = wallet.getUsdtSellPosition().add(wallet.getUsdtFrozenSellPosition()).multiply(wallet.getUsdtShareNumber()).multiply(contractCoin.getCloseFee());
            // 新建合约委托单
            ContractOrderEntrust orderEntrust = new ContractOrderEntrust();
            orderEntrust.setContractId(contractCoin.getId()); // 合约ID
            orderEntrust.setMemberId(wallet.getMemberId()); // 用户ID
            orderEntrust.setSymbol(contractCoin.getSymbol()); // 交易对符号
            orderEntrust.setBaseSymbol(contractCoin.getSymbol().split("/")[1]); // 基币/结算币
            orderEntrust.setCoinSymbol(contractCoin.getSymbol().split("/")[0]); // 币种符号
            orderEntrust.setDirection(ContractOrderDirection.BUY); // 平仓方向：平空/平多
            orderEntrust.setContractOrderEntrustId(GeneratorUtil.getOrderId("CE"));
            orderEntrust.setVolume(wallet.getUsdtBuyPosition().add(wallet.getUsdtFrozenSellPosition())); // 平仓张数
            orderEntrust.setTradedVolume(wallet.getUsdtSellPosition().add(wallet.getUsdtFrozenSellPosition())); // 已交易数量
            orderEntrust.setTradedPrice(price); // 成交价格
            orderEntrust.setPrincipalUnit("USDT"); // 保证金单位
            orderEntrust.setPrincipalAmount(BigDecimal.ZERO); // 保证金数量
            orderEntrust.setCreateTime(DateUtil.getTimeMillis()); // 开仓时间
            orderEntrust.setType(ContractOrderType.MARKET_PRICE);
            orderEntrust.setTriggerPrice(BigDecimal.ZERO); // 触发价
            orderEntrust.setEntrustPrice(BigDecimal.ZERO); // 委托价格
            orderEntrust.setEntrustType(ContractOrderEntrustType.CLOSE); // 平仓
            orderEntrust.setTriggeringTime(0L); // 触发时间，暂时无效
            orderEntrust.setShareNumber(wallet.getUsdtShareNumber());
            orderEntrust.setProfitAndLoss(sellPL); // 盈亏（保证金 - 平仓手续费）
            orderEntrust.setPatterns(wallet.getUsdtPattern()); // 仓位模式
            orderEntrust.setCloseFee(closeFee);
            orderEntrust.setCurrentPrice(price);
            orderEntrust.setIsBlast(1); // 是爆仓单
            orderEntrust.setStatus(ContractOrderEntrustStatus.ENTRUST_SUCCESS); // 委托状态：委托成功
            ContractOrderEntrust retObj = contractOrderEntrustService.save(orderEntrust);
            if (retObj != null) {
                logger.info("空仓，清空仓位");

                // 更新总空仓位
                if (wallet.getMemberId() != 1) {
                    contractCoinService.increaseTotalSell(contractCoin.getId(), orderEntrust.getVolume().negate());
                }

                // 更新平台收益
                contractCoinService.increaseTotalProfit(contractCoin.getId(), wallet.getUsdtSellPrincipalAmount());
                // 统一处理用户盈亏
                handlePl(wallet.getMemberId(), BigDecimal.ZERO.subtract(wallet.getUsdtSellPrincipalAmount()));
                // 更新平台平仓手续费
                contractCoinService.increaseTotalCloseFee(contractCoin.getId(), closeFee);
                // 统一处理手续费
                handleFee(wallet.getMemberId(), closeFee);

                // 空仓清空（可用仓位 + 冻结仓位），空仓保证金清空
                memberContractWalletService.blastSell(wallet.getId());

                // 更新钱包
                wallet.setUsdtSellPosition(BigDecimal.ZERO);
                wallet.setUsdtFrozenSellPosition(BigDecimal.ZERO);
                wallet.setUsdtSellPrincipalAmount(BigDecimal.ZERO);

                // 查询所有平空单
                // 撤销所有卖出平多单（包含计划委托）
                List<ContractOrderEntrust> closingList = contractOrderEntrustService.queryAllEntrustClosingOrdersByContractCoin(wallet.getMemberId(), contractCoin.getId(), ContractOrderDirection.BUY);
                for (ContractOrderEntrust item : closingList) {
                    cancelContractOrderEntrust(item, true);
                }
            }
        }
    }

    // 爆全仓
    public void blastAll(MemberContractWallet wallet, BigDecimal price) {
        logger.info("全仓爆仓，用户ID：{}，执行价：{}", wallet.getMemberId(), price);

        // 平多
        blastBuy(wallet, price);

        // 平空
        blastSell(wallet, price);

        // 清空可用余额
        memberContractWalletService.decreaseUsdtBalance(wallet.getId(), wallet.getUsdtBalance());

        // 更新平台收益
        contractCoinService.increaseTotalProfit(contractCoin.getId(), wallet.getUsdtBalance());

        // 统一处理用户盈亏(全部亏光)
        handlePl(wallet.getMemberId(), BigDecimal.ZERO.subtract(wallet.getUsdtBalance()));
    }

    // 撤销订单（委托单）
    public synchronized void cancelContractOrderEntrust(ContractOrderEntrust orderEntrust, boolean isBlast) {
        // 查找订单
        LinkedList<ContractOrderEntrust> list = null;
        if(orderEntrust.getEntrustType() == ContractOrderEntrustType.OPEN) {
            if(orderEntrust.getType() == ContractOrderType.SPOT_LIMIT) {
                list = this.openOrderSpotList;
                logger.info("取消开仓订单 - 计划委托类型");
            }else{
                logger.info("取消开仓订单 - 非计划委托类型");
                list = this.openOrderList;
            }
        }else{
            if(orderEntrust.getType() == ContractOrderType.SPOT_LIMIT) {
                list = this.closeOrderSpotList;
                logger.info("取消平仓订单 - 计划委托类型");
            }else{
                list = this.closeOrderList;
                logger.info("取消平仓订单 - 非计划委托类型");
            }
        }
        synchronized (list) {
            logger.info("撤销订单列表大小：{}", list.size());
            Iterator<ContractOrderEntrust> orderIterator = list.iterator();
            while ((orderIterator.hasNext())) {
                ContractOrderEntrust order = orderIterator.next();
                logger.info("撤销订单ID：{}, 对比 开仓订单ID: {}", orderEntrust.getId(), order.getId());
                if (order.getId().longValue() == orderEntrust.getId().longValue()) {
                    logger.info("撤销订单，在引擎中发现订单，予以撤销");
                    MemberContractWallet wallet = memberContractWalletService.findByMemberIdAndContractCoin(orderEntrust.getMemberId(), contractCoin);
                    // 更新数据库
                    if(orderEntrust.getEntrustType() == ContractOrderEntrustType.OPEN) {
                        // 开仓单，撤单需要解冻USDT
                        if(orderEntrust.getType() == ContractOrderType.LIMIT_PRICE || orderEntrust.getType() == ContractOrderType.MARKET_PRICE) {
                            // 限价或市价单，需要解冻保证金 | 如果是爆仓单，则无需解冻保证金
                            if(!isBlast) {
                                memberContractWalletService.thawUsdtBalance(wallet, orderEntrust.getPrincipalAmount().add(orderEntrust.getOpenFee()));
                            }
                        }else{
                            // 计划委托单，什么都不用做，因为计划委托单没有冻结任何资产
                        }
                    }else{
                        if(orderEntrust.getType() == ContractOrderType.LIMIT_PRICE || orderEntrust.getType() == ContractOrderType.MARKET_PRICE) {
                            // 平仓单，撤单需要解冻仓位Position
                            if (orderEntrust.getDirection() == ContractOrderDirection.BUY) { // 平仓单 - 买入平空： 释放空仓冻结
                                if(!isBlast) {
                                    memberContractWalletService.thrawUsdtSellPosition(wallet.getId(), orderEntrust.getVolume(), order.getQuantity());
                                }
                            } else {
                                if(!isBlast) {
                                    memberContractWalletService.thrawUsdtBuyPosition(wallet.getId(), orderEntrust.getVolume(), order.getQuantity());
                                }
                            }
                        }else{
                            // 计划委托单，什么都不用做，因为计划委托单没有冻结任何资产
                        }
                    }
                    contractOrderEntrustService.updateStatus(orderEntrust.getId(), ContractOrderEntrustStatus.ENTRUST_CANCEL);
                    orderIterator.remove();
                }
            }
        }
    }

    /**
     * 初始化Thumb
     */
    public void initializeThumb() {
        this.thumb = new CoinThumb();
        this.thumb.setChg(BigDecimal.ZERO);                 // 变化百分比（例：4%）
        this.thumb.setChange(BigDecimal.ZERO);              // 变化金额
        this.thumb.setOpen(BigDecimal.ZERO);                // 开盘价
        this.thumb.setClose(BigDecimal.ZERO);               // 收盘价
        this.thumb.setHigh(BigDecimal.ZERO);                // 最高价
        this.thumb.setLow(BigDecimal.ZERO);                 // 最低价
        this.thumb.setBaseUsdRate(BigDecimal.valueOf(7.0)); // 基础USDT汇率
        this.thumb.setLastDayClose(BigDecimal.ZERO);        // 前日收盘价
        this.thumb.setSymbol(this.symbol);                  // 交易对符号
        this.thumb.setUsdRate(BigDecimal.valueOf(7.0));     // USDT汇率
        this.thumb.setZone(0);                              // 交易区
        this.thumb.setVolume(BigDecimal.ZERO);              // 成交量
        this.thumb.setTurnover(BigDecimal.ZERO);            // 成交额
    }

    public void handleCoinThumb() {
        for (MarketHandler storage : handlers) {
            storage.handleTrade(symbol, thumb);
        }
    }

    public void handleKLineStorage(KLine kLine) {
        for (MarketHandler storage : handlers) {
            storage.handleKLine(symbol, kLine);
        }
    }
    // 获取交易对符号
    public String getSymbol() { return this.symbol; }
    // 获取币种符号
    public String getCoinSymbol() { return this.coinSymbol; }
    // 获取基币符号
    public String getBaseSymbol() { return this.baseSymbol; }
    // 获取交易对最新报价
    public BigDecimal getNowPrice() { return this.nowPrice; }
    // 获取交易对最新行情
    public CoinThumb getThumb() { return this.thumb; }
    // 获取最新成交明细
    public List<ContractTrade> getLastedTradeList() { return this.lastedTradeList; }
    // 获取盘口数据
    public TradePlate getTradePlate(ContractOrderDirection direction){
        if(direction == ContractOrderDirection.BUY){
            return buyTradePlate;
        }
        else{
            return sellTradePlate;
        }
    }
    // 设置合约币种服务
    public void setContractCoinService(ContractCoinService contractCoinService) { this.contractCoinService = contractCoinService; }
    // 设置合约订单委托服务
    public void setContractOrderEntrustService(ContractOrderEntrustService contractOrderEntrustService){ this.contractOrderEntrustService = contractOrderEntrustService; }
    // 添加处理者
    public void addHandler(MarketHandler storage) {
        handlers.add(storage);
    }
    public void setExchangePushJob(ExchangePushJob job) { this.exchangePushJob = job; }

    public void setMemberTransactionService(MemberTransactionService memberTransactionService) {
        this.memberTransactionService = memberTransactionService;
    }

    public void setMemberContractWalletService(MemberContractWalletService memberContractWalletService) {
        this.memberContractWalletService = memberContractWalletService;
    }

    /**
     * 更新合约交易对信息
     * @param coin
     */
    public void updateContractCoin(ContractCoin coin) {
        synchronized (contractCoin) {
            contractCoin = coin;
        }
    }


    /**
     * 定点爆仓
     * @param newPrice
     */
    public void refreshBlastPrice(BigDecimal newPrice, ContractCoinMatchFactory matchFactory) {
        logger.info("========>>>>>>定点爆仓，爆仓执行价：{}", newPrice);
        // 尚未启动
        if(!this.isStarted) return;

        this.process(newPrice, matchFactory);
    }

    /**
     * 获取引擎中所有用户持仓信息
     * @return
     */
    public List<MemberContractWallet> getMemberContractWalletList() {
        return this.memberContractWalletList;
    }

    /**
     * 处理手续费
     */
    public void handleFee(Long memberId, BigDecimal fee){
        logger.info("处理手续费，用户：{}, 手续费：{}", memberId, fee);
    }

    /**
     * 处理盈亏
     */
    public void handlePl(Long memberId, BigDecimal pL){

        MemberTransaction memberTransaction = new MemberTransaction();
        memberTransaction.setFee(BigDecimal.ZERO);
        memberTransaction.setAmount(pL);
        memberTransaction.setMemberId(memberId);
        memberTransaction.setSymbol(contractCoin.getSymbol().split("/")[1]);
        memberTransaction.setType(pL.compareTo(BigDecimal.ZERO) > 0 ? TransactionType.CONTRACT_PROFIT : TransactionType.CONTRACT_LOSS);
        memberTransaction.setCreateTime(DateUtil.getCurrentDate());
        memberTransaction.setRealFee("0");
        memberTransaction.setDiscountFee("0");
        memberTransaction = memberTransactionService.save(memberTransaction);

        logger.info("处理盈亏，用户：{}, 实际盈亏：{}", memberId, pL);
    }

    /**
     * 更新钱包
     * @param walletId
     */
    public void memberWalletChange(Long walletId) {
        synchronized (memberContractWalletList) {
            boolean hasWallet = false;
            Iterator<MemberContractWallet> walletIterator = memberContractWalletList.iterator();
            while(walletIterator.hasNext()) {
                MemberContractWallet wallet = walletIterator.next();
                if(wallet.getId().longValue() == walletId.longValue()) {
                    hasWallet = true;
                    logger.info("更新钱包，用户：{}, 内容：{}", wallet.getMemberId(), JSON.toJSONString(wallet));
                    // 更新钱包
                    MemberContractWallet queryResult = memberContractWalletService.findOne(walletId);
                    memberContractWalletList.set(memberContractWalletList.indexOf(wallet), queryResult);
                    break;
                }
            }
            // 找不到，则添加
            if(!hasWallet) {
                MemberContractWallet wallet = memberContractWalletService.findOne(walletId);
                if(wallet != null) {
                    logger.info("新增用户钱包，用户：{}, 内容：{}", wallet.getMemberId(), JSON.toJSONString(wallet));
                    memberContractWalletList.add(wallet);
                }
            }
        }
    }
}
