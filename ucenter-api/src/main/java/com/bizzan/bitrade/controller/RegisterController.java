package com.bizzan.bitrade.controller;

import com.aliyuncs.DefaultAcsClient;
import com.aliyuncs.IAcsClient;
import com.aliyuncs.dm.model.v20151123.SingleSendMailRequest;
import com.aliyuncs.dm.model.v20151123.SingleSendMailResponse;
import com.aliyuncs.exceptions.ClientException;
import com.aliyuncs.http.MethodType;
import com.aliyuncs.profile.DefaultProfile;
import com.aliyuncs.profile.IClientProfile;
import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import lombok.extern.slf4j.Slf4j;
import org.apache.shiro.util.ByteSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.freemarker.FreeMarkerTemplateUtils;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

import com.bizzan.bitrade.constant.CommonStatus;
import com.bizzan.bitrade.constant.MemberLevelEnum;
import com.bizzan.bitrade.constant.SysConstant;
import com.bizzan.bitrade.controller.sdk.NECaptchaVerifier;
import com.bizzan.bitrade.controller.sdk.NESecretPair;
import com.bizzan.bitrade.entity.*;
import com.bizzan.bitrade.entity.transform.AuthMember;
import com.bizzan.bitrade.event.MemberEvent;
import com.bizzan.bitrade.service.CountryService;
import com.bizzan.bitrade.service.LocaleMessageSourceService;
import com.bizzan.bitrade.service.MemberService;
import com.bizzan.bitrade.util.*;

import javax.annotation.Resource;
import javax.mail.MessagingException;
import javax.mail.internet.MimeMessage;
import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static com.bizzan.bitrade.constant.SysConstant.*;
import static com.bizzan.bitrade.constant.SysConstant.EMAIL_REG_CODE_PREFIX;
import static com.bizzan.bitrade.util.MessageResult.error;
import static com.bizzan.bitrade.util.MessageResult.success;
import static org.springframework.util.Assert.isTrue;
import static org.springframework.util.Assert.notNull;

/**
 * 会员注册
 *
 * @author Jammy
 * @date 2019年12月29日
 */
@Controller
@Slf4j
public class RegisterController {

    @Autowired
    private JavaMailSender javaMailSender;

    @Value("${spring.mail.username}")
    private String from;
    @Value("${spark.system.host}")
    private String host;
    @Value("${spark.system.name}")
    private String company;

    /**
     * 手机验证码注册
     */
    @Value("${bigdogex.reg_mobile_verify_support}")
    private Boolean regMobileVerifySupport;

    /**
     * 邮件验证码注册
     */
    @Value("${bigdogex.reg_email_verify_support}")
    private Boolean regEmailVerifySupport;

    /**
     * 邀请码是否必须
     */
    @Value("${bigdogex.need_promotion_code}")
    private Boolean needPromotionCode;

    /**
     * 邀请码是否必须
     */
    @Value("${bigdogex.default_avatar_img}")
    private String defaultAvatarImg;

    @Autowired
    private RedisTemplate redisTemplate;

    @Autowired
    private MemberService memberService;

    @Autowired
    private IdWorkByTwitter idWorkByTwitter;

    @Autowired
    private MemberEvent memberEvent;

    @Autowired
    private CountryService countryService;
    @Autowired
    private GeetestController gtestCon ;

    @Resource
    private LocaleMessageSourceService localeMessageSourceService;

    private String userNameFormat = "U%06d";
    //短信上行验证
    private static final String captchaId = "b7df23a75b054789b77c9d2cc7804fe9"; // 验证码id
    private static final String secretId = "8835fbc77225f5cf8dbc58613d78d2c4"; // 密钥对id
    private static final String secretKey = "67d091cb32ac9efb387e83f1727e7fea"; // 密钥对key

    private final NECaptchaVerifier verifier = new NECaptchaVerifier(captchaId, new NESecretPair(secretId, secretKey));

    private SingleSendMailRequest request = new SingleSendMailRequest();

    /**
     * 登录支持的国家
     *
     * @return
     */
    @RequestMapping(value = "/support/country", method = RequestMethod.POST)
    @ResponseBody
    public MessageResult allCountry(@RequestParam(value = "isReg" ,required = false) Integer isReg) {
        MessageResult result = success();
        List<Country> list = null;
        if(isReg == null || isReg.intValue() == 0) {
            list = countryService.getAllCountry();
        }else if(isReg.intValue() == 1){
            list = countryService.getAllRegCountry();
        }else{
            list = new ArrayList<>();
        }
        result.setData(list);
        return result;
    }

