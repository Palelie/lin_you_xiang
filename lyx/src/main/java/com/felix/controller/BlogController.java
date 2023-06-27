package com.felix.controller;


import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.felix.model.dto.Result;
import com.felix.model.dto.UserDTO;
import com.felix.model.entity.Blog;
import com.felix.service.IBlogService;
import com.felix.model.constants.SystemConstants;
import com.felix.utils.UserHolder;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;

/**
 * <p>
 * 前端控制器
 * </p>
 */
@RestController
@RequestMapping("/blog")
public class BlogController {

    @Resource
    private IBlogService blogService;

    /**
     * 保存探店笔记，并推送到粉丝收件箱（redis）中
     * @param blog 笔记内容
     * @return 笔记id
     */
    @PostMapping
    public Result saveBlog(@RequestBody Blog blog) {
        return blogService.saveBlog(blog);
    }

    /**
     * 给探店笔记点赞
     * @param id 笔记id
     * @return
     */
    @PutMapping("/like/{id}")
    public Result likeBlog(@PathVariable("id") Long id) {
        blogService.likeBlog(id);
        return Result.ok();
    }

    @GetMapping("/of/me")
    public Result queryMyBlog(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        // 根据用户查询
        Page<Blog> page = blogService.query()
                .eq("user_id", user.getId()).page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        return Result.ok(records);
    }

    /**
     * 获取热点探店笔记列表
     * @param current
     * @return 返回探店笔记列表
     */
    @GetMapping("/hot")
    public Result queryHotBlog(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        return blogService.queryHotBlog(current);
    }

    /**
     * 根据笔记id获取探店笔记
     * @param id
     * @return
     */
    @GetMapping("/{id}")
    public Result queryHotBlog(@PathVariable("id") Long id) {
        return blogService.queryBlog(id);
    }

    /**
     * 获取点赞排行榜列表
     * @param id 笔记id
     * @return 点赞排序列表
     */
    @GetMapping("/likes/{id}")
    public Result queryBlogLikes(@PathVariable("id") Long id) {
        return blogService.queryBlogLikes(id);
    }


    /**
     * 根据id查询博主的探店笔记
     * @param id 博主id
     * @param current 当前页码
     * @return 笔记集合
     */
    @GetMapping("/of/user")
    public Result queryBlogByUserId(
            @RequestParam("id")Long id,
            @RequestParam(value = "current",defaultValue = "1")Integer current
    ){
        //根据用户id分页查询
        List<Blog> blogs = blogService.query()
                .eq("user_id", id)
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE))
                .getRecords();
        if (blogs == null){
            return Result.ok(Collections.emptyList());
        }
        return Result.ok(blogs);
    }


    /**
     * 查询关注列表的推送消息（使用推模式实现feed流）
     * @param max 上次推送的最小时间值（score，按从大到小排）
     * @param offset 偏移量
     * @return 收件箱里推送到笔记，本次查询的最小时间戳和偏移量
     */
    @GetMapping("/of/follow")
    public Result queryBlogOfFollow(
            @RequestParam("lastId")Long max,
            @RequestParam(value = "offset", defaultValue = "0") Integer offset
    ){
        return blogService.queryBlogOfFollow(max,offset);
    }
}
