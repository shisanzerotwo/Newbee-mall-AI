package ltd.newbee.mall.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Redis 缓存工具类
 *
 * 【为什么用 StringRedisTemplate + JSON,而不是 RedisTemplate 默认的 JDK 序列化?】
 * 1. 项目中的实体类(如 NewBeeMallGoods)没有实现 Serializable,JDK 序列化会直接报错
 * 2. JSON 字符串存储,在 redis-cli 里可以直接查看内容,便于调试
 * 3. 面试常问点:RedisTemplate 默认的 JdkSerializationRedisSerializer 存的是二进制,
 *    不仅可读性差,还会因为类结构变化导致反序列化失败
 *
 * 【为什么不用现有的 JsonUtil?】
 * JsonUtil.jsonToObj(Class, String) 无法反序列化泛型 List<VO>,
 * 这里使用 Jackson 的 TypeReference 解决泛型问题
 */
@Component
public class RedisCacheUtil {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 从缓存读取对象(支持泛型 List,通过 TypeReference 传入)
     * @param key 缓存 key
     * @param typeReference 泛型类型,如 new TypeReference<List<NewBeeMallIndexCarouselVO>>(){}
     * @return 缓存对象,缓存不存在或反序列化失败返回 null(降级为查库)
     */
    public <T> T get(String key, TypeReference<T> typeReference) {
        try {
            String json = stringRedisTemplate.opsForValue().get(key);
            if (!StringUtils.hasText(json)) {
                return null;
            }
            return objectMapper.readValue(json, typeReference);
        } catch (IOException e) {
            // 反序列化失败:删除脏数据,返回 null 让上层走数据库查询
            stringRedisTemplate.delete(key);
            return null;
        }
    }

    /**
     * 写入缓存
     * @param key 缓存 key
     * @param obj 待缓存对象(会被序列化为 JSON)
     * @param ttlSeconds 过期时间(秒)
     */
    public void set(String key, Object obj, long ttlSeconds) {
        try {
            String json = objectMapper.writeValueAsString(obj);
            stringRedisTemplate.opsForValue().set(key, json, ttlSeconds, TimeUnit.SECONDS);
        } catch (JsonProcessingException e) {
            // 序列化失败不阻断业务,仅打日志
            System.err.println("Redis 缓存序列化失败, key = " + key + ", error = " + e.getMessage());
        }
    }

    /**
     * 删除缓存 key(后台数据修改后调用,保证缓存一致性)
     */
    public void delete(String key) {
        stringRedisTemplate.delete(key);
    }
}