    /**
     * 检查用户名是否重复
     *
     * @param username
     * @return
     */
    @RequestMapping(value = "/register/check/username")
    @ResponseBody
    public MessageResult checkUsername(String username) {
        MessageResult result = success();
        if (memberService.usernameIsExist(username)) {
            result.setCode(500);
            result.setMessage(localeMessageSourceService.getMessage("ACTIVATION_FAILS_USERNAME"));
        }
        return result;
    }

    /**
     * 邮箱注册
     *
     * @param loginByEmail
     * @param bindingResult
     * @return
     */
    @RequestMapping("/register/email")
    @ResponseBody
    public MessageResult registerByEmail(HttpServletRequest request, @Valid LoginByEmail loginByEmail, BindingResult bindingResult) throws Exception {

        String ip = getRemoteIp(request);
        MessageResult result = BindingResultUtil.validate(bindingResult);
        if (result != null) {
            return result;
        }
        /*String email = loginByEmail.getEmail();
        isTrue(!memberService.emailIsExist(email), localeMessageSourceService.getMessage("EMAIL_ALREADY_BOUND"));
        isTrue(!memberService.usernameIsExist(loginByEmail.getUsername()), localeMessageSourceService.getMessage("USERNAME_ALREADY_EXISTS"));
        // isTrue(memberService.userPromotionCodeIsExist(loginByEmail.getPromotion()),localeMessageSourceService.getMessage("USER_PROMOTION_CODE_EXISTS"));
        ValueOperations valueOperations = redisTemplate.opsForValue();

        Object code =valueOperations.get(SysConstant.EMAIL_REG_CODE_PREFIX + email);
        notNull(code, localeMessageSourceService.getMessage("VERIFICATION_CODE_NOT_EXISTS"));
        if (!code.toString().equals(loginByEmail.getCode())) {
            return error(localeMessageSourceService.getMessage("VERIFICATION_CODE_INCORRECT"));
        } else {
            valueOperations.getOperations().delete(SysConstant.EMAIL_REG_CODE_PREFIX + email);
        }*/

        //不可重复随机数
        String loginNo = String.valueOf(idWorkByTwitter.nextId());
        //盐
        String credentialsSalt = ByteSource.Util.bytes(loginNo).toHex();
        //生成密码
        String password = Md5.md5Digest(loginByEmail.getPassword() + credentialsSalt).toLowerCase();
        Member member = new Member();

        member.setMemberLevel(MemberLevelEnum.GENERAL);
        Location location = new Location();
        location.setCountry(loginByEmail.getCountry());
        member.setLocation(location);
        Country country = new Country();
        country.setZhName(loginByEmail.getCountry());
        member.setCountry(country);
        member.setUsername(loginByEmail.getUsername());
        member.setPassword(password);
        member.setEmail(loginByEmail.getEmail());
        member.setSalt(credentialsSalt);
        member.setAvatar("https://bizzan.oss-cn-hangzhou.aliyuncs.com/defaultavatar.png"); // 默认用户头像
        Member member1 = memberService.save(member);

        if (member1 != null) {
            // Member为@entity注解类，与数据库直接映射，因此，此处setPromotionCode会直接同步到数据库
            member1.setPromotionCode(GeneratorUtil.getPromotionCode(member1.getId()));
            memberService.save(member1);
            memberEvent.onRegisterSuccess(member1, loginByEmail.getPromotion().trim(), ip);
            return success(localeMessageSourceService.getMessage("REGISTRATION_SUCCESS"));
        } else {
            return error(localeMessageSourceService.getMessage("REGISTRATION_FAILED"));
        }
    }

