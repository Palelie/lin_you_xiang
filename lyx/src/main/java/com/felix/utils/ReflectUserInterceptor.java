package com.felix.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.felix.model.constants.RedisConstants;
import com.felix.model.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class ReflectUserInterceptor implements HandlerInterceptor {

    private StringRedisTemplate stringRedisTemplate;

    public ReflectUserInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 拦截所有请求，处理请求头token数据，设置UserDTO数据
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {

        //1、从request的请求头中获取token
        String token = request.getHeader("authorization");
        if (StrUtil.isBlank(token)){
            return true;
        }
        //2、从Redis中获取userDTO
        String tokenKey = RedisConstants.LOGIN_USER_KEY + token;
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(tokenKey);
        //判断用户是否存在
        if (userMap.isEmpty()){
            return true;
        }

        //3、将Map转换为Bean
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), true);

        //4、保存userDTO到ThreadLocal
        UserHolder.saveUser(userDTO);

        //5、刷新redis中token的有效期
        stringRedisTemplate.expire(tokenKey,RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);

        //6、放行
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        UserHolder.removeUser();
    }
}
