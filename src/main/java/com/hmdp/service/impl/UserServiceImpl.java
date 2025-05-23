package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;


    @Override
    public Result sedCode(String phone) {
        //1. 校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            //2.如果不符合，返回错误信息
            return Result.fail("手机号格式错误");
        }
        //3. 符合，生成验证码
        String code = RandomUtil.randomNumbers(6);
        //4. 保存验证码到Redis(Session->Redis)
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY+phone,code,LOGIN_CODE_TTL, TimeUnit.MINUTES);
//        stringRedisTemplate.expire(LOGIN_CODE_KEY+phone,LOGIN_CODE_TTL,TimeUnit.MINUTES);
        //5. 发送验证码
        log.debug("发送短信验证码成功，验证码:{}",code);
        //返回ok
        return Result.ok();
    }
    //获取某个用户某个月的签到信息
    @Override
    public Result sign() {
//        1.获取当前登录用户
        Long userId = UserHolder.getUser().getId();
//        2.获取日期
        LocalDateTime now = LocalDateTime.now();
//        3.拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyy/MM"));
        String key =  USER_SIGN_KEY + userId + keySuffix;
//            4.获取今天时本月的第几天
        int dayOfMonth = now.getDayOfMonth();
//5.写入reids setbit key offset 1
        stringRedisTemplate.opsForValue().setBit(key,dayOfMonth - 1, true);
        return Result.ok();
    }

    @Override
    public Result signCount() {
        //        1.获取当前登录用户
        Long userId = UserHolder.getUser().getId();
//        2.获取日期
        LocalDateTime now = LocalDateTime.now();
//        3.拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyy/MM"));
        String key =  USER_SIGN_KEY + userId + keySuffix;
//            4.获取今天时本月的第几天
        int dayOfMonth = now.getDayOfMonth();
//        5.获取本月截至今天为止的所有签到记录，返回的是一个十进制的数字
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0)
        );
        if (result == null || result.isEmpty()){
            //没有任何签到结果
            return Result.ok(0);
        }
        Long num = result.get(0);
        if (num == null || num == 0){
            return Result.ok(0);
        }
//        6.循环遍历
        int count = 0;
//
        while (true){
            //                6.1让这个数字与1做与运算，得到数字的最后一个bit位   判断这个bit位是否为0
            if ((num & 1) == 0) {
                //                如果为0，说明未签到，结束
                break;
            }else {
                //                如果不为0，说明已经签到，计数器+1
                count++;
            }
            //                把数字右移一位，抛弃最后一个bit位，继续下一个bit位
            num >>>= 1;
        }
        return Result.ok(count);
    }

    @Override
    public Result login(LoginFormDTO loginForm) {
        //1. 重新校验手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误");
        }
        //2. 校验验证码(从Session获取->从Redis中获取）
//        Object cacheCode = session.getAttribute("code");
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        String inputCode = loginForm.getCode();
        if (cacheCode == null || !cacheCode.equals(inputCode)){
            //3. 不一致，报错
            return Result.fail("验证码错误");
        }
        //4.一致，根据手机号查询用户
        User user = query().eq("phone", phone).one();
        //5. 判断用户是否存在
        if (user == null){
            //6. 不存在，创建并保存新用户
            user = createUserWithPhone(phone);
        }
        //7.保存用户信息到session ->Redis
        //7.1随机生成token，作为登录令牌
        String token = UUID.randomUUID().toString(true);//Simple:带中划线的
        // 7.2将User对象转为Hash存储
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO);
        //        7.3存储
        //        session.setAttribute("user",BeanUtil.copyProperties(user,UserDTO.class));
        String tokenKey = LOGIN_USER_KEY+token;
        //这两行代码目的：将id类型从Object->String类型以填入Redis
        Long id = (Long) userMap.get("id");
        userMap.put("id",id.toString());

        stringRedisTemplate.opsForHash().putAll(tokenKey,userMap);
//        7.4设置token有效期
        stringRedisTemplate.expire(tokenKey,LOGIN_USER_TTL,TimeUnit.MINUTES);
//        8.返回token   客户端（浏览器/App）存储了这个 token（在 localStorage中，老师给的nginx代码中有这个）
        return Result.ok(token);
    }


    private User createUserWithPhone(String phone) {
        // 1.创建用户
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        // 2.保存用户
        save(user);
        return user;
    }
}