    @Async
    public void sentEmail(ValueOperations valueOperations, LoginByEmail loginByEmail, String email) throws MessagingException, IOException, TemplateException {
        //缓存邮箱和注册信息
        String token = UUID.randomUUID().toString().replace("-", "");
        MimeMessage mimeMessage = javaMailSender.createMimeMessage();
        MimeMessageHelper helper = null;
        helper = new MimeMessageHelper(mimeMessage, true);
        helper.setFrom(from);
        helper.setTo(email);
        helper.setSubject(company);
        Map<String, Object> model = new HashMap<>(16);
        model.put("username", loginByEmail.getUsername());
        model.put("token", token);
        model.put("host", host);
        model.put("name", company);
        Configuration cfg = new Configuration(Configuration.VERSION_2_3_26);
        cfg.setClassForTemplateLoading(this.getClass(), "/templates");
        Template template = cfg.getTemplate("activateEmail.ftl");
        String html = FreeMarkerTemplateUtils.processTemplateIntoString(template, model);
        helper.setText(html, true);
        //发送邮件
        javaMailSender.send(mimeMessage);
        valueOperations.set(token, loginByEmail, 12, TimeUnit.HOURS);
        valueOperations.set(email, "", 12, TimeUnit.HOURS);
    }

    /**
     * 手机注册
     *
     * @param loginByPhone
     * @param bindingResult
     * @return
     * @throws Exception
     */
    @RequestMapping("/register/phone")
    @ResponseBody
    @Transactional(rollbackFor = Exception.class)
    public MessageResult loginByPhone(
            @Valid LoginByPhone loginByPhone,
            BindingResult bindingResult,HttpServletRequest request) throws Exception {

        MessageResult result = BindingResultUtil.validate(bindingResult);
        if (result != null) {
            return result;
        }

        if ("中国".equals(loginByPhone.getCountry())) {
            Assert.isTrue(ValidateUtil.isMobilePhone(loginByPhone.getPhone().trim()), localeMessageSourceService.getMessage("PHONE_EMPTY_OR_INCORRECT"));
        }
        String ip = getRemoteIp(request);
        String phone = loginByPhone.getPhone();
        ValueOperations valueOperations = redisTemplate.opsForValue();

        isTrue(!memberService.phoneIsExist(phone), localeMessageSourceService.getMessage("PHONE_ALREADY_EXISTS"));
        isTrue(!memberService.usernameIsExist(loginByPhone.getUsername()), localeMessageSourceService.getMessage("USERNAME_ALREADY_EXISTS"));

        // 邀请码必须
        if(needPromotionCode) {
            if (!StringUtils.hasText(loginByPhone.getPromotion().trim())) {
                return error(localeMessageSourceService.getMessage("USER_PROMOTION_CODE_NEED"));
            }
        }

        // 填写了邀请码，那么检查邀请码是否存在
        if (StringUtils.hasText(loginByPhone.getPromotion().trim())) {
            isTrue(memberService.userPromotionCodeIsExist(loginByPhone.getPromotion()),localeMessageSourceService.getMessage("USER_PROMOTION_CODE_EXISTS"));
        }

        // 是否需要短信验证码验证
        if(regMobileVerifySupport){
            // 需要验证
            Object code =valueOperations.get(SysConstant.PHONE_REG_CODE_PREFIX + phone);
            notNull(code, localeMessageSourceService.getMessage("VERIFICATION_CODE_NOT_EXISTS"));
            if (!code.toString().equals(loginByPhone.getCode())) {
                return error(localeMessageSourceService.getMessage("VERIFICATION_CODE_INCORRECT"));
            } else {
                valueOperations.getOperations().delete(SysConstant.PHONE_REG_CODE_PREFIX + phone);
            }
        }

        // 检查国家是否存在
        Country countryResult = countryService.findOne(loginByPhone.getCountry());
        Assert.notNull(countryResult, localeMessageSourceService.getMessage("USER_REG_COUNTRY_NEED"));

        //不可重复随机数
        String loginNo = String.valueOf(idWorkByTwitter.nextId());
        //盐
        String credentialsSalt = ByteSource.Util.bytes(loginNo).toHex();
        //生成密码
        String password = Md5.md5Digest(loginByPhone.getPassword() + credentialsSalt).toLowerCase();
        Member member = new Member();
        member.setSuperPartner("0");// 普通用户：0 代理商：1
        member.setMemberLevel(MemberLevelEnum.GENERAL);
        Location location = new Location();
        location.setCountry(loginByPhone.getCountry());
        Country country = new Country();
        country.setZhName(loginByPhone.getCountry());
        member.setCountry(country);
        member.setLocation(location);
        member.setUsername(loginByPhone.getUsername());
        member.setPassword(password);
        member.setMobilePhone(phone);
        member.setSalt(credentialsSalt);
        member.setAvatar(defaultAvatarImg);
        Member member1 = memberService.save(member);
        if (member1 != null) {
            member1.setPromotionCode(GeneratorUtil.getPromotionCode(member1.getId()));
            memberService.save(member1);
            memberEvent.onRegisterSuccess(member1, loginByPhone.getPromotion().trim(), ip);
            return success(localeMessageSourceService.getMessage("REGISTRATION_SUCCESS"));
        } else {
            return error(localeMessageSourceService.getMessage("REGISTRATION_FAILED"));
        }
    }

