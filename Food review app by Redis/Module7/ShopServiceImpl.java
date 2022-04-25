package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.RedisUtils;
import com.hmdp.utils.SystemConstants;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  service implementation class
 * </p>
 *
 * @author lik
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedisUtils redisUtils;

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    @Override
    public Result queryById(Long id) {
//        Shop shop = redisUtils.queryByNormalId(Shop.class,CACHE_SHOP_KEY,id,this::getById,
//                CACHE_SHOP_TTL,TimeUnit.MINUTES);
        Shop shop = redisUtils.queryByHotIdPlus(Shop.class,CACHE_SHOP_KEY,id,this::getById,
                CACHE_SHOP_TTL,TimeUnit.MINUTES);
        if(shop==null) return Result.fail("Shop not exist");
        return Result.ok(shop);
    }

    @Override
    public void cleanRedis(String key) {
        /*
        If exception, the transaction should roll back so it better add the update method here
        and add @Transactional and because I'm doing a single project thus it can guarantee
        its atomicity. If we are using disturbed system, better to use TCC.
         */
        stringRedisTemplate.delete(key);
    }

    @Override
    public Result queryByHotId(Long id) {
        //        search in redis
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        //        return if exist
        if (StrUtil.isNotBlank(shopJson)) return Result.ok(JSONUtil.toBean(shopJson,Shop.class));
        //        if the nonexistent shop
        if (Objects.equals(shopJson, "")) return Result.fail("Shop not exist");
//        lock the hotId from searching it from database
        Shop shop;
        try{// you may add an 'if' to judge if the returned shop is null
            if(!tryLock(LOCK_SHOP_KEY+id)){
                Thread.sleep(50);
                return queryByHotId(id);// return is important
            }
            //        search in database
            shop = getById(id);
            // Simulate query is slow
            Thread.sleep(200);
            //        return false if not exist
            if (shop==null){
//            prevent Cache penetration
                stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
                return Result.fail("Shop not exist");
            }
            //        write in redis and return
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(shop),
                    CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }finally {
            freeLock(LOCK_SHOP_KEY+id);
        }
        return Result.ok(shop);
    }

    @Override
    public Result queryByHotIdPlus(Long id) {
//         Using the logical expiration time and thread pool on the basis of Mutex method.
//         search in redis
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
//        In theory, It's impossible for the non-existent situation because we need to set the
//        logical expiration time for all hot shop id.
        if(StrUtil.isBlank(shopJson)) return Result.fail("Hot shop not exist");
//        deserialize Json to Object
        RedisData redisData = JSONUtil.toBean(shopJson,RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(),Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
//        Determine whether it has expired
        if(expireTime.isAfter(LocalDateTime.now())) return Result.ok(shop);
//        get mutex
        if(tryLock(LOCK_SHOP_KEY+id)) {
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try{
                    warmUpHotId2Redis(id,LOCK_SHOP_TTL);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    freeLock(LOCK_SHOP_KEY+id);
                }
            });
        }
        return Result.ok(shop);
    }

    @Override
    public Result queryByNormalId(Long id) {
        //        search in redis
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
//        return if exist
        if (StrUtil.isNotBlank(shopJson)) return Result.ok(JSONUtil.toBean(shopJson,Shop.class));
//        if the nonexistent shop
        if (Objects.equals(shopJson, "")) return Result.fail("How many times should I told you the shop is not exist?");
//        search in database
        Shop shop = getById(id);
//        return false if not exist
        if (shop==null){
//            prevent Cache penetration
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
            return Result.fail("Shop not exist");
        }
//        write in redis and return
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(shop),
                CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return Result.ok(shop);
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        if(x==null||y==null){
            // Paging query by type
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // return data
            return Result.ok(page.getRecords());
        }
        String key = SHOP_GEO_KEY+typeId;
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo().search(
                key,
                GeoReference.fromCoordinate(x, y),
                new Distance(5000),//meters
                RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
        );
        if(results==null) return Result.ok(Collections.emptyList());
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if(list.size()<=from) return Result.ok(Collections.emptyList());
        //sub from to end
        List<Long> ids = new ArrayList<>(list.size());
        Map<String,Distance> distanceMap = new HashMap<>(list.size());
        list.stream().skip(from).forEach(result->{
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr,distance);
        });
        String idStr = StrUtil.join(",",ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        return Result.ok(shops);
    }

    private void freeLock(String key) {stringRedisTemplate.delete(key);}

    private Boolean tryLock(String key){
        Boolean locked = stringRedisTemplate.opsForValue().setIfAbsent(key, "locked", LOCK_SHOP_TTL, TimeUnit.SECONDS);
//        if directly return locked, it may occur NullPointer because of Unboxing
        return BooleanUtil.isTrue(locked);
    }
    public void warmUpHotId2Redis(Long id, Long ttl) throws InterruptedException {
        Shop shop = getById(id);
        Thread.sleep(200);
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(ttl));
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));
    }
}
