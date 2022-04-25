package com.hmdp.interceptor;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import com.hmdp.utils.UserHolder;
import lombok.AllArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_TTL;

@AllArgsConstructor
public class RefreshTokenInterceptor implements HandlerInterceptor {
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
//        get session
//        HttpSession session = request.getSession();
//        get token and key from RequestHeader
        String token = request.getHeader("authorization");
        String key = LOGIN_USER_KEY + token;
        if(StrUtil.isBlank(token)) return true;
//        get user from session
//        User user = (User) session.getAttribute("user");
//        get user from redis by token
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(key);
//        if(user==null) return false;//response.setStatus(401);
        if(userMap.isEmpty()) return true;
//        convert HashMap res into UserDTO
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(),false);
//        refresh the token expire time
        stringRedisTemplate.expire(key,LOGIN_USER_TTL, TimeUnit.SECONDS);
//        keep user info into ThreadLocal
        UserHolder.saveUser(userDTO);
        return true;
    }
//    @Override
//    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
//        UserHolder.removeUser();
//    }
}
