# Food review app by Redis

## Module1: Implement UserLogin based on Session

For related codes, see MvcConfig.java, LoginInterceptor.java, RefreshTokenInterceptor.java and UserServiceImpl.java

### Version 1.0

For related codes, see UserServiceImpl.java

Verify the mobile phone number, generate and send the verification code and save it to the session, ( at this time, the front end should prohibit modifying the mobile phone number), if it passes, determine whether to add a new user, and finally save the user UserDTO (rather than User entity to avoid exposing sensitive information and save space) to the session.

```java
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
```

```java
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
```

```java
private User createUserByPhone(String phone) {
    User user = new User();
    user.setPhone(phone);
    user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
    save(user);
    return user;
}
```

#### Why save the user info into the Session?

Because some requests require the user to log in to operate, so that we need to verify the user login status.

### Version 2.0

For related codes, see MvcConfig.java and LoginInterceptor.java

Because there are many functional modules that require the user to log in before they can operate, we'd better make the function of judging the user's login status as an interceptor to realize the idea of code decoupling. If the user has logged in, then save the user information to Threadlocal, which is convenient for other function modules to read.

#### Why Threadlocal?

Because it is thread-safe, it will not be modified by other threads.

```java
public class UserHolder {
    private static final ThreadLocal<UserDTO> tl = new ThreadLocal<>();

    public static void saveUser(UserDTO user){
        tl.set(user);
    }

    public static UserDTO getUser(){
        return tl.get();
    }

    public static void removeUser(){
        tl.remove();
    }
}
```

### Version 3.0

For load balancing, Nginx often switches requests to different Tomcat server. Because session is not shared between Tomcat clusters, So better to use redis to save user information, and set the login validity period, aka TTL.

### Version 4.0

For related codes, see MvcConfig.java, LoginInterceptor.java and RefreshTokenInterceptor.java

In this software, the validity period after the user logs in is 30 minutes. However, the validity period of the login should be refreshed after each operation of the user, so as not to affect user's experience. Therefore, we need to judge whether the user is logged in every time the user requests. Just refresh the validity period. So we also need to write an interceptor. The scope of this interceptor is different from the previous one, because not every request needs to be logged in, but every request needs to determine whether the login status needs to be refreshed. At the same time, two  interceptors both have a code on judging whether the user is logged in, so we can extract the code for judging the user's login status to decouple them.

```java
@Override
public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate)).addPathPatterns("/**").order(0);
    registry.addInterceptor(new LoginInterceptor()).excludePathPatterns(
            "/user/code",
            "/user/login",
            "/blog/hot",
            "/shop-type/**",
            "/shop/**",
            "/upload/**",
            "/voucher/**"
    ).order(1);//add interceptor and exclude path

}
```

```java
public class LoginInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        return UserHolder.getUser() != null;
    }
}
```

```java
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
```



## Module2: Query for store

In the process of software application development, there are often some data that are frequently accessed but not frequently modified. When the amount of user's access is huge, it will cause a lot of unnecessary pressure on our mysql. Why it can be avoided? Because these data can be replaced by redis, such as the store information of the store, these data can be written in advance by redis, so that users send access, if redis hits, there is no need to access the database and the speed is also greatly improved because redis is stored in memory rather than disk.

### How to ensure data consistency? Double deletion delay strategy

When the database needs to be updated, first delete the cache in redis, then update the database, and delete the cache again after a short period of time to prevent another thread writing the wrong value from the database to the cache during updating. Of course, you can also cancel the waiting time for performance reasons.

### Version 1.0

For related codes, see ShopServiceImpl.java

save all the shop info into redis.

```java
public void warmUpHotId2Redis(Long id, Long ttl) throws InterruptedException {
  Shop shop = getById(id);
  Thread.sleep(200);
  RedisData redisData = new RedisData();
  redisData.setData(shop);
  redisData.setExpireTime(LocalDateTime.now().plusSeconds(ttl));
  stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));
}
```

### Version 2.0

Prevent Cache penetration by Cache empty objects. 

Sometimes large amount of access to a nonexist data may block mysql. At this time, we have two choices: 1. add null into redis. 2. Using Bloom filter. I chose the first.

```java
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
```

### Version 3.0

Add a random number to the TTL of each key to prevent all the keys invalidating at the same time and causing cache avalanche

### Version 4.0

Solve Cache Breakdown by using MuteX 

```java
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
```

```java
    private void freeLock(String key) {stringRedisTemplate.delete(key);}

    private Boolean tryLock(String key){
        Boolean locked = stringRedisTemplate.opsForValue().setIfAbsent(key, "locked", LOCK_SHOP_TTL, TimeUnit.SECONDS);
//        if directly return locked, it may occur NullPointer because of Unboxing
        return BooleanUtil.isTrue(locked);
    }
```

### Version 5.0

In previous version, other thread can't do nothing but wait when updating the redis until update complated, which is a quite waste of resouces. What's more, it may occur deadlock when query muti data. So I plan to sacrifice consistency for high availability by adding logical expiration time into redis as a string, so it will never actual expire. Therefore, other threads can using the old data before update completed rather than wait.

