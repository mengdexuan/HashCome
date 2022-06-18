package com.bizzan.bitrade.vendor.provider.support;

import com.aliyuncs.CommonRequest;
import com.aliyuncs.CommonResponse;
import com.aliyuncs.IAcsClient;
import com.aliyuncs.exceptions.ClientException;
import com.aliyuncs.exceptions.ServerException;
import com.aliyuncs.http.MethodType;
import com.bizzan.bitrade.util.HttpSend;
import com.bizzan.bitrade.util.MessageResult;
import com.bizzan.bitrade.vendor.provider.SMSProvider;
import com.mashape.unirest.http.exceptions.UnirestException;
import lombok.extern.slf4j.Slf4j;
import net.sf.json.JSONObject;
import org.dom4j.DocumentException;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

@Slf4j
public class AliSMSProvider implements SMSProvider {

    private IAcsClient client;

    public AliSMSProvider(IAcsClient client) {
        this.client = client;
    }

    public static String getName() {
        return "aliyun";
    }

    @Override
    public MessageResult sendSingleMessage(String mobile, String content) throws UnirestException {

        CommonRequest request = new CommonRequest();
        request.setSysMethod(MethodType.POST);
        //域名，请勿修改
        request.setSysDomain("dysmsapi.ap-southeast-1.aliyuncs.com");
        //API版本号，请勿修改
        request.setSysVersion("2018-05-01");
        //API名称
        request.setSysAction("SendMessageToGlobe");
        //接收号码，格式为：国际码+号码，必填
        request.putQueryParameter("To",  "86" + mobile);
        //发送方senderId，选填
        //request.putQueryParameter("From", "1234567890");
        //短信内容，必填
        content = String.format("您的验证码为：%s，请按页面提示填写，切勿泄露于他人。", content);
        request.putQueryParameter("Message", content);
        try {
            CommonResponse response = client.getCommonResponse(request);
            return parseXml(response.getData());
        } catch (ServerException e) {
            e.printStackTrace();
        } catch (ClientException e) {
            e.printStackTrace();
        }
        return MessageResult.error("send fail");
    }

    @Override
    public MessageResult sendMessageByTempId(String mobile, String content, String templateId) throws Exception {
        return null;
    }

    @Override
    public MessageResult sendInternationalMessage(String content,  String areaCode, String phone) throws IOException, DocumentException {

        CommonRequest request = new CommonRequest();
        request.setSysMethod(MethodType.POST);
        //域名，请勿修改
        request.setSysDomain("dysmsapi.ap-southeast-1.aliyuncs.com");
        //API版本号，请勿修改
        request.setSysVersion("2018-05-01");
        //API名称
        request.setSysAction("SendMessageToGlobe");
        //接收号码，格式为：国际码+号码，必填
        request.putQueryParameter("To",  areaCode + phone);
        //发送方senderId，选填
        //request.putQueryParameter("From", "1234567890");
        //短信内容，必填
        content = String.format("Code is %s, it will be valid within ten minutes.", content);
        request.putQueryParameter("Message", content);
        try {
            CommonResponse response = client.getCommonResponse(request);
            log.info(response.getData());
            return parseXml(response.getData());
        } catch (ServerException e) {
            e.printStackTrace();
        } catch (ClientException e) {
            e.printStackTrace();
        }
        return MessageResult.error("send fail");
    }

    /**
     * 获取验证码信息格式
     *
     * @param code
     * @return
     */
    @Override
    public String formatVerifyCode(String code) {
        return String.format("【djw】您的验证码为：%s，请按页面提示填写，切勿泄露于他人。", code);
    }

    @Override
    public MessageResult sendVerifyMessage(String mobile, String verifyCode) throws Exception {
        String content = formatVerifyCode(verifyCode);
        return sendSingleMessage(mobile, content);
    }

    private MessageResult parseXml(String xml) {
        JSONObject myJsonObject = JSONObject.fromObject(xml);
        String code = myJsonObject.getString("ResponseCode");
        MessageResult result = new MessageResult(500, "系统错误");
        if(code.equalsIgnoreCase("OK"))
        {
            result.setCode(0);
            result.setMessage("success");
        }
        return result;
    }

    @Override
    public MessageResult sendCustomMessage(String mobile, String content) throws Exception {
        // TODO Auto-generated method stub
        return null;
    }
}
