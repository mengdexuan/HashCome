package com.bizzan.bitrade.event;

import com.alibaba.fastjson.JSONObject;
import com.bizzan.bitrade.constant.PromotionLevel;
import com.bizzan.bitrade.constant.PromotionRewardType;
import com.bizzan.bitrade.constant.RewardRecordType;
import com.bizzan.bitrade.constant.TransactionType;
import com.bizzan.bitrade.dao.MemberDao;
import com.bizzan.bitrade.entity.*;
import com.bizzan.bitrade.service.*;
import com.bizzan.bitrade.util.BigDecimalUtils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.Date;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author Jammy
 * @date 2019年01月09日
 */
@Service
@Slf4j
public class MemberEvent {
    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;
    @Autowired
    private MemberDao memberDao;
    @Autowired
    private MemberService memberService;
    @Autowired
    private MemberPromotionService memberPromotionService;
    @Autowired
    private RewardPromotionSettingService rewardPromotionSettingService;
    @Autowired
    private MemberWalletService memberWalletService;
    @Autowired
    private RewardRecordService rewardRecordService;
    @Autowired
    private MemberTransactionService memberTransactionService;
    @Autowired
    private MemberRecordService memberRecordService;

    /**
     * 如果值为1，推荐注册的推荐人必须被推荐人实名认证才能获得奖励
     */
    @Value("${commission.need.real-name:1}")
    private int needRealName;
    
    @Value("${commission.promotion.second-level:0}")
    private int promotionSecondLevel ;

    private Pattern reg = Pattern.compile("^(127\\.0\\.0\\.1)|(localhost)|(10\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3})|(172\\.((1[6-9])|(2\\d)|(3[01]))\\.\\d{1,3}\\.\\d{1,3})|(192\\.168\\.\\d{1,3}\\.\\d{1,3})$");

    /**
     * 注册成功事件
     *
     * @param member 持久化对象
     */
    public void onRegisterSuccess(Member member, String promotionCode, String ip) throws InterruptedException {

        JSONObject json = new JSONObject();
        json.put("uid", member.getId());
        //发送给wallet项目consumer处理（）
        log.info("Kafka send member-register............");
        kafkaTemplate.send("member-register", json.toJSONString());

        //发送给contract-swap-api项目consumer处理
        kafkaTemplate.send("member-register-swap", json.toJSONString());

        //推广活动
        if (StringUtils.hasText(promotionCode)) {
            Member member1 = memberDao.findMemberByPromotionCode(promotionCode);
            if (member1 != null) {
                member.setInviterId(member1.getId());
                memberService.save(member);
                //如果不需要实名认证，直接发放奖励
                if (needRealName == 0) {
                    promotion(member1, member);
                }
            }
        }

        memberRecordLog(ip, member.getMobilePhone(), member.getEmail(), "regist", member.getId(), "success", 3, "用户注册成功");
    }

    /**
     * 登录成功事件
     *
     * @param member 持久化对象
     */
    public void onLoginSuccess(Member member, String ip) {
        memberRecordLog(ip, member.getMobilePhone(), member.getEmail(), "login", member.getId(), "success", 0, "用户登录成功");
    }
    public void onLoginSuccess(Member member, String ip, String action) {
        memberRecordLog(ip, member.getMobilePhone(), member.getEmail(), action, member.getId(), "success", 4, "Token登录成功");
    }
    public void onLoginFail(String phone, String email, String ip, String remark) {
        memberRecordLog(ip, phone, email, "login", null, "failed", 1, "用户登录失败");
    }

