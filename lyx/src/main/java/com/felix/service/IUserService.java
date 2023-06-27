package com.felix.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.felix.model.dto.LoginFormDTO;
import com.felix.model.dto.Result;
import com.felix.model.entity.User;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

/**
 * <p>
 *  服务类
 * </p>
 *
 */
public interface IUserService extends IService<User> {

    Result sendCode(String phone, HttpSession session);

    Result login(LoginFormDTO loginForm);

    Result logout(HttpServletRequest request);

    /**
     * 实现用户签到
     * @return 无
     */
    Result sign();

    /**
     * 计算连续签到的天数
     * @return 连续签到天数
     */
    Result signCount();
}
