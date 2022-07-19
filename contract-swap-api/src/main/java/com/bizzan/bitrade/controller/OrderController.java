package com.bizzan.bitrade.controller;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.bizzan.bitrade.constant.BooleanEnum;
import com.bizzan.bitrade.engine.ContractCoinMatchFactory;
import com.bizzan.bitrade.entity.*;
import com.bizzan.bitrade.entity.transform.AuthMember;
import com.bizzan.bitrade.service.*;
import com.bizzan.bitrade.util.DateUtil;
import com.bizzan.bitrade.util.GeneratorUtil;
import com.bizzan.bitrade.util.MessageResult;

import lombok.extern.slf4j.Slf4j;
import org.apache.shiro.util.Assert;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.domain.Page;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import static com.bizzan.bitrade.constant.SysConstant.SESSION_MEMBER;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 委托订单处理类
 */
@Slf4j
@RestController
@RequestMapping("/order")
public class OrderController {
    @Autowired
    private MemberWalletService walletService;

    @Autowired
    private CoinService coinService;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Value("${exchange.max-cancel-times:-1}")
    private int maxCancelTimes;

    @Autowired
    private LocaleMessageSourceService msService;

    @Autowired
    private MemberService memberService;

    @Autowired
    private ContractCoinService contractCoinService;

    @Autowired
    private ContractOrderEntrustService contractOrderEntrustService;

    @Autowired
    private MemberTransactionService memberTransactionService;

    @Autowired
    private MemberContractWalletService memberContractWalletService;

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private RedisTemplate redisTemplate;

    @Autowired
    private ContractCoinMatchFactory contractCoinMatchFactory; // 合约引擎工厂

