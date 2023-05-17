package com.felix.service;

import com.felix.dto.Result;
import com.felix.entity.VoucherOrder;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 */
public interface IVoucherOrderService extends IService<VoucherOrder> {

    /**
     * 秒杀券抢购1
     * 使用redisson实现分布式锁进行秒杀券抢购
     * @param voucherId
     * @return
     */
    Result seckillVoucher1(Long voucherId);

    /**
     * 秒杀券抢购2
     * 先使用redis处理库存和一人一单条件，再用消息队列异步处理订单信息
     * @param voucherId 秒杀券ID
     * @return
     */
    public Result seckillVoucher(Long voucherId);

    /**
     * 保存订单信息1（使用分布式锁实现秒杀券抢单）
     * @param voucherId
     * @param userId
     * @return
     */
    Result createVoucherOrder(Long voucherId,Long userId);

    /**
     * 保存订单信息2（使用消息队列异步保存订单信息）
     * @param voucherOrder
     */
    void handleVoucherOrder1(VoucherOrder voucherOrder);

    /**
     * 保存订单信息3（使用Redis的Stream结构实现消息队列异步保存订单信息）
     * @param voucherOrder
     */
    void createVoucherOrder(VoucherOrder voucherOrder);
}
