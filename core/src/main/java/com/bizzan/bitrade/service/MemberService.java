package com.bizzan.bitrade.service;

import com.bizzan.bitrade.constant.CertifiedBusinessStatus;
import com.bizzan.bitrade.constant.CommonStatus;
import com.bizzan.bitrade.constant.MemberLevelEnum;
import com.bizzan.bitrade.dao.MemberDao;
import com.bizzan.bitrade.dao.MemberSignRecordDao;
import com.bizzan.bitrade.dao.MemberTransactionDao;
import com.bizzan.bitrade.entity.*;
import com.bizzan.bitrade.event.MemberEvent;
import com.bizzan.bitrade.exception.AuthenticationException;
import com.bizzan.bitrade.pagination.Criteria;
import com.bizzan.bitrade.pagination.PageResult;
import com.bizzan.bitrade.pagination.Restrictions;
import com.bizzan.bitrade.service.Base.BaseService;
import com.bizzan.bitrade.util.*;
import com.querydsl.core.types.Predicate;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQuery;

import lombok.extern.slf4j.Slf4j;
import net.sf.json.JSONObject;
import org.apache.commons.lang.StringUtils;
import org.apache.shiro.util.ByteSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.bizzan.bitrade.constant.TransactionType.ACTIVITY_AWARD;

import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.List;

@Slf4j
@Service
public class MemberService extends BaseService {

    @Autowired
    private MemberDao memberDao;

    @Autowired
    private MemberSignRecordDao signRecordDao;

    @Autowired
    private MemberTransactionDao transactionDao;

    @Autowired
    private IdWorkByTwitter idWorkByTwitter;

    @Autowired
    private MemberEvent memberEvent;

    //third party verify token
    @Value("${thirdVerify.url}")
    private String thirdVerifyUrl;

    /**
     * 邮箱自动注册
     * @param email
     * @param password
     * @param countryStr eg: 中国
     */
    Member autoRegisterByEmail(String email,String password,String countryStr) throws InterruptedException {

        //不可重复随机数
        String loginNo = String.valueOf(idWorkByTwitter.nextId());
        //盐
        String credentialsSalt = ByteSource.Util.bytes(loginNo).toHex();
        //生成密码
        String pwd = null;
        try {
            pwd = Md5.md5Digest(password + credentialsSalt).toLowerCase();
        } catch (Exception e) {
            e.printStackTrace();
        }
        Member member = new Member();
        member.setMemberLevel(MemberLevelEnum.GENERAL);
        Location location = new Location();
        location.setCountry(countryStr);
        member.setLocation(location);
        Country country = new Country();
        country.setZhName(countryStr);
        member.setCountry(country);
        member.setUsername(email);
        member.setPassword(pwd);
        member.setEmail(email);
        member.setSalt(credentialsSalt);
        member.setAvatar("https://bizzan.oss-cn-hangzhou.aliyuncs.com/defaultavatar.png"); // 默认用户头像
        Member member1 = save(member);

        if (member1 != null) {
            // Member为@entity注解类，与数据库直接映射，因此，此处setPromotionCode会直接同步到数据库
            member1.setPromotionCode(GeneratorUtil.getPromotionCode(member1.getId()));
            save(member1);
            memberEvent.onRegisterSuccess(member1, "", countryStr);
        }

        return member1;
    }


    /**
     * 手机自动注册
     * @param phone
     * @param password
     * @param countryStr eg: 中国
     */
    void autoRegisterByPhone(String phone,String password,String countryStr){
        //不可重复随机数
        String loginNo = String.valueOf(idWorkByTwitter.nextId());
        //盐
        String credentialsSalt = ByteSource.Util.bytes(loginNo).toHex();
        //生成密码
        try {
            String pwd = Md5.md5Digest(password + credentialsSalt).toLowerCase();
        } catch (Exception e) {
            e.printStackTrace();
        }
        Member member = new Member();
        member.setSuperPartner("0");// 普通用户：0 代理商：1
        member.setMemberLevel(MemberLevelEnum.GENERAL);
        Location location = new Location();
        location.setCountry(countryStr);
        Country country = new Country();
        country.setZhName(countryStr);
        member.setCountry(country);
        member.setLocation(location);
        member.setUsername(phone);
        member.setPassword(password);
        member.setMobilePhone(phone);
        member.setSalt(credentialsSalt);
        member.setAvatar("https://bizzan.oss-cn-hangzhou.aliyuncs.com/defaultavatar.png"); // 默认用户头像
        save(member);
    }


