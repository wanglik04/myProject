package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;

/**
 * <p>
 *  service implementation class
 * </p>
 *
 * @author lik
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserService userService;

    @Resource
    private IFollowService followService;

    @Override
    public Result queryHotBlog(Integer current) {
        // query by user
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // get current page
        List<Blog> records = page.getRecords();
        // search user
//        records.forEach(this::fillUserInBlog);
        records.forEach(blog -> {
            this.fillUserInBlog(blog);
            this.fillBlogLiked(blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result queryBlogById(Long id) {
        Blog blog = getById(id);
        if(blog==null) return Result.fail("Nonexistent Blog");
        fillUserInBlog(blog);
        //check if blog has been liked
        fillBlogLiked(blog);
        return Result.ok(blog);
    }

    private void fillBlogLiked(Blog blog) {
        UserDTO user = UserHolder.getUser();
        if(user==null) return;// not login
        Long userId = user.getId();
        String watch1 = BLOG_LIKED_KEY + blog.getId();
        String watch2 = userId.toString();
        Double score = stringRedisTemplate.opsForZSet().score(watch1, watch2);
        blog.setIsLike(score!=null);
    }

    @Override
    public Result likeBlog(Long id) {
        Long userId = UserHolder.getUser().getId();
        String key = BLOG_LIKED_KEY+id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if(score!=null){
            boolean success = update().setSql("liked = liked - 1").eq("id", id).update();
            if(success) stringRedisTemplate.opsForZSet().remove(key, userId.toString());
        }else {
            boolean success = update().setSql("liked = liked + 1").eq("id", id).update();
            if(success) stringRedisTemplate.opsForZSet().add(key, userId.toString(),System.currentTimeMillis());
        }
        return Result.ok();
    }

    @Override
    @Transactional
    public Result queryBlogLikes(Long id) {
        String key = BLOG_LIKED_KEY+id;
        Set<String> tops = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if(tops == null||tops.isEmpty()) return Result.ok(Collections.emptyList());
        List<Long> ids = tops.stream().map(Long::valueOf).collect(Collectors.toList());
//        List<UserDTO> userDTOS = userService.listByIds(ids).stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class)).collect(Collectors.toList()); //Bug
        List<UserDTO> userDTOS = userService.query().in("id", ids).last("ORDER BY FIELD(id," + StrUtil.join(",", ids) + ")").list().stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class)).collect(Collectors.toList());
        return Result.ok(userDTOS);
    }

    @Override
    public Result saveBolg(Blog blog) {
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        if(!save(blog))return Result.fail("Failed to save blog");
        //push new blog to followers
        //get all followers
        List<Follow> fans = followService.query().eq("follow_user_id", user.getId()).list();
        for (Follow fan : fans) {
            Long userId = fan.getUserId();
            String key = FEED_KEY+userId;
            stringRedisTemplate.opsForZSet().add(key,blog.getId().toString(),System.currentTimeMillis());
        }
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        Long curUserId = UserHolder.getUser().getId();
        String key = FEED_KEY+curUserId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet().reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        if(typedTuples==null||typedTuples.isEmpty()) return Result.ok();
        List<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime = 0;
        int os = 1;
        for (ZSetOperations.TypedTuple<String> tuple : typedTuples) {
            String value = tuple.getValue();
            ids.add(Long.valueOf(value));
            long time = tuple.getScore().longValue();
            if(time==minTime) os++;
            else {
                minTime = time;
                os = 1;
            }
        }
        String idStr = StrUtil.join(",", ids);
        List<Blog> blogs = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Blog blog : blogs) {
            fillUserInBlog(blog);
            fillBlogLiked(blog);
        }
        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setList(blogs);
        scrollResult.setMinTime(minTime);
        scrollResult.setOffset(os);
        return Result.ok(scrollResult);
    }

    private void fillUserInBlog(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
}
