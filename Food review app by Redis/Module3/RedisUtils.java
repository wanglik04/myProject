package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Slf4j
@Component
public class RedisUtils {

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    @Resource
    StringRedisTemplate stringRedisTemplate;

    public void saveAndSetExpire(String key, Object object, Long expire, TimeUnit timeUnit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(object),expire,timeUnit);
    }

    public void saveAndSetLogicalExpire(String key, Object object, Long expire, TimeUnit timeUnit){
        RedisData redisData = new RedisData();
        redisData.setData(object);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(expire)));
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(redisData));
    }

    public <R,ID> R queryByNormalId(Class<R> type, String keyPrefix, ID id, Function<ID,R> db, Long expire, TimeUnit timeUnit) {
        String key = keyPrefix+id;
        //        search in redis
        String json = stringRedisTemplate.opsForValue().get(key);
//        return if exist
        if (StrUtil.isNotBlank(json)) return JSONUtil.toBean(json,type);
//        if the nonexistent shop
        if (Objects.equals(json, "")) return null;
//        search in database
        R r = db.apply(id);
//        return false if not exist
        if (r==null){
//            prevent Cache penetration
            saveAndSetExpire(CACHE_SHOP_KEY+id,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
            return null;
        }
//        write in redis and return
        saveAndSetExpire(key,r,expire,timeUnit);
        return r;
    }

    public <R,ID> R queryByHotIdPlus(Class<R> type, String keyPrefix, ID id, Function<ID,R> db, Long expire, TimeUnit timeUnit){
        String key = keyPrefix+id;
//         Using the logical expiration time and thread pool on the basis of Mutex method.
//         search in redis
        String json = stringRedisTemplate.opsForValue().get(key);
//        In theory, It's impossible for the non-existent situation because we need to set the
//        logical expiration time for all hot shop id.
        if(StrUtil.isBlank(json)) return null;
//        deserialize Json to Object
        RedisData redisData = JSONUtil.toBean(json,RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(),type);
        LocalDateTime expireTime = redisData.getExpireTime();
//        Determine whether it has expired
        if(expireTime.isAfter(LocalDateTime.now())) return r;
//        get mutex
        if(tryLock(LOCK_SHOP_KEY+id)) {
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try{
                    R new_r = db.apply(id);
                    saveAndSetLogicalExpire(key,new_r,expire,timeUnit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    freeLock(LOCK_SHOP_KEY+id);
                }
            });
        }
        return r;
    }

    private void freeLock(String key) {stringRedisTemplate.delete(key);}

    private Boolean tryLock(String key){
        Boolean locked = stringRedisTemplate.opsForValue().setIfAbsent(key, "locked", LOCK_SHOP_TTL, TimeUnit.SECONDS);
//        if directly return locked, it may occur NullPointer because of Unboxing
        return BooleanUtil.isTrue(locked);
    }
}