    /**
     * 发送绑定邮箱验证码
     *
     * @param email
     * @param user
     * @return
     */
    @RequestMapping("/bind/email/code")
    @ResponseBody
    @Transactional(rollbackFor = Exception.class)
    public MessageResult sendBindEmail(String email, @SessionAttribute(SESSION_MEMBER) AuthMember user) {
        Assert.isTrue(ValidateUtil.isEmail(email), localeMessageSourceService.getMessage("WRONG_EMAIL"));
        Member member = memberService.findOne(user.getId());
        Assert.isNull(member.getEmail(), localeMessageSourceService.getMessage("BIND_EMAIL_REPEAT"));
        Assert.isTrue(!memberService.emailIsExist(email), localeMessageSourceService.getMessage("EMAIL_ALREADY_BOUND"));
        String code = String.valueOf(GeneratorUtil.getRandomNumber(100000, 999999));
        ValueOperations valueOperations = redisTemplate.opsForValue();
        if (valueOperations.get(EMAIL_BIND_CODE_PREFIX + email) != null) {
            return error(localeMessageSourceService.getMessage("EMAIL_ALREADY_SEND"));
        }
        try {
            sentEmailCode(valueOperations, email, code);
        } catch (Exception e) {
            e.printStackTrace();
            return error(localeMessageSourceService.getMessage("SEND_FAILED"));
        }
        return success(localeMessageSourceService.getMessage("SENT_SUCCESS_TEN"));
    }

    @Async
    public void sentEmailCode(ValueOperations valueOperations, String email, String code) throws MessagingException, IOException, TemplateException {
        MimeMessage mimeMessage = javaMailSender.createMimeMessage();
        MimeMessageHelper helper = null;
        helper = new MimeMessageHelper(mimeMessage, true);
        helper.setFrom(from);
        helper.setTo(email);
        helper.setSubject(company);
        Map<String, Object> model = new HashMap<>(16);
        model.put("code", code);
        Configuration cfg = new Configuration(Configuration.VERSION_2_3_26);
        cfg.setClassForTemplateLoading(this.getClass(), "/templates");
        Template template = cfg.getTemplate("bindCodeEmail.ftl");
        String html = FreeMarkerTemplateUtils.processTemplateIntoString(template, model);
        helper.setText(html, true);

        //发送邮件
        javaMailSender.send(mimeMessage);
        log.info("send email for {},content:{}", email, html);
        valueOperations.set(EMAIL_BIND_CODE_PREFIX + email, code, 10, TimeUnit.MINUTES);
    }

    /**
     * 增加提币地址验证码
     *
     * @param user
     * @return
     */
    @RequestMapping("/add/address/code")
    @ResponseBody
    @Transactional(rollbackFor = Exception.class)
    public MessageResult sendAddAddress(@SessionAttribute(SESSION_MEMBER) AuthMember user) {
        String code = String.valueOf(GeneratorUtil.getRandomNumber(100000, 999999));
        ValueOperations valueOperations = redisTemplate.opsForValue();
        Member member = memberService.findOne(user.getId());
        String email = member.getEmail();
        if (email == null) {
            return error(localeMessageSourceService.getMessage("NOT_BIND_EMAIL"));
        }
        if (valueOperations.get(ADD_ADDRESS_CODE_PREFIX + email) != null) {
            return error(localeMessageSourceService.getMessage("EMAIL_ALREADY_SEND"));
        }
        try {
            sentEmailAddCode(valueOperations, email, code);
        } catch (Exception e) {
            e.printStackTrace();
            return error(localeMessageSourceService.getMessage("SEND_FAILED"));
        }
        return success(localeMessageSourceService.getMessage("SENT_SUCCESS_TEN"));
    }

