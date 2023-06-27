package com.felix;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import com.felix.model.dto.UserDTO;
import com.felix.model.entity.Shop;
import com.felix.model.entity.User;
import com.felix.service.IUserService;
import com.felix.service.impl.ShopServiceImpl;
import com.felix.utils.RedisIdWorker;
import lombok.Cleanup;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import static com.felix.model.constants.RedisConstants.LOGIN_USER_KEY;
import static com.felix.model.constants.RedisConstants.SHOP_GEO_KEY;

@SpringBootTest
class LYXApplicationTests {

    @Resource
    private ShopServiceImpl shopService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private ExecutorService es = Executors.newFixedThreadPool(500);

    @Test
    void testSaveShop() {
        shopService.saveShop2Redis(1L,10L);
    }

    @Test
    void testIdWorker() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(300);

        Runnable task = () -> {
            for (int i = 0; i < 100; i++) {
                long id = redisIdWorker.nextId("order");
                System.out.println("id = " + id);
            }
            latch.countDown();
        };
        long begin = System.currentTimeMillis();
        for (int i = 0; i < 300; i++) {
            es.submit(task);
        }
        latch.await();
        long end = System.currentTimeMillis();
        System.out.println("time = " + (end - begin));
    }

    /**
     * 取出用户表中1000个用户数据保存到redis中，并将对应token保存在文件中以便jmeter测试
     * @throws IOException
     */
    @Test
    void addUserToken() throws IOException {
        List<User> list = userService.lambdaQuery().last("limit 1000").list();
        for (User user : list){
            String token = UUID.randomUUID().toString(true);
            UserDTO userDTO = BeanUtil.copyProperties(user,UserDTO.class);
            Map<String ,Object> userMap = BeanUtil.beanToMap(userDTO,new HashMap<>(),
                    CopyOptions.create().ignoreNullValue()
                            .setFieldValueEditor((fieldKey,fieldVal) -> fieldVal.toString()));
            String tokenKey = LOGIN_USER_KEY + token;
            stringRedisTemplate.opsForHash().putAll(tokenKey,userMap);
            stringRedisTemplate.expire(tokenKey,30, TimeUnit.MINUTES);
        }
        Set<String> keys = stringRedisTemplate.keys(LOGIN_USER_KEY + "*");
        @Cleanup FileWriter fileWriter = new FileWriter("F:\\apache-jmeter-5.5\\bin\\MyTest\\tokens.txt");
        @Cleanup BufferedWriter bufferedWriter = new BufferedWriter(fileWriter);

        assert keys != null;
        for (String key : keys){
            String token = key.substring(LOGIN_USER_KEY.length());
            String text = token + "\n";
            bufferedWriter.write(text);
        }
    }

    /**
     * 将店铺的类型id、店铺id和经纬度信息保存到redis中
     */
    @Test
    void loadShopDat2a(){
        //1. 查询店铺信息
        List<Shop> list = shopService.list();

        //2. 将店铺信息根据店铺类型进行分组,按照typeId分组，typeId一致的放到一个集合
        Map<Long, List<Shop>> result = list.stream().collect(Collectors.groupingBy(Shop::getTypeId));

        //3. 遍历map，将所需的店铺信息进行提取和封装，并分批完成写入Redis
        Set<Map.Entry<Long, List<Shop>>> entries = result.entrySet();
        for (Map.Entry<Long, List<Shop>> entry : entries){
            //获取店铺类型id
            Long typeId = entry.getKey();
            //获取同类型店铺信息
            List<Shop> shops = entry.getValue();
            //格式化每个类型的所有店铺的信息
            List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>(shops.size());
            for (Shop shop : shops){
                String shopId = shop.getId().toString();
                Double x = shop.getX();
                Double y = shop.getY();
                RedisGeoCommands.GeoLocation<String> location = new RedisGeoCommands.GeoLocation<>(shopId,new Point(x,y));
                locations.add(location);
            }

            //4. 将分组后每一组的店铺信息保存到redis中，写入redis GEOADD key 经度 纬度 member
            String key = SHOP_GEO_KEY + typeId;
            stringRedisTemplate.opsForGeo().add(key,locations);
        }
    }

    /**
     * UV（Unique visitor）访客量计算
     * 测试百万数据访问量
     * 使用redis的HyperLogLog类型计算
     */
    @Test
    void testHyperLogLog(){
        //准备数据
        String[] users = new String[1000];
        String key = "UV_users";
        //准备存入100w条数据
        for (int i = 0; i < 1000000; i++){
            int index = i % 1000;
            users[index] = key + i;
            if (index == 999){
                //每1000条数据存入一次
                stringRedisTemplate.opsForHyperLogLog().add(key,users);
            }
        }
        //计算数据总量
        Long size = stringRedisTemplate.opsForHyperLogLog().size(key);
        System.out.println(size);
    }

}
