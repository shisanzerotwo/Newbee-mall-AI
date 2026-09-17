package ltd.newbee.mall.util;

import ltd.newbee.mall.common.Constants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Set;

/**
 * 订单超时延迟队列(基于 Redis ZSet)
 *
 * 【原理】
 * ZSet 是"有序集合",每个成员带一个 score(分数),按 score 从小到大排序。
 * 这里把 score 设为"订单的到期时间戳",于是:
 * - ZRANGEBYSCORE 0 now  → 一条命令取出所有"已到期"的订单号(score 小于当前时间)
 * - 天然按时间排序,精确到秒,无需遍历全表
 *
 * 【与"定时扫表"方案对比(面试常问)】
 * | 维度       | 定时扫表                   | Redis ZSet 延迟队列        |
 * |-----------|--------------------------|--------------------------|
 * | 查询范围   | 全表扫 order_status=0     | 只处理真正到期的订单       |
 * | 精度       | 依赖扫描频率(分钟级)       | 秒级                      |
 * | 数据量大了 | 越来越慢                  | 只扫到期的那一批           |
 * | 缺点       | 简单可靠                  | 依赖 Redis,重启会丢队列   |
 *
 * 【注意】Redis 重启会丢失 ZSet 数据,真实系统需配合"启动时扫库兜底"
 * (把未支付且超时的订单重新入队)。本项目作为学习演示,暂不处理该兜底。
 */
@Component
public class OrderDelayQueue {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 订单入队:设置 score = 当前时间 + 超时时长
     * @param orderNo 订单号(ZSet 的 member)
     * @param expireSeconds 超时秒数
     */
    public void add(String orderNo, long expireSeconds) {
        long expireTimestamp = System.currentTimeMillis() + expireSeconds * 1000;
        stringRedisTemplate.opsForZSet().add(Constants.ORDER_DELAY_QUEUE_KEY, orderNo, expireTimestamp);
    }

    /**
     * 订单出队:支付成功后调用,防止到期的待支付订单被误关
     * @param orderNo 订单号
     */
    public void remove(String orderNo) {
        stringRedisTemplate.opsForZSet().remove(Constants.ORDER_DELAY_QUEUE_KEY, orderNo);
    }

    /**
     * 取出所有已到期的订单号(score <= 当前时间)
     * 注意:只是"取出",不移除——处理成功后才由调用方移除,失败则留在队列下轮重试
     * @return 已到期订单号集合(可能为空)
     */
    public Set<String> pollExpired() {
        long now = System.currentTimeMillis();
        Set<String> orderNos = stringRedisTemplate.opsForZSet()
                .rangeByScore(Constants.ORDER_DELAY_QUEUE_KEY, 0, now);
        return orderNos == null ? Collections.emptySet() : orderNos;
    }

    /**
     * 移除已成功处理的订单(处理成功后调用)
     * @param orderNo 订单号
     */
    public void removeProcessed(String orderNo) {
        stringRedisTemplate.opsForZSet().remove(Constants.ORDER_DELAY_QUEUE_KEY, orderNo);
    }
}