    private SimpleDateFormat dateTimeFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    /**
     * 合约下单（开仓）- 金本位
     * 二种操作类型：买入开多，卖出开多
     *
     * @param authMember
     * @return
     */
    @RequestMapping("open")
    public MessageResult openOrder(@SessionAttribute(SESSION_MEMBER) AuthMember authMember,
                                   @RequestParam(value = "contractCoinId") Long contractCoinId,// 合约交易对
                                   @RequestParam(value = "direction") ContractOrderDirection direction,// 1：买（平空）  2：卖（平多）
                                   @RequestParam(value = "type") ContractOrderType type,// 0：市价 1：限价 2：计划委托
                                   @RequestParam(value = "triggerPrice", required = false) BigDecimal triggerPrice,// 触发价格
                                   @RequestParam(value = "entrustPrice") BigDecimal entrustPrice,// 委托价格(计划委托时如为0：市价成交)
                                   @RequestParam(value = "leverage") BigDecimal leverage,// 委托价格
                                   @RequestParam(value = "usdtNum", required = false) BigDecimal usdtNum,// 开仓usdt数量
                                   @RequestParam(value = "volume", required = false) BigDecimal volume// 委托数量（张）
    ) {

        // 输入合法性检查
        if (contractCoinId == null || direction == null || type == null || leverage == null) {
            return MessageResult.error(500, msService.getMessage("ILLEGAL_ARGUMENT"));
        }
        if (direction != ContractOrderDirection.BUY && direction != ContractOrderDirection.SELL) {
            return MessageResult.error(500, msService.getMessage("ILLEGAL_ARGUMENT"));
        }
        if (type != ContractOrderType.MARKET_PRICE && type != ContractOrderType.LIMIT_PRICE && type != ContractOrderType.SPOT_LIMIT) {
            return MessageResult.error(500, msService.getMessage("ILLEGAL_ARGUMENT"));
        }

        if (usdtNum == null && volume == null) {
            return MessageResult.error(500, "下单量为空！");
        }

        // 检查交易对是否存在
        ContractCoin contractCoin = contractCoinService.findOne(contractCoinId);

        if (usdtNum != null) {
            //换算成张算
            long temp = usdtNum.divide(contractCoin.getShareNumber(), 4, BigDecimal.ROUND_DOWN).longValue();
            volume = BigDecimal.valueOf(temp);
        }

        if (contractCoin == null) {
            return MessageResult.error(500, msService.getMessage("NONSUPPORT_COIN"));
        }
        // 检查用户钱包是否存在
        MemberContractWallet memberContractWallet = memberContractWalletService.findByMemberIdAndContractCoin(authMember.getId(), contractCoin);
        if (memberContractWallet == null) {
            return MessageResult.error(500, "合约钱包不存在");
        }
        // 尝试修改杠杆（仅当空仓时可修改杠杆）
        BigDecimal walletPosition = direction == ContractOrderDirection.BUY ? memberContractWallet.getUsdtBuyPosition().add(memberContractWallet.getUsdtFrozenBuyPosition()) : memberContractWallet.getUsdtSellPosition().add(memberContractWallet.getUsdtFrozenSellPosition());
        // 仓位为空，则下单时顺便修改一下杠杆
        if (walletPosition.compareTo(BigDecimal.ZERO) == 0) {
            if (direction == ContractOrderDirection.BUY) {
                memberContractWalletService.modifyUsdtBuyLeverage(memberContractWallet.getId(), leverage);
            } else {
                memberContractWalletService.modifyUsdtSellLeverage(memberContractWallet.getId(), leverage);
            }
        }
        ContractOrderPattern pattern = memberContractWallet.getUsdtPattern();
        // 获取杠杆倍数
        leverage = direction == ContractOrderDirection.BUY ? memberContractWallet.getUsdtBuyLeverage() : memberContractWallet.getUsdtSellLeverage();
        // 限价单及计划委托单需要输入委托价格
        if (type == ContractOrderType.LIMIT_PRICE || type == ContractOrderType.SPOT_LIMIT) {
            if (entrustPrice == null) {
                return MessageResult.error(500, "请输入委托价格");
            }
        }
        // 检查用户合法性
        Member member = memberService.findOne(authMember.getId());
        if (member == null) return MessageResult.error(500, "下单用户不存在");
        // 用户是否被禁止交易
        if (member.getTransactionStatus().equals(BooleanEnum.IS_FALSE)) {
            return MessageResult.error(500, msService.getMessage("CANNOT_TRADE"));
        }

        // 检查交易对是否可用
        if (contractCoin.getEnable() != 1 || contractCoin.getExchangeable() != 1) {
            return MessageResult.error(500, msService.getMessage("COIN_FORBIDDEN"));
        }
        // 是否允许开多仓（买涨）
        if (contractCoin.getEnableOpenBuy() == BooleanEnum.IS_FALSE && direction == ContractOrderDirection.BUY) {
            return MessageResult.error(500, "暂不允许开仓做多");
        }
        // 是否允许开空仓（买跌）
        if (contractCoin.getEnableOpenSell() == BooleanEnum.IS_FALSE && direction == ContractOrderDirection.SELL) {
            return MessageResult.error(500, "暂不允许开仓做空");
        }
        // 是否允许市价开仓做多
        if (contractCoin.getEnableMarketBuy() == BooleanEnum.IS_FALSE && direction == ContractOrderDirection.BUY) {
            return MessageResult.error(500, "暂不允许市价开仓做多");
        }
        // 是否允许市价开仓做空
        if (contractCoin.getEnableMarketSell() == BooleanEnum.IS_FALSE && direction == ContractOrderDirection.SELL) {
            return MessageResult.error(500, "暂不允许市价开仓做空");
        }

        // 杠杆倍数是否在规定范围
        if (contractCoin.getLeverageType() == 1) { // 分离倍数
            String[] leverageArr = contractCoin.getLeverage().split(",");
            boolean isExist = false;
            for (String str : leverageArr) {
                if (BigDecimal.valueOf(Integer.parseInt(str)).compareTo(leverage) == 0) {
                    isExist = true;
                }
            }
            if (!isExist) {
                return MessageResult.error(500, "杠杆倍数不存在");
            }
        } else { // 范围倍数
            String[] leverageArr = contractCoin.getLeverage().split(",");
            if (leverageArr.length != 2) return MessageResult.error(500, "币种杠杆设置错误，请联系管理员");

            BigDecimal low = BigDecimal.valueOf(Integer.parseInt(leverageArr[0]));
            BigDecimal high = BigDecimal.valueOf(Integer.parseInt(leverageArr[1]));
            if (leverage.compareTo(low) < 0 || leverage.compareTo(high) > 0) {
                return MessageResult.error(500, "杠杆倍数超出允许范围");
            }
        }
        // 检查下单数量是否符合范围
        if (volume.compareTo(contractCoin.getMinShare()) < 0)
            return MessageResult.error(500, "最小下单手数不能低于" + contractCoin.getMinShare() + "手");
        if (volume.compareTo(contractCoin.getMaxShare()) > 0)
            return MessageResult.error(500, "最大下单手数不能高于" + contractCoin.getMaxShare() + "手");
        if (volume.compareTo(BigDecimal.ONE) < 0)
            return MessageResult.error(500, "开仓张数不符规定");//小于1张
        if (BigDecimal.valueOf(volume.intValue()).compareTo(volume) != 0)
            return MessageResult.error(500, "开仓张数不符规定"); // 含有小数

        // 检查合约引擎是否存在
        if (!contractCoinMatchFactory.containsContractCoinMatch(contractCoin.getSymbol())) {
            return MessageResult.error(500, "合约撮合引擎异常，请稍后下单");
        }

        // 计算开仓价（滑点 > 市价用）
        BigDecimal openPrice = BigDecimal.ZERO;
        BigDecimal currentPrice = contractCoinMatchFactory.getContractCoinMatch(contractCoin.getSymbol()).getNowPrice();
        // 委托价格是否太高或太低(限价单需要在2%的价格范围内下单)
        if (type == ContractOrderType.LIMIT_PRICE) {
            if (entrustPrice.compareTo(currentPrice.multiply(BigDecimal.ONE.add(BigDecimal.valueOf(0.2)))) > 0
                    || entrustPrice.compareTo(currentPrice.multiply(BigDecimal.ONE.subtract(BigDecimal.valueOf(0.2)))) < 0) {
                return MessageResult.error(500, "下单价格超限");
            }
        }

        // 计算市价成交价格（大约）
        openPrice = currentPrice;
        if (direction == ContractOrderDirection.BUY) { // 买入，滑点计算，做多，更高价格成交
            if (contractCoin.getSpreadType() == 1) { // 滑点类型：百分比
                openPrice = currentPrice.add(currentPrice.multiply(contractCoin.getSpread())); // 已当前价成交（或滑点价成交）
            } else { // 滑点类型：固定额
                openPrice = currentPrice.add(contractCoin.getSpread());
            }
        } else { // 卖出，滑点计算，做空，更低价格成交
            if (contractCoin.getSpreadType() == 1) { // 滑点类型：百分比
                openPrice = currentPrice.subtract(currentPrice.multiply(contractCoin.getSpread())); // 已当前价成交（或滑点价成交）
            } else { // 滑点类型：固定额
                openPrice = currentPrice.subtract(contractCoin.getSpread());
            }
        }
        if (openPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return MessageResult.error(500, "合约撮合引擎异常，请稍后下单");
        }

        // 开仓，检查保证金是否足够
        /**
         * 本段代码涉及到合约保证金的计算
         * 在开仓操作下，需要计算所有持仓订单和委托订单的保证金，不局限于本币种
         *
         */
        // 0、计算当前开仓订单所需保证金
        // 合约张数 * 合约面值 / 杠杆倍数 （该计算方式适合于金本位，即USDT作为保证金模式）
        BigDecimal principalAmount = volume.multiply(contractCoin.getShareNumber().divide(leverage, 4, BigDecimal.ROUND_DOWN));

        // 1、计算开仓手续费(合约张数 * 合约面值 * 开仓费率）
        BigDecimal openFee = volume.multiply(contractCoin.getShareNumber()).multiply(contractCoin.getOpenFee());

        // 当前账户为逐仓模式时，只需比较可用余额
        if (memberContractWallet.getUsdtPattern() == ContractOrderPattern.FIXED) {
            if (principalAmount.add(openFee).compareTo(memberContractWallet.getUsdtBalance()) > 0) {
                return MessageResult.error(500, "合约账户资金不足");
            }
        }
        // 全仓模式，需要计算空仓和多仓总权益
        if (memberContractWallet.getUsdtPattern() == ContractOrderPattern.CROSSED) {
            // 计算金本位权益（多仓 + 空仓）
            BigDecimal usdtTotalProfitAndLoss = BigDecimal.ZERO;
            // 多仓计算方法：（当前价格 / 开仓均价 - 1）* （可用仓位 + 冻结仓位） * 合约面值
            if (memberContractWallet.getUsdtBuyPrice().compareTo(BigDecimal.ZERO) > 0 && memberContractWallet.getUsdtBuyPosition().compareTo(BigDecimal.ZERO) > 0) {
                usdtTotalProfitAndLoss = usdtTotalProfitAndLoss.add(currentPrice.divide(memberContractWallet.getUsdtBuyPrice(), 4, BigDecimal.ROUND_DOWN).subtract(BigDecimal.ONE).multiply(memberContractWallet.getUsdtBuyPosition().add(memberContractWallet.getUsdtFrozenBuyPosition())).multiply(memberContractWallet.getUsdtShareNumber()));
            }

            // 空仓计算方法：（1 - 当前价格 / 开仓均价）* （可用仓位 + 冻结仓位） * 合约面值
            if (memberContractWallet.getUsdtSellPrice().compareTo(BigDecimal.ZERO) > 0 && memberContractWallet.getUsdtSellPosition().compareTo(BigDecimal.ZERO) > 0) {
                usdtTotalProfitAndLoss = usdtTotalProfitAndLoss.add(BigDecimal.ONE.subtract(currentPrice.divide(memberContractWallet.getUsdtSellPrice(), 4, BigDecimal.ROUND_DOWN)).multiply(memberContractWallet.getUsdtSellPosition().add(memberContractWallet.getUsdtFrozenSellPosition())).multiply(memberContractWallet.getUsdtShareNumber()));
            }

            // 加上仓位保证金
            usdtTotalProfitAndLoss = usdtTotalProfitAndLoss.add(memberContractWallet.getUsdtBuyPrincipalAmount());
            // 经过上面的计算，可能会得到一个正值，也可能得到一个负值，如果是负值，因为是全仓模式，就需要用余额减去该数值，然后计算余额是否足够
            if (usdtTotalProfitAndLoss.compareTo(BigDecimal.ZERO) < 0) {
                if (principalAmount.add(openFee).compareTo(memberContractWallet.getUsdtBalance().add(usdtTotalProfitAndLoss)) > 0) {
                    return MessageResult.error(500, "合约账户资金不足");
                }
            } else { // 如果持仓权益是正值，则直接跟可用余额比较即可
                if (principalAmount.add(openFee).compareTo(memberContractWallet.getUsdtBalance()) > 0) {
                    return MessageResult.error(500, "合约账户资金不足");
                }
            }
        }

        // 计划委托中的触发价格需要大于0
        if (type == ContractOrderType.SPOT_LIMIT) {
            if (triggerPrice == null) {
                return MessageResult.error(500, "请设置触发价");
            }
            if (entrustPrice == null) {
                return MessageResult.error(500, "请设置委托价");
            }
            if (triggerPrice.compareTo(BigDecimal.ZERO) <= 0) {
                return MessageResult.error(500, "触发价格必须大于0");
            }
            if (entrustPrice.compareTo(BigDecimal.ZERO) < 0) {
                return MessageResult.error(500, "计划委托价格不能小于0");
            }
        }
        // 新建合约委托单
        ContractOrderEntrust orderEntrust = new ContractOrderEntrust();
        orderEntrust.setContractId(contractCoin.getId()); // 合约ID
        orderEntrust.setMemberId(member.getId()); // 用户ID
        orderEntrust.setSymbol(contractCoin.getSymbol()); // 交易对符号
        orderEntrust.setBaseSymbol(contractCoin.getSymbol().split("/")[1]); // 基币/结算币
        orderEntrust.setCoinSymbol(contractCoin.getSymbol().split("/")[0]); // 币种符号
        orderEntrust.setDirection(direction); // 开仓方向：做多/做空
        orderEntrust.setContractOrderEntrustId(GeneratorUtil.getOrderId("CE"));
        orderEntrust.setVolume(volume); // 开仓张数
        orderEntrust.setTradedVolume(BigDecimal.ZERO); // 已交易数量
        orderEntrust.setTradedPrice(BigDecimal.ZERO); // 成交价格
        orderEntrust.setPrincipalUnit("USDT"); // 保证金单位
        orderEntrust.setPrincipalAmount(principalAmount); // 保证金数量
        orderEntrust.setCreateTime(DateUtil.getTimeMillis()); // 开仓时间
        orderEntrust.setType(type);
        orderEntrust.setTriggerPrice(triggerPrice); // 触发价
        orderEntrust.setEntrustPrice(entrustPrice); // 委托价格
        orderEntrust.setEntrustType(ContractOrderEntrustType.OPEN); // 开仓
        orderEntrust.setTriggeringTime(0L); // 触发时间，暂时无效
        orderEntrust.setShareNumber(contractCoin.getShareNumber());
        orderEntrust.setProfitAndLoss(BigDecimal.ZERO); // 盈亏（仅平仓计算）
        orderEntrust.setPatterns(memberContractWallet.getUsdtPattern()); // 仓位模式
        orderEntrust.setOpenFee(openFee); // 开仓手续费
        orderEntrust.setStatus(ContractOrderEntrustStatus.ENTRUST_ING); // 委托状态：委托中
        orderEntrust.setCurrentPrice(currentPrice);
        orderEntrust.setIsBlast(0); // 不是爆仓单
        if (type == ContractOrderType.SPOT_LIMIT) { // 是否是计划委托单
            orderEntrust.setIsFromSpot(1);
        } else {
            orderEntrust.setIsFromSpot(0);
        }

        // 保存委托单
        ContractOrderEntrust retObj = contractOrderEntrustService.save(orderEntrust);

        // 冻结保证金 + 手续费(限价或者市价时，冻结资金，计划委托不冻结资金)
        if (type == ContractOrderType.LIMIT_PRICE || type == ContractOrderType.MARKET_PRICE) {
            memberContractWalletService.freezeUsdtBalance(memberContractWallet, principalAmount.add(openFee));
        } else {
            // 计划委托，同向只能有一个（如止盈或止损单只能有一个）
        }

        if (retObj != null) {
            // 发送消息至Exchange系统
            kafkaTemplate.send("swap-order-open", JSON.toJSONString(retObj));

            //通知钱包变更
            JSONObject jsonObj = new JSONObject();
            jsonObj.put("symbol", contractCoin.getSymbol());
            jsonObj.put("walletId", memberContractWallet.getId());
            kafkaTemplate.send("member-wallet-change", JSON.toJSONString(jsonObj));

            log.info(">>>>>>>>>>订单提交完成>>>>>>>>>>");
            // 返回结果
            MessageResult result = MessageResult.success("下单成功");
            result.setData(retObj);
            return result;
        } else {
            // 返回结果
            MessageResult result = MessageResult.error("下单失败");
            result.setData(null);
            return result;
        }
    }

