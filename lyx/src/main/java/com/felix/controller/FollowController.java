package com.felix.controller;


import com.felix.dto.Result;
import com.felix.service.IFollowService;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

/**
 * <p>
 *  前端控制器
 * </p>
 */
@RestController
@RequestMapping("/follow")
public class FollowController {

    @Resource
    private IFollowService followService;

    /**
     * 用户关注和取关
     * @param followUserId 被关注的用户id
     * @param isFollow 判断是关注还是取关
     * @return
     */
    @PutMapping("/{id}/{isFollow}")
    public Result follow(@PathVariable("id")Long followUserId,@PathVariable("isFollow")Boolean isFollow){
        return followService.follow(followUserId,isFollow);
    }

    /**
     * 查询用户是否关注
     * @param followUserId 要查询的用户id
     * @return
     */
    @GetMapping("/or/not/{id}")
    public Result isFollow(@PathVariable("id")Long followUserId){
        return followService.isFollow(followUserId);
    }


    /**
     * 查找共同关注用户
     * @param id 查找用户的id
     * @return 关注用户列表信息（DTO封装）
     */
    @GetMapping("/common/{id}")
    public Result queryCommonFollows(@PathVariable("id")Long id){
        return followService.queryCommonFollows(id);
    }
}
