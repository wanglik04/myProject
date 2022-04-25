package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import lombok.AllArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;


import static com.hmdp.utils.RedisConstants.KEY_PREFIX;

@AllArgsConstructor
public class RedisLock implements ILock{
    private StringRedisTemplate stringRedisTemplate;
    private String name;
    //    for determine if the threadId when unlock
    private static final String ID_PREFIX = UUID.randomUUID().toString(true)+"-";
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX+name, threadId, timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);
    }

//    using lua script to promise atomic
    @Override
    public void unlock() {
        stringRedisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(KEY_PREFIX + name),
                ID_PREFIX + Thread.currentThread().getId());
    }

//    @Override
//    public void unlock() {
//        String curThreadId = ID_PREFIX + Thread.currentThread().getId();
//        String lockThreadId = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
//        if(curThreadId.equals(lockThreadId)) stringRedisTemplate.delete(KEY_PREFIX+name);
//    }
}
