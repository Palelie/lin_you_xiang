package com.felix.service;

import com.felix.model.dto.Result;
import com.felix.model.entity.Shop;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 */
public interface IShopService extends IService<Shop> {

    /**
     * 根据id查询商铺信息
     * @param id 商铺id
     * @return 商铺详情数据
     */
    Result queryShopById(Long id);

    /**
     * 新增商铺信息
     * @param shop 商铺数据
     * @return 商铺id
     */
    Result updateShop(Shop shop);

    /**
     * 返回指定类型店铺信息，并按最近位置排序
     * @param typeId 店铺类型
     * @param current 当前页数
     * @param x 经度
     * @param y 纬度
     * @return 店铺信息；List<Shop>
     */
    Result queryShopByGeo(Integer typeId, Integer current, Double x, Double y);
}
