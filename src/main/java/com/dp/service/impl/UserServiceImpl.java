package com.dp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.dp.model.dto.LoginFormDTO;
import com.dp.model.dto.Result;
import com.dp.model.dto.UserDTO;
import com.dp.model.entity.User;
import com.dp.mapper.UserMapper;
import com.dp.service.IUserService;
import com.dp.utils.RedisConstants;
import com.dp.utils.RegexUtils;
import com.dp.utils.SystemConstants;
import com.dp.utils.UserHolder;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.dp.utils.RedisConstants.USER_SIGN_KEY;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author forya
 * @since 2021-12-22
 */
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    private static final String CODE = "code";
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 1.校验手机号，不符合返回错误信息
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误");
        }
        // 2.生成验证码, 长度：6,hutool随机生成
        String code = RandomUtil.randomNumbers(6);

        // 3.保存验证码到redis, set key value ex 120
        //"dp:user:code一般不直接写，而是直接定义成常量或者枚举或者定义在工具类里
        stringRedisTemplate.opsForValue().set(RedisConstants.LOGIN_CODE_KEY + phone, code, RedisConstants.LOGIN_CODE_TTL, TimeUnit.MINUTES);

        //4.发送验证码（自己：发送完毕让code失效？）
        //todo 需要使用平台，例如阿里，这里就弄个假的
        log.debug("短信验证码发送成功：" + code);

        //5.返回OK
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        String phone = loginForm.getPhone();
        String code = loginForm.getCode();
        // 1.校验手机号和验证码，不一致：报错
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误");
        }

        //从Redis中取出验证码
        String cacheCode = stringRedisTemplate.opsForValue().get(RedisConstants.LOGIN_CODE_KEY + phone);


        if(StringUtils.isEmpty(cacheCode)) {
            return Result.fail("code为空");
        }
        if(!cacheCode.equals(code)) {
            return Result.fail("验证码错误");
        }

        // 2.根据手机查询用户
        User user = query().eq("phone", phone).one();

        // 4 用户不存在，自动注册保存到数据库，在保存到session
        if(user == null) {
            user = createUserByPhone(phone);
        }

        // 3.用户存在，转成UserDTO，保存到redis（随机生成token作为登录令牌，将user转为hash，保存到redis）
        UserDTO userDTO = new UserDTO();
        BeanUtil.copyProperties(user, userDTO);

        // 随机生成token作为登录令牌，使用UUID
        String token = UUID.randomUUID().toString(true);

        //以token为key，存储userDTO为Hash，并确保Map中值必须为String类型
        Map<String, Object> userMap = BeanUtil.beanToMap(
                userDTO,
                new HashMap<>(),
                CopyOptions.create().setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()) );

        //保存到redis
        //注意这里stringRedisTemplate要求key和value都要是String，
        // 而userMap中的id是long类型，直接put会报 类型转换异常 ：ClassCastException: class java.lang.Long cannot be cast to class java.lang.String
        stringRedisTemplate.opsForHash().putAll(RedisConstants.LOGIN_USER_KEY + token, userMap);
        //下面是设置有效期，从存储开始就计算
        stringRedisTemplate.expire(RedisConstants.LOGIN_USER_KEY + token, RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);
        log.debug("userDTO" + userDTO.toString());

        //todo 登录完毕应该让验证码失效
        //返回给前端
        return Result.ok(token);
    }

    @Override
    public Result sign() {
        // 1.设置key
        Long userId = UserHolder.getUser().getId();
        LocalDateTime now = LocalDateTime.now();
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));

        String key = USER_SIGN_KEY + userId + keySuffix;

        // 2.今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        int offset = dayOfMonth - 1;

        // 3.写入redis
        stringRedisTemplate.opsForValue().setBit(key, offset, true);

        return Result.ok();
    }

    @Override
    public Result signCount() {
        // 1.获取本月到今天为止所有签到数据
        Long userId = UserHolder.getUser().getId();
        LocalDateTime now = LocalDateTime.now();
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));

        String key = USER_SIGN_KEY + userId + keySuffix;

        // 今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();

        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands
                        .create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth))
                        .valueAt(0)
        );

        if(result == null || result.isEmpty()) {
            return Result.ok(0);
        }

        Long num = result.get(0);
        if(num == null || num ==0) {
            return Result.ok(0);
        }
        // 2.统计
        int count = 0;
        while ((num & 1) != 0) {
            count++;
            num >>>= 1; // 无符号右移 

        }
        return Result.ok(count);

    }


    private User createUserByPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        save(user);
        return user;
    }
}


