package com.bizzan.bitrade.vendor.provider.support;

import com.alibaba.fastjson.JSONObject;
import com.bizzan.bitrade.dto.SmsDTO;
import com.bizzan.bitrade.service.SmsService;
import com.bizzan.bitrade.util.HttpSend;
import com.bizzan.bitrade.util.MessageResult;
import com.bizzan.bitrade.vendor.provider.SMSProvider;
import com.sparkframework.security.Encrypt;
import lombok.extern.slf4j.Slf4j;
import org.dom4j.DocumentException;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * @Author: bigdogex.com
 * @Date: 2021/6/30 6:27 下午
 */

@Slf4j
public class FactorSMSProvider implements SMSProvider {
    private String username; // appID
    private String password; // signature
    private String sign; // sign
    private String ensign; // english sign
    private String gateway; // gateway

    private String internationalGateway;
    private String internationalUsername;
    private String internationalPassword;

    String sendUrl = "https://2factor.in/API/V1/440ca85f-9844-11eb-80ea-0200cd936042/SMS/";

    @Autowired
    private SmsService smsService;

    public FactorSMSProvider(String username, String password, String sign, String ensign, String gateway, String internationalGateway, String internationalUsername, String internationalPassword) {
        this.username = username;
        this.password = password;
        this.sign = sign;
        this.ensign = ensign;
        this.gateway = gateway;
        this.internationalGateway = internationalGateway;
        this.internationalPassword = internationalPassword;
        this.internationalUsername = internationalUsername;
    }

    public static String getName() {
        return "factor";
    }

    @Override
    public MessageResult sendSingleMessage(String mobile, String content) throws Exception {
        SmsDTO smsDTO = smsService.getByStatus();
//        if("yunxin".equals(smsDTO.getSmsName())){
        return sendMessage(mobile,content,smsDTO);
//        }
//        return null;
    }

    @Override
    public MessageResult sendMessageByTempId(String mobile, String content, String templateId) throws Exception {
        return null;
    }

    public MessageResult sendMessage(String mobile, String content,SmsDTO smsDTO) throws Exception{
        String smsUrl = sendUrl + "+86" + mobile + "/" + content;

        log.info("Factor短信====", smsUrl);
        String returnStr= HttpSend.post(smsUrl, null);
        log.info("result = {}", returnStr);
        return parseResult(returnStr);
    }

    @Override
    public MessageResult sendInternationalMessage(String content, String nationCode, String phone) throws IOException, DocumentException {

        String smsUrl = sendUrl + "+" + nationCode + phone + "/" + content;

        log.info("Factor短信====", smsUrl);
        String returnStr= HttpSend.post(smsUrl, null);
        log.info("result = {}", returnStr);
        return parseResult(returnStr);
    }

    private MessageResult parseResult(String result) {
        // 返回参数形式：
        // {parseResult
        //    "status":"success"
        //    "send_id":"093c0a7df143c087d6cba9cdf0cf3738"
        //    "fee":1,
        //    "sms_credits":14197
        //}
        JSONObject jsonObject = JSONObject.parseObject(result);

        MessageResult mr = new MessageResult(500, "系统错误");
        if(jsonObject.getString("Status").equalsIgnoreCase("Success")) {
            mr.setCode(0);
            mr.setMessage("短信发送成功！");
        }else{
            mr.setCode(1);
            mr.setMessage("短信发送失败，请联系平台处理！");
        }
        return mr;
    }

    /**
     * 转换返回值类型为UTF-8格式.
     * @param is
     * @return
     */
    public String convertStreamToString(InputStream is) {
        StringBuilder sb1 = new StringBuilder();
        byte[] bytes = new byte[4096];
        int size = 0;

        try {
            while ((size = is.read(bytes)) > 0) {
                String str = new String(bytes, 0, size, "UTF-8");
                sb1.append(str);
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                is.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return sb1.toString();
    }

    @Override
    public MessageResult sendCustomMessage(String mobile, String content) throws Exception {

        Map<String, String> params = new HashMap<String, String>();
        params.put("ac", "send");
        params.put("uid", username);
        params.put("pwd", Encrypt.MD5(password + username));
        params.put("mobile", mobile);
        params.put("encode", "utf8");
        params.put("content", content);

        log.info("云信短信====", params.toString());
        String returnStr= HttpSend.post(gateway, params);
        log.info("result = {}", returnStr);
        return parseResult(returnStr);
    }
}
