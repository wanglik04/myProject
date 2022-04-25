package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * service implementation class
 * </p>
 *
 * @author lik
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        //verify phone
        if (RegexUtils.isPhoneInvalid(phone)) return Result.fail("Wrong format");
        //generate verification code
        String code = RandomUtil.randomNumbers(6);
        //save the code to session
        //session.setAttribute("code",code);
        //save the code to redis
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);
        //send the code
        log.debug("Send success, {}", code);
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        String phone = loginForm.getPhone();
        //not to verify phone again cuz its meaningless, better to save the phone into the
        // session as well, I mean redis, not session.
        //get code from session and verify
        //Object cacheCode = session.getAttribute("code");
        //get code from redis
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        String code = loginForm.getCode();
        if (cacheCode == null || !cacheCode.equals(code)) return Result.fail("Wrong");
        //select * from tb_user where phone = ?
        User user = query().eq("phone", phone).one();
        if (user == null) user = createUserByPhone(phone);
//        session.setAttribute("user",user);
//        save user info into redis
//        generate unique token
        String token = UUID.randomUUID().toString(true);
//        convert user into hashMap and save and set expire
        UserDTO userDTO = BeanUtil.copyProperties(user,UserDTO.class);
        String tokenKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey,BeanUtil.beanToMap(userDTO,
                new HashMap<>(),
                CopyOptions.create().setIgnoreNullValue(true).setFieldValueEditor((fieldName,
                        fieldValue)->fieldValue.toString())));
        stringRedisTemplate.expire(tokenKey,LOGIN_USER_TTL,TimeUnit.SECONDS);
//        return token


        return Result.ok(token);
    }

    @Override
    public Result sign() {
        Long userId = UserHolder.getUser().getId();
        LocalDateTime now = LocalDateTime.now();
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY+userId+keySuffix;
        int dayOfMonth = now.getDayOfMonth();
        stringRedisTemplate.opsForValue().setBit(key,dayOfMonth-1,true);
        return Result.ok();
    }

    @Override
    public Result signCount() {
        Long userId = UserHolder.getUser().getId();
        LocalDateTime now = LocalDateTime.now();
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY+userId+keySuffix;
        int dayOfMonth = now.getDayOfMonth();
        //get this month sign record from today
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create().get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0));
        if(result==null||result.isEmpty()) return Result.ok(0);
        Long num = result.get(0);
        if(num==null||num == 0) return Result.ok(0);
        int cnt = 0;
        while(true){
            if ((num&1)==0) {
               break;
            }else {
                cnt++;
                num>>>=1;
            }
        }
        return Result.ok(cnt);
    }

    private User createUserByPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        save(user);
        return user;
    }
}
