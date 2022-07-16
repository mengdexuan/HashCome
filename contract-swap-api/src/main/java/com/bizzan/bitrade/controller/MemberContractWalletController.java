package com.bizzan.bitrade.controller;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.bizzan.bitrade.constant.WalletType;
import com.bizzan.bitrade.engine.ContractCoinMatchFactory;
import com.bizzan.bitrade.entity.*;
import com.bizzan.bitrade.entity.transform.AuthMember;
import com.bizzan.bitrade.service.*;
import com.bizzan.bitrade.util.MessageResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.List;

import static com.bizzan.bitrade.constant.SysConstant.SESSION_MEMBER;

@Slf4j
@RestController
@RequestMapping("/wallet")
public class MemberContractWalletController {
    @Autowired
    private MemberContractWalletService memberContractWalletService;

    @Autowired
    private MemberWalletService walletService;

    @Autowired
    private MemberService memberService;
    @Autowired
    private RestTemplate restTemplate;
    @Autowired
    private ContractCoinService contractCoinService;

    @Autowired
    private WalletTransRecordService walletTransRecordService;

    @Autowired
    private ContractCoinMatchFactory contractCoinMatchFactory; // 合约引擎工厂

    @Autowired
    private ContractOrderEntrustService contractOrderEntrustService;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;
    /**
     * 查询所有合约账户
     *
     * @param authMember
     * @return
     */
    @RequestMapping("list")
    public MessageResult getWalletList(@SessionAttribute(SESSION_MEMBER) AuthMember authMember) {
        Member member = memberService.findOne(authMember.getId());
        if(member == null) {
            return MessageResult.error(500, "用户不存在");
        }
        List<MemberContractWallet> list = memberContractWalletService.findAllByMemberId(authMember.getId());
        if(list == null) {
            return MessageResult.error(500, "不存在合约账户");
        }

        String serviceName = "bitrade-market";
        String url = "http://" + serviceName + "/market/exchange-rate/usd-cny";
        ResponseEntity<MessageResult> retResult = restTemplate.getForEntity(url, MessageResult.class);
        BigDecimal rate = BigDecimal.valueOf(7);
        if (retResult.getStatusCode().value() == 200 && retResult.getBody().getCode() == 0) {
            rate = new BigDecimal((Double) retResult.getBody().getData());
        }

        // 计算账户权益
        for (MemberContractWallet wallet : list) {
            BigDecimal currentPrice = contractCoinMatchFactory.getContractCoinMatch(wallet.getContractCoin().getSymbol()).getNowPrice();
            // 计算金本位权益（多仓 + 空仓）
            BigDecimal usdtTotalProfitAndLoss = BigDecimal.ZERO;
            // 多仓计算方法：（当前价格 / 开仓均价 - 1）* （可用仓位 + 冻结仓位） * 合约面值
            if(wallet.getUsdtBuyPrice().compareTo(BigDecimal.ZERO) > 0) {
                usdtTotalProfitAndLoss = usdtTotalProfitAndLoss.add(currentPrice.divide(wallet.getUsdtBuyPrice(), 8, BigDecimal.ROUND_HALF_DOWN).subtract(BigDecimal.ONE).multiply(wallet.getUsdtBuyPosition().add(wallet.getUsdtFrozenBuyPosition())).multiply(wallet.getUsdtShareNumber()));
            }

            // 空仓计算方法：（1 - 当前价格 / 开仓均价）* （可用仓位 + 冻结仓位） * 合约面值
            if(wallet.getUsdtSellPrice().compareTo(BigDecimal.ZERO) > 0) {
                usdtTotalProfitAndLoss = usdtTotalProfitAndLoss.add(BigDecimal.ONE.subtract(currentPrice.divide(wallet.getUsdtSellPrice(),8, BigDecimal.ROUND_HALF_DOWN)).multiply(wallet.getUsdtSellPosition().add(wallet.getUsdtFrozenSellPosition())).multiply(wallet.getUsdtShareNumber()));
            }

            wallet.setUsdtTotalProfitAndLoss(usdtTotalProfitAndLoss);
            wallet.getContractCoin().setCurrentPrice(currentPrice);
            wallet.getContractCoin().setUsdtRate(rate);
        }

        MessageResult result = MessageResult.success("success");
        result.setData(list);
        return result;
    }


