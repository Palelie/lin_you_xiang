package com.felix.utils;

import com.felix.model.dto.UserDTO;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;


public class LoginInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {

        //检查LocalThread中是否有UserDTO数据
        UserDTO userDTO = UserHolder.getUser();

        if (userDTO == null){
            response.setStatus(401);
            return false;
        }
        //用户存在：放行
        return true;
    }
}
