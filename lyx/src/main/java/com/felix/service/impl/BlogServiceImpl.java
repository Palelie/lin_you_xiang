package com.felix.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.felix.dto.Result;
import com.felix.dto.ScrollResult;
import com.felix.dto.UserDTO;
import com.felix.entity.Blog;
import com.felix.entity.Follow;
import com.felix.entity.User;
import com.felix.mapper.BlogMapper;
import com.felix.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.felix.service.IFollowService;
import com.felix.service.IUserService;
import com.felix.utils.SystemConstants;
import com.felix.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.felix.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.felix.utils.RedisConstants.FEED_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 */
@Service
@Slf4j
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    
    @Resource
    private IFollowService followService;

    /**
     * 获取热点探店笔记列表
     * @param current
     * @return 返回探店笔记列表
     */
    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            this.queryBlogUser(blog);
            this.queryIsLike(blog);
        });
        return Result.ok(records);
    }


    /**
     * 查找发布笔记的用户信息并存入blog中
     * @param blog
     */
    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }

    /**
     * 根据笔记id获取探店笔记
     * @param id 笔记id
     * @return
     */
    @Override
    public Result queryBlog(Long id) {
        //1、查询笔记
        Blog blog = getById(id);
        if (blog == null){
            return Result.fail("笔记不存在！");
        }
        //2、查询用户信息（自己将用户信息维护到blog实体类中，建议用VO或DTO封装）
        this.queryBlogUser(blog);
        //3、判断用户是否点赞过该探店笔记并将数据存入blog中
        this.queryIsLike(blog);
        return Result.ok(blog);
    }

    /**
     * 判断用户是否点赞过该探店笔记并将数据存入blog中
     * @param blog
     */
    private void queryIsLike(Blog blog) {

        UserDTO user = UserHolder.getUser();
        //如果用户未登录，则不需要封装是否点赞信息
        if (user == null){
            return;
        }
        Long userId = user.getId();
        String key = BLOG_LIKED_KEY + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score != null);
    }

    /**
     * 给探店笔记点赞
     * @param id 笔记id
     * @return
     */
    @Override
    public void likeBlog(Long id) {
        //1、获取用户id
        Long userId = UserHolder.getUser().getId();

        //2、在redis中判断该用户是否已经点赞
        //使用SortedSet保证顺序和唯一性
        String key = BLOG_LIKED_KEY + id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());

        //3、未点赞：返回score为null
        if (score == null) {
            //3.1 数据库点赞数+1
            boolean success = update().setSql("liked = liked + 1").eq("id", id).update();
            //3.2 往redis中存入已点赞信息（用户id）
            if (success == true){
                stringRedisTemplate.opsForZSet().add(key,userId.toString(),System.currentTimeMillis());
            } else{
                log.error("点赞数据修改错误");
            }
        }
        //4、已点赞：
        else {
            //4.1 数据库点赞数-1
            boolean success = update().setSql("liked = liked - 1").eq("id", id).update();

            //4.2 移除redis中用户的点赞信息（移除用户id）
            if (success == true){
                stringRedisTemplate.opsForZSet().remove(key,userId.toString());
            }else {
                log.error("点赞数据修改错误");
            }
        }
    }

    /**
     * 获取点赞排行榜列表
     * @param id 笔记id
     * @return 点赞排序列表
     */
    @Override
    public Result queryBlogLikes(Long id) {
        //1、获取top5点赞用户
        String key = BLOG_LIKED_KEY + id;
        Set<String> top5User = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        //2、判断是否有用户点赞
        if (top5User == null || top5User.isEmpty()){
            return Result.ok(Collections.emptyList());
        }

        //3、解析出其中的用户id
        //为避免在数据库查询中顺序被重新打乱，需要用该sql语句查询：WHERE id IN ( 5 , 1 ) ORDER BY FIELD(id, 5, 1)
        List<Long> userIdList = top5User.stream().map(Long::valueOf).collect(Collectors.toList());

        //4、根据id查询用户
        String idStr = StrUtil.join(",", userIdList);
        List<User> users = userService.query().in("id", userIdList)
                .last("order by field(id," + idStr + ")").list();

        //5、将用户数据脱敏后再返回前端
        List<UserDTO> userDTOList = users.stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());

        //6、返回
        return Result.ok(userDTOList);
    }

    /**
     * 保存探店笔记，并推送到粉丝收件箱（redis）中
     * @param blog 笔记内容
     * @return 笔记id
     */
    @Override
    public Result saveBlog(Blog blog) {
        //1、 获取登录用户
        Long id = UserHolder.getUser().getId();
        blog.setUserId(id);
        
        //2、保存探店博文
        boolean isSuccess = save(blog);
        if (!isSuccess){
            return Result.fail("笔记保存失败");
        }

        //3、查询博主的所有粉丝id
        List<Long> fansId = followService.query().eq("follow_user_id", id).list()
                .stream().map(Follow::getUserId).collect(Collectors.toList());

        //4、将笔记信息（笔记id，当前时间：用于排序）推送到粉丝的收件箱中
        Long blogId = blog.getId();
        Double currentTime = Double.valueOf(System.currentTimeMillis());
        for (Long fanId : fansId){
            String key = FEED_KEY + fanId;
            stringRedisTemplate.opsForZSet().add(key,blogId.toString(),currentTime);
        }

        // 返回id
        return Result.ok(blogId);
    }

    /**
     * 查询关注列表的推送消息（使用推模式实现feed流）
     * @param max 上次推送的最小时间值（score，按从大到小排）
     * @param offset 偏移量
     * @return 收件箱里推送到笔记，本次查询的最小时间戳和偏移量
     */
    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        //1、获取用户id
        Long userId = UserHolder.getUser().getId();

        //2、查询用户的收件箱 redis指令：ZREVRANGEBYSCORE key Max Min LIMIT offset count
        String key = FEED_KEY + userId;
        //set保存的对象包含原始数据和score值
        Set<ZSetOperations.TypedTuple<String>> blogInfo = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, Double.valueOf(max), Long.valueOf(offset), 4);
        if (blogInfo == null || blogInfo.isEmpty()){
            return Result.ok();
        }


        //3、提取出收件箱中的信息（笔记id、新的最小时间戳、新的偏移量）
        List<Long> blogIds = new ArrayList<>(blogInfo.size());//初始化list数组大小避免容量扩容，提高效率
        long minTime = 0;
        int newOffset = 1;
        for (ZSetOperations.TypedTuple<String> typedTuple : blogInfo){
            //获取笔记id
            blogIds.add(Long.valueOf(typedTuple.getValue()));
            //获取最小时间戳（最后遍历到的时间戳）
            long temp = typedTuple.getScore().longValue();
            //判断偏移量，避免由于出现相同的时间戳导致多读或漏读
            if (temp == minTime){
                newOffset++;
            }else{
                minTime = temp;
                newOffset = 1;
            }
        }
        //如果本次查询这一页的最小时间戳和上次查询的最小时间戳相同，则需要叠加两次的偏移量
        newOffset = minTime == max ? newOffset + offset : newOffset;

        //4、根据收件箱里的笔记id，分页查询笔记内容
        String idsStr = StrUtil.join(",", blogIds);
        List<Blog> blogs = query().in("id", blogIds).last("order by field(id," + idsStr + ")").list();

        //5、补充完善笔记内容
        for (Blog blog : blogs){
            //是否被点赞
            queryIsLike(blog);
            //查询笔记博主信息并保存进去
            queryBlogUser(blog);
        }

        //6、封装返回对象
        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setList(blogs);
        scrollResult.setMinTime(minTime);
        scrollResult.setOffset(newOffset);


        return Result.ok(scrollResult);
    }
}
