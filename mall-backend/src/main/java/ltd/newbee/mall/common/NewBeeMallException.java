/**
 * =====================================================================
 * NewBeeMallException.java - 自定义业务异常类
 * 【项目背景】
 * 在传统的Spring Boot应用中，异常处理往往比较混乱：
 * - Controller层直接抛出 RuntimeException，前端返回500 Generic Error
 * - 业务错误（如"库存不足""用户名已存在"）与技术错误（如数据库连接失败）混在一起
 *
 * NewBeeMallException 是针对电商业务场景设计的自定义业务异常类，它的核心价值是：
 * 1. 将业务层面的"预期外情况"与技术层面的"系统故障"区分开
 * 2. 提供细粒度的错误消息，让前端能给出友好的提示而非"服务器出错"
 * 3. 与全局异常处理器 NewBeeMallExceptionHandler 配合，实现统一的错误响应格式
 *
 * 【核心功能与设计理念】
 *
 * ┌──────────────────────────────────────────────────────────────────────┐
 * │ 设计理念：Fail-fast + 明确业务语义                                  │
 * │ 所有业务校验失败的场景，都应该通过 NewBeeMallException.fail()       │
 * │ 抛出，而不是返回一个简单的错误码字符串                             │
 * └──────────────────────────────────────────────────────────────────────┘
 *
 * 【使用模式详解】
 *
 * 模式一：静态 fail() 方法（最常用）
 *    ──────────────────────────────────
 *    // 在Service层的业务逻辑中
 *    if (stock < orderQuantity) {
 *        NewBeeMallException.fail("库存不足！当前库存：" + stock);
 *    }
 *
 *    // 效果：直接抛出异常，停止当前执行流程，交给上层捕获处理
 *
 * 模式二：构造器方式
 *    ──────────────────────────────────
 *    throw new NewBeeMallException("用户名已存在");
 *
 *    // 等价于 NewBeeMallException.fail("用户名已存在")，但更冗长
 *
 * 【与全局异常处理器的协作关系】
 *
 * 调用链示意图：
 *
 * Controller  →  Service  →  DAO  →  数据层
 *              ↑             ↑
 *      (调用业务逻辑)     (NewBeeMallException.fail())
 *                              ↓
 *                      [异常抛出]
 *                              ↓
 *         NewBeeMallExceptionHandler (@RestControllerAdvice)
 *                              ↓
 *         ┌─────────────────────────────────────┐
 *         │ 判断异常类型：                      │
 *         │  ┌──────────────┐                  │
 *         │  │ 是           │ NO → 未知异常     │
 *         │  │ NewBeeMall │                  │
 *         │  │ Exception   │ YES →            │
 *         │  │            │ 提取message给用户│
 *         │  └────────────┘                  │
 *         │ 判断请求类型：                    │
 *         │  ┌──────────────────────────┐    │
 *         │  │ AJAX请求 ?               │    │
 *         │  │ (检查Content-Type/Accept/│    │
 *         │  │  X-Requested-With头)      │    │
 *         │  ├──────────────────────────┤    │
 *         │  │ YES → 返回JSON结果       │    │
 *         │  │   {code:500, message:...} │    │
 *         │  │ NO  → 渲染error视图      │    │
 *         │  └──────────────────────────┘    │
 *         └─────────────────────────────────────┘
 *
 * 【实际业务中的典型使用场景】
 *
 * 场景1：购物车数量限制检查（ShoppingCartController / ShoppingCartService）
 *     // 添加商品到购物车时
 *     int currentCount = cartService.countByUserIdAndGoodsId(userId, goodsId);
 *     if (currentCount >= Constants.SHOPPING_CART_ITEM_LIMIT_NUMBER) {
 *         NewBeeMallException.fail(
 *             String.format("超出单个商品最大购买数量！上限%d个",
 *                           Constants.SHOPPING_CART_ITEM_LIMIT_NUMBER));
 *     }
 *
 * 场景2：商品库存不足（OrderService）
 *     int realStock = goodsService.getStockById(goodsId);
 *     if (realStock < orderQty) {
 *         NewBeeMallException.fail(ServiceResultEnum.SHOPPING_ITEM_COUNT_ERROR.getResult());
 *         // ServiceResultEnum.SHOPPING_ITEM_COUNT_ERROR.getResult() = "库存不足！"
 *     }
 *
 * 场景3：用户登录验证（PersonalController / UserService）
 *     // 密码校验失败
 *     if (!passwordMatch) {
 *         NewBeeMallException.fail(ServiceResultEnum.LOGIN_ERROR.getResult());
 *         // "登录失败！"
 *     }
 *
 * 场景4：订单状态校验（OrderController）
 *     // 用户只能取消待支付的订单
 *     if (order.getOrderStatus() != NewBeeMallOrderStatusEnum.ORDER_PRE_PAY.getOrderStatus()) {
 *         NewBeeMallException.fail(ServiceResultEnum.ORDER_STATUS_ERROR.getResult());
 *         // "订单状态异常！"
 *     }
 *
 * 场景5：商品是否在售（GoodsController）
 *     NewBeeMallGoods goods = goodsService.getById(goodsId);
 *     if (Constants.SELL_STATUS_DOWN == goods.getGoodsSellStatus()) {
 *         NewBeeMallException.fail(ServiceResultEnum.GOODS_PUT_DOWN.getResult());
 *         // "商品已下架！"
 *     }
 *
 * 【与其他异常类的区别】
 *
 * ┌──────────────────────────────────────────────────────────────────────┐
 * │ NewBeeMallException          │ Spring's IllegalArgumentException      │
 * │ ──────────────────────────── │ ────────────────────────────────────── │
 * │ 自定义业务异常，用于业务规则   │ Java标准库异常，用于参数非法等通用校验 │
 * │ 携带具体的业务错误信息       │ 通常不携带业务细节信息                 │
 * │ 由业务代码主动抛出           │ 框架或工具类内部抛出                   │
 * │ 被 NewBeeMallExceptionHandler │ 被 DefaultHandlerExceptionResolver     │
 * │ 单独处理并返回友好提示       │ 统一处理返回500                        │
 * └──────────────────────────────────────────────────────────────────────┘
 *
 * ⚠️ 注意事项：
 * 1. NewBeeMallException 属于 RuntimeException（非检查异常），不需要强制声明throws
 * 2. 它不应该用来处理数据库连接失败、空指针等技术性异常，这些应由后端统一捕获
 * 3. 抛出的异常消息应该对用户有意义，不要暴露敏感信息（如数据库表名、堆栈跟踪）
 * 4. 在Service层使用fail()后，事务可能会回滚，需要根据@Rollback配置确认行为
 * =====================================================================
 */
