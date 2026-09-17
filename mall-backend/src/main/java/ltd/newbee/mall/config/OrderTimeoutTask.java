package ltd.newbee.mall.config;

import ltd.newbee.mall.service.NewBeeMallOrderService;
import ltd.newbee.mall.util.OrderDelayQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 订单超时自动关闭定时任务
 *
 * 【职责】
 * 每 10 秒从 Redis ZSet 延迟队列中取出"已到期"的待支付订单,
 * 逐个调用 orderService.closeTimeoutOrder() 自动关闭并恢复库存。
 *
 * 【为什么用 @Scheduled 而不是 Quartz?】
 * 单体项目单实例,Spring 自带的 @Scheduled 足够,无需引入重量级调度框架。
 * 若将来多实例部署,需加分布式锁避免重复消费(本项目单实例,不涉及)。
 *
 * 【失败重试机制】
 * - 关闭成功的订单:closeTimeoutOrder() 返回 true → 从 ZSet 移除
 * - 关闭失败的订单:返回 false → 留在 ZSet 中,下一轮(10秒后)重试
 * - 这样即使某次数据库抖动,订单也会在下轮被继续处理,不会丢
 */
@Component
public class OrderTimeoutTask {

    private static final Logger log = LoggerFactory.getLogger(OrderTimeoutTask.class);

    @Autowired
    private OrderDelayQueue orderDelayQueue;

    @Autowired
    private NewBeeMallOrderService newBeeMallOrderService;

    /**
     * 每 10 秒执行一次(上次执行完成后间隔 10 秒,避免任务堆积)
     */
    @Scheduled(fixedDelay = 10000)
    public void closeExpiredOrders() {
        // 取出所有已到期的订单号(score <= 当前时间)
        Set<String> expiredOrderNos = orderDelayQueue.pollExpired();
        if (expiredOrderNos.isEmpty()) {
            return;
        }
        for (String orderNo : expiredOrderNos) {
            try {
                boolean success = newBeeMallOrderService.closeTimeoutOrder(orderNo);
                if (success) {
                    // 处理成功(已关闭 或 订单状态已变化无需关闭)→ 从队列移除
                    orderDelayQueue.removeProcessed(orderNo);
                }
                // 处理失败:留在队列,下轮重试
            } catch (Exception e) {
                // 单笔订单处理异常不影响其他订单;该订单留在队列下轮重试
                log.error("订单超时关闭异常, orderNo = " + orderNo, e);
            }
        }
    }
}
