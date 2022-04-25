package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.FOLLOWS_KEY;

/**
 * <p>
 *  service implementation class
 * </p>
 *
 * @author lik
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {
    @Resource
    StringRedisTemplate stringRedisTemplate;

    @Resource
    IUserService userService;

    @Override
    public Result checkFollow(Long followUserId) {
        Long userId = UserHolder.getUser().getId();
        Integer count = query().eq("user_id", userId).eq("follow_user_id", followUserId).count();
        return Result.ok(count>0);
    }

    @Override
    @Transactional
    public Result follow(Long followUserID, Boolean toFollow) {
        Long userId = UserHolder.getUser().getId();
        String key = FOLLOWS_KEY+userId;
        if(toFollow){
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserID);
            if(save(follow)) stringRedisTemplate.opsForSet().add(key,followUserID.toString());

        }else {
            if(remove(new QueryWrapper<Follow>().eq("user_id", userId).eq("follow_user_id", followUserID))) stringRedisTemplate.opsForSet().remove(key,followUserID.toString());
        }
        return Result.ok();
    }

    @Override
    public Result getCommon(Long tarUserId) {
        Long curUserId = UserHolder.getUser().getId();
        String curUserKey = FOLLOWS_KEY+curUserId;
        String tarUserKey = FOLLOWS_KEY+tarUserId;
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(curUserKey, tarUserKey);
        if(intersect==null||intersect.isEmpty()) return Result.ok(Collections.EMPTY_LIST);
        List<Long> commons = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        List<UserDTO> userDTOs = userService.listByIds(commons).stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class)).collect(Collectors.toList());
        return Result.ok(userDTOs);
    }
}
