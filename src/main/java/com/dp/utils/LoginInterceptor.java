package com.dp.utils;

import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

//第二个拦截器，目的：判读是否拦截
public class LoginInterceptor implements HandlerInterceptor {

    //用redis进行登录校验
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (UserHolder.getUser() == null) {
            // 用户未登录，要重定向到登录页面
            response.setStatus(HttpServletResponse.SC_MOVED_TEMPORARILY); // 302状态码
            // 对于API服务和前后端分离的应用，通常不会进行页面重定向，而是返回一个特定的错误码和消息，让前端根据这些信息进行页面跳转或显示相应的错误信息。
            return false;
        }
        return true;
    }

    //销毁用户信息，避免内存泄漏
    //todo 为什么要销毁用户信息，从而避免内存泄漏(好像跟ThreadLocal有关）
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        UserHolder.removeUser();
        HandlerInterceptor.super.afterCompletion(request, response, handler, ex);
    }
}
