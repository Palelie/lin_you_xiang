package com.felix.listener;

import cn.hutool.json.JSONUtil;
import com.felix.entity.VoucherOrder;
import com.felix.service.impl.VoucherOrderServiceImpl;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import static com.felix.utils.RabbitmqConstants.*;

/**
 * rabbitmq消费端
 */
@Component
public class VoucherOrderListener {

    @Resource
    private VoucherOrderServiceImpl voucherOrderService;

    /**
     * 消费者：监听队列A获取订单信息并存入数据库
     * @param voucherOrderStr
     */
    @RabbitListener(queues = QUEUE)
    @Transactional
    public void listenQueueA(String voucherOrderStr){
        VoucherOrder voucherOrder = JSONUtil.toBean(voucherOrderStr,VoucherOrder.class);
        voucherOrderService.createVoucherOrder(voucherOrder);
    }

    /**
     * 消费者：监听死信队列获取溢出或超时订单信息并存入数据库
     * @param voucherOrderStr
     */
    @RabbitListener(queues = DEAD_QUEUE)
    @Transactional
    public void listenDeadQueue(String voucherOrderStr){
        VoucherOrder voucherOrder = JSONUtil.toBean(voucherOrderStr,VoucherOrder.class);
        voucherOrderService.createVoucherOrder(voucherOrder);
    }
}
