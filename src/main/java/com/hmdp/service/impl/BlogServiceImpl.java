package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page =query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            this.queryBlogUser(blog);
            this.isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result queryBlogById(Long id) {
//        1.查询blog
        Blog blog = getById(id);
//        2.查询blog相关的用户
        queryBlogUser(blog);
//        3.查询blog是否被点赞
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    private void isBlogLiked(Blog blog) {
        Long userId = UserHolder.getUser().getId();
        Boolean isLike = stringRedisTemplate.opsForSet().isMember("blog:liked:" + blog.getId(), String.valueOf(userId));
        blog.setIsLike(BooleanUtil.isTrue(isLike));
    }

    @Override
    public Result likeBlog(Long id) {
//        1.判断是否点赞过
//        获取用户id
        Long userId = UserHolder.getUser().getId();
        Boolean isLike = stringRedisTemplate.opsForSet().isMember("blog:liked:" + id, String.valueOf(userId));
        if (BooleanUtil.isTrue(isLike)){
            //        已经点赞则点赞数-1
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            if (isSuccess){//该判断是必要的,只有数据库更改成功后才可以更新redis
                stringRedisTemplate.opsForSet().remove("blog:liked:" + id, String.valueOf(userId));
            }
            return Result.ok(true);
        }
        //        未点赞过则点赞数+1
        boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
        if (isSuccess){
            stringRedisTemplate.opsForSet().add("blog:liked:" + id, String.valueOf(userId));
        }
        return Result.ok(false);
    }

    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }

}