    @Async
    public void sentEmailAddCode(ValueOperations valueOperations, String email, String code) throws MessagingException, IOException, TemplateException {
        MimeMessage mimeMessage = javaMailSender.createMimeMessage();
        MimeMessageHelper helper = null;
        helper = new MimeMessageHelper(mimeMessage, true);
        helper.setFrom(from);
        helper.setTo(email);
        helper.setSubject(company);
        Map<String, Object> model = new HashMap<>(16);
        model.put("code", code);
        Configuration cfg = new Configuration(Configuration.VERSION_2_3_26);
        cfg.setClassForTemplateLoading(this.getClass(), "/templates");
        Template template = cfg.getTemplate("addAddressCodeEmail.ftl");
        String html = FreeMarkerTemplateUtils.processTemplateIntoString(template, model);
        helper.setText(html, true);
        //发送邮件
        javaMailSender.send(mimeMessage);
        valueOperations.set(ADD_ADDRESS_CODE_PREFIX + email, code, 10, TimeUnit.MINUTES);
    }

    @RequestMapping("/reset/email/code")
    @ResponseBody
    @Transactional(rollbackFor = Exception.class)
    public MessageResult sendResetPasswordCode(String account) {
        Member member = memberService.findByEmail(account);
        Assert.notNull(member, localeMessageSourceService.getMessage("MEMBER_NOT_EXISTS"));
        ValueOperations valueOperations = redisTemplate.opsForValue();
        if (valueOperations.get(RESET_PASSWORD_CODE_PREFIX + account) != null) {
            return error(localeMessageSourceService.getMessage("EMAIL_ALREADY_SEND"));
        }
        try {
            String code = String.valueOf(GeneratorUtil.getRandomNumber(100000, 999999));
            sentResetPassword(valueOperations, account, code);
        } catch (Exception e) {
            e.printStackTrace();
            return error(localeMessageSourceService.getMessage("SEND_FAILED"));
        }
        return success(localeMessageSourceService.getMessage("SENT_SUCCESS_TEN"));
    }

    @Async
    public void sentResetPassword(ValueOperations valueOperations, String email, String code) throws MessagingException, IOException, TemplateException {
        MimeMessage mimeMessage = javaMailSender.createMimeMessage();
        MimeMessageHelper helper = null;
        helper = new MimeMessageHelper(mimeMessage, true);
        helper.setFrom(from);
        helper.setTo(email);
        helper.setSubject(company);
        Map<String, Object> model = new HashMap<>(16);
        model.put("code", code);
        Configuration cfg = new Configuration(Configuration.VERSION_2_3_26);
        cfg.setClassForTemplateLoading(this.getClass(), "/templates");
        Template template = cfg.getTemplate("resetPasswordCodeEmail.ftl");
        String html = FreeMarkerTemplateUtils.processTemplateIntoString(template, model);
        helper.setText(html, true);
        //发送邮件
        javaMailSender.send(mimeMessage);
        valueOperations.set(RESET_PASSWORD_CODE_PREFIX + email, code, 10, TimeUnit.MINUTES);
    }

