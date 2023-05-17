package com.felix.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.felix.utils.RedisConstants.*;

/**
 * 自定义封装Redis工具类
 */
@Slf4j
@Component
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    //自定义线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /** 方法1：将任意Java对象序列化为json并存储在string类型的key中，并且可以设置TTL过期时间
     *
     * @param key Redis key
     * @param objValue Redis value
     * @param time 超时时间
     * @param timeUnit 时间类型
     */
    public void set(String key, Object objValue, Long time, TimeUnit timeUnit){
        //将Object对象序列化为Json字符串
        String json = JSONUtil.toJsonStr(objValue);

        //写入redis
        //为避免缓存雪崩，给不同key的TTL添加随机值
        stringRedisTemplate.opsForValue().set(key,json,time + RandomUtil.randomLong(10),timeUnit);
    }


    /** 方法2：将任意Java对象序列化为json并存储在string类型的key中，并且可以设置逻辑过期时间，用于处理缓存击穿问题
     *
     * @param key Redis key
     * @param objValue Redis value
     * @param time 超时时间
     * @param timeUnit 时间类型
     */
    public void setWithLogicalExpire(String key,Object objValue,Long time,TimeUnit timeUnit){
        //设置逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(objValue);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(time));

        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(redisData));
    }

    /** 方法3：根据指定的key查询缓存，并反序列化为指定类型，利用缓存空值的方式解决缓存穿透问题
     *
     * @param keyPrefix Redis key的前缀
     * @param id Redis key的具体id
     * @param type 指定返回类的类型
     * @param dbFallback 自定义sql语句
     * @param time Redis数据超时时间
     * @param timeUnit 超时时间类型
     * @param <R> 返回类
     * @param <ID> 查找类型
     * @return 返回所查找的类
     */
    public  <R,ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type,
                                          Function<ID,R> dbFallback,Long time,TimeUnit timeUnit){

        //1、从Redis中查询缓存
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);

        //2、redis中用户存在：直接返回数据，空串也算空
        if (StrUtil.isNotBlank(json)){
            R r = JSONUtil.toBean(json, type);
            return r;
        }
        //如果得到的数据是为解决缓存穿透的空缓存，返回错误信息(null)
        if ("".equals(json)){
            return null;
        }

        //3、不存在：从数据库中查找
        R r = dbFallback.apply(id);
        //4、数据不存在：返回错误（null）
        if (r == null){
            //为解决缓存穿透，将空缓存存入redis，并设置超时时间以减少出现数据不一致的情况
            stringRedisTemplate.opsForValue().set(key,"", time,timeUnit);
            return null;
        }

        //5、数据存在：将查询到的数据存入redis，并设置超时时间
        //为避免缓存雪崩，给不同key的TTL添加随机值
        this.set(key,r,time,timeUnit);

        return r;
    }

    /** 方法4：根据指定的key查询缓存，并反序列化为指定类型，利用逻辑过期解决缓存击穿问题
     * 特点：
     *  1、在Redis中采用"String"类型存储商户信息
     *  2、不存在"缓存穿透"问题，默认热点信息已经提前缓存到Redis，若缓存查不到热点信息直接返回null
     *  3、采取"逻辑过期+互斥锁"解决缓存击穿问题
     *
     * @param keyPrefix Redis key的前缀
     * @param id Redis key的具体id
     * @param type 指定返回类的类型
     * @param lockPrefix 互斥锁在Redis中key的前缀
     * @param dbFallback 自定义sql语句
     * @param time Redis数据超时时间
     * @param timeUnit 超时时间类型
     * @param <R> 返回类
     * @param <ID> 查找类型
     * @return 返回所查找的类
     */
    public <R,ID> R queryWithLogicalExpire(String keyPrefix,ID id,Class<R> type,String lockPrefix,
                                           Function<ID,R> dbFallback,Long time,TimeUnit timeUnit){
        String key = keyPrefix + id;
        //1、从缓存中获取热点店铺信息
        String json = stringRedisTemplate.opsForValue().get(key);

        //2、缓存信息不存在：直接返回null
        if (StrUtil.isBlank(json)){
            return null;
        }

        //3、缓存信息存在
        //3.1 判断逻辑时间是否过期
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();


        //3.1.1 逻辑时间未过期，直接返回
        if (expireTime.isAfter(LocalDateTime.now())){
            return r;
        }

        //3.1.2 逻辑时间过期：尝试获取互斥锁
        String lockKey = lockPrefix + id;
        Boolean flag = tryLock(lockKey);

        //3.1.3 互斥锁获取成功：再次读取缓存判断逻辑时间是否过期（Double-Check）
        if (flag){
            json = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(json)){
                redisData = JSONUtil.toBean(json,RedisData.class);
                expireTime = redisData.getExpireTime();
                r = JSONUtil.toBean((JSONObject) redisData.getData(),type);
                //Double-Check逻辑时间未过期
                if (expireTime.isAfter(LocalDateTime.now())) return r;

                //3.2.1 逻辑时间过期：开启新线程从数据库中读取数据，并更新到Redis
                CACHE_REBUILD_EXECUTOR.submit(() -> {
                    //查询数据库
                    R newR = dbFallback.apply(id);
                    //重建缓存
                    this.setWithLogicalExpire(key,newR,time,timeUnit);
                });
            }
            //3.2.2 归还互斥锁
            this.unLock(lockKey);
        }

        //3.2.3 逻辑时间未过期/互斥锁获取失败/Double-Check的逻辑时间未过期：返回当前Redis缓存数据（即使是旧数据也返回）
        return r;

    }

    /** 方法5：根据指定的key查询缓存，并反序列化为指定类型，利用"互斥锁"解决缓存击穿问题
     *
     * @param keyPrefix Redis key的前缀
     * @param id Redis key的具体id
     * @param type 指定返回类的类型
     * @param lockPrefix 互斥锁在Redis中key的前缀
     * @param dbFallback 自定义sql语句
     * @param time Redis数据超时时间
     * @param timeUnit 超时时间类型
     * @param <R> 返回类
     * @param <ID> 查找类型
     * @return 返回所查找的类
     */
    public <R,ID> R queryWithMutex(String keyPrefix, ID id, Class<R> type, String lockPrefix,
                                   Function<ID,R> dbFallback,Long time,TimeUnit timeUnit){

        //1、从Redis中查询缓存
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);

        //2、redis中用户存在：直接返回数据，空串也算空
        if (StrUtil.isNotBlank(json)){
            R r = JSONUtil.toBean(json, type);
            return r;
        }
        //如果得到的数据是为解决缓存穿透的空缓存，返回错误信息(null)
        if ("".equals(json)){
            return null;
        }

        //3、不存在：从数据库中查找
        //3.1 获取对应的互斥锁
        String lockKey = lockPrefix + id;
        R r = null;
        try {
            boolean flag = tryLock(lockKey);

            //3.2 失败：休眠然后重新查找（从缓存开始重新查找）
            if (!flag){
                return queryWithMutex(keyPrefix,id,type,lockPrefix,dbFallback,time,timeUnit);
            }

            //3.3 成功：再次查询缓存（DoubleCheck），以免这次获取锁的同时其他进程正好返回数据到缓存
            json = stringRedisTemplate.opsForValue().get(key);

            //3.4 缓存存在：直接返回缓存数据
            if (StrUtil.isNotBlank(json)) {
                r = JSONUtil.toBean(json,type);
                return r;
            }
            //如果得到的数据是为解决缓存穿透的空缓存，返回错误信息(null)
            if ("".equals(json)){
                return null;
            }

            //4 缓存不存在：从数据库中查找数据
            //模拟数据库查询延迟
            Thread.sleep(200);
            r = dbFallback.apply(id);
            //4.1 数据不存在：返回错误（null）
            if (r == null){
                //4.2 为解决缓存穿透，将空缓存存入redis，并设置超时时间以减少出现避免数据不一致
                stringRedisTemplate.opsForValue().set(key,"", CACHE_NULL_TTL,TimeUnit.MINUTES);
                return null;
            }

            //4.3 数据存在：将查询到的数据存入redis，并设置超时时间
            //为避免缓存雪崩，给不同key的TTL添加随机值
            this.set(key,r,time,timeUnit);

        } catch (Exception e) {
            throw new RuntimeException(e);
        }finally {
            //4.4 归还互斥锁
            unLock(lockKey);
        }
        return r;
    }

    /**
     * 获取互斥锁（解决缓存击穿）
     * @param key 锁名
     * @return 是否成功获取锁
     */
    private boolean tryLock(String key){
        //锁的值随意，这里锁的有效时间设为10秒
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        //flag类型为Boolean，如果采用自动拆箱可能会返回null
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 归还互斥锁
     * @param key 锁名
     */
    private void unLock(String key){
        stringRedisTemplate.delete(key);
    }


}



