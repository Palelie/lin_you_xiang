package com.felix.utils;

import cn.hutool.core.collection.ListUtil;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock {

    //锁的名称
    private String name;
    private StringRedisTemplate stringRedisTemplate;
    //锁的名称前缀
    private static final String KEY_PREFIX = "lock:";
    //锁的值的前缀（用UUID区分不同的JVM，防止误删操作）
    private static final String ID_PREFIX = UUID.randomUUID().toString() + "-";

    //lua脚本
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;

    //在类加载时就导入脚本，降低性能损耗
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }



    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 通过redis获取分布式锁
     * @param timeoutSec 锁持有的超时时间，到期自动释放锁
     * @return
     */
    @Override
    public boolean tryLock(long timeoutSec) {

        //获取线程标识(存入Redis的value中，UUID用于区分不同JVM，线程id用于区分不同线程)
        String  threadId = ID_PREFIX + Thread.currentThread().getId();

        //获取锁
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + name, threadId, timeoutSec, TimeUnit.SECONDS);

        //防止拆箱后变成null的情况
        return Boolean.TRUE.equals(success);
    }

    /**
     * 删除锁：使用lua脚本来保证删除锁操作的原子性，避免误删情况出现
     */
    @Override
    public void unlock() {
        stringRedisTemplate.execute(UNLOCK_SCRIPT,
                ListUtil.toList(KEY_PREFIX + name),
                ID_PREFIX + Thread.currentThread().getId());
    }

    /**
     * 删除锁
     */
    /*
    @Override
    public void unlock() {
        //判断锁里面的线程标识是否是当前线程（解决锁超时导致的误删问题，但依然无法完全解决锁超时导致的并发问题）
        String threadId = ID_PREFIX + Thread.currentThread().getId();

        String lockId = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);

        //判断标识是否一致
        if (threadId.equals(lockId)){
            //释放锁
            stringRedisTemplate.delete(KEY_PREFIX + name);
        }
    }

     */
}
