/**
 * =====================================================================
 * NewBeeMallOrderStatusEnum.java - 订单状态枚举类
 * 【项目背景】
 * 电商系统的订单生命周期复杂，涉及用户下单、支付、发货、收货、售后等各个阶段。
 * 订单的状态变化是业务流程的核心，直接影响库存、资金、物流等环节。
 * NewBeeMallOrderStatusEnum对订单的全生命周期状态进行了标准化定义。
 *
 * 【完整状态机图谱】
 *
 *    ┌────────────────────┐              ┌─────────────────┐
 *    │   ORDER_PRE_PAY    │ (0) 待支付   │  ORDER_PAID     │ (1) 已支付
 *    │                     │             │                 │
 *    │ 用户下单完成       │ ───────────►│ 支付平台验证通过│
 *    │ 需要支付          │              │ 或人工确认      │
 *    └────────────────────┘              └─────────┬──────┘
 *                                                   │
 *                                                   ▼
 *                                           ┌──────────────────┐     ┌─────────────────┐
 *                                           │  ORDER_PACKAGED  │ (2) 配货完成│ ORDER_EXPRESS   │ (3)
 *出库成功
 *                                           │ 仓库打包完毕      │ ───────►│                │
 *                                           │ 准备发出         │        │                │
 *                                           └────────────────┘     └─────────┬──────┘
 *                                                                        │
 *                                                                        ▼
 *                                                        ┌──────────────────┐
 *                                                        │   ORDER_SUCCESS  │ (4) 交易成功
 *                                                        │ 用户确认收货      │
 *                                                        │                   │
 *                                                        └──────────────────┘
 *
 *     关闭路径（从各阶段到关闭状态）：
 *
 * ORDER_PRE_PAY ──超时未支付──► ORDER_CLOSED_BY_EXPIRED (-2)
 * ORDER_PRE_PAY ──手动取消───► ORDER_CLOSED_BY_MALLUSER (-1)
 * ORDER_PAID ──缺货拒发───────► ORDER_CLOSED_BY_JUDGE (-3)
 *
 * 【状态值详细解析】
 *
 * ┌──────────────────────────────────────────────────────────────────────────────┐
 * │ DEFAULT (-9, "ERROR") : 默认错误值                                              │
 * │ • 用途：初始化变量、校验失败的兜底值                                          │
 * │ • 永远不会作为订单的真实状态出现在业务中                                      │
 * ├──────────────────────────────────────────────────────────────────────────────┤
 * │ ORDER_PRE_PAY (0, "待支付")                                                    │
 * │ • 定义：用户已提交订单但尚未完成支付                                        │
 * │ • 关键操作：                                                                 │
 * │   - 生成临时锁定库存（防止超卖）                                            │
 * │   - 设置超时时间（默认30分钟或1小时），超时后自动关闭                        │
 * │   - 用户可查看此状态的订单并选择"立即支付"                                  │
 * │ • 重要：这是从"创建"到"激活"的过渡状态                                     │
 * ├──────────────────────────────────────────────────────────────────────────────┤
 * │ ORDER_PAID (1, "已支付")                                                       │
 * │ • 定义：用户已完成支付，资金已在途或已到账                                   │
 * │ • 触发条件：                                                               │
 * │   - 支付宝/微信等第三方平台回调通知成功                                    │
 * │   - 系统确认支付结果                                                       │
 * │ • 后续流程：                                                               │
 * │   1. 释放之前锁定的库存（或直接扣减库存）                                 │
 * │   2. 进入"配货完成"阶段                                                  │
 * │   3. 可生成电子发票                                                      │
 * ├──────────────────────────────────────────────────────────────────────────────┤
 * │ ORDER_PACKAGED (2, "配货完成")                                               │
 * │ • 定义：仓库工作人员已打包商品，等待物流公司揽收                            │
 * │ • 业务操作：                                                               │
 * │   - 仓储管理系统发出拣货指令                                             │
 * │   - 扫描包裹并记录打包时间                                               │
 * │   - 通知物流公司取件                                                     │
 * │ • 用户可见：                                                             │
 * │   - 订单状态变为"配货中"                                                 │
 * │   - 可查看打包进度                                                       │
 * ├──────────────────────────────────────────────────────────────────────────────┤
 * │ ORDER_EXPRESS (3, "出库成功")                                                │
 * │ • 定义：商品已交给物流公司，正在运输途中                                   │
 * │ • 关键信息：                                                             │
 * │   - 物流公司名称（如顺丰、中通）                                         │
 * │   - 运单号（Tracking Number）                                            │
 * │   - 预计到达时间                                                         │
 * │ • 用户可见：                                                             │
 * │   - 点击可查看物流详情                                                   │
 * │   - 可申请退款/退货（在签收前）                                          │
 * ├──────────────────────────────────────────────────────────────────────────────┤
 * │ ORDER_SUCCESS (4, "交易成功")                                                │
 * │ • 定义：用户确认收货，交易闭环完成                                         │
 * │ • 触发条件：                                                             │
 * │   - 用户在APP/网页点击"确认收货"按钮                                     │
 * │   - 或者物流显示"签收"后系统自动确认（可选配置）                         │
 * │ • 后续操作：                                                             │
 * │   - 完成佣金结算（如有分销商）                                         │
 * │   - 生成评价入口                                                         │
 * │   - 计入销售统计                                                         │
 * │   - 进入售后期（7天无理由退货窗口）                                     │
 * ├──────────────────────────────────────────────────────────────────────────────┤
 * │ ORDER_CLOSED_BY_MALLUSER (-1, "手动关闭")                                    │
 * │ • 定义：用户主动申请取消订单                                               │
 * │ • 触发时机：                                                             │
 * │   - 在待支付状态下单期内用户主动取消                                     │
 * │   - 或在支付完成后（需商家同意退款）                                     │
 * │ • 退款处理：                                                             │
 * │   - 原路退回（支付宝/微信）                                              │
 * │   - 需要审核（若已发货）                                                 │
 * ├──────────────────────────────────────────────────────────────────────────────┤
 * │ ORDER_CLOSED_BY_EXPIRED (-2, "超时关闭")                                     │
 * │ • 定义：用户下单后在规定时间内未支付，系统自动取消订单                     │
 * │ • 超时时长：                                                             │
 * │   - 通常设置为30分钟~1小时（可由Constants或其他配置调整）                  │
 * │ • 业务意义：                                                             │
 * │   - 避免库存长时间被锁定而不释放                                       │
 * │   - 提高库存周转率                                                       │
 * │ • 提醒：                                                                │
 * │   - 部分系统会在超时前5分钟发送短信/Push通知提醒                           │
 * ├──────────────────────────────────────────────────────────────────────────────┤
 * │ ORDER_CLOSED_BY_JUDGE (-3, "商家关闭")                                       │
 * │ • 定义：因商品缺货、违规等原因由商家强制关闭订单                           │
 * │ • 常见场景：                                                             │
 * │   - 用户付款后发现商品已下架或库存不足                                   │
 * │   - 商品描述与实际不符，需要撤销交易                                     │
 * │ • 影响：                                                                 │
 * │   - 必须全额退款                                                         │
 * │   - 可能触发用户对店铺的投诉                                             │
 * ├──────────────────────────────────────────────────────────────────────────────┤
 * 【状态流转的核心业务逻辑】
 *
 * 1. 【新建订单】→ 状态自动设为 ORDER_PRE_PAY(0)
 *    - 代码位置：OrderController.saveOrder() → orderService.saveOrder()
 *
 * 2. 【支付成功回调】→ 状态从 PRE_PAY(0) 更新为 PAID(1)
 *    - 代码位置：OrderController.paySuccess() → orderService.paySuccess()
 *    - 同时更新时间戳pay_time
 *
 * 3. 【库存检查与扣减】→ 在支付成功后执行库存扣减
 *    - 注意：部分设计是支付时预占库存，支付成功后正式扣减
 *
 * 4. 【仓库配货】→ 状态从 PAID(1) 更新为 PACKAGED(2)
 *    - 后台管理员操作：admin/orders/updateStatus
 *
 * 5. 【物流出库】→ 状态从 PACKAGED(2) 更新为 EXPRESS(3)
 *    - 录入运单号后触发
 *
 * 6. 【用户确认收货】→ 状态从 EXPRESS(3) 更新为 SUCCESS(4)
 *    - 点击"确认收货"按钮
 *
 * 7. 【自动超时关闭】定时任务扫描 PRE_PAY(0) 且创建时间超过阈值的订单 → 关闭为 EXPIRED(-2)
 *
 * 8. 【用户主动取消】PRE_PAY(0) 状态下用户点击"取消订单" → 关闭为 USER_CLOSE(-1)
 *
 * 9. 【商家关闭】PAID(1) 及以上状态因缺货无法发货 → 关闭为 JUDGE_CLOSE(-3)
 *
 * 【在代码中的典型使用方式】
 *
 * // 判断订单是否可以取消（只有待支付状态下允许用户自己取消）
 * if (order.getOrderStatus() == NewBeeMallOrderStatusEnum.ORDER_PRE_PAY.getOrderStatus()) {
 *     orderService.cancelOrder(orderNo, userId);
 * }
 *
 * // 判断订单是否已完成交易，可以进入评价环节
 * if (order.getOrderStatus() == NewBeeMallOrderStatusEnum.ORDER_SUCCESS.getOrderStatus()) {
 *     showReviewForm();
 * }
 *
 * // 查询用户的所有订单时，过滤掉已关闭的不重要的订单（可选）
 * List<Order> list = orderMapper.selectByUserIdWithStatus(userId, Arrays.asList(
 *     NewBeeMallOrderStatusEnum.ORDER_PRE_PAY.getOrderStatus(),
 *     NewBeeMallOrderStatusEnum.ORDER_PAID.getOrderStatus(),
 *     NewBeeMallOrderStatusEnum.ORDER_SUCCESS.getOrderStatus()
 * ));
 *
 * // 状态机转换时的安全检查（防止非法状态跳转）
 * public boolean transitionOrderStatus(Order order, int newStatus) {
 *     int current = order.getOrderStatus();
 *     // 只允许合理的路径：0->1->2->3->4，或任意状态到关闭状态
 *     if ((current == 0 && newStatus == 1) || // 支付
 *         (current == 1 && newStatus == 2) || // 配货
 *         (current == 2 && newStatus == 3) || // 出库
 *         (current == 3 && newStatus == 4) || // 收货
 *         (newStatus <= 0 && newStatus >= -3)) { // 任何状态都可以关闭
 *         return true;
 *     }
 *     return false;
 * }
 *
 * 【数据库设计关联】
 * - NewBeeMallOrder 表的 order_status 字段存储的是该枚举的int值（integer类型）
 * - 建议建立索引 order_status 以加速按状态查询
 * - 配合 create_time 字段可做超时关闭任务的筛选
 * =====================================================================
 */
