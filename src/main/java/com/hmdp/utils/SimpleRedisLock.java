package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.json.JSONUtil;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock {
    private String name;
    private StringRedisTemplate stringRedisTemplate;

    private static final String KEY_PREFIX = "lock:";

    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        Boolean flag = stringRedisTemplate
                .opsForValue()
                .setIfAbsent(KEY_PREFIX + name, ID_PREFIX  + threadId, timeoutSec, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    @Override
    public void unLock() {
        //获取锁中的线程标识
        String threadId1 = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
        //获取当前线程标识
        String threadId2 = ID_PREFIX + Thread.currentThread().getId();
        if(threadId1.equals(threadId2)) {
            stringRedisTemplate.delete(KEY_PREFIX + name);
        }
    }
}