    @RequestMapping("list-empty")
    public MessageResult getWalletList() {
        Member member = memberService.findOne(1L);
        if(member == null) {
            return MessageResult.error(500, "用户不存在");
        }
        List<MemberContractWallet> list = memberContractWalletService.findAllByMemberId(1L);
        if(list == null) {
            return MessageResult.error(500, "不存在合约账户");
        }
        // 计算账户权益
        for (MemberContractWallet wallet : list) {
            wallet.setUsdtPattern(ContractOrderPattern.FIXED);
            wallet.setUsdtBuyPosition(BigDecimal.ZERO);
            wallet.setUsdtTotalProfitAndLoss(BigDecimal.ZERO);
            wallet.setCoinBalance(BigDecimal.ZERO);
            wallet.setCoinBuyLeverage(BigDecimal.TEN); // 10倍杠杆
            wallet.setCoinBuyPosition(BigDecimal.ZERO);
            wallet.setCoinBuyPrice(BigDecimal.ZERO);
            wallet.setCoinBuyPrincipalAmount(BigDecimal.ZERO);
            wallet.setCoinFrozenBalance(BigDecimal.ZERO);
            wallet.setCoinFrozenBuyPosition(BigDecimal.ZERO);
            wallet.setCoinFrozenSellPosition(BigDecimal.ZERO);
            wallet.setCoinPattern(ContractOrderPattern.FIXED);
            wallet.setCoinSellLeverage(BigDecimal.TEN);
            wallet.setCoinSellPosition(BigDecimal.ZERO);
            wallet.setCoinSellPrice(BigDecimal.ZERO);
            wallet.setCoinSellPrincipalAmount(BigDecimal.ZERO);
            wallet.setCoinTotalProfitAndLoss(BigDecimal.ZERO);
            wallet.setMemberId(1L);
            wallet.setUsdtBalance(BigDecimal.ZERO);
            wallet.setUsdtBuyLeverage(BigDecimal.TEN);
            wallet.setUsdtBuyPrice(BigDecimal.ZERO);
            wallet.setUsdtBuyPrincipalAmount(BigDecimal.ZERO);
            wallet.setUsdtFrozenBalance(BigDecimal.ZERO);
            wallet.setUsdtFrozenBuyPosition(BigDecimal.ZERO);
            wallet.setUsdtFrozenSellPosition(BigDecimal.ZERO);
            wallet.setUsdtSellLeverage(BigDecimal.TEN);
            wallet.setUsdtSellPosition(BigDecimal.ZERO);
            wallet.setUsdtSellPrice(BigDecimal.ZERO);
            wallet.setUsdtSellPrincipalAmount(BigDecimal.ZERO);
        }

        MessageResult result = MessageResult.success("success");
        result.setData(list);
        return result;
    }