package ltd.newbee.mall.common;

/**
 * @author 13
 * @qq交流群 796794009
 * @email 2449207463@qq.com
 * @link https://github.com/newbee-ltd
 *
 * @apiNote 订单状态枚举
 *          定义订单在生命周期中经历的各种状态，包括正常流转和异常关闭
 */
public enum NewBeeMallOrderStatusEnum {

    DEFAULT(-9, "ERROR"),                 // 默认/错误值，不用于实际订单状态
    ORDER_PRE_PAY(0, "待支付"),           // 用户下单后等待支付
    ORDER_PAID(1, "已支付"),              // 支付成功
    ORDER_PACKAGED(2, "配货完成"),        // 仓库打包完成
    ORDER_EXPRESS(3, "出库成功"),         // 商品已发出，物流运输中
    ORDER_SUCCESS(4, "交易成功"),         // 用户确认收货
    ORDER_CLOSED_BY_MALLUSER(-1, "手动关闭"), // 用户主动取消
    ORDER_CLOSED_BY_EXPIRED(-2, "超时关闭"),   // 超过支付时限未付自动关闭
    ORDER_CLOSED_BY_JUDGE(-3, "商家关闭");      // 商家因缺货等原因强制关闭

    private int orderStatus; // 订单状态的数值编码（对应数据库NewBeeMallOrder.order_status字段）
    private String name;     // 订单状态的中文名称（便于前端展示和日志记录）