    /**
     * 合约平仓
     * 四种操作类型：买入平空，卖出平多
     *
     * @param authMember
     * @return
     */
    @RequestMapping("close")
    public MessageResult closeOrder(@SessionAttribute(SESSION_MEMBER) AuthMember authMember,
                                    @RequestParam(value = "contractCoinId") Long contractCoinId,// 合约交易对
                                    @RequestParam(value = "direction") ContractOrderDirection direction,// 1：买（平空）  2：卖（平多）
                                    @RequestParam(value = "type") ContractOrderType type,// 1：市价 2：限价 3：计划委托
                                    @RequestParam(value = "triggerPrice", required = false) BigDecimal triggerPrice,// 触发价格
                                    @RequestParam(value = "entrustPrice") BigDecimal entrustPrice,// 委托价格(计划委托时如为0：市价成交)
                                    @RequestParam(value = "volume") BigDecimal volume// 委托数量（张）
    ) {
        // 输入合法性检查
        if (contractCoinId == null || direction == null || type == null || volume == null) {
            return MessageResult.error(500, msService.getMessage("ILLEGAL_ARGUMENT"));
        }
        // 检查合约是否存在
        ContractCoin contractCoin = contractCoinService.findOne(contractCoinId);
        if (contractCoin == null) {
            return MessageResult.error(500, "合约不存在");
        }
        // 限价单及计划委托单需要输入委托价格
        if (type == ContractOrderType.LIMIT_PRICE || type == ContractOrderType.SPOT_LIMIT) {
            if (entrustPrice == null) {
                return MessageResult.error(500, "请输入委托价格");
            }
        }
        // 计划委托需要输入触发价格
        if (type == ContractOrderType.SPOT_LIMIT) {
            if (triggerPrice == null) {
                return MessageResult.error(500, "请输入触发价格");
            }
        }
        // 检查用户合法性
        Member member = memberService.findOne(authMember.getId());
        if (member == null) return MessageResult.error(500, "下单用户不存在");
        // 用户是否被禁止交易
        if (member.getTransactionStatus().equals(BooleanEnum.IS_FALSE)) {
            return MessageResult.error(500, msService.getMessage("CANNOT_TRADE"));
        }

        // 获取账户
        MemberContractWallet memberContractWallet = memberContractWalletService.findByMemberIdAndContractCoin(authMember.getId(), contractCoin);
        if (memberContractWallet == null) {
            return MessageResult.error(500, msService.getMessage("NONSUPPORT_COIN"));
        }

        if (volume.compareTo(BigDecimal.ONE) < 0)
            return MessageResult.error(500, "平仓张数不符规定");//小于1张
        if (BigDecimal.valueOf(volume.intValue()).compareTo(volume) != 0)
            return MessageResult.error(500, "平仓张数不符规定"); // 含有小数

        if (direction == ContractOrderDirection.BUY) {
            // 买入平空，检查空仓仓位是否足够
            if (memberContractWallet.getUsdtSellPosition().compareTo(volume) < 0) {
                return MessageResult.error(500, "委托量大于可平仓量");
            }
        } else {
            // 卖出平多，检查多仓仓位是否足够
            if (memberContractWallet.getUsdtBuyPosition().compareTo(volume) < 0) {
                return MessageResult.error(500, "委托量大于可平仓量");
            }
        }

        BigDecimal currentPrice = contractCoinMatchFactory.getContractCoinMatch(contractCoin.getSymbol()).getNowPrice();

        // 委托价格是否太高或太低(限价单需要在2%的价格范围内下单)
        if (type == ContractOrderType.LIMIT_PRICE) {
            if (entrustPrice.compareTo(currentPrice.multiply(BigDecimal.ONE.add(BigDecimal.valueOf(0.02)))) > 0
                    || entrustPrice.compareTo(currentPrice.multiply(BigDecimal.ONE.subtract(BigDecimal.valueOf(0.02)))) < 0) {
                return MessageResult.error(500, "下单价格超限");
            }
        }

        // 1、计算开仓手续费(合约张数 * 合约面值 * 开仓费率）
        BigDecimal closeFee = volume.multiply(contractCoin.getShareNumber()).multiply(contractCoin.getCloseFee());

        // 新建合约委托单
        ContractOrderEntrust orderEntrust = new ContractOrderEntrust();
        orderEntrust.setContractId(contractCoin.getId()); // 合约ID
        orderEntrust.setMemberId(member.getId()); // 用户ID
        orderEntrust.setSymbol(contractCoin.getSymbol()); // 交易对符号
        orderEntrust.setBaseSymbol(contractCoin.getSymbol().split("/")[1]); // 基币/结算币
        orderEntrust.setCoinSymbol(contractCoin.getSymbol().split("/")[0]); // 币种符号
        orderEntrust.setDirection(direction); // 平仓方向：平空/平多
        orderEntrust.setContractOrderEntrustId(GeneratorUtil.getOrderId("CE"));
        orderEntrust.setVolume(volume); // 平仓张数
        orderEntrust.setTradedVolume(BigDecimal.ZERO); // 已交易数量
        orderEntrust.setTradedPrice(BigDecimal.ZERO); // 成交价格
        orderEntrust.setPrincipalUnit("USDT"); // 保证金单位
        orderEntrust.setPrincipalAmount(BigDecimal.ZERO); // 保证金数量
        orderEntrust.setCreateTime(DateUtil.getTimeMillis()); // 开仓时间
        orderEntrust.setType(type);
        orderEntrust.setTriggerPrice(triggerPrice); // 触发价
        orderEntrust.setEntrustPrice(entrustPrice); // 委托价格
        orderEntrust.setEntrustType(ContractOrderEntrustType.CLOSE); // 开仓
        orderEntrust.setTriggeringTime(0L); // 触发时间，暂时无效
        orderEntrust.setShareNumber(contractCoin.getShareNumber());
        orderEntrust.setProfitAndLoss(BigDecimal.ZERO); // 盈亏（仅平仓计算）
        orderEntrust.setPatterns(memberContractWallet.getUsdtPattern()); // 仓位模式
        orderEntrust.setCloseFee(closeFee);
        orderEntrust.setCurrentPrice(currentPrice);
        orderEntrust.setIsBlast(0); // 不是爆仓单
        if (type == ContractOrderType.SPOT_LIMIT) { // 是否是计划委托单
            orderEntrust.setIsFromSpot(1);
        } else {
            orderEntrust.setIsFromSpot(0);
        }
        //计算平仓应该扣除多少保证金(平仓量/（可平仓量+冻结平仓量） *  保证金总量）
        if (type != ContractOrderType.SPOT_LIMIT) {
            if (direction == ContractOrderDirection.BUY) { // 买入平空
                BigDecimal mPrinc = volume.divide(memberContractWallet.getUsdtSellPosition().add(memberContractWallet.getUsdtFrozenSellPosition()), 8, RoundingMode.HALF_UP).multiply(memberContractWallet.getUsdtSellPrincipalAmount());
                orderEntrust.setPrincipalAmount(mPrinc);
            } else {
                BigDecimal mPrinc = volume.divide(memberContractWallet.getUsdtBuyPosition().add(memberContractWallet.getUsdtFrozenBuyPosition()), 8, RoundingMode.HALF_UP).multiply(memberContractWallet.getUsdtBuyPrincipalAmount());
                orderEntrust.setPrincipalAmount(mPrinc);
            }
        }

        // 计算滑点成交价（市价下单时用此价格）
        BigDecimal dealPrice = currentPrice;
        if (direction == ContractOrderDirection.BUY) { // 买入平空，滑点计算，更低价格
            if (contractCoin.getSpreadType() == 1) { // 滑点类型：百分比
                dealPrice = currentPrice.add(currentPrice.multiply(contractCoin.getSpread())); // 已当前价成交（或滑点价成交）
            } else { // 滑点类型：固定额
                dealPrice = currentPrice.add(contractCoin.getSpread());
            }
        } else { // 卖出，滑点计算，做空，更低价格成交
            if (contractCoin.getSpreadType() == 1) { // 滑点类型：百分比
                dealPrice = currentPrice.subtract(currentPrice.multiply(contractCoin.getSpread())); // 已当前价成交（或滑点价成交）
            } else { // 滑点类型：固定额
                dealPrice = currentPrice.subtract(contractCoin.getSpread());
            }
        }
        //
        if (dealPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return MessageResult.error(500, "合约撮合引擎异常，请稍后下单");
        }

        // 限价或市价下单时，需要冻结仓位，计划委托下单则无需冻结
        if (type == ContractOrderType.LIMIT_PRICE || type == ContractOrderType.MARKET_PRICE) {
            // 冻结持仓量
            if (direction == ContractOrderDirection.BUY) {
                // 冻结空仓持仓
                memberContractWalletService.freezeUsdtSellPosition(memberContractWallet.getId(), volume);
            } else {
                // 冻结多仓持仓
                memberContractWalletService.freezeUsdtBuyPosition(memberContractWallet.getId(), volume);
            }
        }

        // 保存委托单
        orderEntrust.setStatus(ContractOrderEntrustStatus.ENTRUST_ING); // 委托状态：委托中
        ContractOrderEntrust retObj = contractOrderEntrustService.save(orderEntrust);

        if (retObj != null) {
            // 发送消息至Exchange系统
            kafkaTemplate.send("swap-order-close", JSON.toJSONString(retObj));


            //通知钱包变更
            JSONObject jsonObj = new JSONObject();
            jsonObj.put("symbol", contractCoin.getSymbol());
            jsonObj.put("walletId", memberContractWallet.getId());
            kafkaTemplate.send("member-wallet-change", JSON.toJSONString(jsonObj));

            log.info(">>>>>>>>>>订单提交完成>>>>>>>>>>");
            // 返回结果
            MessageResult result = MessageResult.success("下单成功");
            result.setData(retObj);
            return result;
        } else {
            // 返回结果
            MessageResult result = MessageResult.error("下单失败");
            result.setData(null);
            return result;
        }
    }

