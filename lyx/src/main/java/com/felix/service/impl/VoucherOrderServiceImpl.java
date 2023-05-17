package com.felix.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.json.JSONUtil;
import com.felix.dto.Result;
import com.felix.entity.SeckillVoucher;
import com.felix.entity.VoucherOrder;
import com.felix.listener.MyCallBack;
import com.felix.mapper.VoucherOrderMapper;
import com.felix.service.ISeckillVoucherService;
import com.felix.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.felix.utils.RedisIdWorker;
import com.felix.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import static com.felix.utils.RabbitmqConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private RabbitTemplate rabbitTemplate;

    //lua脚本
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    //在类加载时就导入脚本，降低性能损耗
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    /**
     * 秒杀券抢购4
     * 先使用redis判断库存和一人一单条件，再用rabbitmq异步处理订单信息
     * @param voucherId 秒杀券ID
     * @return
     */

    /**
     * 注入回调函数
     */
    @Resource
    private MyCallBack myCallBack;
    //依赖注入 rabbitTemplate 之后再设置它的回调对象

    @PostConstruct
    public void init(){
        rabbitTemplate.setConfirmCallback(myCallBack);
    }

    @Override
    /**
     * 判断一人一单后将订单信息发到rabbitmq
     */
    public Result seckillVoucher(Long voucherId){
        Long userId = UserHolder.getUser().getId();
        Long orderId = redisIdWorker.nextId("order");

        //1、执行lua脚本（往消息队列中传数据的任务也在这个lua脚本中完成了）
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), orderId.toString());
        int r = result.intValue();
        //2、判断返回值是否为0
        //2.1 不为0:没有购买资格
        if (r != 0){
            //如果为1：库存不足；如果为2:不可重复下单
            return Result.fail(result == 1 ? "库存不足" : "不可重复下单");
        }

        //3、将订单信息发送到RabbitMQ
        //3.1 封装VoucherOrder订单对象
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        //3.2 指定消息发送 id 为 1
        CorrelationData correlationData=new CorrelationData(orderId.toString());
        //3.3 将对象转化为Json存入Rabbitmq
        rabbitTemplate.convertAndSend(EXCHANGE,"msg", JSONUtil.toJsonStr(voucherOrder),correlationData);

        //生产者发布确认


        //4、返回订单id
        return Result.ok(orderId);
    }

//    //异步处理线程池
//    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
//
//    //在类初始化之后执行，因为当这个类初始化好了之后，随时都是有可能要执行的
//    @PostConstruct      //该注解使该方法在对象初始化完成后遍开始执行
//    private void init() {
//        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
//    }

    //由于是spring的事务是放在threadLocal中，此时的是多线程，事务会失效,需要在主线程中先获取
    private IVoucherOrderService proxy;

    /**
     * 秒杀券抢购3
     * 先使用redis处理库存和一人一单条件，再用Redis的Stream结构实现消息队列异步处理订单信息
     * @param voucherId 秒杀券ID
     * @return
     */
    public Result seckillVoucher3(Long voucherId){
        Long userId = UserHolder.getUser().getId();
        Long orderId = redisIdWorker.nextId("order");

        //1、执行lua脚本（往消息队列中传数据的任务也在这个lua脚本中完成了）
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), orderId.toString());
        int r = result.intValue();
        //2、判断返回值是否为0
        //2.1 不为0:没有购买资格
        if (r != 0){
            //如果为1：库存不足；如果为2:不可重复下单
            return Result.fail(result == 1 ? "库存不足" : "不可重复下单");
        }
        //在主线程创建代理对象（以便容器管理的类可以使用事务注解）
        proxy = (IVoucherOrderService) AopContext.currentProxy();

        //3、返回订单id
        return Result.ok(orderId);
    }

    /**
     * 对象初始化结束后便开始这个任务从消息队列中取数据
     */
    private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            while (true) {

                try {
                    // 1.获取消息队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS s1 >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create("stream.orders", ReadOffset.lastConsumed())
                    );
                    // 2.判断订单信息是否为空
                    if (list == null || list.isEmpty()) {
                        // 如果为null，说明没有消息，继续下一次循环
                        continue;
                    }
                    // 解析数据
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    // 3.操作数据库：扣除库存、创建订单（交给容器管理以使用事务注解）
                    proxy.createVoucherOrder(voucherOrder);
                    // 4.确认消息 XACK
                    stringRedisTemplate.opsForStream().acknowledge("s1", "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                    //处理异常消息
                    handlePendingList();
                }
        }
    }

        /**
         * 当消费者取出消息后出现异常时，需重新从pending-list中处理已取出队列但未进行确认的消息
         */
        @Transactional(rollbackFor = Exception.class)
        private void handlePendingList() {
            while (true) {
                try {
                    // 1.获取pending-list中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS s1 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create("stream.orders", ReadOffset.from("0"))
                    );
                    // 2.判断订单信息是否为空
                    if (list == null || list.isEmpty()) {
                        // 如果为null，说明没有异常消息，结束循环
                        break;
                    }
                    // 解析数据
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    // 3.创建订单
                    createVoucherOrder(voucherOrder);
                    // 4.确认消息 XACK
                    stringRedisTemplate.opsForStream().acknowledge("s1", "g1", record.getId());
                } catch (Exception e) {
                    //手工回滚异常
                    TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
                    log.error("处理pendding订单异常", e);
                    try {
                        //避免对异常消息疯狂循环处理
                        Thread.sleep(20);

                    } catch (Exception a) {
                        a.printStackTrace();
                    }
                }
            }
        }
    }
    /**
     * 执行数据库操作：
     * 1. 扣减库存
     * 2。 提交订单
     * @param voucherOrder
     */
    @Override
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        long voucherId = voucherOrder.getVoucherId();
        //扣减库存（使用乐观锁：用库存量代替版本号）
        seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .gt("stock",0)
                .update();

        //提交订单
        save(voucherOrder);
    }

        /**
     * 秒杀券抢购2
     * 先使用redis处理库存和一人一单条件，再用JDK消息队列异步处理订单信息
     * @param voucherId 秒杀券ID
     * @return
     */
    public Result seckillVoucher2(Long voucherId){
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");

        //1、执行lua脚本
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT, Collections.emptyList(), voucherId.toString(), userId.toString());
        int r = result.intValue();
        //2、判断返回值是否为0
        //2.1 不为0:没有购买资格
        if (r != 0){
            //如果为1：库存不足；如果为2:不可重复下单
            return Result.fail(result == 1 ? "库存不足" : "不可重复下单");
        }

        //2.2 为0：保存到阻塞队列中
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        proxy = (IVoucherOrderService)AopContext.currentProxy();
        orderTasks.add(voucherOrder);

        //3、返回订单id
        return Result.ok(orderId);
    }



    //JDK实现的阻塞消息队列
    private BlockingQueue<VoucherOrder> orderTasks =new ArrayBlockingQueue<>(1024 * 1024);


    // 用于线程池处理的任务
