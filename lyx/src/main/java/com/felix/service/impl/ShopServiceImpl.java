package com.felix.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.felix.model.dto.Result;
import com.felix.model.entity.Shop;
import com.felix.mapper.ShopMapper;
import com.felix.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.felix.utils.CacheClient;
import com.felix.model.constants.RedisConstants;
import com.felix.utils.RedisData;
import com.felix.model.constants.SystemConstants;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.felix.model.constants.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    /**
     * 根据id查询商铺信息
     * @param id 商铺id
     * @return 商铺详情数据
     */
    @Override
    public Result queryShopById(Long id) {

        //Shop shop = queryWithMutexByString(id);
        //Shop shop = queryWithLogicalExpire(id);
        //使用封装工具类
        //Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY,id,Shop.class, this::getById,CACHE_SHOP_TTL,TimeUnit.MINUTES);

        //Shop shop = cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY,id,Shop.class,LOCK_SHOP_KEY,this::getById,CACHE_SHOP_TTL,TimeUnit.MINUTES);

        Shop shop = cacheClient.queryWithMutex(CACHE_SHOP_KEY,id,Shop.class,LOCK_SHOP_KEY,this::getById,CACHE_SHOP_TTL,TimeUnit.MINUTES);

        if (shop == null){
            return Result.fail("店铺信息不存在！");
        }
        return Result.ok(shop);
    }

    /**
     * 根据id查询商户信息1 （已封装到工具类中）
     * 1、在Redis中采用String类型存储商户信息
     * 2、采取在Redis中缓存空对象解决缓存穿透问题
     * 3、采取互斥锁解决缓存击穿问题
     * @param id 热点店铺id
     * @return
     */
    private Shop queryWithMutexByString(Long id){

        //1、从Redis中查询商铺缓存
        String shopKey = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(shopKey);

        //2、redis中用户存在：直接返回数据，空串也算空
        if (StrUtil.isNotBlank(shopJson)){
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        //如果得到的数据是为解决缓存穿透的空缓存，返回错误信息(null)
        if ("".equals(shopJson)){
            return null;
        }

        //3、不存在：从数据库中查找
        //3.1 获取对应的互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        Shop shop = null;
        try {
            boolean flag = tryLock(lockKey);

            //3.2 失败：休眠然后重新查找（从缓存开始重新查找）
            if (!flag){
                //模拟数据库查询延迟
                Thread.sleep(50);
                return queryWithMutexByString(id);

            }

            //3.3 成功：再次查询缓存（DoubleCheck），以免这次获取锁的同时其他进程正好返回数据到缓存
            shopJson = stringRedisTemplate.opsForValue().get(shopKey);

            //3.4 缓存存在：直接返回缓存数据
            if (StrUtil.isNotBlank(shopJson)) {
                shop = JSONUtil.toBean(shopJson,Shop.class);
                return shop;
            }
            //如果得到的数据是为解决缓存穿透的空缓存，返回错误信息(null)
            if ("".equals(shopJson)){
                return null;
            }

            //4 缓存不存在：从数据库中查找数据
            shop = getById(id);
            //4.1 数据不存在：返回错误（null）
            if (shop == null){
                //4.2 为解决缓存穿透，将空缓存存入redis，并设置超时时间以减少出现避免数据不一致
                stringRedisTemplate.opsForValue().set(shopKey,"", CACHE_NULL_TTL,TimeUnit.MINUTES);
                return null;
            }

            //4.3 数据存在：将查询到的数据存入redis，并设置超时时间
            //为避免缓存雪崩，给不同key的TTL添加随机值
            stringRedisTemplate.opsForValue().set(shopKey,JSONUtil.toJsonStr(shop),
                    CACHE_SHOP_TTL + RandomUtil.randomLong(10), TimeUnit.MINUTES);

        } catch (Exception e) {
            throw new RuntimeException(e);
        }finally {
            //4.4 归还互斥锁
            unLock(lockKey);
        }
        return shop;
    }


    //自定义线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    /**
     * 根据id查询商户信息2  （已封装到工具类中）
     * 1、在Redis中采用"String"类型存储商户信息
     * 2、不存在"缓存穿透"问题，默认热点信息已经提前缓存到Redis，若缓存查不到热点信息直接返回null
     * 3、采取"逻辑过期+互斥锁"解决缓存击穿问题
     * @param id 热点店铺id
     * @return 店铺信息
     */
    private Shop queryWithLogicalExpire(Long id){
        //1、从缓存中获取热点店铺信息
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);

        //2、缓存信息不存在：直接返回null
        if (StrUtil.isBlank(shopJson)){
            return null;
        }

        //3、缓存信息存在
        //3.1 判断逻辑时间是否过期
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();


        //3.1.1 逻辑时间未过期，直接返回
        if (expireTime.isAfter(LocalDateTime.now())){
            return shop;
        }

        //3.1.2 逻辑时间过期：尝试获取互斥锁
        Boolean flag = tryLock(LOCK_SHOP_KEY + id);

        //3.1.3 互斥锁获取成功：再次读取缓存判断逻辑时间是否过期（Double-Check）
        if (flag){
            shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
            if (StrUtil.isNotBlank(shopJson)){
                redisData = JSONUtil.toBean(shopJson,RedisData.class);
                expireTime = redisData.getExpireTime();
                shop = JSONUtil.toBean((JSONObject) redisData.getData(),Shop.class);
                if (expireTime.isAfter(LocalDateTime.now())) return shop;

                //3.2.1 逻辑时间过期：开启新线程从数据库中读取数据，并更新到Redis
                CACHE_REBUILD_EXECUTOR.submit(() -> {
                    this.saveShop2Redis(id,LOCK_SHOP_TTL);
                });
            }
            //3.2.2 归还互斥锁
            this.unLock(LOCK_SHOP_KEY + id);
        }

        //3.2.3 逻辑时间未过期/互斥锁获取失败/Double-Check的逻辑时间未过期：返回当前Redis缓存数据（即使是旧数据也返回）
        return shop;

    }

    /**
     * 从数据库中将热点数据（热点商铺）保存到Redis中预热
     * @param id 商铺id
     * @param expireSeconds 逻辑删除时间
     */
    public void saveShop2Redis(Long id,Long expireSeconds){
        //1、从数据库中获取热点商铺
        //模拟数据库查询延迟
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        Shop shop = getById(id);

        //2、将商铺信息封装为RedisData对象（加入逻辑删除时间字段）
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));

        //3、将封装后的对象存入Redis中
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id,JSONUtil.toJsonStr(redisData));

    }

    /**
     * 获取互斥锁（解决缓存击穿）    （已封装到工具类中）
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
     * 归还互斥锁    （已封装到工具类中）
     * @param key 锁名
     */
    private void unLock(String key){
        stringRedisTemplate.delete(key);
    }

    /**
     * 根据id查询商户信息3
     * 1、在Redis中采用Hash类型存储商户信息
     * 2、采取在Redis中缓存空对象解决缓存穿透问题
     * @param id 热点店铺id
     * @return
     */
    private Shop queryByHash(Long id){

        //使用Hash类型存储店铺数据
        //1、从Redis中查询商铺缓存
        String shopKey = RedisConstants.CACHE_SHOP_KEY + id;
        Map<Object, Object> map = stringRedisTemplate.opsForHash().entries(shopKey);

        //优化：为避免缓存穿透，判断返回的map是否为null（默认为空集合，自定义"isNull"字段进行判断）
        if ("true".equals(map.get("isNull"))){
            return null;
        }

        //2、redis中用户存在：直接返回数据
        if (!map.isEmpty()){
            Shop shop = BeanUtil.fillBeanWithMap(map,new Shop(),true);
            return shop;
        }

        //3、不存在：从数据库中查找
        Shop shop = getById(id);

        //4、数据库中用户不存在：返回错误
        if (shop == null){
            //优化：为避免缓存穿透，手动将null数据存入redis（默认为空map）
            stringRedisTemplate.opsForHash().put(shopKey,"isNull","true");
            stringRedisTemplate.expire(shopKey,RedisConstants.CACHE_NULL_TTL,TimeUnit.MINUTES);
            return null;
        }

        //5、存在：将查询到的数据存入redis
        Map<String, Object> shopMap = BeanUtil.beanToMap(shop, new HashMap<>(), CopyOptions.create()
                .setIgnoreNullValue(true)
                .setFieldValueEditor((fieldKey, fieldvalue) -> {
                    if (fieldvalue != null) return fieldvalue.toString();
                    else return null;
                }));
        stringRedisTemplate.opsForHash().putAll(shopKey,shopMap);

        //6、设置超时时间（超时剔除）
        //为避免缓存雪崩，给不同key的TTL添加随机值
        stringRedisTemplate.expire(shopKey,RedisConstants.CACHE_SHOP_TTL + RandomUtil.randomLong(10),TimeUnit.MINUTES);

        //6、返回数据
        return shop;
    }

    /**
     * 新增商铺信息
     * @param shop 商铺数据
     * @return 商铺id
     */
    @Override
    @Transactional
    public Result updateShop(Shop shop) {
        Long id = shop.getId();
        if (id == null){
            return Result.fail("店铺id不能为空");
        }
        //1、修改数据库
        updateById(shop);

        //2、删除Redis中数据
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        return Result.ok(id);
    }

    /**
     * 返回指定类型店铺信息，并按最近位置排序
     * @param typeId 店铺类型
     * @param current 当前页数
     * @param x 经度
     * @param y 纬度
     * @return 店铺信息；List<Shop>
     */
    @Override
    public Result queryShopByGeo(Integer typeId, Integer current, Double x, Double y) {
        //1. 判断是否需要根据坐标查询
        if (x == null || y == null){
            //分页查询
            Page<Shop> shops = query().eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            return Result.ok(shops.getRecords());
        }

        //2.计算分页参数
        int from = (current-1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;

        //3.查询redis、按照距离排序、分页。结果：shopId、distance
        String key = SHOP_GEO_KEY + typeId;
        //对应redis指令：GEOSEARCH key BYLONLAT x y BYRADIUS 10 WITHDISTANCE
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo()
                .search(
                        key,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(5000),
                        //注意这里分页限制查询的范围是0～end
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
                );

        //4.筛选集合信息
        //没有店铺信息
        if (results == null){
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        //没有新内容页了
        if (list.size() <= from){
            return Result.ok(Collections.emptyList());
        }

        //5.提取店铺id和距离
        //保存店铺id
        List<Long> shopIds = new ArrayList<>(end - from);
        //保存店铺id与距离的映射关系
        Map<Long,Double> map = new HashMap<>(end - from);
        list.stream().skip(from).forEach(result -> {
            //获取店铺id
            Long shopId = Long.valueOf(result.getContent().getName());
            shopIds.add(shopId);

            //获取距离信息
            double distance = result.getDistance().getValue();
            map.put(shopId,distance);
        });

        //6.根据id查询店铺信息
        String idsStr = StrUtil.join(",", shopIds);
        List<Shop> id = query().in("id", shopIds).last("order by field(id," + idsStr + ")").list();

        //7.将店铺距离数据保存到店铺信息中
        List<Shop> shops = id.stream().map(shop -> {
            Double distance = map.get(shop.getId());
            return shop.setDistance(distance);
        }).collect(Collectors.toList());
        return Result.ok(shops);
    }
}