```java
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
```

```java
public void saveAndSetLogicalExpire(String key, Object object, Long expire, TimeUnit timeUnit){
    RedisData redisData = new RedisData();
    redisData.setData(object);
    redisData.setExpireTime(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(expire)));
    stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(redisData));
}
```

## Module3: Coupon order

### Version 1.0

Considering the high concurrency scenario, if multiple requests arrive at the database at the same time, the database commodity inventory number may become negative, that is, the oversold phenomenon. Therefore, a judgment condition is added to the modification statement of the database, and the default transaction isolation level of innodb is used to solve the problem.

```java
@Override
@Transactional
public void addSeckillVoucher(Voucher voucher) {
    // save hot voucher into database
    save(voucher);
    // save hot voucher info into database
    SeckillVoucher seckillVoucher = new SeckillVoucher();
    seckillVoucher.setVoucherId(voucher.getId());
    seckillVoucher.setStock(voucher.getStock());
    seckillVoucher.setBeginTime(voucher.getBeginTime());
    seckillVoucher.setEndTime(voucher.getEndTime());
    seckillVoucherService.save(seckillVoucher);
    //save hot voucher into redis
    stringRedisTemplate.opsForValue().set(SECKILL_STOCK_KEY+voucher.getId(),
            voucher.getStock().toString());
}
```

```java
    @Override
    public Result buyHotVoucher(Long voucherId) {
        // select * from tb_seckill_voucher
        SeckillVoucher hotVoucher = seckillVoucherService.getById(voucherId);
        // determine the time range
        if (LocalDateTime.now().isBefore(hotVoucher.getBeginTime())) return Result.fail("Not yet!");
        if (LocalDateTime.now().isAfter(hotVoucher.getEndTime())) return Result.fail("Time passed!");
        // determine the stock is enough
        if (hotVoucher.getStock()<1) return Result.fail("sold out");
//          creatOrder
//        return createHotVoucherOrder(voucherId);
//        return createHotVoucherOrderFromCluster(voucherId);
        return createHotVoucherOrderFromClusterByUsingRedisson(voucherId);
    }
```

### Version 2.0

Because some coupons are limited, we need to make sure that one person can only place one order. Then we shall using muteX.

```java
@Transactional
public Result createHotVoucherOrder(Long voucherId){
    //get userId from session
    Long userId = UserHolder.getUser().getId();
    synchronized (userId.toString().intern()){//muteX
        if(query().eq("voucher_id",voucherId).eq("user_id",userId).count()>0) return Result.fail("Can only buy once");
        // update stock
        boolean success = seckillVoucherService.update().setSql("stock = stock-1").eq("voucher_id", voucherId).gt("stock",0).update();
        if(!success) return Result.fail("sold out");
        // add new order
        VoucherOrder voucherOrder = new VoucherOrder();
        Long orderId = redisIdGenerator.nextId(SECKILL_STOCK_KEY);
        //set and save
        voucherOrder.setVoucherId(voucherId);
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        save(voucherOrder);
        return Result.ok(orderId);
    }
}
```

### Version 3.0

However, JVM lock can't prevent this from cluster. Then we shall use the setnx command in Redis to implement Distributed lock. 

```java
@Transactional
public Result createHotVoucherOrderFromCluster(Long voucherId){
    //get userId from session
    Long userId = UserHolder.getUser().getId();
    RedisLock redisLock = new RedisLock(stringRedisTemplate,"order"+userId);
    if(!redisLock.tryLock(1200)) return Result.fail("Repeated order");
    try {
        if (query().eq("voucher_id", voucherId).eq("user_id", userId).count() > 0) return Result.fail("Can only buy once");
        // update stock
        boolean success = seckillVoucherService.update().setSql("stock = stock-1").eq("voucher_id", voucherId).gt("stock", 0).update();
        if (!success) return Result.fail("sold out");
        // add new order
        VoucherOrder voucherOrder = new VoucherOrder();
        Long orderId = redisIdGenerator.nextId(SECKILL_STOCK_KEY);
        //set and save
        voucherOrder.setVoucherId(voucherId);
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        save(voucherOrder);
        return Result.ok(orderId);
    }finally {
        redisLock.unlock();
    }
}
```

Implemented by Redisson client

```java
@Transactional
public Result createHotVoucherOrderFromClusterByUsingRedisson(Long voucherId){
    //get userId from session
    Long userId = UserHolder.getUser().getId();
    RLock redisLock = redissonClient.getLock("lock:order:" + userId);
    if(!redisLock.tryLock()) return Result.fail("Repeated order");
    try {
        if (query().eq("voucher_id", voucherId).eq("user_id", userId).count() > 0) return Result.fail("Can only buy once");
        // update stock
        boolean success = seckillVoucherService.update().setSql("stock = stock-1").eq("voucher_id", voucherId).gt("stock", 0).update();
        if (!success) return Result.fail("sold out");
        // add new order
        VoucherOrder voucherOrder = new VoucherOrder();
        Long orderId = redisIdGenerator.nextId(SECKILL_STOCK_KEY);
        //set and save
        voucherOrder.setVoucherId(voucherId);
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        save(voucherOrder);
        return Result.ok(orderId);
    }finally {
        redisLock.unlock();
    }
}
```