    /**
     * 一键平仓（市价全平）
     *
     * @param authMember
     * @param contractCoinId
     * @param type           0:市价平多  1:市价平空  2:市价平多+平空
     * @return
     */
    @RequestMapping("close-all")
    public MessageResult closeAll(@SessionAttribute(SESSION_MEMBER) AuthMember authMember, Long contractCoinId, Integer type) {
        Assert.notNull(contractCoinId, "请输入合约ID");
        Assert.notNull(type, "请输入平仓类型");

        Member member = memberService.findOne(authMember.getId());
        Assert.notNull(member, "用户不存在");

        ContractCoin contractCoin = contractCoinService.findOne(contractCoinId);
        Assert.notNull(contractCoin, "合约不存在");

        MemberContractWallet wallet = memberContractWalletService.findByMemberIdAndContractCoin(authMember.getId(), contractCoin);
        Assert.notNull(wallet, "不存在该币种合约账户");

        BigDecimal volumeBuy = BigDecimal.ZERO;
        BigDecimal volumeSell = BigDecimal.ZERO;
        if (type == 0) { // 平多
            // 检查多仓是否存在
            if (wallet.getUsdtFrozenBuyPosition().compareTo(BigDecimal.ZERO) > 0) {
                return MessageResult.error("请先撤销其他平多单");
            }
            if (wallet.getUsdtBuyPosition().compareTo(BigDecimal.ZERO) <= 0) {
                return MessageResult.error("当前没有多仓可平");
            }
            volumeBuy = wallet.getUsdtBuyPosition();
        } else if (type == 1) {
            if (wallet.getUsdtFrozenSellPosition().compareTo(BigDecimal.ZERO) > 0) {
                return MessageResult.error("请先撤销其他平空单");
            }
            if (wallet.getUsdtSellPosition().compareTo(BigDecimal.ZERO) <= 0) {
                return MessageResult.error("当前没有空仓可平");
            }
            volumeSell = wallet.getUsdtSellPosition();
        } else if (type == 2) {
            if (wallet.getUsdtFrozenSellPosition().compareTo(BigDecimal.ZERO) > 0 || wallet.getUsdtFrozenBuyPosition().compareTo(BigDecimal.ZERO) > 0) {
                return MessageResult.error("请先撤销其他平多单或平空单");
            }
            if (wallet.getUsdtBuyPosition().compareTo(BigDecimal.ZERO) <= 0 && wallet.getUsdtSellPosition().compareTo(BigDecimal.ZERO) <= 0) {
                return MessageResult.error("当前没有可平单持仓或空单持仓");
            }
            volumeBuy = wallet.getUsdtBuyPosition();
            volumeSell = wallet.getUsdtSellPosition();
        }

        BigDecimal currentPrice = contractCoinMatchFactory.getContractCoinMatch(contractCoin.getSymbol()).getNowPrice();

        // 多仓平仓
        if (type == 0 || type == 2) {
            BigDecimal volume = volumeBuy;
            // 1、计算平仓手续费(合约张数 * 合约面值 * 开仓费率）
            BigDecimal closeFee = volume.multiply(contractCoin.getShareNumber()).multiply(contractCoin.getCloseFee());

            // 新建合约委托单
            ContractOrderEntrust orderEntrust = new ContractOrderEntrust();
            orderEntrust.setContractId(contractCoin.getId()); // 合约ID
            orderEntrust.setMemberId(member.getId()); // 用户ID
            orderEntrust.setSymbol(contractCoin.getSymbol()); // 交易对符号
            orderEntrust.setBaseSymbol(contractCoin.getSymbol().split("/")[1]); // 基币/结算币
            orderEntrust.setCoinSymbol(contractCoin.getSymbol().split("/")[0]); // 币种符号
            orderEntrust.setDirection(ContractOrderDirection.SELL); // 平仓方向：平空/平多
            orderEntrust.setContractOrderEntrustId(GeneratorUtil.getOrderId("CE"));
            orderEntrust.setVolume(volume); // 平仓张数
            orderEntrust.setTradedVolume(BigDecimal.ZERO); // 已交易数量
            orderEntrust.setTradedPrice(BigDecimal.ZERO); // 成交价格
            orderEntrust.setPrincipalUnit("USDT"); // 保证金单位
            orderEntrust.setPrincipalAmount(BigDecimal.ZERO); // 保证金数量
            orderEntrust.setCreateTime(DateUtil.getTimeMillis()); // 开仓时间
            orderEntrust.setType(ContractOrderType.MARKET_PRICE);
            orderEntrust.setTriggerPrice(BigDecimal.ZERO); // 触发价
            orderEntrust.setEntrustPrice(BigDecimal.ZERO); // 委托价格
            orderEntrust.setEntrustType(ContractOrderEntrustType.CLOSE); // 平仓
            orderEntrust.setTriggeringTime(0L); // 触发时间，暂时无效
            orderEntrust.setShareNumber(contractCoin.getShareNumber());
            orderEntrust.setProfitAndLoss(BigDecimal.ZERO); // 盈亏（仅平仓计算）
            orderEntrust.setPatterns(wallet.getUsdtPattern()); // 仓位模式
            orderEntrust.setCloseFee(closeFee);
            orderEntrust.setCurrentPrice(currentPrice);
            orderEntrust.setIsBlast(0); // 不是爆仓单
            orderEntrust.setIsFromSpot(0);
            orderEntrust.setPrincipalAmount(wallet.getUsdtBuyPrincipalAmount()); // 全部多仓保证金

            memberContractWalletService.freezeUsdtBuyPosition(wallet.getId(), volume);

            // 保存委托单
            orderEntrust.setStatus(ContractOrderEntrustStatus.ENTRUST_ING); // 委托状态：委托中
            ContractOrderEntrust retObj = contractOrderEntrustService.save(orderEntrust);

            if (retObj != null) {
                // 发送消息至Exchange系统
                kafkaTemplate.send("swap-order-close", JSON.toJSONString(retObj));

                //通知钱包变更
                JSONObject jsonObj = new JSONObject();
                jsonObj.put("symbol", contractCoin.getSymbol());
                jsonObj.put("walletId", wallet.getId());
                kafkaTemplate.send("member-wallet-change", JSON.toJSONString(jsonObj));

                log.info(">>>>>>>>>>订单提交完成>>>>>>>>>>");
                // 返回结果
                MessageResult result = MessageResult.success("下单成功");
                result.setData(retObj);
                return result;
            } else {
                // 返回结果
                MessageResult result = MessageResult.error("下单失败");
                result.setData(null);
                return result;
            }
        }


        // 空仓平仓
        if (type == 1 || type == 2) {
            BigDecimal volume = volumeSell;
            // 1、计算平仓手续费(合约张数 * 合约面值 * 开仓费率）
            BigDecimal closeFee = volume.multiply(contractCoin.getShareNumber()).multiply(contractCoin.getCloseFee());

            // 新建合约委托单
            ContractOrderEntrust orderEntrust = new ContractOrderEntrust();
            orderEntrust.setContractId(contractCoin.getId()); // 合约ID
            orderEntrust.setMemberId(member.getId()); // 用户ID
            orderEntrust.setSymbol(contractCoin.getSymbol()); // 交易对符号
            orderEntrust.setBaseSymbol(contractCoin.getSymbol().split("/")[1]); // 基币/结算币
            orderEntrust.setCoinSymbol(contractCoin.getSymbol().split("/")[0]); // 币种符号
            orderEntrust.setDirection(ContractOrderDirection.BUY); // 平仓方向：平空/平多
            orderEntrust.setContractOrderEntrustId(GeneratorUtil.getOrderId("CE"));
            orderEntrust.setVolume(volume); // 平仓张数
            orderEntrust.setTradedVolume(BigDecimal.ZERO); // 已交易数量
            orderEntrust.setTradedPrice(BigDecimal.ZERO); // 成交价格
            orderEntrust.setPrincipalUnit("USDT"); // 保证金单位
            orderEntrust.setPrincipalAmount(BigDecimal.ZERO); // 保证金数量
            orderEntrust.setCreateTime(DateUtil.getTimeMillis()); // 开仓时间
            orderEntrust.setType(ContractOrderType.MARKET_PRICE);
            orderEntrust.setTriggerPrice(BigDecimal.ZERO); // 触发价
            orderEntrust.setEntrustPrice(BigDecimal.ZERO); // 委托价格
            orderEntrust.setEntrustType(ContractOrderEntrustType.CLOSE); // 平仓
            orderEntrust.setTriggeringTime(0L); // 触发时间，暂时无效
            orderEntrust.setShareNumber(contractCoin.getShareNumber());
            orderEntrust.setProfitAndLoss(BigDecimal.ZERO); // 盈亏（仅平仓计算）
            orderEntrust.setPatterns(wallet.getUsdtPattern()); // 仓位模式
            orderEntrust.setCloseFee(closeFee);
            orderEntrust.setCurrentPrice(currentPrice);
            orderEntrust.setIsBlast(0); // 不是爆仓单
            orderEntrust.setIsFromSpot(0);
            orderEntrust.setPrincipalAmount(wallet.getUsdtSellPrincipalAmount()); // 全部多仓保证金

            memberContractWalletService.freezeUsdtSellPosition(wallet.getId(), volume);

            // 保存委托单
            orderEntrust.setStatus(ContractOrderEntrustStatus.ENTRUST_ING); // 委托状态：委托中
            ContractOrderEntrust retObj = contractOrderEntrustService.save(orderEntrust);

            if (retObj != null) {
                // 发送消息至Exchange系统
                kafkaTemplate.send("swap-order-close", JSON.toJSONString(retObj));

                //通知钱包变更
                JSONObject jsonObj = new JSONObject();
                jsonObj.put("symbol", contractCoin.getSymbol());
                jsonObj.put("walletId", wallet.getId());
                kafkaTemplate.send("member-wallet-change", JSON.toJSONString(jsonObj));

                log.info(">>>>>>>>>>订单提交完成>>>>>>>>>>");
                // 返回结果
                MessageResult result = MessageResult.success("下单成功");
                result.setData(retObj);
                return result;
            } else {
                // 返回结果
                MessageResult result = MessageResult.error("下单失败");
                result.setData(null);
                return result;
            }
        }

        return MessageResult.error("未能平多或平空，请联系管理员");
    }

