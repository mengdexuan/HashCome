package com.bizzan.bitrade;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpUtil;

import java.util.HashMap;
import java.util.Map;

public class Test {

    public static void main(String[] args) {
//        String url = "http://10.8.28.39:8801/swap/order/open";
//        String url = "http://10.8.28.39:8801/uc/login";
        String url = "http://103.218.242.25:8801/swap/order/position-detail";

        HttpRequest post = HttpUtil.createPost(url);
        post.header("x-auth-token","58cc5f38-04bc-4105-beba-ee3093c2bcf3");

        Map<String,Object> param = new HashMap<>();
        param.put("type","1"); //0:增加  1：减少
        param.put("principal","10");
        param.put("contractCoinId","1");
        param.put("direction","1");

        post.form(param);

        String result = post.execute().body();

//        String result = HttpUtil.post(url, param);

        System.out.println(result);

    }


}
