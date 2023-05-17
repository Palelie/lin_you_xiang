package com.felix.config;

import org.springframework.amqp.core.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;

import static com.felix.utils.RabbitmqConstants.*;

@Configuration
public class RabbitmqConfig {

    /**
     * 声明交换机A
     * @return
     */
    @Bean
    public DirectExchange directExchangeA(){
        return new DirectExchange(EXCHANGE);
    }

    /**
     * 声明队列A（绑定死信交换机和RoutingKey）
     * @return
     */
    @Bean
    public Queue queueA(){
        final HashMap<String, Object> arguments
                = new HashMap<>();
        //设置死信交换机
        arguments.put("x-dead-letter-exchange",DEAD_EXCHANGE);
        //设置死信RoutingKey
        arguments.put("x-dead-letter-routing-key","dkey");
        //设置TTL设置10秒过期
        arguments.put("x-message-ttl",10000);
        //生成并返回队列
        return QueueBuilder.durable(QUEUE)
                .withArguments(arguments)
                .build();
    }

    /**
     * 声明死信交换机
     * @return
     */
    @Bean
    public DirectExchange deadExchange(){
        return new DirectExchange(DEAD_EXCHANGE);
    }

    /**
     * 声明死信队列
     * @return
     */
    @Bean
    public Queue deadQueue(){
        return QueueBuilder.durable(DEAD_QUEUE).build();
    }

    /**
     * 队列A绑定交换机A
     * @param queueA 上面注册的队列A（名字同方法名）
     * @param directExchangeA 上面注册的交换机A（名字同方法名）
     * @return
     */
    @Bean
    public Binding queueABindingExchangeA(@Qualifier("queueA")Queue queueA,
                                          @Qualifier("directExchangeA") DirectExchange directExchangeA){
        return BindingBuilder.bind(queueA).to(directExchangeA).with("msg");
    }

    /**
     * 死信队列绑定死信交换机
     * @param deadQueue 上面注册的死信队列（名字同方法名）
     * @param deadExchange 上面注册的死信交换机（名字同方法名）
     * @return
     */
    @Bean
    public Binding deadQueueABindingDeadExchange(@Qualifier("deadQueue")Queue deadQueue,
                                          @Qualifier("deadExchange") DirectExchange deadExchange){
        return BindingBuilder.bind(deadQueue).to(deadExchange).with("dkey");
    }

}
