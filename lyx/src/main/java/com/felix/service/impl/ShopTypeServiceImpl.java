package com.felix.service.impl;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.json.JSONUtil;
import com.felix.dto.Result;
import com.felix.entity.ShopType;
import com.felix.mapper.ShopTypeMapper;
import com.felix.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.felix.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 返回店铺类型列表及数据
     * @return
     */
    @Override
    public Result queryTypeList() {
        //1、从redis查找是否有店铺类型数据（redis的list中集合存储的是ShopType类的Json）
        String shopTypeKey = RedisConstants.CACHE_SHOP_TYPE;
        //取出redis中list集合的全部数据
        List<String> list = stringRedisTemplate.opsForList().range(shopTypeKey, 0, -1);

        //2、存在：取出数据后进行排序然后返回
        if (!list.isEmpty()){
            //将集合中的Json数据转换为Bean对象，并进行排序
            List<ShopType> typeList = list.stream()
                    .map(str -> JSONUtil.toBean(str, ShopType.class))
                    .sorted(Comparator.comparing(ShopType::getSort))
                    .collect(Collectors.toList());
            return Result.ok(typeList);
        }
        //3、不存在：从数据库中查找数据
        List<ShopType> sortList = query().orderByAsc("sort").list();


        //4、将数据存入Redis
        //将list集合中的Bean类型转为Json字符串
        List<String> jsonList = sortList.stream().map(JSONUtil::toJsonStr).collect(Collectors.toList());
        stringRedisTemplate.opsForList().rightPushAll(shopTypeKey,jsonList);
        //为避免缓存雪崩，给不同key的TTL添加随机值
        stringRedisTemplate.expire(shopTypeKey,RedisConstants.CACHE_SHOP_TTL + RandomUtil.randomLong(10) , TimeUnit.MINUTES);

        //5、返回数据
        return Result.ok(sortList);
    }
}
