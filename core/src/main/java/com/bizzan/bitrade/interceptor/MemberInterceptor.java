package com.bizzan.bitrade.interceptor;


import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.StringUtils;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.web.context.support.WebApplicationContextUtils;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

import com.bizzan.bitrade.constant.SysConstant;
import com.bizzan.bitrade.entity.Member;
import com.bizzan.bitrade.entity.transform.AuthMember;
import com.bizzan.bitrade.event.MemberEvent;
import com.bizzan.bitrade.service.MemberService;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Calendar;

/**
 * @author Jammy
 * @date 2019年01月11日
 */
@Slf4j
public class MemberInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        HttpSession session = request.getSession();
        log.info(request.getRequestURL().toString());
        AuthMember user = (AuthMember) session.getAttribute(SysConstant.SESSION_MEMBER);
        if (user != null) {
            return true;
        } else {
            String token = request.getHeader("access-auth-token");
            log.info("token:{}",token);
            //解决service为null无法注入问题
            BeanFactory factory = WebApplicationContextUtils.getRequiredWebApplicationContext(request.getServletContext());
            MemberService memberService = (MemberService) factory.getBean("memberService");
            MemberEvent memberEvent = (MemberEvent) factory.getBean("memberEvent");
            Member member = memberService.loginWithToken(token, request.getRemoteAddr(), "");
            if (member != null) {
//                Calendar calendar = Calendar.getInstance();
//                calendar.add(Calendar.HOUR_OF_DAY, 24 * 7);
//                member.setTokenExpireTime(calendar.getTime());
                memberService.save(member);
                String ip = getRemoteIp(request);
                memberEvent.onLoginSuccess(member, request.getRemoteAddr(), "token_login");
                session.setAttribute(SysConstant.SESSION_MEMBER, AuthMember.toAuthMember(member));
                log.info("{} login success", member.getEmail());
                return true;
            } else {
                ajaxReturn(response, 4000, "当前登录状态过期，请您重新登录！");
                return false;
            }
        }
    }


    public static void ajaxReturn(HttpServletResponse response, int code, String msg) throws IOException, JSONException {
        response.setCharacterEncoding("UTF-8");
        response.setContentType("text/json; charset=UTF-8");
        PrintWriter out = response.getWriter();
        JSONObject json = new JSONObject();
        json.put("code", code);
        json.put("message", msg);
        out.print(json.toString());
        out.flush();
        out.close();
    }

    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler,
                           ModelAndView modelAndView) throws Exception {
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex)
            throws Exception {

    }


    public String getRemoteIp(HttpServletRequest request) {
        if (StringUtils.isNotBlank(request.getHeader("X-Real-IP"))) {
            return request.getHeader("X-Real-IP");
        } else if (StringUtils.isNotBlank(request.getHeader("X-Forwarded-For"))) {
            return request.getHeader("X-Forwarded-For");
        } else if (StringUtils.isNotBlank(request.getHeader("Proxy-Client-IP"))) {
            return request.getHeader("Proxy-Client-IP");
        }
        return request.getRemoteAddr();
    }

}
