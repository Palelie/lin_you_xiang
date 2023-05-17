package com.felix.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.felix.dto.LoginFormDTO;
import com.felix.dto.Result;
import com.felix.entity.User;

import javax.servlet.http.HttpSession;

/**
 * <p>
 *  服务类
 * </p>
 *
 */
public interface IUserService extends IService<User> {

    Result sendCode(String phone, HttpSession session);

    Result login(LoginFormDTO loginForm, HttpSession session);

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
