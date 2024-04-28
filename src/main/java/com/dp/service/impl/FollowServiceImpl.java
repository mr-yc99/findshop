package com.dp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.dp.model.dto.Result;
import com.dp.model.dto.UserDTO;
import com.dp.model.entity.Blog;
import com.dp.model.entity.Follow;
import com.dp.mapper.FollowMapper;
import com.dp.model.entity.User;
import com.dp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.dp.service.IUserService;
import com.dp.utils.SystemConstants;
import com.dp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import javax.annotation.Resource;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.dp.utils.RedisConstants.FOLLOW_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author forya
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IUserService userService;

    // 关注或取关
    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        // 1. 获取当前登录用户
        UserDTO user = UserHolder.getUser();
        if(user == null){
            return Result.fail("用户未登录");
        }

        Long userId = user.getId();

        // 2. 未关注：新增数据
        if(isFollow) {
            // 保存到数据库
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            boolean isSave = save(follow);

            // 保存到redis中
            if(isSave){
                stringRedisTemplate.opsForSet().add(FOLLOW_KEY + userId, followUserId + "");
            }
            return Result.ok();
        }

        // 3. 已关注：取关(删除数据库和redis中的数据)
        QueryWrapper<Follow> queryWrapper = new QueryWrapper<Follow>().eq("follow_user_id", followUserId).eq("user_id", userId);
        boolean isRemove = remove(queryWrapper);

        if (isRemove) {
            stringRedisTemplate.opsForSet().remove(FOLLOW_KEY + userId, followUserId + "");
        }


        return Result.ok();
    }

    @Override
    public Result isFollow(Long followUserId) {
        UserDTO user = UserHolder.getUser();
        if(user == null){
            return Result.fail("用户未登录");
        }
        Long userId = user.getId();

        Integer count = query().eq("follow_user_id", followUserId).eq("user_id", userId).count();

        if(count > 0){
            return Result.ok(true);
        }

        return Result.ok(false);
    }

    @Override
    public Result followCommons(Long id) {
        UserDTO userDTO = UserHolder.getUser();
        if(userDTO == null){
            return Result.fail("用户未登录");
        }
        Long userId = userDTO.getId();

        String myKey = FOLLOW_KEY + userId;
        // 求交集
        String key = FOLLOW_KEY + id;
        //得到共同关注的id的set，但是是String类型的
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(myKey, key);
        // 解析set
        if (intersect != null) {
            List<Long> commonIds = intersect.stream().map(Long::valueOf).collect(Collectors.toList());

            // 目的是返回UserDTO列表
            List<User> users = userService.listByIds(commonIds);
            List<UserDTO> userDTOS = users
                    .stream()
                    .map(user -> BeanUtil.copyProperties(userDTO, UserDTO.class))
                    .collect(Collectors.toList());

            return Result.ok(userDTOS);
        }

        return Result.ok(Collections.emptyList());
    }
}
