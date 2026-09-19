package ltd.newbee.mall.service.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 客服接口的接入面防护（M3-C，DESIGN §7.2「接入面」）。
 *
 * <p>{@code POST /api/cs/chat} 是<b>公开、匿名可达</b>的端点，每次调用都会打上游模型
 * （烧额度）+ 可能触发工具循环 + 写一行会话记忆。没有限流的话，
 * 任何人写个循环就能把额度烧光、把库写满。本类提供两件事：
 * <ol>
 *   <li><b>令牌桶限流</b>：按「会话键」限制每分钟请求数（默认 10 次/分钟）；</li>
 *   <li><b>单会话在途去重</b>：同一会话同时只允许 1 个在途请求（§7.2 原文要求）。</li>
 * </ol>
 *
 * <h3>键怎么选（与 §7.4 的会话维度一致）</h3>
 * 由调用方传入：登录用户 {@code u:<userId>}；匿名 {@code c:<conversationId>}；
 * 两者都没有时兜底 {@code ip:<客户端IP>} —— <b>否则等于没限流</b>。
 * <b>刻意不用 HttpSession</b>：本项目未启用 Spring Session，session 是 Tomcat 内存态、
 * 重启即变，用它会给会话引入第二个维度（与 §7.4 的既定决策冲突）。
 *
 * <h3>为什么不是全局锁</h3>
 * 本类在每个请求上都会被调用，绝不能有全局锁。令牌桶是
 * <b>每个键一把锁</b>（{@code synchronized (bucket)}）；
 * 在途计数走 {@link ConcurrentHashMap#compute} 的<b>按键</b>原子性，都不用全局锁。
 *
 * <h3>为什么必须限制桶的数量</h3>
 * 桶按会话键创建，而会话键来自客户端（匿名时就是前端传来的 {@code conversationId}）——
 * 攻击者可以每次换一个 id，让 map 无界增长直到 OOM。
 * 所以桶数量超过 {@code MAX_BUCKETS} 时会<b>惰性清理</b>：丢掉"令牌已满"（空闲）的桶。
 */
@Component
public class CsRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(CsRateLimiter.class);

    /** 令牌用「毫令牌」存整数，避免浮点误差。 */
    private static final long MILLI = 1000L;

    private static final long ONE_MINUTE_NANOS = 60_000_000_000L;

    /** 桶数量上限；超出后触发惰性清理（防 conversationId 无限增长打爆内存）。 */
    private static final int MAX_BUCKETS = 10_000;

    /** 每插入这么多次，检查一次是否需要清理。 */
    private static final int SWEEP_EVERY = 512;

    /** 被拒绝的原因。对客户端一律是 429；这里区分只为日志/用量可观测。 */
    public enum DenyKind { OK, RATE, IN_FLIGHT }

    /** 判定结果。 */
    public record Decision(boolean allowed, DenyKind kind, String message) {
        static Decision ok() {
            return new Decision(true, DenyKind.OK, null);
        }
    }

    private final long capacity;              // 每分钟允许的请求数（= 桶容量）
    private final long capacityMilli;
    private final int maxInFlightPerKey;

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> inFlight = new ConcurrentHashMap<>();
    private final AtomicLong inserts = new AtomicLong();

    public CsRateLimiter(@Value("${cs.limit.requests-per-minute:10}") long requestsPerMinute,
                         @Value("${cs.limit.max-inflight-per-conversation:1}") long maxInFlightPerConversation) {
        this.capacity = Math.max(1, requestsPerMinute);
        this.capacityMilli = this.capacity * MILLI;
        this.maxInFlightPerKey = (int) Math.max(1, Math.min(Integer.MAX_VALUE, maxInFlightPerConversation));
        log.info("客服接入面防护就绪：限流 {} 次/分钟，单会话在途上限 {}，桶上限 {}",
                this.capacity, this.maxInFlightPerKey, MAX_BUCKETS);
    }

    /**
     * 尝试放行一个请求。<b>包含"占用在途名额"这一步</b>，所以调用方必须配套
     * {@link #release(String)}（放在 {@code finally} 里），否则该会话会被永久锁死。
     */
    public Decision tryAcquire(String key) {
        // ① 先查令牌（顺序固定 → 行为可预测：满了就一定是 RATE，而不是 IN_FLIGHT）
        if (!consumeToken(key)) {
            return new Decision(false, DenyKind.RATE,
                    "提问太频繁了，请稍等片刻再试（每分钟最多 " + capacity + " 次）");
        }
        // ② 再占在途名额：同一会话同时只允许 1 个请求（不排队，直接拒 —— 排队会让用户以为卡住）
        if (!tryStart(key)) {
            return new Decision(false, DenyKind.IN_FLIGHT,
                    "上一条还在回答中，请等它答完再问");
        }
        return Decision.ok();
    }

    /**
     * 释放一个请求占用的在途名额。
     *
     * <p><b>必须在所有结束路径上调用</b>（正常结束 / 异常 / 超时）——
     * 漏掉任何一条路径，那个会话就再也发不出请求了。
     */
    public void release(String key) {
        // computeIfPresent 对该键是原子的：不会与并发的 tryStart 交错，
        // 因此不会出现"减到 0 后又被别的线程加上、却被我们移除"的丢计数问题。
        inFlight.computeIfPresent(key, (k, counter) -> counter.decrementAndGet() <= 0 ? null : counter);
    }

    // ------------------------------------------------------------------
    // 内部：令牌桶（每桶一把锁）
    // ------------------------------------------------------------------

    private static final class Bucket {
        long tokensMilli;
        long lastRefillNanos;

        Bucket(long tokensMilli, long nowNanos) {
            this.tokensMilli = tokensMilli;
            this.lastRefillNanos = nowNanos;
        }
    }

    private boolean consumeToken(String key) {
        long now = System.nanoTime();

        // ⚠️ sweepIfNeeded() 必须在 computeIfAbsent **外面** 调：
        //   ConcurrentHashMap 的契约明确要求映射函数不得修改该 map，
        //   在映射函数里删条目属于未定义行为，高并发下会抛 IllegalStateException（→ 500）。
        //   （claude 复核发现的原实现就是写在里面；函数内部有取模短路，多调无副作用）
        sweepIfNeeded();

        Bucket bucket = buckets.computeIfAbsent(key,
                k -> new Bucket(capacityMilli, now));   // 新会话给满额度，否则第一次就被拒

        synchronized (bucket) {
            long elapsed = now - bucket.lastRefillNanos;
            if (elapsed > 0) {
                long refill = elapsed * capacityMilli / ONE_MINUTE_NANOS;
                if (refill > 0) {
                    bucket.tokensMilli = Math.min(capacityMilli, bucket.tokensMilli + refill);
                    bucket.lastRefillNanos = now;
                }
            }
            if (bucket.tokensMilli < MILLI) {
                return false;
            }
            bucket.tokensMilli -= MILLI;
            return true;
        }
    }

    /**
     * 惰性清理：桶已满（= 很久没请求）就丢掉。
     *
     * <p>本方法在 {@code computeIfAbsent} 的映射函数里被调用，所以不能碰正在创建的那个键；
     * 清掉"令牌已满"的桶是安全的 —— 等价于"这个会话空闲很久了，配额重置也没影响"。
     */
    /**
     * 惰性清理：把桶数控制在 {@code MAX_BUCKETS} 以内。
     *
     * <h3>⚠️ 为什么需要“强制压回上限”（实测踩到）</h3>
     * 最初的实现只删「令牌已满」或「超过一个补充周期没被碰过」的桶。但真实洪泛模式是
     * <b>每个键只用一次</b>（匿名客户端不断换 conversationId），而 {@link #consumeToken}
     * 在新建桶后会立即扣掉一个令牌 → 桶<b>永远不是满的</b> → 旧条件一个都删不掉。
     * 实测：插入 12000 个不同键后 {@code bucketCount()=12000}，**上限形同虚设**。
     *
     * <p>所以分两步：先按“空闲/已满”删（这些丢了无副作用），
     * 若仍超上限，<b>再按迭代顺序删到达标</b>（丢一个新用过的桶最多让该会话提前拿回配额，
     * 比内存无限增长可取）。
     */
    private void sweepIfNeeded() {
        if (inserts.incrementAndGet() % SWEEP_EVERY != 0 || buckets.size() <= MAX_BUCKETS) {
            return;
        }
        int removed = 0;
        Iterator<Map.Entry<String, Bucket>> it = buckets.entrySet().iterator();
        while (it.hasNext()) {
            Bucket b = it.next().getValue();
            synchronized (b) {
                // ① 令牌已满（这一分钟内没用过配额）；
                // ② 超过一个完整补充周期没被碰过（lastRefillNanos 只在真正补充时推进，
                //    所以它近似“最后一次使用”）—— 此时丢掉等价于配额重置，无副作用。
                boolean full = b.tokensMilli >= capacityMilli;
                boolean longIdle = (System.nanoTime() - b.lastRefillNanos) > ONE_MINUTE_NANOS;
                if (full || longIdle) {
                    it.remove();
                    removed++;
                }
            }
        }

        // ③ 仍超上限：强制再删到 MAX_BUCKETS（应对“每键只用一次”的洪泛）
        if (buckets.size() > MAX_BUCKETS) {
            int need = buckets.size() - MAX_BUCKETS;
            Iterator<Map.Entry<String, Bucket>> forced = buckets.entrySet().iterator();
            while (forced.hasNext() && need > 0) {
                forced.next();
                forced.remove();
                removed++;
                need--;
            }
        }

        if (removed > 0) {
            log.info("限流桶惰性清理：移除 {} 个桶（当前 {} 个，上限 {}）",
                    removed, buckets.size(), MAX_BUCKETS);
        }
    }

    // ------------------------------------------------------------------
    // 内部：在途名额（按键原子，无全局锁）
    // ------------------------------------------------------------------

    private boolean tryStart(String key) {
        AtomicInteger slot = new AtomicInteger(-1);   // -1 = 未设置；0 = 拒绝；1 = 允许
        inFlight.compute(key, (k, counter) -> {
            AtomicInteger c = (counter == null) ? new AtomicInteger() : counter;
            if (c.get() >= maxInFlightPerKey) {
                slot.set(0);
            } else {
                c.incrementAndGet();
                slot.set(1);
            }
            return c;
        });
        return slot.get() == 1;
    }

    // ------------------------------------------------------------------
    // 测试/排障用的只读内省（包级可见）
    // ------------------------------------------------------------------

    int bucketCount() {
        return buckets.size();
    }

    int inFlightCount(String key) {
        AtomicInteger counter = inFlight.get(key);
        return counter == null ? 0 : counter.get();
    }

    /** 公开：/health 要对外报告当前限流配置（本值不含敏感信息）。 */
    public long capacityPerMinute() {
        return capacity;
    }
}
