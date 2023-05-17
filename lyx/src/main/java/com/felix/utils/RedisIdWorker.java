package com.felix.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 使用Redis生成全局唯一Id
 *
 * long类型数据8字节64bit（1bit为字符位，31bit为时间戳，32bit为序列号）
 */
@Component
public class RedisIdWorker {

    //开始时间戳
    private static final long BEGIN_TIMESTAMP = 1640995200L;

    //序列号位数
    private static final int COUNT_BITS = 32;

    private StringRedisTemplate stringRedisTemplate;

    public RedisIdWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 获取全局唯一Id
     * @param keyPrefix Redis中的业务前缀
     * @return 全局唯一Id
     */
    public long nextId(String keyPrefix){
        //1、生成时间戳
        LocalDateTime nowTime = LocalDateTime.now();
        long nowSecond = nowTime.toEpochSecond(ZoneOffset.UTC);
        long timeStamp = nowSecond - BEGIN_TIMESTAMP;

        //2、生成序列号
        //2.1 生成时间，精确到天，用于Redis的key后缀，分天存储避免数据过大，且方便统计数据量
        String date = nowTime.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        Long count = stringRedisTemplate.opsForValue().increment("inc:" + keyPrefix + ":" + date);

        //3、拼接并返回
        return timeStamp << 32 | count;
    }

    /**
     * 生成某时间点的秒数
     */
    public static void main(String[] args) {
        //设定初始日期为2022年1月1日0时0分0秒
        LocalDateTime time = LocalDateTime.of(2022,1,1,0,0,0);
        //转化为秒数
        long second = time.toEpochSecond(ZoneOffset.UTC);
        System.out.println(second);
    }

}