    /**
     * 条件查询对象 pageNo pageSize 同时传时分页
     *
     * @param booleanExpressionList
     * @param pageNo
     * @param pageSize
     * @return
     */
    @Transactional(readOnly = true)
    public PageResult<Member> queryWhereOrPage(List<BooleanExpression> booleanExpressionList, Integer pageNo, Integer pageSize) {
        List<Member> list;
        JPAQuery<Member> jpaQuery = queryFactory.selectFrom(QMember.member)
                .where(booleanExpressionList.toArray(new BooleanExpression[booleanExpressionList.size()]));
        jpaQuery.orderBy(QMember.member.id.desc());
        if (pageNo != null && pageSize != null) {
            list = jpaQuery.offset((pageNo - 1) * pageSize).limit(pageSize).fetch();
        } else {
            list = jpaQuery.fetch();
        }
        return new PageResult<>(list, jpaQuery.fetchCount());
    }

    public Member save(Member member) {
        return memberDao.save(member);
    }

    public Member saveAndFlush(Member member) {
        return memberDao.saveAndFlush(member);
    }

    @Transactional(rollbackFor = Exception.class)
    public Member loginWithToken(String token, String ip, String device) {
        if (StringUtils.isBlank(token)) {
            return null;
        }
        //Member mr = memberDao.findMemberByTokenAndTokenExpireTimeAfter(token,new Date());
        //Member mr = memberDao.findMemberByToken(token);

        CheckToken checkToken = null;
        // 验证token，获取用户信息
        try {
            String checkResult = HttpClientUtil.post(
                            JSONObject.fromObject(String.format("{\"token\": \"%s\"}", token)),
                            thirdVerifyUrl, "", "");

            log.info(thirdVerifyUrl+":"+checkResult);

            JSONObject jsonObj = JSONObject.fromObject(checkResult);
            if (jsonObj.isEmpty() || (200 != (int)jsonObj.get("code")))  {
                log.error("third party token user info is null");
                return null;
            }

            checkToken = (CheckToken)JSONObject.toBean(JSONObject.fromObject(jsonObj.get("data")), CheckToken.class);
        } catch (IOException e) {
            log.error("can't get third party token user info due to network");
            return null;
        }

        //判断用户是否存在, 不存在则注册
        log.info(thirdVerifyUrl+":"+checkToken.getEmail());
        Member member = memberDao.findMemberByEmail(checkToken.getEmail());
        if (null == member) {
            try {
                log.info(thirdVerifyUrl+":register member"+checkToken.getEmail());
                member = autoRegisterByEmail(checkToken.getEmail(), checkToken.getEmail(), "中国");
            } catch (InterruptedException e) {
                log.error("register member fail or register record fail");
                return null;
            }
        }

        log.info(thirdVerifyUrl+":"+checkToken.getExpire());
        SimpleDateFormat sdf =  new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        try {
            member.setTokenExpireTime(sdf.parse(checkToken.getExpire()));
        } catch (ParseException e) {
            log.error("can't set token expire");
            return null;
        }

        return member;
    }

    public Member login(String username, String password) throws Exception {
        Member member = memberDao.findMemberByMobilePhoneOrEmail(username, username);
        if (member == null) {
            throw new AuthenticationException("账号或密码错误");
        } else if (!Md5.md5Digest(password + member.getSalt()).toLowerCase().equals(member.getPassword())) {
            throw new AuthenticationException("账号或密码错误");
        } else if (member.getStatus().equals(CommonStatus.ILLEGAL)) {
            throw new AuthenticationException("该帐号处于未激活/禁用状态，请联系客服");
        }
        return member;
    }

    /**
     * @author Jammy
     * @description
     * @date 2019/12/25 18:42
     */
    public Member findOne(Long id) {
        return memberDao.findOne(id);
    }

    /**
     * @author Jammy
     * @description 查询所有会员
     * @date 2019/12/25 18:43
     */
    @Override
    public List<Member> findAll() {
        return memberDao.findAll();
    }