    public void onCheckLogin(String phone, String email, String ip, Long memberId) {
        memberRecordLog(ip, phone, email, "checkLogin", memberId, "success", 2, "检查是否登录");
    }
    private void promotion(Member member1, Member member) {
        RewardPromotionSetting rewardPromotionSetting = rewardPromotionSettingService.findByType(PromotionRewardType.REGISTER);
        if (rewardPromotionSetting != null) {
            MemberWallet memberWallet1 = memberWalletService.findByCoinAndMember(rewardPromotionSetting.getCoin(), member1);
            BigDecimal amount1 = JSONObject.parseObject(rewardPromotionSetting.getInfo()).getBigDecimal("one");
            memberWallet1.setToReleased(BigDecimalUtils.add(memberWallet1.getToReleased(), amount1));
            memberWalletService.save(memberWallet1);
            RewardRecord rewardRecord1 = new RewardRecord();
            rewardRecord1.setAmount(amount1);
            rewardRecord1.setCoin(rewardPromotionSetting.getCoin());
            rewardRecord1.setMember(member1);
            rewardRecord1.setRemark(rewardPromotionSetting.getType().getCnName());
            rewardRecord1.setType(RewardRecordType.PROMOTION);
            rewardRecordService.save(rewardRecord1);
            MemberTransaction memberTransaction = new MemberTransaction();
            memberTransaction.setFee(BigDecimal.ZERO);
            memberTransaction.setAmount(amount1);
            memberTransaction.setSymbol(rewardPromotionSetting.getCoin().getUnit());
            memberTransaction.setType(TransactionType.PROMOTION_AWARD);
            memberTransaction.setMemberId(member1.getId());
            memberTransaction.setRealFee("0");
            memberTransaction.setDiscountFee("0");
            memberTransaction.setCreateTime(new Date());
            memberTransactionService.save(memberTransaction);
        }
        member1.setFirstLevel(member1.getFirstLevel() + 1);
        memberService.save(member1);
        MemberPromotion one = new MemberPromotion();
        one.setInviterId(member1.getId());
        one.setInviteesId(member.getId());
        one.setLevel(PromotionLevel.ONE);
        memberPromotionService.save(one);
        
        if (member1.getInviterId() != null) {
            Member member2 = memberDao.findOne(member1.getInviterId());
            // 当A推荐B，B推荐C，如果C通过实名认证，则B和A都可以获得奖励
            promotionLevelTwo(rewardPromotionSetting, member2, member);
        }
    }

    private void promotionLevelTwo(RewardPromotionSetting rewardPromotionSetting, Member member2, Member member) {
        if (rewardPromotionSetting != null) {
            MemberWallet memberWallet2 = memberWalletService.findByCoinAndMember(rewardPromotionSetting.getCoin(), member2);
            BigDecimal amount2 = JSONObject.parseObject(rewardPromotionSetting.getInfo()).getBigDecimal("two");
            memberWallet2.setToReleased(BigDecimalUtils.add(memberWallet2.getToReleased(), amount2));
            memberWalletService.save(memberWallet2);
            RewardRecord rewardRecord2 = new RewardRecord();
            rewardRecord2.setAmount(amount2);
            rewardRecord2.setCoin(rewardPromotionSetting.getCoin());
            rewardRecord2.setMember(member2);
            rewardRecord2.setRemark(rewardPromotionSetting.getType().getCnName());
            rewardRecord2.setType(RewardRecordType.PROMOTION);
            rewardRecordService.save(rewardRecord2);
            MemberTransaction memberTransaction = new MemberTransaction();
            memberTransaction.setFee(BigDecimal.ZERO);
            memberTransaction.setAmount(amount2);
            memberTransaction.setSymbol(rewardPromotionSetting.getCoin().getUnit());
            memberTransaction.setType(TransactionType.PROMOTION_AWARD);
            memberTransaction.setMemberId(member2.getId());memberTransaction.setRealFee("0");
            memberTransaction.setDiscountFee("0");
            memberTransaction.setCreateTime(new Date());
            memberTransactionService.save(memberTransaction);
        }
        member2.setSecondLevel(member2.getSecondLevel() + 1);
        memberService.save(member2);
        MemberPromotion two = new MemberPromotion();
        two.setInviterId(member2.getId());
        two.setInviteesId(member.getId());
        two.setLevel(PromotionLevel.TWO);
        memberPromotionService.save(two);
        if (member2.getInviterId() != null) {
            Member member3 = memberDao.findOne(member2.getInviterId());
            member3.setThirdLevel(member3.getThirdLevel() + 1);
            memberService.save(member3);
        }
    }

    private void memberRecordLog(String ip, String phone, String email, String action, Long memberId, String result, int type, String remark){
        //if(reg.matcher(ip).find()) return;

        MemberRecord mr = new MemberRecord();

        mr.setIp(ip);
        mr.setAction(action);
        mr.setEmail(email);
        mr.setPhone(phone);
        mr.setMemberId(memberId);
        mr.setResult(result);
        mr.setCreateTime(new Date());
        mr.setAnalysised(0); // 未解析IP地址信息
        mr.setActionType(type); // 登录失败

        mr.setIsoCode("");
        mr.setCountryName("");
        mr.setCountryNameZh("");
        mr.setSubdivision("");
        mr.setSubdivisionZh("");
        mr.setCityName("");
        mr.setCityNameZh("");
        mr.setLatitude("");
        mr.setLongitude("");
        mr.setRemark(remark);
//        memberRecordService.save(mr);
    }
}
