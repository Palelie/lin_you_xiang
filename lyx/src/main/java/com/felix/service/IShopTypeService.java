package com.felix.service;

import com.felix.dto.Result;
import com.felix.entity.ShopType;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 */
public interface IShopTypeService extends IService<ShopType> {

    /**
     * 返回店铺类型列表及数据
     * @return
     */
    Result queryTypeList();
}
