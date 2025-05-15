package com.hmdp.utils;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
public class LoginInterceptor implements HandlerInterceptor {

    /*
    * 拦截器：登录校验实现
    * */

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)  {
       //后执行，只需要查询ThreadLocal的用户是否存在即可
        if(UserHolder.getUser() == null){
            response.setStatus(401);
            return false;
        }
        return true;
    }

}