    /**
     * 获取用户指定合约币种的钱包信息
     * @param authMember
     * @return
     */
    @RequestMapping("detail")
    public MessageResult getContractWallet(@SessionAttribute(SESSION_MEMBER) AuthMember authMember, Long contractCoinId) {
        ContractCoin coin = contractCoinService.findOne(contractCoinId);
        if(coin == null) {
            return MessageResult.error(500, "交易对不存在");
        }
        Member member = memberService.findOne(authMember.getId());
        if(member == null) {
            return MessageResult.error(500, "用户不存在");
        }
        MemberContractWallet wallet = memberContractWalletService.findByMemberIdAndContractCoin(authMember.getId(), coin);
        if(wallet == null) {
            return MessageResult.error(500, "不存在该币种合约账户");
        }

        // 计算账户权益
        BigDecimal currentPrice = contractCoinMatchFactory.getContractCoinMatch(coin.getSymbol()).getNowPrice();

        // 计算金本位权益（多仓 + 空仓）
        BigDecimal usdtTotalProfitAndLoss = BigDecimal.ZERO;
        // 多仓计算方法：（当前价格 / 开仓均价 - 1）* （可用仓位 + 冻结仓位） * 合约面值
        if(wallet.getUsdtBuyPrice().compareTo(BigDecimal.ZERO) > 0) {
            usdtTotalProfitAndLoss = usdtTotalProfitAndLoss.add(currentPrice.divide(wallet.getUsdtBuyPrice(), 8, BigDecimal.ROUND_HALF_DOWN).subtract(BigDecimal.ONE).multiply(wallet.getUsdtBuyPosition().add(wallet.getUsdtFrozenBuyPosition())).multiply(wallet.getUsdtShareNumber()));
        }
        // 空仓计算方法：（1 - 当前价格 / 开仓均价）* （可用仓位 + 冻结仓位） * 合约面值
        if(wallet.getUsdtSellPrice().compareTo(BigDecimal.ZERO) > 0) {
            usdtTotalProfitAndLoss = usdtTotalProfitAndLoss.add(BigDecimal.ONE.subtract(currentPrice.divide(wallet.getUsdtSellPrice(), 8, BigDecimal.ROUND_HALF_DOWN)).multiply(wallet.getUsdtSellPosition().add(wallet.getUsdtFrozenSellPosition())).multiply(wallet.getUsdtShareNumber()));
        }

        wallet.setUsdtTotalProfitAndLoss(usdtTotalProfitAndLoss);
        wallet.getContractCoin().setCurrentPrice(currentPrice);

        MessageResult result = MessageResult.success("success");
        result.setData(wallet);
        return result;
    }