    public List<Member> findPromotionMember(Long id) {
        return memberDao.findAllByInviterId(id);
    }

    public Page<Member> findPromotionMemberPage(Integer pageNo, Integer pageSize,Long id){
        Sort orders = Criteria.sortStatic("id");
        PageRequest pageRequest = new PageRequest(pageNo, pageSize, orders);

        Criteria<Member> specification = new Criteria<Member>();
        specification.add(Restrictions.eq("inviterId", id, false));
        return memberDao.findAll(specification, pageRequest);
    }

    /**
     * @author Jammy
     * @description 分页
     * @date 2019/1/12 15:35
     */
    public Page<Member> page(Integer pageNo, Integer pageSize, CommonStatus status) {
        //排序方式 (需要倒序 这样    Criteria.sort("id","createTime.desc") ) //参数实体类为字段名
        Sort orders = Criteria.sortStatic("id");
        //分页参数
        PageRequest pageRequest = new PageRequest(pageNo, pageSize, orders);
        //查询条件
        Criteria<Member> specification = new Criteria<Member>();
        specification.add(Restrictions.eq("status", status, false));
        return memberDao.findAll(specification, pageRequest);
    }

    public Page<Member> findByPage(Integer pageNo, Integer pageSize) {
        //排序方式 (需要倒序 这样    Criteria.sort("id","createTime.desc") ) //参数实体类为字段名
        Sort orders = Criteria.sortStatic("id");
        //分页参数
        PageRequest pageRequest = new PageRequest(pageNo, pageSize, orders);
        //查询条件
        Criteria<Member> specification = new Criteria<Member>();
        return memberDao.findAll(specification, pageRequest);
    }

    public boolean emailIsExist(String email) {
        List<Member> list = memberDao.getAllByEmailEquals(email);
        return list.size() > 0 ? true : false;
    }

    public boolean usernameIsExist(String username) {
        return memberDao.getAllByUsernameEquals(username).size() > 0 ? true : false;
    }

    public boolean phoneIsExist(String phone) {
        return memberDao.getAllByMobilePhoneEquals(phone).size() > 0 ? true : false;
    }

    public Member findByUsername(String username) {
        return memberDao.findByUsername(username);
    }

    public Member findByEmail(String email) {
        return memberDao.findMemberByEmail(email);
    }

    public Member findByPhone(String phone) {
        return memberDao.findMemberByMobilePhone(phone);
    }

    public Page<Member> findAll(Predicate predicate, Pageable pageable) {
        return memberDao.findAll(predicate, pageable);
    }

    public String findUserNameById(long id) {
        return memberDao.findUserNameById(id);
    }

    //签到事件
    @Transactional(rollbackFor = Exception.class)
    public void signInIncident(Member member, MemberWallet memberWallet, Sign sign) {
        member.setSignInAbility(false);//失去签到能力
        memberWallet.setBalance(BigDecimalUtils.add(memberWallet.getBalance(), sign.getAmount()));//签到收益

        // 签到记录
        signRecordDao.save(new MemberSignRecord(member, sign));
        //账单明细
        MemberTransaction memberTransaction = new MemberTransaction();
        memberTransaction.setMemberId(member.getId());
        memberTransaction.setAmount(sign.getAmount());
        memberTransaction.setType(ACTIVITY_AWARD);
        memberTransaction.setSymbol(sign.getCoin().getUnit());
        transactionDao.save(memberTransaction);
    }

    //重置会员签到
    public void resetSignIn() {
        memberDao.resetSignIn();
    }

    public void updateCertifiedBusinessStatusByIdList(List<Long> idList) {
        memberDao.updateCertifiedBusinessStatusByIdList(idList, CertifiedBusinessStatus.DEPOSIT_LESS);
    }

    /**
     * 判断验证码是否存在
     * @param promotion
     * @return
     */
    public boolean userPromotionCodeIsExist(String promotion) {
        return memberDao.getAllByPromotionCodeEquals(promotion).size() > 0 ? true : false;
    }

    public Long getMaxId() {
    	return memberDao.getMaxId();
    }

	public Member findMemberByPromotionCode(String code) {
		return memberDao.findMemberByPromotionCode(code);
	}
}