    /**
     * 合约撤销单
     *
     * @param authMember
     * @param entrustId
     * @return
     */
    @RequestMapping("cancel")
    public MessageResult cancelOrder(@SessionAttribute(SESSION_MEMBER) AuthMember authMember,
                                     Long entrustId
    ) {
        ContractOrderEntrust entrustOrder = contractOrderEntrustService.findOne(entrustId);
        if (entrustOrder == null) {
            return MessageResult.error(500, "委托不存在");
        }
        if (entrustOrder.getMemberId() != authMember.getId()) {
            return MessageResult.error(500, "非法操作");
        }
        if (entrustOrder.getStatus() != ContractOrderEntrustStatus.ENTRUST_ING) {
            return MessageResult.error(500, "委托状态错误");
        }

        // 发送消息至Exchange系统
        kafkaTemplate.send("swap-order-cancel", JSON.toJSONString(entrustOrder));

        log.info(">>>>>>>>>>订单提交完成>>>>>>>>>>");
        // 返回结果
        MessageResult result = MessageResult.success("撤单成功");
        result.setData(entrustOrder);
        return result;
    }

    /**
     * 合约撤销单(撤销所有委托，限价+计划+市价)
     *
     * @param authMember
     * @param contractCoinId
     * @return
     */
    @RequestMapping("cancel-all")
    public MessageResult cancelAllOrder(@SessionAttribute(SESSION_MEMBER) AuthMember authMember,
                                        Long contractCoinId
    ) {
        List<ContractOrderEntrust> orderList = contractOrderEntrustService.findAllByMemberIdAndContractId(authMember.getId(), contractCoinId);
        if (orderList != null && orderList.size() > 0) {
            for (int i = 0; i < orderList.size(); i++) {
                ContractOrderEntrust entrustOrder = orderList.get(i);
                if (entrustOrder.getMemberId() != authMember.getId()) {
                    continue;
                }
                if (entrustOrder.getStatus() != ContractOrderEntrustStatus.ENTRUST_ING) {
                    continue;
                }

                // 发送消息至Exchange系统
                kafkaTemplate.send("swap-order-cancel", JSON.toJSONString(entrustOrder));
            }
        }
        log.info(">>>>>>>>>>撤销所有委托成功>>>>>>>>>>");
        // 返回结果
        MessageResult result = MessageResult.success("撤单成功");
        return result;
    }

    /**
     * 获取当前持仓列表
     *
     * @param authMember
     * @return
     */
    @RequestMapping("position-list")
    public MessageResult positionList(@SessionAttribute(SESSION_MEMBER) AuthMember authMember) {
        Member member = memberService.findOne(authMember.getId());
        if (member == null) {
            return MessageResult.error(500, "用户不存在");
        }
        List<MemberContractWallet> list = memberContractWalletService.findAllByMemberId(authMember.getId());
        if (list == null) {
            return MessageResult.error(500, "不存在合约账户");
        }

        // 计算账户权益
        for (MemberContractWallet wallet : list) {
            BigDecimal currentPrice = contractCoinMatchFactory.getContractCoinMatch(wallet.getContractCoin().getSymbol()).getNowPrice();
            // 计算金本位权益（多仓 + 空仓）
            BigDecimal usdtTotalProfitAndLoss = BigDecimal.ZERO;
            // 多仓计算方法：（当前价格 / 开仓均价 - 1）* （可用仓位 + 冻结仓位） * 合约面值
            if (wallet.getUsdtBuyPrice().compareTo(BigDecimal.ZERO) > 0) {
                usdtTotalProfitAndLoss = usdtTotalProfitAndLoss.add(currentPrice.divide(wallet.getUsdtBuyPrice(), 4, BigDecimal.ROUND_DOWN).subtract(BigDecimal.ONE).multiply(wallet.getUsdtBuyPosition().add(wallet.getUsdtFrozenBuyPosition())).multiply(wallet.getUsdtShareNumber()));
            }

            // 空仓计算方法：（1 - 当前价格 / 开仓均价）* （可用仓位 + 冻结仓位） * 合约面值
            if (wallet.getUsdtSellPrice().compareTo(BigDecimal.ZERO) > 0) {
                usdtTotalProfitAndLoss = usdtTotalProfitAndLoss.add(BigDecimal.ONE.subtract(currentPrice.divide(wallet.getUsdtSellPrice(), 4, BigDecimal.ROUND_DOWN)).multiply(wallet.getUsdtSellPosition().add(wallet.getUsdtFrozenSellPosition())).multiply(wallet.getUsdtShareNumber()));
            }

            wallet.setUsdtTotalProfitAndLoss(usdtTotalProfitAndLoss);
        }

        MessageResult result = MessageResult.success("success");
        result.setData(list);
        return result;
    }

    /**
     * 获取当前持仓详情
     *
     * @param authMember
     * @return
     */
    @RequestMapping("position-detail")
    public MessageResult positionDetail(@SessionAttribute(SESSION_MEMBER) AuthMember authMember, Long contractCoinId) {
        Member member = memberService.findOne(authMember.getId());
        if (member == null) {
            return MessageResult.error(500, "用户不存在");
        }
        ContractCoin coin = contractCoinService.findOne(contractCoinId);
        if (coin == null) {
            return MessageResult.error(500, "币种不存在");
        }
        MemberContractWallet wallet = memberContractWalletService.findByMemberIdAndContractCoin(authMember.getId(), coin);
        if (wallet == null) {
            return MessageResult.error(500, "不存在该币种合约账户");
        }

        // 计算账户权益
        BigDecimal currentPrice = contractCoinMatchFactory.getContractCoinMatch(coin.getSymbol()).getNowPrice();

        // 计算金本位权益（多仓 + 空仓）
        BigDecimal usdtTotalProfitAndLoss = BigDecimal.ZERO;
        // 多仓计算方法：（当前价格 / 开仓均价 - 1）* （可用仓位 + 冻结仓位） * 合约面值
        if (wallet.getUsdtBuyPrice().compareTo(BigDecimal.ZERO) > 0) {
            usdtTotalProfitAndLoss = usdtTotalProfitAndLoss.add(currentPrice.divide(wallet.getUsdtBuyPrice(), 4, BigDecimal.ROUND_DOWN).subtract(BigDecimal.ONE).multiply(wallet.getUsdtBuyPosition().add(wallet.getUsdtFrozenBuyPosition())).multiply(wallet.getUsdtShareNumber()));
        }
        // 空仓计算方法：（1 - 当前价格 / 开仓均价）* （可用仓位 + 冻结仓位） * 合约面值
        if (wallet.getUsdtSellPrice().compareTo(BigDecimal.ZERO) > 0) {
            usdtTotalProfitAndLoss = usdtTotalProfitAndLoss.add(BigDecimal.ONE.subtract(currentPrice.divide(wallet.getUsdtSellPrice(), 4, BigDecimal.ROUND_DOWN)).multiply(wallet.getUsdtSellPosition().add(wallet.getUsdtFrozenSellPosition())).multiply(wallet.getUsdtShareNumber()));
        }

        wallet.setUsdtTotalProfitAndLoss(usdtTotalProfitAndLoss);


        String serviceName = "ADMIN";
        String url = "http://" + serviceName + "/admin/swap-coin/feePercent";

        /**
         * 查询 admin 服务，获取服务费率
         */
        ParameterizedTypeReference<List<Map<String,BigDecimal>>> typeRef = new ParameterizedTypeReference<List<Map<String,BigDecimal>>>() {};
        ResponseEntity<List<Map<String,BigDecimal>>> responseEntity = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(null), typeRef);
        List<Map<String, BigDecimal>> tempList = responseEntity.getBody();

        setMoreBlast(wallet);
        setLessBlast(wallet);

        for (Map<String, BigDecimal> item:tempList){
            if (item.containsKey(wallet.getContractCoin().getSymbol())){
                BigDecimal feePercent = item.get(wallet.getContractCoin().getSymbol());
                wallet.setFeePercent(feePercent);
            }
        }

