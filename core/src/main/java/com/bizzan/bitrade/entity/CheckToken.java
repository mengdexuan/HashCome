package com.bizzan.bitrade.entity;

import lombok.Data;

//"user_id": 113, //用户id
//        "email": "18610040247@163.com", //电子邮箱，比如 example@qq.com
//        "mobile": "186****0247", //手机号码 可能为空
//        "expire": "2023-08-10 14:22:28" //过期时间
@Data
public class CheckToken {
    private String userId;
    private String email;
    private String mobile;
    private String expire;
}