package ltd.newbee.mall.common;

/**
 * @author 13
 * @qq交流群 796794009
 * @email 2449207463@qq.com
 * @link https://github.com/newbee-ltd
 *
 * @apiNote 自定义业务异常类
 *          用于在业务逻辑层抛出具体的业务错误信息，区别于技术异常。
 *          配合 NewBeeMallExceptionHandler 全局异常处理器使用，
 *          实现业务异常的统一处理和友好反馈。
 */
public class NewBeeMallException extends RuntimeException {

    /**
     * 无参构造方法
 * 用途：满足RuntimeException基类构造要求，偶尔有框架需要无参构造
 * 实际业务中很少直接使用，推荐使用带参数的构造或静态fail()方法
     */
    public NewBeeMallException() {
    }

    /**
     * 带消息的构造方法
 * @param message 异常详细信息，将作为错误消息传递给客户端
 * 用法示例：throw new NewBeeMallException("库存不足");
 * 或者：throw new NewBeeMallException(ServiceResultEnum.SHOPPING_ITEM_COUNT_ERROR.getResult());
     */
    public NewBeeMallException(String message) {
        super(message);
    }

    /**
     * 【功能】静态方法：快速抛出自定义业务异常
 *
 * 【设计目的】
 * 避免每次都要写 "throw new NewBeeMallException()"，简化代码书写。
 * fail() 这个方法名直观地表达了"业务失败，无法继续"的含义。
 *
 * 【最佳实践】
 * 在所有业务校验失败的分支中使用此方法，例如：
 *   if (user == null) NewBeeMallException.fail("用户不存在");
 *   if (!password.equals(hash)) NewBeeMallException.fail("密码错误");
 *   if (stock < quantity) NewBeeMallException.fail("库存不足");
 *
 * @param message 异常消息内容，将被传递给前端显示或在日志中记录
 * 【注意】message应该是用户可读的错误描述，不应包含堆栈跟踪等敏感信息
 */
    public static void fail(String message) {
        throw new NewBeeMallException(message);
    }
}