    /**
     * 忘记密码后重置密码
     *
     * @param mode     0为手机验证，1为邮箱验证
     * @param account  手机或邮箱
     * @param code     验证码
     * @param password 新密码
     * @return
     */
    @RequestMapping(value = "/reset/login/password", method = RequestMethod.POST)
    @ResponseBody
    @Transactional(rollbackFor = Exception.class)
    public MessageResult forgetPassword(int mode, String account, String code, String password) throws Exception {
        Member member = null;
        ValueOperations valueOperations = redisTemplate.opsForValue();
        Object redisCode = valueOperations.get(SysConstant.RESET_PASSWORD_CODE_PREFIX + account);
        notNull(redisCode, localeMessageSourceService.getMessage("VERIFICATION_CODE_NOT_EXISTS"));
        if (mode == 0) {
            member = memberService.findByPhone(account);
        } else if (mode == 1) {
            member = memberService.findByEmail(account);
        }
        isTrue(password.length() >= 6 && password.length() <= 20, localeMessageSourceService.getMessage("PASSWORD_LENGTH_ILLEGAL"));
        notNull(member, localeMessageSourceService.getMessage("MEMBER_NOT_EXISTS"));
        if (!code.equals(redisCode.toString())) {
            return error(localeMessageSourceService.getMessage("VERIFICATION_CODE_INCORRECT"));
        } else {
            valueOperations.getOperations().delete(SysConstant.RESET_PASSWORD_CODE_PREFIX + account);
        }
        //生成密码
        String newPassword = Md5.md5Digest(password + member.getSalt()).toLowerCase();
        member.setPassword(newPassword);
        return success();
    }
    /**
     * 发送解绑旧邮箱验证码
     *
     * @param user
     * @return
     */
    @RequestMapping(value = "/untie/email/code", method = RequestMethod.POST)
    @ResponseBody
    public MessageResult untieEmailCode(@SessionAttribute(SESSION_MEMBER) AuthMember user){
        Member member = memberService.findOne(user.getId());
        isTrue(member.getEmail()!=null, localeMessageSourceService.getMessage("NOT_BIND_EMAIL"));
        ValueOperations valueOperations = redisTemplate.opsForValue();
        Object cache = valueOperations.get(SysConstant.EMAIL_UNTIE_CODE_PREFIX + member.getEmail());
        if(cache!=null){
            return error(localeMessageSourceService.getMessage("EMAIL_ALREADY_SEND"));
        }
        String code = String.valueOf(GeneratorUtil.getRandomNumber(100000, 999999));
        try {
            sentResetPassword(valueOperations, member.getEmail(), code);
        } catch (MessagingException | IOException | TemplateException e) {
            e.printStackTrace();
        }
        return success();
    }

    /**
     * 发送新邮箱验证码
     *
     * @param user
     * @return
     */
    @RequestMapping(value = "/update/email/code", method = RequestMethod.POST)
    @ResponseBody
    public MessageResult updateEmailCode(@SessionAttribute(SESSION_MEMBER) AuthMember user,String email){
        if(memberService.emailIsExist(email)){
            return MessageResult.error(localeMessageSourceService.getMessage("REPEAT_EMAIL_REQUEST"));
        }
        Member member = memberService.findOne(user.getId());
        ValueOperations valueOperations = redisTemplate.opsForValue();
        isTrue(member.getEmail()!=null, localeMessageSourceService.getMessage("NOT_BIND_EMAIL"));
        Object cache = valueOperations.get(SysConstant.EMAIL_UPDATE_CODE_PREFIX + email);
        if(cache!=null){
            return error(localeMessageSourceService.getMessage("EMAIL_ALREADY_SEND"));
        }
        String code = String.valueOf(GeneratorUtil.getRandomNumber(100000, 999999));
        try {
            sentResetPassword(valueOperations, email, code);
        } catch (MessagingException | IOException | TemplateException e) {
            e.printStackTrace();
        }
        return success();
    }

    /**
     * 注册验证码
     * @param email
     * @return
     */
    @RequestMapping("/reg/email/code")
    @ResponseBody
    @Transactional(rollbackFor = Exception.class)
    public MessageResult sendRegEmail(String email) {
        Assert.isTrue(ValidateUtil.isEmail(email), localeMessageSourceService.getMessage("WRONG_EMAIL"));

        Assert.isTrue(!memberService.emailIsExist(email), localeMessageSourceService.getMessage("EMAIL_ALREADY_BOUND"));
        String code = String.valueOf(GeneratorUtil.getRandomNumber(100000, 999999));
        ValueOperations valueOperations = redisTemplate.opsForValue();
        if (valueOperations.get(EMAIL_REG_CODE_PREFIX + email) != null) {
            return error(localeMessageSourceService.getMessage("EMAIL_ALREADY_SEND"));
        }
        try {
            sentEmailRegCode(valueOperations, email, code);
        } catch (Exception e) {
            e.printStackTrace();
            return error(localeMessageSourceService.getMessage("SEND_FAILED"));
        }
        return success(localeMessageSourceService.getMessage("SENT_SUCCESS_TEN"));
    }

