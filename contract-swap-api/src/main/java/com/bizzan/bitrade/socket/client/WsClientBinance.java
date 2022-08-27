package com.bizzan.bitrade.socket.client;

import com.bizzan.bitrade.engine.ContractCoinMatchFactory;
import com.bizzan.bitrade.entity.ContractCoin;
import com.bizzan.bitrade.job.ExchangePushJob;
import com.bizzan.bitrade.service.ContractCoinService;
import com.bizzan.bitrade.service.ContractMarketService;
import com.bizzan.bitrade.socket.ws.WebSocketBinance;
import com.bizzan.bitrade.socket.ws.WebSocketHuobi;
import com.bizzan.bitrade.util.WebSocketConnectionManage;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;

public class WsClientBinance {

    private ContractCoinService contractCoinService;

    private ContractMarketService marketService;

    private ExchangePushJob exchangePushJob;

    private ContractCoinMatchFactory matchFactory;

    public WsClientBinance(ContractCoinMatchFactory factory) {
        this.matchFactory = factory;
    }

    public void run() {

        List<ContractCoin> contractCoinList = contractCoinService.findAll();

        try {
            URI uri = new URI("wss://fstream.binance.com/ws");
            WebSocketBinance ws = new WebSocketBinance(uri, matchFactory, marketService, exchangePushJob, contractCoinService);

            WebSocketConnectionManage.setWebSocket(ws);
            WebSocketConnectionManage.getClient().connect(ws);

        } catch (URISyntaxException e) {
            e.printStackTrace();
        }
    }

    public void setContractCoinService(ContractCoinService service) {
        this.contractCoinService = service;
    }
    public void setContractMarketService(ContractMarketService service) { this.marketService = service; }
    public void setExchangePushJob(ExchangePushJob pushJob) { this.exchangePushJob = pushJob; }
}
