package com.felix.service;

import com.felix.model.dto.Result;
import com.felix.model.entity.Blog;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 */
public interface IBlogService extends IService<Blog> {

    /**
     * 获取热点探店笔记列表
     * @param current
     * @return 返回探店笔记列表
     */
    Result queryHotBlog(Integer current);

    /**
     * 根据笔记id获取探店笔记
     * @param id
     * @return
     */
    Result queryBlog(Long id);

    /**
     * 给探店笔记点赞
     * @param id 笔记id
     * @return
     */
    void likeBlog(Long id);

    /**
     * 获取点赞排行榜列表
     * @param id 笔记id
     * @return 点赞排序列表
     */
    Result queryBlogLikes(Long id);

    /**
     * 保存探店笔记，并推送到粉丝收件箱（redis）中
     * @param blog 笔记内容
     * @return 笔记id
     */
    Result saveBlog(Blog blog);

    /**
     * 查询关注列表的推送消息（使用推模式实现feed流）
     * @param max 上次推送的最小时间值（score，按从大到小排）
     * @param offset 偏移量
     * @return 收件箱里推送到笔记，本次查询的最小时间戳和偏移量
     */
    Result queryBlogOfFollow(Long max, Integer offset);
}
