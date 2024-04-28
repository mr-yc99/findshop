package com.dp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.db.AbstractDb;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dp.model.dto.Result;
import com.dp.model.dto.ScrollResult;
import com.dp.model.dto.UserDTO;
import com.dp.model.entity.Blog;
import com.dp.mapper.BlogMapper;
import com.dp.model.entity.Follow;
import com.dp.model.entity.User;
import com.dp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.dp.service.IFollowService;
import com.dp.service.IUserService;
import com.dp.utils.SystemConstants;
import com.dp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.dp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.dp.utils.RedisConstants.FEED_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author forya
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Resource
    private IUserService userService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IFollowService followService;
    @Resource
    private IBlogService blogService;

    // 分页查询，current：当前页的页码
    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();

        // 查询用户和当前用户是否点赞
        records.forEach(blog -> {
            this.queryBlogUser(blog);
            this.isBlogLiked(blog);
        });

        // 查询blog是否点赞
        return Result.ok(records);
    }

    @Override
    public Result queryBlogById(Long id) {
        // 查询blog
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("博客不存在");
        }

        // 查询用户
        queryBlogUser(blog);

        // 查询blog是否点赞
        isBlogLiked(blog);

        return Result.ok(blog);
    }

    private void isBlogLiked(Blog blog) {
        // 1. 获取登录用户，判断当前登录用户是否已经点赞（从redis中）
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            // 用户未登录，无需查询
            return;
        }
        Long userId = user.getId();


        String key = BLOG_LIKED_KEY + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId + "");
        if (score != null) {
            blog.setIsLike(true);
        }

        blog.setIsLike(false);
    }

    // 点赞功能实现
    @Override
    public Result likeBlog(Long id) {
        // 1. 获取登录用户，判断当前登录用户是否已经点赞（从redis中）
        Long userId = UserHolder.getUser().getId();

        String key = BLOG_LIKED_KEY + id;

        // 空则表示没有该元素
        Double score = stringRedisTemplate.opsForZSet().score(key, userId + "");

        // 2. 未点赞 -》对应数据库点赞数加1 -》 保存用户到对应redis的set中
        if (score == null) {
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();

            if (isSuccess) {
                stringRedisTemplate.opsForZSet().add(key, userId + "", System.currentTimeMillis());
            }
        }else {
        // 3. 以点赞 -》取消点赞，数据库点赞数减1 -》 删除redis中的对应用户
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().remove(key, userId + "");
            }
        }

        return Result.ok();
    }

    // 点赞列表功能实现（显示前5名）
    @Override
    public Result queryBlogLikes(Long id) {
        // 1. 查询top5的点赞用户（按时间）
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(BLOG_LIKED_KEY + id, 0, 5);

        if(top5 == null || top5.isEmpty()){
            return Result.ok(Collections.emptyList());
        }

        // 2. 从Set中先获取用户id
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());

        // 用户id之间用逗号 ， 进行拼接
        String idStr = StrUtil.join(",", ids);

        // 3. 根据id查询用户，从小到大显示
        List<User> users = userService.query().in("id", ids).last("ORDER BY FIELD (id, " + idStr + ")").list();

        // 4.转换成DTO
        List<UserDTO> collect = users.stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class)).collect(Collectors.toList());

        return Result.ok(collect);
    }

    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 保存探店博文
        boolean isSuccess = save(blog);

        if (!isSuccess) {
            return Result.fail("笔记上传失败，请重试");
        }

        //查询粉丝, follow_user_id才是被关注的人
        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();

        //并进行推送: 把blogId放到粉丝收件箱（收件箱为：redis的ZSet，value值是blogId + 时间戳）
        for (Follow follow : follows) {
            Long userId = follow.getUserId();


            String key = FEED_KEY + userId;
            stringRedisTemplate.opsForZSet().add(key, blog.getId() + "", System.currentTimeMillis());
        }

        // 返回id
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        // 1.查询当前用户收件箱（包含blogId和时间戳）
        Long userId = UserHolder.getUser().getId();
        String key = FEED_KEY + userId;

        // 获取从max（上次查询最小时间）开始的blogId和时间戳
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, max, 0, offset, 3);

        if(typedTuples == null || typedTuples.isEmpty()){
            return Result.ok();
        }

        ArrayList<Long> blogIds = new ArrayList<>(typedTuples.size());

        long minTime = 0;
        int os = 1; // offset

        // 2. 解析数据：解析出minTime，offset，blogId
        for (ZSetOperations.TypedTuple<String> typedTuple : typedTuples) {
            // 2.1 获取blogId
            String blogIdStr = typedTuple.getValue();
            blogIds.add(Long.valueOf(blogIdStr));

            // 2.2 解析minTime和offset
            if(typedTuple.getScore().longValue() == minTime){
                os++;
            }else {
                minTime = typedTuple.getScore().longValue();
                os = 1;
            }
        }

        // 3. 根据id查blog
        // 跟上面一样，不能用mp里的listByIds查，因为这个方法最终的sql使用in来查，
        // 不能保证是我们想要的顺序
        String blogIdsStr = StrUtil.join(",", blogIds);
        List<Blog> blogs = blogService.query()
                .in("id", blogIdsStr)
                .last("ORDER BY FIELD (id, " + blogIdsStr + ")")
                .list();


        for (Blog blog : blogs) {
            queryBlogUser(blog);
            isBlogLiked(blog);
        }


        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setList(blogs);
        scrollResult.setMinTime(minTime);
        scrollResult.setOffset(os);

        return Result.ok(scrollResult);
    }

    // 查询用户
    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
}
