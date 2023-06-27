package com.felix.service;

import com.felix.model.dto.Result;
import com.felix.model.entity.Follow;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 */
public interface IFollowService extends IService<Follow> {

    /**
     * 用户关注和取关
     * @param followUserId 被关注的用户id
     * @param isFollow 判断是关注还是取关
     * @return
     */
    Result follow(Long followUserId, Boolean isFollow);

    /**
     * 查询用户是否关注
     * @param followUserId 要查询的用户id
     * @return
     */
    Result isFollow(Long followUserId);

    /**
     * 查找共同关注用户
     * @param id 查找用户的id
     * @return 关注用户列表信息（DTO封装）
     */
    Result queryCommonFollows(Long id);
}