    /**
     * 资金划转
     * @param member
     * @param unit
     * @param from
     * @param to
     * @param amount
     * @return
     */
    @RequestMapping("trans")
    public MessageResult transWallet(@SessionAttribute(SESSION_MEMBER) AuthMember member,
                                     @RequestParam(value = "unit") String unit,// 划转币种单位
                                     @RequestParam(value = "from") WalletType from,// 从那个类型的钱包转出
                                     @RequestParam(value = "to") WalletType to,// 转入那个类型的钱包
                                     @RequestParam(value = "fromWalletId", required = false) Long fromWalletId,// 转出钱包ID
                                     @RequestParam(value = "toWalletId", required = false) Long toWalletId,// 转入钱包ID
                                     @RequestParam(value = "amount") BigDecimal amount// 划转数量
                                     ) {
        Member member1 = memberService.findOne(member.getId());
        if(member1 == null) {
            return MessageResult.error("非法请求");
        }
        if(fromWalletId == toWalletId) {
            return MessageResult.error("请选择不同的划转钱包");
        }
        if(from != WalletType.SPOT && from != WalletType.SWAP) {
            return MessageResult.error("转出钱包不存在");
        }
        if(to != WalletType.SPOT && to != WalletType.SWAP) {
            return MessageResult.error("转入钱包不存在");
        }

        if(from == WalletType.SPOT && to == WalletType.SWAP) {// 从 币币钱包 划转到 合约钱包
            MemberWallet walletFrom = walletService.findByCoinUnitAndMemberId(unit, member.getId());
            MemberContractWallet walletTo = memberContractWalletService.findOne(toWalletId);

            if(walletFrom == null || walletTo == null) {
                return MessageResult.error("转出或转入钱包不存在");
            }
            if(walletFrom.getBalance().compareTo(amount) < 0) {
                return MessageResult.error("转出钱包余额不足");
            }
            if(unit.equals("USDT")) {
                walletService.deductBalance(walletFrom, amount);
                memberContractWalletService.increaseUsdtBalance(walletTo.getId(), amount);
            }else{
                // TODO
                // 划转币本位，暂时未开发
            }
            //通知钱包变更
            JSONObject jsonObj = new JSONObject();
            jsonObj.put("symbol", walletTo.getContractCoin().getSymbol());
            jsonObj.put("walletId", walletTo.getId());
            kafkaTemplate.send("member-wallet-change", JSON.toJSONString(jsonObj));

        }else if(from == WalletType.SWAP && to == WalletType.SPOT) {// 从 合约钱包 划转到 币币钱包
            MemberContractWallet walletFrom = memberContractWalletService.findOne(fromWalletId);
            MemberWallet walletTo = walletService.findByCoinUnitAndMemberId(unit, member.getId());
            if(walletFrom == null || walletTo == null) {
                return MessageResult.error("转出或转入钱包不存在");
            }
            if(unit.equals("USDT")) {
                if (walletFrom.getUsdtBalance().compareTo(amount) < 0) {
                    return MessageResult.error("转出钱包余额不足");
                }
                memberContractWalletService.decreaseUsdtBalance(walletFrom.getId(), amount);
                walletService.increaseBalance(walletTo.getId(), amount);
            }
            //通知钱包变更
            JSONObject jsonObj = new JSONObject();
            jsonObj.put("symbol", walletFrom.getContractCoin().getSymbol());
            jsonObj.put("walletId", walletFrom.getId());
            kafkaTemplate.send("member-wallet-change", JSON.toJSONString(jsonObj));

        }else if(from == WalletType.SWAP && to == WalletType.SPOT){ // 永续合约之间划转
            MemberContractWallet walletFrom = memberContractWalletService.findOne(fromWalletId);
            MemberContractWallet walletTo = memberContractWalletService.findOne(toWalletId);
            if(walletFrom == null || walletTo == null) {
                return MessageResult.error("合约钱包不存在");
            }
            if(fromWalletId == toWalletId) {
                return MessageResult.error("请选择不同的合约钱包账户");
            }
            if(unit.equals("USDT")) {
                if(walletFrom.getUsdtBalance().compareTo(amount) < 0) {
                    return MessageResult.error("转出钱包余额不足");
                }
                memberContractWalletService.decreaseUsdtBalance(walletFrom.getId(), amount);
                memberContractWalletService.increaseUsdtBalance(walletTo.getId(), amount);

                //通知钱包变更
                JSONObject jsonObj = new JSONObject();
                jsonObj.put("symbol", walletFrom.getContractCoin().getSymbol());
                jsonObj.put("walletId", walletFrom.getId());
                kafkaTemplate.send("member-wallet-change", JSON.toJSONString(jsonObj));

                //通知钱包变更
                JSONObject jsonObj2 = new JSONObject();
                jsonObj2.put("symbol", walletTo.getContractCoin().getSymbol());
                jsonObj2.put("walletId", walletTo.getId());
                kafkaTemplate.send("member-wallet-change", JSON.toJSONString(jsonObj2));
            }
        }else{
            return MessageResult.error("找不到转出转入钱包");
        }
        // 新增划转记录
        WalletTransRecord record = new WalletTransRecord();
        record.setAmount(amount);
        record.setUnit(unit);
        record.setSource(from);
        record.setTarget(to);
        record.setMemberId(member.getId());
        walletTransRecordService.save(record);
        return MessageResult.success();
    }

    @RequestMapping("all")
    public MessageResult getContractWallet(@SessionAttribute(SESSION_MEMBER) AuthMember authMember, String symbol) {
        List<MemberContractWallet> list = contractCoinMatchFactory.getContractCoinMatch(symbol).getMemberContractWalletList();
        MessageResult result = MessageResult.success("success");
        result.setData(list);
        return result;
    }

}
