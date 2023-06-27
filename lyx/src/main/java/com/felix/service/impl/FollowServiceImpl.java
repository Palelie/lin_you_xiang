package com.felix.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.felix.model.dto.Result;
import com.felix.model.dto.UserDTO;
import com.felix.model.entity.Follow;
import com.felix.mapper.FollowMapper;
import com.felix.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.felix.service.IUserService;
import com.felix.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 */
@Service
@Slf4j
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserService userService;

    /**
     * 用户关注和取关
     * @param followUserId 被关注的用户id
     * @param isFollow 判断是关注还是取关
     * @return
     */
    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        Long userId = UserHolder.getUser().getId();
        String key = "follows:" + userId;
        boolean success = true;
        //1、判断用户是要关注还是取关
        if (isFollow) {
            //关注：添加数据
            Follow follow = new Follow();
            follow.setFollowUserId(followUserId);
            follow.setUserId(userId);
            success = save(follow);

            //将关注信息存入redis
            stringRedisTemplate.opsForSet().add(key, String.valueOf(followUserId));

        }else {
            //取关：删除数据
            remove(new QueryWrapper<Follow>().eq("user_id",userId).eq("follow_user_id",followUserId));

            //将关注信息从redis中移除
            stringRedisTemplate.opsForSet().remove(key,followUserId.toString());
        }
        if (!success) {
            log.error("关注/取关数据保存失败");
            return Result.fail("数据库异常，关注数据保存失败");
        }
        return Result.ok();
    }

    /**
     * 查询用户是否关注
     * @param followUserId 要查询的用户id
     * @return
     */
    @Override
    public Result isFollow(Long followUserId) {
        //查找数据是否存在
        Long userId = UserHolder.getUser().getId();
        Integer count = query().eq("user_id", userId).eq("follow_user_id", followUserId).count();
        // 判断
        return Result.ok(count > 0);
    }

    /**
     * 查找共同关注用户
     * @param id 查找用户的id
     * @return 关注用户列表信息（DTO封装）
     */
    @Override
    public Result queryCommonFollows(Long id) {
        //1、获取当前用户id
        Long userId = UserHolder.getUser().getId();

        //2、在redis中求两个用户的关注列表的交集
        String key1 = "follows:" + userId;
        String key2 = "follows:" + id;
        List<Long> followsId = stringRedisTemplate.opsForSet().intersect(key1, key2)
                .stream().map(Long::valueOf).collect(Collectors.toList());

        if (followsId == null || followsId.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        //3、根据用户id查询用户信息并封装为DTO

        List<UserDTO> users = userService.listByIds(followsId)
                .stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class)).collect(Collectors.toList());

        return Result.ok(users);
    }
}