// 当初始化完毕后，就会去从对列中去拿信息
    private class VoucherOrderHandler1 implements Runnable {
        @Override
        public void run() {
            while (true) {
                try {
                    // 1.获取队列中的订单信息
                    VoucherOrder voucherOrder = orderTasks.take();
                    // 2.创建订单
                    proxy.handleVoucherOrder1(voucherOrder);
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                }
            }
        }
    }
    //由于在redis中已经完成了对库存和一人一单的判断，这里不需要再加锁，直接保存订单信息即可（但事务还是需要）
    @Transactional
    @Override
    public void handleVoucherOrder1(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();
        //保存订单
        save(voucherOrder);
        //扣减库存
        seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id",voucherId)
                .gt("stock",0)
                .update();
    }





        /**
     * 秒杀券抢购1
     * 使用redisson实现分布式锁进行秒杀券抢购
     * @param voucherId 秒杀券ID
     * @return
     */
    @Override
    public Result seckillVoucher1(Long voucherId) {
        //1、查找秒杀券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);

        //2、判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())){
            return Result.fail("秒杀未开始！");
        }

        //3、判断秒杀是否结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())){
            return Result.fail("秒杀已结束！");
        }

        //4、判断秒杀券是否还有库存
        if (voucher.getStock() < 1){
            return Result.fail("库存不足！");
        }

        Long userId = UserHolder.getUser().getId();

        //5、实现一人一单功能并扣减库存（使用乐观锁：用库存量代替版本号）
        /*
        //注意：必须先提交事务再释放锁才能完全避免并发问题
        synchronized (userId.toString().intern()){  //将锁的粒度控制为单个用户，最大限度减少性能损耗
            //获取代理对象。如果直接用this.调用方法，则不会被spring容器代理，事务注解也就无法生效
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId,userId);
        }
         */

        /*使用自定义Redis实现的分布式锁解决分布式并发问题
        //1、创建锁对象
        SimpleRedisLock simpleRedisLock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);

        //2、获取锁对象
        boolean success = simpleRedisLock.tryLock(500);

         */

        //使用Redisson工具实现分布式锁
        //1、创建锁对象
        RLock lock = redissonClient.getLock("order:" + userId);

        //2、获取锁对象
        boolean success = lock.tryLock();

        //3、获取失败，返回错误
        if (!success){
            return Result.fail("不可重复下单！");
        }

        //4、获取成功：实现业务逻辑
        try {
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId,userId);
        } finally {
            //simpleRedisLock.unlock();
            lock.unlock();
        }
    }

    /**
     * 保存订单信息1（使用分布式锁实现秒杀券抢单）
     * @param voucherId
     * @param userId
     * @return
     */
    //提取方法，便于受互斥锁和事务的控制
    @Override
    @Transactional
    public Result createVoucherOrder(Long voucherId,Long userId) {
        //判断是否满足"一人一单"
        Integer count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0){
            return Result.fail("不可重复购买！");
        }

        //扣减库存（使用乐观锁：用库存量代替版本号）
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .gt("stock",0)
                .update();
        if (!success){
            return Result.fail("库存不足！");
        }

        //创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        //订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        //秒杀券id
        voucherOrder.setVoucherId(voucherId);
        //用户id
        voucherOrder.setUserId(userId);

        //提交订单
        save(voucherOrder);

        return Result.ok(orderId);
    }


}