        MessageResult result = MessageResult.success("success");
        result.setData(wallet);
        return result;
    }


    /**
     * 已知条件：
         B1: 多仓均价 getUsdtBuyPrice
         B2: 开多仓位 getUsdtBuyPosition
         B3: 开多冻结仓位 getUsdtFrozenBuyPosition
         S1: 空仓均价 getUsdtSellPrice
         S2: 开空仓位 getUsdtSellPosition
         S3: 开空冻结仓位 getUsdtFrozenSellPosition
         C1: 合约⾯值 getUsdtShareNumber
         C2: 做多杠杆倍数 getUsdtBuyLeverage
         C3: 做空杠杆倍数 getUsdtSellLeverage
         C4: 平仓⼿续费 getCloseFee
         C5: 维持保证⾦率 getMaintenanceMarginRate
         D1:多仓保证⾦ getUsdtBuyPrincipalAmount
         D2:空仓保证⾦ getUsdtSellPrincipalAmount
         D3:USDT余额 getUsdtBalance
         D4:冻结USDT余额 getUsdtFrozenBalance


         多仓收益 (X/B1-1)*(B2+B3)*C1
         空仓收益 (1-X/S1)*(S2+S3)*C1
         多仓价值 (B2+B3)*C1/C2
         空仓价值 (S2+S3)*C1/C3
         多仓⼿续费 (B2+B3)*C1*C4
         空仓⼿续费 (S2+S3)*C1*C4

        全仓爆仓价格计算公式：
            X = B1*S1*(C5*((B2+B3)/C2 + (S2+S3)/C3) - D1/C1 + (B2+B3)*C4 - D2/C1 + (S2+S3)*C4 -
        D3/C1 - D4/C1 + (B2+B3) - (S2+S3))/(S1*(B2+B3) - B1*(S2+S3))


        逐仓爆仓价格计算公式：

            多单：
                X = (C5/C2 + C4 - D1/((B2+B3)*C1) + 1) *B1
            空单：
                X = (1 - C5/C3 - C4 + D2/(((S2+S3)*C1))) * S1

     */


    /**
     * 全仓爆仓价格计算
     * @param wallet
     */
    private BigDecimal fullBlast(MemberContractWallet wallet){

        BigDecimal maintenanceMarginRate = wallet.getContractCoin().getMaintenanceMarginRate();
        BigDecimal usdtBuyLeverage = wallet.getUsdtBuyLeverage();
        BigDecimal usdtBuyPrincipalAmount = wallet.getUsdtBuyPrincipalAmount();
        BigDecimal usdtBuyPosition = wallet.getUsdtBuyPosition();
        BigDecimal usdtFrozenBuyPosition = wallet.getUsdtFrozenBuyPosition();
        BigDecimal usdtShareNumber = wallet.getUsdtShareNumber();
        BigDecimal usdtBuyPrice = wallet.getUsdtBuyPrice();
        BigDecimal usdtSellLeverage = wallet.getUsdtSellLeverage();
        BigDecimal usdtSellPrincipalAmount = wallet.getUsdtSellPrincipalAmount();
        BigDecimal usdtSellPosition = wallet.getUsdtSellPosition();
        BigDecimal usdtFrozenSellPosition = wallet.getUsdtFrozenSellPosition();
        BigDecimal usdtSellPrice = wallet.getUsdtSellPrice();
        BigDecimal closeFee = wallet.getContractCoin().getCloseFee();
        BigDecimal usdtFrozenBalance = wallet.getUsdtFrozenBalance();


//        (B2+B3)/C2
        BigDecimal temp1 = (usdtBuyPosition.add(usdtFrozenBuyPosition)).divide(usdtBuyLeverage,4, BigDecimal.ROUND_DOWN);

//        (S2+S3)/C3
        BigDecimal temp2 = (usdtSellPosition.add(usdtFrozenSellPosition)).divide(usdtSellLeverage,4, BigDecimal.ROUND_DOWN);

//        C5*((B2+B3)/C2 + (S2+S3)/C3)
        BigDecimal temp3 = (temp1.add(temp2)).multiply(maintenanceMarginRate);

//        D1/C1
        BigDecimal temp4 = usdtBuyPrincipalAmount.divide(usdtShareNumber,4, BigDecimal.ROUND_DOWN);

//        (B2+B3)*C4
        BigDecimal temp5 = (usdtBuyPosition.add(usdtFrozenBuyPosition)).multiply(closeFee);

//        D2/C1
        BigDecimal temp6 = usdtSellPrincipalAmount.divide(usdtShareNumber,4, BigDecimal.ROUND_DOWN);

//        (S2+S3)*C4
        BigDecimal temp7 = (usdtSellPosition.add(usdtFrozenSellPosition)).multiply(closeFee);

//        D3/C1
        BigDecimal temp8 = usdtSellLeverage.divide(usdtShareNumber,4, BigDecimal.ROUND_DOWN);

//        D4/C1
        BigDecimal temp9 = usdtFrozenBalance.divide(usdtShareNumber,4, BigDecimal.ROUND_DOWN);

//        (B2+B3)
        BigDecimal temp10 = usdtBuyPosition.add(usdtFrozenBuyPosition);

//        (S2+S3)
        BigDecimal temp11 = usdtSellPosition.add(usdtFrozenSellPosition);

//        S1*(B2+B3)
        BigDecimal temp12 = usdtSellPrice.multiply(temp10);

//        B1*(S2+S3)
        BigDecimal temp13 = usdtBuyPrice.multiply(temp11);

         /*
        X = B1*S1*(
                    C5*((B2+B3)/C2 + (S2+S3)/C3) -
                    D1/C1 +
                    (B2+B3)*C4 -
                    D2/C1 +
                    (S2+S3)*C4 -
                    D3/C1 -
                    D4/C1 +
                    (B2+B3) -
                    (S2+S3)
                    ) / (S1*(B2+B3) - B1*(S2+S3))

                */

        BigDecimal temp14 = temp3.subtract(temp4).add(temp5).subtract(temp6).add(temp7)
                .subtract(temp8).subtract(temp9).add(temp10).subtract(temp11);

        BigDecimal temp15 = usdtBuyPrice.multiply(usdtSellPrice).multiply(temp14);
        BigDecimal temp16 = temp12.subtract(temp13);

        BigDecimal result = temp15.divide(temp16,4, BigDecimal.ROUND_DOWN);

        return result;
    }


    /**
     * 设置多单爆仓价
     * @param wallet
     * @return
     */
    private void setMoreBlast(MemberContractWallet wallet){
        /*
            多仓⼿续费 (B2+B3)*C1*C4

        X = (getMaintenanceMarginRate/getUsdtBuyLeverage
                + getCloseFee -
                getUsdtBuyPrincipalAmount/((getUsdtBuyPosition+getUsdtFrozenBuyPosition)*getUsdtShareNumber)
        +1) * getUsdtBuyPrice

         */

        BigDecimal maintenanceMarginRate = wallet.getContractCoin().getMaintenanceMarginRate();
        BigDecimal usdtBuyLeverage = wallet.getUsdtBuyLeverage();
        BigDecimal usdtBuyPrincipalAmount = wallet.getUsdtBuyPrincipalAmount();
        BigDecimal usdtBuyPosition = wallet.getUsdtBuyPosition();
        BigDecimal usdtFrozenBuyPosition = wallet.getUsdtFrozenBuyPosition();
        BigDecimal usdtShareNumber = wallet.getUsdtShareNumber();
        BigDecimal usdtBuyPrice = wallet.getUsdtBuyPrice();

//        BigDecimal closeFee = usdtBuyPosition.add(usdtFrozenBuyPosition).multiply(usdtShareNumber).multiply(wallet.getContractCoin().getCloseFee());
        BigDecimal closeFee = wallet.getContractCoin().getCloseFee();

        log.info("设置多单爆仓价 maintenanceMarginRate:{},usdtBuyLeverage:{}" +
                        ",usdtBuyPrincipalAmount:{},usdtBuyPosition:{},usdtFrozenBuyPosition:{},usdtShareNumber:{}" +
                        ",usdtBuyPrice:{},closeFee:{}",maintenanceMarginRate,usdtBuyLeverage,usdtBuyPrincipalAmount,
                usdtBuyPosition,usdtFrozenBuyPosition,usdtShareNumber,usdtBuyPrice,closeFee);

        if (usdtBuyPosition.compareTo(BigDecimal.valueOf(0))==0&&usdtFrozenBuyPosition.compareTo(BigDecimal.valueOf(0))==0){
            //持仓为空
            wallet.setMoreBlastPrice(BigDecimal.valueOf(0));
            return;
        }

        if (wallet.getUsdtPattern().equals(ContractOrderPattern.FIXED)){
            log.info("FIXED ...");
            BigDecimal temp1 = maintenanceMarginRate.divide(usdtBuyLeverage, 8, BigDecimal.ROUND_DOWN);
            BigDecimal temp2 = (usdtBuyPosition.add(usdtFrozenBuyPosition)).multiply(usdtShareNumber);
            BigDecimal temp3 = usdtBuyPrincipalAmount.divide(temp2, 8, BigDecimal.ROUND_DOWN);
            BigDecimal temp4 = temp1.add(closeFee).subtract(temp3).add(BigDecimal.valueOf(1));

            BigDecimal moreBlastPrice = temp4.multiply(usdtBuyPrice);
            wallet.setMoreBlastPrice(moreBlastPrice);
        }else {
            wallet.setMoreBlastPrice(fullBlast(wallet));
        }

    }



    /**
     * 设置空单爆仓价
     * @param wallet
     * @return
     */
    private void setLessBlast(MemberContractWallet wallet){

        /**
         * 空仓⼿续费 (S2+S3)*C1*C4
         *
         * X = (1 - getMaintenanceMarginRate/getUsdtSellLeverage - getCloseFee +
         * getUsdtSellPrincipalAmount/((getUsdtSellPosition +
         * getUsdtFrozenSellPosition)*getUsdtShareNumber)) * getUsdtSellPrice
         *
         */

        BigDecimal maintenanceMarginRate = wallet.getContractCoin().getMaintenanceMarginRate();
        BigDecimal usdtSellLeverage = wallet.getUsdtSellLeverage();
        BigDecimal usdtSellPrincipalAmount = wallet.getUsdtSellPrincipalAmount();
        BigDecimal usdtSellPosition = wallet.getUsdtSellPosition();
        BigDecimal usdtFrozenSellPosition = wallet.getUsdtFrozenSellPosition();
        BigDecimal usdtShareNumber = wallet.getUsdtShareNumber();
        BigDecimal usdtSellPrice = wallet.getUsdtSellPrice();

//        BigDecimal closeFee = usdtSellPosition.add(usdtFrozenSellPosition).multiply(usdtShareNumber).multiply(wallet.getContractCoin().getCloseFee());
        BigDecimal closeFee = wallet.getContractCoin().getCloseFee();

        log.info("设置空单爆仓价 maintenanceMarginRate:{},usdtSellLeverage:{}" +
                ",usdtSellPrincipalAmount:{},usdtSellPosition:{},usdtFrozenSellPosition:{},usdtShareNumber:{}" +
                ",usdtSellPrice:{},closeFee:{}",maintenanceMarginRate,usdtSellLeverage,usdtSellPrincipalAmount,
                usdtSellPosition,usdtFrozenSellPosition,usdtShareNumber,usdtSellPrice,closeFee);

        if (usdtSellPosition.compareTo(BigDecimal.valueOf(0))==0&&usdtFrozenSellPosition.compareTo(BigDecimal.valueOf(0))==0){
            //持仓为空
            wallet.setLessBlastPrice(BigDecimal.valueOf(0));
            return;
        }


        if (wallet.getUsdtPattern().equals(ContractOrderPattern.FIXED)){
            log.info("FIXED ...");

            BigDecimal temp1 = maintenanceMarginRate.divide(usdtSellLeverage, 8, BigDecimal.ROUND_DOWN);
            BigDecimal temp2 = (usdtSellPosition.add(usdtFrozenSellPosition)).multiply(usdtShareNumber);
            BigDecimal temp3 = usdtSellPrincipalAmount.divide(temp2, 8, BigDecimal.ROUND_DOWN);
            BigDecimal temp4 = BigDecimal.valueOf(1).subtract(temp1).subtract(closeFee).add(temp3);

            BigDecimal lessBlastPrice = temp4.multiply(usdtSellPrice);
            wallet.setLessBlastPrice(lessBlastPrice);
        }else {
            wallet.setLessBlastPrice(fullBlast(wallet));
        }

    }






    /**
     * 获取当前委托列表
     *
     * @param authMember
     * @return
     */
    @RequestMapping("current")
    public Page<ContractOrderEntrust> entrustList(@SessionAttribute(SESSION_MEMBER) AuthMember authMember,
                                                  Long contractCoinId, // 合约交易对
                                                  int pageNo,
                                                  int pageSize
    ) {
        Page<ContractOrderEntrust> contractOrderEntrustOrders = contractOrderEntrustService.queryPageEntrustingOrdersBySymbol(authMember.getId(), contractCoinId, pageNo, pageSize);
        return contractOrderEntrustOrders;
    }

    /**
     * 获取历史委托列表
     *
     * @param authMember
     * @return
     */
    @RequestMapping("history")
    public Page<ContractOrderEntrust> entrustListHistory(@SessionAttribute(SESSION_MEMBER) AuthMember authMember,
                                                         Long contractCoinId, // 合约交易对
                                                         int pageNo,
                                                         int pageSize
    ) {
        Page<ContractOrderEntrust> contractOrderEntrustOrders = contractOrderEntrustService.queryPageEntrustHistoryOrdersBySymbol(authMember.getId(), contractCoinId, pageNo, pageSize);
        return contractOrderEntrustOrders;
    }

    /**
     * 是否能够切换持仓模式（全仓、逐仓）
     * 需要查询是否有逐仓/全仓订单（有则不能下单）
     *
     * @param authMember
     * @param targetPattern 目标模式（1：全仓， 2：逐仓）
     * @return
     */
    @RequestMapping("can-switch-pattern")
    public MessageResult canSwitchPattern(@SessionAttribute(SESSION_MEMBER) AuthMember authMember, Long contractCoinId, ContractOrderPattern targetPattern) {

        ContractCoin contractCoin = contractCoinService.findOne(contractCoinId);
        if (contractCoin == null) {
            return MessageResult.error(500, "合约不存在");
        }
        MemberContractWallet wallet = memberContractWalletService.findByMemberIdAndContractCoin(authMember.getId(), contractCoin);
        if (wallet == null) {
            return MessageResult.error(500, "合约账户不存在");
        }

        ContractOrderPattern temPattern = targetPattern == ContractOrderPattern.CROSSED ? ContractOrderPattern.FIXED : ContractOrderPattern.CROSSED;
        // 查询所有委托的订单
        long sizeEntrustOrder = contractOrderEntrustService.queryEntrustingOrdersCountByContractCoinIdAndPattern(authMember.getId(), contractCoinId, temPattern);
        if (sizeEntrustOrder > 0) {
            return MessageResult.error(500, "您有正在委托的订单，请先平仓或者撤销");
        }
        MessageResult result = MessageResult.success("success");
        result.setData(null);
        return result;
    }

    /**
     * 更改持仓模式
     * 因本合约是金本位合约，因此切换持仓模式会切换所有交易对的仓位模式，所以会查询所有非目标持仓模式的订单，从而进行判断是否能够切换仓位
     *
     * @param authMember
     * @param contractCoinId
     * @param targetPattern
     * @return
     */
    @RequestMapping("switch-pattern")
    public MessageResult switchPattern(@SessionAttribute(SESSION_MEMBER) AuthMember authMember, Long contractCoinId, ContractOrderPattern targetPattern) {
        ContractCoin contractCoin = contractCoinService.findOne(contractCoinId);
        if (contractCoin == null) {
            return MessageResult.error(500, "合约不存在");
        }
        MemberContractWallet memberContractWallet = memberContractWalletService.findByMemberIdAndContractCoin(authMember.getId(), contractCoin);
        if (memberContractWallet == null) {
            return MessageResult.error(500, "合约账户不存在");
        }

        if (targetPattern != ContractOrderPattern.FIXED && targetPattern != ContractOrderPattern.CROSSED) {
            return MessageResult.error(500, msService.getMessage("ILLEGAL_ARGUMENT"));
        }
        ContractOrderPattern temPattern = targetPattern == ContractOrderPattern.CROSSED ? ContractOrderPattern.FIXED : ContractOrderPattern.CROSSED;
        // 查询当前合约是否有正在委托的单
        long sizeEntrustOrder = contractOrderEntrustService.queryEntrustingOrdersCountByContractCoinIdAndPattern(authMember.getId(), contractCoinId, temPattern);
        if (sizeEntrustOrder > 0) {
            return MessageResult.error(500, "您有正在委托的单，请先撤销");
        }

        memberContractWallet.setUsdtPattern(targetPattern);
        memberContractWallet = memberContractWalletService.save(memberContractWallet);

        if (memberContractWallet != null) {

            //通知钱包变更
            JSONObject jsonObj = new JSONObject();
            jsonObj.put("symbol", contractCoin.getSymbol());
            jsonObj.put("walletId", memberContractWallet.getId());
            kafkaTemplate.send("member-wallet-change", JSON.toJSONString(jsonObj));

            MessageResult result = MessageResult.success("切换仓位模式成功");
            result.setData(memberContractWallet);
            return result;
        } else {
            return MessageResult.error(500, "切换仓位模式失败");
        }
    }

    /**
     * 修改指定交易对的杠杆倍数
     * 调整杠杆倍数理论上应该会将现有持仓订单中的多余保证金释放出来，但是本系统暂未实现此功能，留待后续需要再实现
     * 调整杠杆其实仅仅会影响用户相同保证金能开多少张合约的计算
     *
     * @param authMember
     * @param contractCoinId
     * @param leverage
     * @param direction
     * @return
     */
    @RequestMapping("modify-leverage")
    public MessageResult modifyLeverage(@SessionAttribute(SESSION_MEMBER) AuthMember authMember,
                                        Long contractCoinId,
                                        BigDecimal leverage,
                                        ContractOrderDirection direction) {
        ContractCoin contractCoin = contractCoinService.findOne(contractCoinId);
        if (contractCoin == null) {
            return MessageResult.error(500, "合约不存在");
        }
        MemberContractWallet memberContractWallet = memberContractWalletService.findByMemberIdAndContractCoin(authMember.getId(), contractCoin);
        if (memberContractWallet == null) {
            return MessageResult.error(500, "合约账户不存在");
        }

        // 杠杆倍数是否在规定范围
        if (contractCoin.getLeverageType() == 1) { // 分离倍数
            String[] leverageArr = contractCoin.getLeverage().split(",");
            boolean isExist = false;
            for (String str : leverageArr) {
                if (BigDecimal.valueOf(Integer.parseInt(str)).compareTo(leverage) == 0) {
                    isExist = true;
                }
            }
            if (!isExist) {
                return MessageResult.error(500, "杠杆倍数不存在");
            }
        } else { // 范围倍数
            String[] leverageArr = contractCoin.getLeverage().split(",");
            if (leverageArr.length != 2) return MessageResult.error(500, "币种杠杆设置错误，请联系管理员");

            BigDecimal low = BigDecimal.valueOf(Integer.parseInt(leverageArr[0]));
            BigDecimal high = BigDecimal.valueOf(Integer.parseInt(leverageArr[1]));
            if (leverage.compareTo(low) < 0 || leverage.compareTo(high) > 0) {
                return MessageResult.error(500, "杠杆倍数超出允许范围");
            }
        }

        // 查询当前合约是否有正在委托的单
        long sizeEntrustOrder = contractOrderEntrustService.queryEntrustingOrdersCountByContractCoinId(authMember.getId(), contractCoinId);
        if (sizeEntrustOrder > 0) {
            return MessageResult.error(500, "您有正在委托的单，请先撤销");
        }

        // 如果当前是逐仓模式，需要追加保证金的场合（杠杆倍数增加），要检查保证金是否足够
        if (memberContractWallet.getUsdtPattern() == ContractOrderPattern.FIXED) {
            if (direction == ContractOrderDirection.BUY) { // 调整多仓杠杆倍数
                if (leverage.compareTo(memberContractWallet.getUsdtBuyLeverage()) > 0) { // 如果杠杆加大
                    // 计算保证金
                    BigDecimal needPrinAmount = memberContractWallet.getUsdtBuyPosition().multiply(memberContractWallet.getUsdtShareNumber()).divide(leverage, 8, BigDecimal.ROUND_DOWN);
                    if (needPrinAmount.compareTo(memberContractWallet.getUsdtBuyPrincipalAmount()) > 0) {
                        // 调整保证金(如果余额不足以支付保证金，报错)
                        if (memberContractWallet.getUsdtBalance().compareTo(needPrinAmount.subtract(memberContractWallet.getUsdtBuyPrincipalAmount())) < 0) {
                            return MessageResult.error(500, "调整杠杆过高，账户余额不足以支付保证金");
                        }
                        // 增加保证金
                        memberContractWalletService.increaseUsdtBuyPrincipalAmount(memberContractWallet.getId(), needPrinAmount.subtract(memberContractWallet.getUsdtBuyPrincipalAmount()));
                    }
                }
                // 杠杆倍数降低，不会减少保证金，此处有待商榷，其实无所谓
                // 调整杠杆倍数
                memberContractWalletService.modifyUsdtBuyLeverage(memberContractWallet.getId(), leverage);
            } else { // 调整空仓杠杆倍数
                if (leverage.compareTo(memberContractWallet.getUsdtSellLeverage()) > 0) { // 如果杠杆加大
                    // 计算保证金
                    BigDecimal needPrinAmount = memberContractWallet.getUsdtSellPosition().multiply(memberContractWallet.getUsdtShareNumber()).divide(leverage, 8, BigDecimal.ROUND_DOWN);
                    if (needPrinAmount.compareTo(memberContractWallet.getUsdtSellPrincipalAmount()) > 0) {
                        // 调整保证金(如果余额不足以支付保证金，报错)
                        if (memberContractWallet.getUsdtBalance().compareTo(needPrinAmount.subtract(memberContractWallet.getUsdtSellPrincipalAmount())) < 0) {
                            return MessageResult.error(500, "调整杠杆过高，账户余额不足以支付保证金");
                        }
                        // 增加保证金
                        memberContractWalletService.increaseUsdtSellPrincipalAmount(memberContractWallet.getId(), needPrinAmount.subtract(memberContractWallet.getUsdtSellPrincipalAmount()));
                    }
                }
                // 杠杆倍数降低，不会减少保证金，此处有待商榷，其实无所谓
                // 调整杠杆倍数
                memberContractWalletService.modifyUsdtSellLeverage(memberContractWallet.getId(), leverage);
            }
            //通知钱包变更
            JSONObject jsonObj = new JSONObject();
            jsonObj.put("symbol", contractCoin.getSymbol());
            jsonObj.put("walletId", memberContractWallet.getId());
            kafkaTemplate.send("member-wallet-change", JSON.toJSONString(jsonObj));
        } else {// 如果当前是全仓模式，需要检查保证金够不够(根据最新价格计算)
            BigDecimal totalNeedPrin = BigDecimal.ZERO;
            BigDecimal currentPrice = contractCoinMatchFactory.getContractCoinMatch(contractCoin.getSymbol()).getNowPrice();
            if (direction == ContractOrderDirection.BUY) { // 调整多仓杠杆倍数
                totalNeedPrin = totalNeedPrin.add(memberContractWallet.getUsdtBuyPosition().multiply(memberContractWallet.getUsdtShareNumber()).divide(leverage, 8, BigDecimal.ROUND_DOWN));
            } else {
                totalNeedPrin = totalNeedPrin.add(memberContractWallet.getUsdtSellPosition().multiply(memberContractWallet.getUsdtShareNumber()).divide(leverage, 8, BigDecimal.ROUND_DOWN));
            }

            // 计算账户总权益
            // 计算金本位权益（多仓 + 空仓）
            BigDecimal usdtTotalProfitAndLoss = BigDecimal.ZERO;
            // 多仓计算方法：（当前价格 / 开仓均价 - 1）* （可用仓位 + 冻结仓位） * 合约面值
            if (memberContractWallet.getUsdtBuyPrice().compareTo(BigDecimal.ZERO) > 0) {
                usdtTotalProfitAndLoss = usdtTotalProfitAndLoss.add(currentPrice.divide(memberContractWallet.getUsdtBuyPrice(), 8, BigDecimal.ROUND_DOWN).subtract(BigDecimal.ONE).multiply(memberContractWallet.getUsdtBuyPosition()).multiply(memberContractWallet.getUsdtShareNumber()));
            }
            // 空仓计算方法：（1 - 当前价格 / 开仓均价）* （可用仓位 + 冻结仓位） * 合约面值
            if (memberContractWallet.getUsdtSellPrice().compareTo(BigDecimal.ZERO) > 0) {
                usdtTotalProfitAndLoss = usdtTotalProfitAndLoss.add(BigDecimal.ONE.subtract(currentPrice.divide(memberContractWallet.getUsdtSellPrice(), 8, BigDecimal.ROUND_DOWN)).multiply(memberContractWallet.getUsdtSellPosition()).multiply(memberContractWallet.getUsdtShareNumber()));
            }

            // 如果总共需要的保证金 大于 可用余额+多仓仓位保证金+空仓仓位保证金
            if (totalNeedPrin.compareTo(usdtTotalProfitAndLoss.add(memberContractWallet.getUsdtBalance()).add(memberContractWallet.getUsdtBuyPrincipalAmount()).add(memberContractWallet.getUsdtSellPrincipalAmount())) > 0) {
                return MessageResult.error(500, "调整杠杆过高，账户余额不足以支付保证金");
            }

            if (direction == ContractOrderDirection.BUY) { // 调整多仓杠杆倍数
                memberContractWalletService.modifyUsdtBuyLeverage(memberContractWallet.getId(), leverage);
            } else {
                memberContractWalletService.modifyUsdtSellLeverage(memberContractWallet.getId(), leverage);
            }
            //通知钱包变更
            JSONObject jsonObj = new JSONObject();
            jsonObj.put("symbol", contractCoin.getSymbol());
            jsonObj.put("walletId", memberContractWallet.getId());
            kafkaTemplate.send("member-wallet-change", JSON.toJSONString(jsonObj));
        }

        MessageResult result = MessageResult.success("success");
        result.setData(null);
        return result;
    }

    /**
     * 调整保证金
     *
     * @param authMember
     * @param contractCoinId
     * @param principal
     * @param direction
     * @param type           0:增加  1：减少
     * @return
     */
    @RequestMapping("ajust-principal")
    public MessageResult ajustPrincipal(@SessionAttribute(SESSION_MEMBER) AuthMember authMember,
                                        Long contractCoinId,
                                        BigDecimal principal,
                                        ContractOrderDirection direction,
                                        Integer type) {
        ContractCoin contractCoin = contractCoinService.findOne(contractCoinId);
        if (contractCoin == null) {
            return MessageResult.error(500, "合约不存在");
        }
        MemberContractWallet memberContractWallet = memberContractWalletService.findByMemberIdAndContractCoin(authMember.getId(), contractCoin);
        if (memberContractWallet == null) {
            return MessageResult.error(500, "合约账户不存在");
        }
        if (memberContractWallet.getUsdtPattern() == ContractOrderPattern.CROSSED) {
            return MessageResult.error(500, "全仓模式无需调整保证金");
        }

        // 获取当前价格
        BigDecimal currentPrice = contractCoinMatchFactory.getContractCoinMatch(contractCoin.getSymbol()).getNowPrice();
        if (direction == ContractOrderDirection.BUY) { // 调整多仓保证金
            if (type == 1) { // 减少保证金
                // 计算盈亏
                BigDecimal pL = currentPrice.divide(memberContractWallet.getUsdtBuyPrice(), 8, BigDecimal.ROUND_DOWN).subtract(BigDecimal.ONE).multiply(memberContractWallet.getUsdtBuyPosition()).multiply(memberContractWallet.getUsdtShareNumber());
                // 如果减少后的保证金 小于 持仓需要保证金
                if (memberContractWallet.getUsdtBuyPrincipalAmount().subtract(principal).compareTo(memberContractWallet.getUsdtBuyPrincipalAmount().add(pL)) < 0) {
                    return MessageResult.error(500, "保证金不足，无法调整");
                } else {
                    memberContractWalletService.decreaseUsdtBuyPrincipalAmount(memberContractWallet.getId(), principal);
                    return MessageResult.success("success");
                }
            } else { // 直接增加保证金
                if (memberContractWallet.getUsdtBalance().compareTo(principal) < 0) {
                    return MessageResult.error(500, "保证金不足，无法调整");
                }
                memberContractWalletService.increaseUsdtBuyPrincipalAmount(memberContractWallet.getId(), principal);
                return MessageResult.success("success");
            }
        } else {
            if (type == 1) { // 减少保证金
                // 计算盈亏
                BigDecimal pL = BigDecimal.ONE.subtract(currentPrice.divide(memberContractWallet.getUsdtSellPrice(), 8, BigDecimal.ROUND_DOWN)).multiply(memberContractWallet.getUsdtSellPosition()).multiply(memberContractWallet.getUsdtShareNumber());
                // 如果减少后的保证金 小于 持仓需要保证金
                if (memberContractWallet.getUsdtSellPrincipalAmount().subtract(principal).compareTo(memberContractWallet.getUsdtSellPrincipalAmount().add(pL)) < 0) {
                    return MessageResult.error(500, "保证金不足，无法调整");
                } else {
                    memberContractWalletService.decreaseUsdtSellPrincipalAmount(memberContractWallet.getId(), principal);
                    return MessageResult.success("success");
                }
            } else { // 直接增加保证金
                if (memberContractWallet.getUsdtBalance().compareTo(principal) < 0) {
                    return MessageResult.error(500, "保证金不足，无法调整");
                }
                memberContractWalletService.increaseUsdtSellPrincipalAmount(memberContractWallet.getId(), principal);
                return MessageResult.success("success");
            }
        }
    }
}