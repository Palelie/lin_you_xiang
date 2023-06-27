package com.felix.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.felix.model.dto.LoginFormDTO;
import com.felix.model.dto.Result;
import com.felix.model.dto.UserDTO;
import com.felix.model.entity.User;
import com.felix.mapper.UserMapper;
import com.felix.service.IUserService;
import com.felix.model.constants.RedisConstants;
import com.felix.utils.RegexUtils;
import com.felix.model.constants.SystemConstants;
import com.felix.utils.UserHolder;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.felix.model.constants.RedisConstants.USER_SIGN_KEY;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 */
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 发送手机验证码
     */
    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 发送短信验证码并保存验证码
        //1、校验手机号格式
        boolean phoneInvalid = RegexUtils.isPhoneInvalid(phone);

        //2、不符合：返回错误信息
        if (phoneInvalid){
            return Result.fail("手机号格式错误");
        }

        //3、符合：生成验证码
        String code = RandomUtil.randomNumbers(6);

        //4、将验证码存储到redis中(有效时间：2min)
        stringRedisTemplate.opsForValue().set(RedisConstants.LOGIN_CODE_KEY + phone,code,RedisConstants.LOGIN_CODE_TTL, TimeUnit.MINUTES);

        //5、发送验证码
        log.debug("发送短信验证码：" + code);

        //6、返回ok
        return Result.ok();
    }

    /**
     * 登录功能
     * @param loginForm 登录参数，包含手机号、验证码；或者手机号、密码
     */
    @Override
    public Result login(LoginFormDTO loginForm) {
        // 实现登录功能
        //1、校验手机号格式是否正确
        String phone = loginForm.getPhone();
        boolean phoneInvalid = RegexUtils.isPhoneInvalid(phone);
        //错误：返回错误信息
        if (phoneInvalid){
            return Result.fail("请输入正确的手机号");
        }

        //2、从redis中取出验证码并检查是否正确
        String phoneKey = RedisConstants.LOGIN_CODE_KEY + phone;
        String cacheCode = stringRedisTemplate.opsForValue().get(phoneKey);
        String newCode = loginForm.getCode();
        //错误：返回错误信息
        if (cacheCode == null || !cacheCode.equals(newCode)){
            return Result.fail("验证码错误");
        }

        //3、到数据库查找手机号是否存在
        QueryWrapper<User> userQueryWrapper = new QueryWrapper<>();
        userQueryWrapper.eq("phone",phone);
        //4、存在：获取用户信息
        User user = getOne(userQueryWrapper);

        //5、不存在：新建用户并存入数据库
        if (user == null){
            user = new User();
            user.setPhone(phone);
            user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
            save(user);
        }

        //6、保存用户信息(封装为DTO)到redis中(使用Hash)
        //6.1 生成token(true表示简单类型，没有"-")
        String token = UUID.randomUUID().toString(true);
        UserDTO userDTO = BeanUtil.copyProperties(user,UserDTO.class);
        //6.2 需将Bean里面的非String类型数据转化为String类型才能存入Redis
        Map<String, Object> map = BeanUtil.beanToMap(userDTO, new HashMap<>(), CopyOptions.create()
                .setIgnoreNullValue(true)
                .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
        String tokenKey = RedisConstants.LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey,map);

        //7、设置有效期
        stringRedisTemplate.expire(tokenKey, RedisConstants.LOGIN_USER_TTL,TimeUnit.MINUTES);

        //8、返回token
        return Result.ok(token);
    }

    @Override
    public Result logout(HttpServletRequest request) {
        UserDTO user = UserHolder.getUser();
        if (BeanUtil.isEmpty(user)){
            return null;
        }
        UserHolder.removeUser();
        String token = request.getHeader("authorization");
        if (StrUtil.isNotBlank(token)){
            stringRedisTemplate.delete(RedisConstants.LOGIN_USER_KEY + token);
        }
        return null;
    }

    /**
     * 实现用户签到
     * @return 无
     */
    @Override
    public Result sign() {
        //1、获取用户id
        Long id = UserHolder.getUser().getId();

        //2、获取当天日期
        LocalDateTime now = LocalDateTime.now();

        //3、封装key
        String timeStr = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + id.toString() + timeStr;

        //4、存入redis的bitMap中（以string类型数据存储）
        //计算今天是本月的第几天
        int today = now.getDayOfMonth();
        stringRedisTemplate.opsForValue().setBit(key,today - 1,true);

        return null;
    }

    /**
     * 计算连续签到的天数
     * @return 连续签到天数
     */
    @Override
    public Result signCount() {
        //1、获取用户id
        Long id = UserHolder.getUser().getId();

        //2、获取当天日期
        LocalDateTime now = LocalDateTime.now();

        //3、封装key
        String timeStr = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + id.toString() + timeStr;

        //4、计算今天是本月的第几天
        int today = now.getDayOfMonth();

        //5、获取本月截止今天为止的所有的签到记录，返回的是一个十进制的数字 BITFIELD sign:5:202203 GET u14 0
        List<Long> signs = stringRedisTemplate.opsForValue()
                .bitField(
                        key,
                        BitFieldSubCommands.create().get(BitFieldSubCommands.BitFieldType.unsigned(today)).valueAt(0));

        //6、判断数据是否为空
        if (signs == null || signs.isEmpty()){
            return Result.ok(0);
        }
        Long signDay = signs.get(0);

        //7、遍历循环
        int count = 0; //计数器
        while (true){
            //7.1 让这个数字与1做与运算，得到数字的最后一个bit位  // 判断这个bit位是否为0
            if ((signDay & 1) == 0) {
                //如果为0，退出循环
                break;
            }else {
                //如果为1，计数器加1，数字右移一位
                count ++;
                signDay >>>= 1;
            }
        }

        return Result.ok(count);
    }
}