    /**
     * 普通发送邮件
     * @param valueOperations
     * @param email
     * @param code
     * @throws MessagingException
     * @throws IOException
     * @throws TemplateException
     */
    @Async
    public void sentEmailRegCode(ValueOperations valueOperations, String email, String code) throws MessagingException, IOException, TemplateException {
        MimeMessage mimeMessage = javaMailSender.createMimeMessage();
        MimeMessageHelper helper = null;
        helper = new MimeMessageHelper(mimeMessage, true);
        helper.setFrom(from);
        helper.setTo(email);
        helper.setSubject(company);
        Map<String, Object> model = new HashMap<>(16);
        model.put("code", code);
        Configuration cfg = new Configuration(Configuration.VERSION_2_3_26);
        cfg.setClassForTemplateLoading(this.getClass(), "/templates");
        Template template = cfg.getTemplate("bindCodeEmail.ftl");
        String html = FreeMarkerTemplateUtils.processTemplateIntoString(template, model);
        helper.setText(html, true);
        //发送邮件
        javaMailSender.send(mimeMessage);
        valueOperations.set(EMAIL_REG_CODE_PREFIX + email, code, 10, TimeUnit.MINUTES);
    }

    /**
     * 阿里云邮件发送示例
     * @param valueOperations
     * @param email
     * @param code
     * @throws MessagingException
     * @throws IOException
     * @throws TemplateException
     * @throws ClientException
     * @throws ClientException
     */
//    @Async
//    public void sentEmailRegCodeAliyun(ValueOperations valueOperations, String email, String code) throws MessagingException, IOException, TemplateException, ClientException, ClientException {
//        MimeMessage mimeMessage = javaMailSender.createMimeMessage();
//        MimeMessageHelper helper = null;
//        helper = new MimeMessageHelper(mimeMessage, true);
//        helper.setFrom(from);
//        helper.setTo(email);
//        helper.setSubject(company);
//        Map<String, Object> model = new HashMap<>(16);
//        model.put("code", code);
//        Configuration cfg = new Configuration(Configuration.VERSION_2_3_26);
//        cfg.setClassForTemplateLoading(this.getClass(), "/templates");
//        Template template = cfg.getTemplate("bindCodeEmail.ftl");
//        String html = FreeMarkerTemplateUtils.processTemplateIntoString(template, model);
//        helper.setText(html, true);
//
//        request.setAccountName(emailFromAddress);
//        request.setFromAlias(emailAlias);
//        request.setAddressType(1);
//        request.setTagName(emailTag);
//        request.setReplyToAddress(true);
//        request.setToAddress(email);
//        //可以给多个收件人发送邮件，收件人之间用逗号分开，批量发信建议使用BatchSendMailRequest方式
//        //request.setToAddress(“邮箱1,邮箱2”);
//        request.setSubject("Verification Code");
//        //如果采用byte[].toString的方式的话请确保最终转换成utf-8的格式再放入htmlbody和textbody，若编码不一致则会被当成垃圾邮件。
//        //注意：文本邮件的大小限制为3M，过大的文本会导致连接超时或413错误
//        request.setHtmlBody(html);
//        //SDK 采用的是http协议的发信方式, 默认是GET方法，有一定的长度限制。
//        //若textBody、htmlBody或content的大小不确定，建议采用POST方式提交，避免出现uri is not valid异常
//        request.setMethod(MethodType.POST);
//        //开启需要备案，0关闭，1开启
//        //request.setClickTrace(“0”);
//        //如果调用成功，正常返回httpResponse；如果调用失败则抛出异常，需要在异常中捕获错误异常码；错误异常码请参考对应的API文档;
//        SingleSendMailResponse httpResponse = client.getAcsResponse(request);
//
//        log.info("send email for {},content:{}", email, html);
//        valueOperations.set(EMAIL_REG_CODE_PREFIX + email, code, 10, TimeUnit.MINUTES);
//
//    }

    public String getRemoteIp(HttpServletRequest request) {
        if (org.apache.commons.lang.StringUtils.isNotBlank(request.getHeader("X-Real-IP"))) {
            return request.getHeader("X-Real-IP");
        } else if (org.apache.commons.lang.StringUtils.isNotBlank(request.getHeader("X-Forwarded-For"))) {
            return request.getHeader("X-Forwarded-For");
        } else if (org.apache.commons.lang.StringUtils.isNotBlank(request.getHeader("Proxy-Client-IP"))) {
            return request.getHeader("Proxy-Client-IP");
        }
        return request.getRemoteAddr();
    }

}
