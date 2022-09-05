package com.bizzan.bitrade.job;

import com.bizzan.bitrade.dao.MemberContractWalletDao;
import com.bizzan.bitrade.entity.ContractCoin;
import com.bizzan.bitrade.entity.MemberContractWallet;
import com.bizzan.bitrade.service.ContractCoinService;
import com.bizzan.bitrade.service.MemberContractWalletService;
import com.google.common.collect.Lists;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 *
 * 扣减用户持仓资金费率
 * @author mengdexuan on 2022/7/16 17:27.
 */
@Slf4j
@Component
public class FundingRateJob {
    @Autowired
    MemberContractWalletDao memberContractWalletDao;

    @Autowired
    MemberContractWalletService memberContractWalletService;

    @Autowired
    ContractCoinService contractCoinService;

    List<Map<String,BigDecimal>> feePercentList = Lists.newArrayList();

    @Autowired
    RestTemplate restTemplate;


    //每 8 小时执行一次
    @Scheduled(cron = "0 0 */8 * * ?")
    public void run(){
        feePercentList.clear();

        String serviceName = "ADMIN";
        String url = "http://" + serviceName + "/admin/swap-coin/feePercent";

        /**
         * 查询 admin 服务，获取服务费率
         */
        ParameterizedTypeReference<List<Map<String,BigDecimal>>> typeRef = new ParameterizedTypeReference<List<Map<String,BigDecimal>>>() {};
        ResponseEntity<List<Map<String,BigDecimal>>> responseEntity = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(null), typeRef);
        feePercentList =responseEntity.getBody();

        List<MemberContractWallet> list = memberContractWalletDao.findAllPosition();
        for (MemberContractWallet item:list){
            dealOne(item);
        }
    }


    private void dealOne(MemberContractWallet wallet){

//        保证金 - （多仓价值 * 保证金率）
/*
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
*/
        BigDecimal usdtBuyLeverage = wallet.getUsdtBuyLeverage();
        BigDecimal usdtBuyPosition = wallet.getUsdtBuyPosition();
        BigDecimal usdtFrozenBuyPosition = wallet.getUsdtFrozenBuyPosition();
        BigDecimal usdtShareNumber = wallet.getUsdtShareNumber();
        BigDecimal usdtSellLeverage = wallet.getUsdtSellLeverage();
        BigDecimal usdtSellPosition = wallet.getUsdtSellPosition();
        BigDecimal usdtFrozenSellPosition = wallet.getUsdtFrozenSellPosition();

        if (usdtBuyPosition.add(usdtFrozenBuyPosition).compareTo(BigDecimal.valueOf(0)) > 0){
            BigDecimal rate = wallet.getContractCoin().getFeePercent();

            //多单
//            多仓价值 (B2+B3)*C1/C2
            BigDecimal temp1 = usdtBuyPosition.add(usdtFrozenBuyPosition).multiply(usdtShareNumber).divide(usdtBuyLeverage, 4, BigDecimal.ROUND_DOWN);
            BigDecimal temp2 = temp1.multiply(rate);

            //减少保证金
            memberContractWalletService.decreaseUsdtBuyPrincipalAmountWithoutBalance(wallet.getId(),temp2);

            // 更新平台收益
            contractCoinService.increaseTotalProfit(wallet.getContractCoin().getId(), temp2);

            log.info("{} 多单扣除资金费率 {}",wallet.getContractCoin().getName(),temp2);
        }


        if (usdtSellPosition.add(usdtFrozenSellPosition).compareTo(BigDecimal.valueOf(0)) > 0){
            BigDecimal rate = getRate(wallet);

            //空单
//            空仓价值 (S2+S3)*C1/C3

            BigDecimal temp1 = usdtSellPosition.add(usdtFrozenSellPosition).multiply(usdtShareNumber).divide(usdtSellLeverage, 4, BigDecimal.ROUND_DOWN);
            BigDecimal temp2 = temp1.multiply(rate);

            //减少保证金
            memberContractWalletService.decreaseUsdtSellPrincipalAmountWithoutBalance(wallet.getId(),temp2);

            // 更新平台收益
            contractCoinService.increaseTotalProfit(wallet.getContractCoin().getId(), temp2);

            log.info("{} 空单扣除资金费率 {}",wallet.getContractCoin().getName(),temp2);
        }

    }


    private BigDecimal getRate(MemberContractWallet wallet){
        BigDecimal rate = null;

        for (Map<String,BigDecimal> item:feePercentList){
            if (item.containsKey(wallet.getContractCoin().getSymbol())){
                rate = item.get(wallet.getContractCoin().getSymbol());
                break;
            }
        }

        if (rate==null){
            return BigDecimal.valueOf(0);
        }

        return rate;
    }



}