### Version 4.0

We must take into account the situation that the redis crashed, and lock is not release. It may cause blockage, to solve this, we shall set TTL. 

By doing this will create a new problem, that is, when my business is executed for too long and the lock is released due to TTL, other businesses come in and grab my lock, and finally my business ends and the locks used by other threads are deleted. The solution is to use the thread id as part of the key, and determine whether the current thread is in use when releasing the lock.

```lua
if(redis.call('get',KEYS[1])==ARGV[1]) then
    return redis.call('del',KEYS[1])
end
return 0
```

```java
//    using lua script to promise atomic
    @Override
    public void unlock() {
        stringRedisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(KEY_PREFIX + name),
                ID_PREFIX + Thread.currentThread().getId());
    }
```

```java
@Override
public boolean tryLock(long timeoutSec) {
    String threadId = ID_PREFIX + Thread.currentThread().getId();
    Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX+name, threadId, timeoutSec, TimeUnit.SECONDS);
    return Boolean.TRUE.equals(success);
}
```

```java
private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
static {
    UNLOCK_SCRIPT = new DefaultRedisScript<>();
    UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
    UNLOCK_SCRIPT.setResultType(Long.class);
}
```

### Version 5.0

Under normal circumstances, the operation of adding, deleting, modifying and checking the database is time-consuming. In order to improve performance, I decided to use the blocking queue to execute asynchronously. That is to say, the generated order number is first returned to the front end, and the operation of updating the database is placed in the blocking queue. Start another thread to listen to the blocked queue all the time and execute it asynchronously.

```java
private class HVOrderHandler implements Runnable{
        @Override
        public void run() {
            while(true){
                try {
                    VoucherOrder voucherOrder = orderTasks.take();
                    createHotVoucherOrderAsync(voucherOrder);
                } catch (Exception e) {
                    log.error("asynchronously Handle order exception: "+e);
                }
            }
        }

        private void createHotVoucherOrderAsync(VoucherOrder voucherOrder) {
            //get userId from session
            Long userId = voucherOrder.getUserId();
            Long voucherId = voucherOrder.getVoucherId();
            RLock redisLock = redissonClient.getLock("lock:order:" + userId);
            if(!redisLock.tryLock()) {
                log.error("Repeated order");
                return;
            }
            try {
                if (query().eq("voucher_id", voucherId).eq("user_id", userId).count() > 0){
                    log.error("Can only buy once");
                    return;
                }
                // update stock
                boolean success = seckillVoucherService.update().setSql("stock = stock-1").eq("voucher_id", voucherId).gt("stock", 0).update();
                if (!success){
                    log.error("sold out");
                    return;
                }
                save(voucherOrder);
            }finally {
                redisLock.unlock();
            }
        }
    }
```

```java
@Override
public Result buyHotVoucherPlus(Long voucherId){
    Long userId = UserHolder.getUser().getId();
    Long result = stringRedisTemplate.execute(HV_SCRIPT, Collections.emptyList(), voucherId.toString(), userId.toString());
    assert result != null;
    int r = result.intValue();
    if(r!=0) return Result.fail(r==1?"empty!":"repeat order");
    // add new order
    VoucherOrder voucherOrder = new VoucherOrder();
    Long orderId = redisIdGenerator.nextId(SECKILL_STOCK_KEY);
    voucherOrder.setVoucherId(voucherId);
    voucherOrder.setId(orderId);
    voucherOrder.setUserId(userId);
    //save blocking queue
    orderTasks.add(voucherOrder);
    return Result.ok(orderId);
}
```

But blocking queues have memory limitations and data security issues. I'd better use stream in Redis as a MQ. :)

## Module4 Implement user blog posting and likes

### Version 1.0

One thing need to be noticed is that one blog can only be liked by one user once.

```java
private void fillBlogLiked(Blog blog) {
    UserDTO user = UserHolder.getUser();
    if(user==null) return;// not login
    Long userId = user.getId();
    String watch1 = BLOG_LIKED_KEY + blog.getId();
    String watch2 = userId.toString();
    Double score = stringRedisTemplate.opsForZSet().score(watch1, watch2);
    blog.setIsLike(score!=null);
}
```

```java
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
```

```java
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
```

## Module5 Friends follow

we need to implement, users can be able to view other users' watch lists and common followers.

```java
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
```

## Module6 Scrolling pagination query

we want to implement Automatically update tweets when users scroll down.

```java
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
```

## Module7 Find nearby shops

Implemented by geo data struct in Redis

```java
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
```

## Modele8 Implement the check-in function

Implemented by BitMap data struct in Redis, Using the feature of up to 31 days per month adopts binary mapping, 0 means not checked in, 1 means checked in, which can effectively save space.

```java
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
```

## Module9 Unique vistor statistics

Using Hyperloglog, Cardinality can be determined for very large sets with only 0.0081 error.