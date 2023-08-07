package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    //线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    //解决《缓存穿透问题》
    @Override
    public Result queryShopById(Long id) {
        //缓存穿透
        //Shop shop = queryWithPassThrough(id);
        //互斥锁解决缓存击穿
        //Shop shop = queryWithMutex(id);
        //逻辑过期解决缓存击穿问题
        Shop shop = queryWithLogicalExpire(id);
        return Result.ok(shop);
    }

    //逻辑过期解决《缓存击穿问题》
    public Shop queryWithLogicalExpire(Long id) {
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isBlank(shopJson)) {
            //缓存未命中
            return null;
        }
        //缓存命中 判断是否过期
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        Shop shop = (Shop) redisData.getData();
        if(LocalDateTime.now().isBefore(expireTime)) {
            //未过期 返回最新数值
            return shop;
        }
        //过期 争抢互斥锁
        boolean flag = tryLock(lockKey);
        if(flag) {
            //获取锁成功 开启独立线程修改数据库
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    //重建缓存
                    this.saveShop2Redis(id, 20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unLock(lockKey);
                }
            });
        }
        //获取锁失败  返回旧数值
        return shop;
    }

    //互斥锁解决《缓存击穿问题》
    public Shop queryWithMutex(Long id) {
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(shopJson)) {
            //缓存命中且不为空值
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        if (shopJson == "") {
            //缓存命中但为空值
            return null;
        }
        Shop shop = null;
        try {
            //缓存未命中 获取互斥锁
            boolean flag = tryLock(lockKey);
            if(!flag) {
                //获取互斥锁失败
                Thread.sleep(10);
                return queryWithMutex(id);
            }
            //获取互斥锁成功 查询数据库
            shop = getById(id);
            if (shop == null) {
                //数据库为空  redis中赋予空值
                stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
                return null;
            }
            //数据库存在 存入redis
            //因为缓存未命中 所以shopJson为空 将数据库中的shop重新转化
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            unLock(lockKey);
        }
        return shop;
    }

    //解决《缓存穿透问题》
    public Shop queryWithPassThrough(Long id) {
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(shopJson)) {
            //缓存命中且不为空值
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        if (shopJson == "") {
            //缓存命中但为空值
            return null;
        }
        //缓存未命中 查询数据库
        Shop shop = getById(id);
        if (shop == null) {
            //数据库为空  redis中赋予空值
            stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
            return null;
        }
        //数据库存在 存入redis
        //因为缓存未命中 所以shopJson为空 将数据库中的shop重新转化
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;
    }

    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unLock(String key) {
        stringRedisTemplate.delete(key);
    }

    private void saveShop2Redis(Long id, Long expireSeconds) {
        //查询数据库
        Shop shop = getById(id);
        //封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //存入redis
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if(id == null) {
            return Result.fail("店铺id不能为空");
        }
        //更新数据库
        updateById(shop);
        //删除缓存
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        stringRedisTemplate.opsForHash().delete(key);
        return Result.ok();
    }
}
