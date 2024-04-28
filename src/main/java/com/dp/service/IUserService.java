package com.dp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.dp.model.dto.LoginFormDTO;
import com.dp.model.dto.Result;
import com.dp.model.entity.User;

import javax.servlet.http.HttpSession;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author forya
 * @since 2021-12-22
 */
public interface IUserService extends IService<User> {

    Result sendCode(String phone, HttpSession session);

    Result login(LoginFormDTO loginForm, HttpSession session);

    Result sign();

    Result signCount();
}