    /**
     * 构造方法：初始化状态编码和名称
     *
     * @param orderStatus 状态的整数值，存储到数据库
     * @param name 状态的中文描述文本
     */
    NewBeeMallOrderStatusEnum(int orderStatus, String name) {
        this.orderStatus = orderStatus;
        this.name = name;
    }

    /**
     * 【功能】根据订单状态数值获取对应的枚举对象
 *
 * 【用途】
 * ① 从数据库读取订单时，将order_status字段的int值还原为枚举
 * ② 业务逻辑中进行状态判断（if (status == ORDER_SUCCESS)）
 * ③ 安全兜底：无效状态返回DEFAULT
 *
 * @param orderStatus 待转换的状态数值（-3至4之间的整数）
 * @return 对应的 NewBeeMallOrderStatusEnum 枚举对象，不存在返回 DEFAULT
 */
    public static NewBeeMallOrderStatusEnum getNewBeeMallOrderStatusEnumByStatus(int orderStatus) {
        for (NewBeeMallOrderStatusEnum newBeeMallOrderStatusEnum : NewBeeMallOrderStatusEnum.values()) {
            if (newBeeMallOrderStatusEnum.getOrderStatus() == orderStatus) {
                return newBeeMallOrderStatusEnum;
            }
        }
        return DEFAULT; // 状态码无效，返回默认值
    }

    /**
     * 【功能】获取订单状态的数值编码
 *
 * 【用途】
 * ① 存入数据库时调用（OrderMapper插入时）
 * ② 与其他系统集成时传输状态标识
 * ③ 直接比较：if (order.getOrderStatus() == NewBeeMallOrderStatusEnum.ORDER_PRE_PAY.getOrderStatus())
 *
 * @return 订单状态的整数值
     */
    public int getOrderStatus() {
        return orderStatus;
    }

    /**
     * 【功能】设置订单状态的数值编码（仅供框架兼容使用）
 *
 * 【说明】枚举属性理论上不可变，set方法仅用于MyBatis等框架的反序列化反射赋值
 */
    public void setOrderStatus(int orderStatus) {
        this.orderStatus = orderStatus;
    }

    /**
     * 【功能】获取订单状态的中文名称
 *
 * 【用途】
 * ① 前端模板直接显示给用户（如 <span th:text="${orderStatusString}"/>）
 * ② 日志记录时输出人类可读的信息
 * ③ API响应中包含状态描述时使用
 *
 * @return 状态的中文描述
     */
    public String getName() {
        return name;
    }

    /**
     * 【功能】设置订单状态的中文名称（仅供框架兼容使用）
 */
    public void setName(String name) {
        this.name = name;
    }
}
