/**
 * =====================================================================
 * PayTypeEnum.java - 支付方式枚举类
 * 【项目背景】
 * 现代电商系统通常支持多种支付方式，以满足不同用户的支付习惯和需求。
 * Newbee Mall（新蜂商城）目前主要支持支付宝和微信支付两种主流第三方支付平台。
 * PayTypeEnum 枚举清晰地定义了所有可用的支付选项及其对应的编码值。
 *
 * 【核心功能与设计目标】
 * 1. 统一支付方式表示：避免在不同模块中使用不同的字符串或数字表示同一支付方式
 * 2. 便于扩展：未来增加微信支付、银联、PayPal等只需添加新枚举值
 * 3. 与数据库字段对齐：payment_type字段存储的是该枚举的int编码值
 * 4. 前端友好：配合payTypeString字段可直接显示"支付宝"/"微信支付"等中文提示
 *
 * 【支付方式的业务语义详解】
 *
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │ PAYMENT_MODE_DEFAULT (-1, "ERROR") : 默认/未设置状态                    │
 * ├─────────────────────────────────────────────────────────────────────────┤
 * │ • 用途：初始化对象时的占位值，或数据库中payment_type为NULL时的映射     │
 * │ • 绝不可能作为用户实际选择的支付方式出现在订单中                      │
 * │ • 业务逻辑：如果订单的payType是DEFAULT，说明支付尚未完成或未选择        │
 * │   （实际上ORDER_PRE_PAY时payType可能也是NOT_PAY，直到用户选择了支付方式│
 * │    才更新为具体支付类型）                                            │
 * │                                                                  │
 * │ PAYMENT_MODE_NOT_PAY (0, "无") : 未选择支付方式                        │
 * ├─────────────────────────────────────────────────────────────────────────┤
 * │ • 定义：用户尚未选择任何支付方式                                     │
 * │ • 使用场景：                                                         │
 * │   - 购物车结算页生成订单暂存时                                       │
 * │   - 用户在支付选择页尚未点击按钮前                                   │
 * │   - 订单创建阶段                                                   │
 * │ • 业务流转：                                                       │
 * │   order.payType = NOT_PAY → 用户选择支付宝/微信 → order.payType = ALI_/pay/weixin_pay │
 * │                                                                    │
 * │ PAYMENT_MODE_ALI_PAY (1, "支付宝") : 支付宝支付                        │
 * ├─────────────────────────────────────────────────────────────────────────┤
 * │ • 定义：用户选择支付宝作为支付方式                                   │
 * │ • 技术实现：                                                       │
 * │   - 后端调用支付宝开放平台的统一下单API                              │
 * │   - 生成支付参数（sign、timestamp、partner等）                         │
 *   - 前端跳转到支付宝收银台                                          │
 * │ • 回调处理：                                                       │
 *   - 支付宝发起POST请求到paySuccess接口                                 │
 *   - 验证签名成功后更新订单状态为PAY_SUCCESS                          │
 *   - payType保持为ALI_PAY不变                                         │
 * │                                                                  │
 * │ PAYMENT_MODE_WEIXIN_PAY (2, "微信支付") : 微信支付                     │
 * ├─────────────────────────────────────────────────────────────────────────┤
 * │ • 定义：用户选择微信支付作为支付方式                                 │
 * │ • 技术实现：                                                       │
 *   - 后台生成微信支付JSAPI或扫码支付参数                               │
 *   - 前端唤起微信H5支付或小程序支付                                  │
 * │ • 回调处理：                                                       │
 *   - 微信服务器发送通知到paySuccess接口                                │
 *   - 验签后更新订单状态                                               │
 * │                                                                  │
 * 【在系统中的完整使用流程】
 *
 * 用户在商品详情→加入购物车→去结算的流程中：
 *
 * Step 1: 用户提交订单暂存 (OrderController.saveOrder)
 *   • 此时订单状态为 ORDER_PRE_PAY (0)
 *   • 订单的 payType = NOT_PAY (0)
 *   • payStatus = PAY_ING (0)
 *
 * Step 2: 用户选择支付页面 (OrderController.selectPayType)
 *   • GET /selectPayType?orderNo=xxx
 *   • 校验订单属于当前用户且状态为待支付
 *   • 跳转到 pay-select.html 模板
 *
 * Step 3: 用户进入支付页面 (OrderController payPage)
 *   • GET /payPage?orderNo=xxx&payType=1  // 1代表支付宝
 *   • 或 GET /payPage?orderNo=xxx&payType=2  // 2代表微信
 *   • 条件检查通过后，重定向到对应模板: mall/alipay.html 或 mall/wxpay.html
 *
 * Step 4: 用户在支付页面完成支付操作
 *   - 支付宝页面：确认付款，授权扣款
 *   - 微信支付页面：输入密码确认
 *
 * Step 5: 支付成功回调 (OrderController paySuccess)
 *   • GET /paySuccess?orderNo=xxx&payType=2
 *   • 调用 orderService.paySuccess(orderNo, payType)
 *   • Service层执行：
 *       a. 查询订单，验证payType是否匹配
 *       b. 验证支付结果（如调用支付平台二次验签）
 *       c. 若验证通过，设置 payStatus = PAY_SUCCESS (1)
 *       d. 保存订单变更
 *   • Controller返回 ResultGenerator.genSuccessResult() {code:200}
 *
 * Step 6: 前端跳转至订单详情页
 *   • 重定向到 /orders/{orderNo}
 *   • 此时订单显示"已支付"状态
 *
 * 【与相关类的关系】
 *
 * 1. NewBeeMallOrder 实体类：
 *    - payType 字段存储 PayTypeEnum 的编码值 (0/1/2)
 *    - payStatus 字段存储 PayStatusEnum 的值
 *
 * 2. NewBeeMallOrderDetailVO 视图对象：
 *    - payTypeByte: 原始的byte类型支付类型编码
 *    - payTypeString: 转换后的字符串描述（如"微信支付"），由controller层填充
 *      String payTypeStr = PayTypeEnum.getPayTypeEnumByType(payType).getName();
 *
 * 3. PayStatusEnum：
 *    - 两者区别：PayType是"通过什么渠道付的钱"，PayStatus是"钱是否到账了"
 *    - 组合示例：
 *      支付中：payType=AL(1), payStatus=PAY_ING(0)
 *      支付成功：payType=AL(1), payStatus=PAY_SUCCESS(1)
 *
 * 【在代码中的典型用法】
 *
 * ① 在Controller判断支付方式，跳转到对应模板
 * public String payOrder(@RequestParam("orderNo") String orderNo, @RequestParam("payType") int payType) {
 *     if (payType == 1) { // ALI_PAY
 *         return "mall/alipay";
 *     } else if (payType == 2) { // WEIXIN_PAY
 *         return "mall/wxpay";
 *     }
 *     return "redirect:/selectPayType?orderNo=" + orderNo;
 * }
 *
 * ② 在服务层根据支付类型调用不同的SDK
 * public String paySuccess(String orderNo, int payType) {
 *     Order order = getOrderByNo(orderNo);
 *     PayTypeEnum type = PayTypeEnum.getPayTypeEnumByType(payType);
 *     switch (type) {
 *         case ALI_PAY:
 *             return alipayService.verifyCallback(orderNo, request);
 *         case WEIXIN_PAY:
 *             return weixinService.verifyCallback(orderNo, request);
 *         default:
 *             return ServiceResultEnum.PAY_ING.getResult(); // 错误状态
 *     }
 * }
 *
 * ③ 在前端模板中根据payType渲染不同的JavaScript
 * <!-- 在 mall/alipay.html 中 -->
 <script>
 var alipayParams = ${JSON.stringify(alipayParams)}; // 后端生成的支付宝支付参数
 Alipay.init(alipayParams);
 </script>
 *
 * <!-- 在 mall/wxpay.html 中 -->
 <script>
 var weixinConfig = ${JSON.stringify(weixinConfig)}; // 后端生成的微信支付JSSDK配置
 WeixinPay.init(weixinConfig);
 </script>
 *
 * 【扩展性考虑】
 * 如果未来需要增加新的支付方式（如Apple Pay、云闪付、银行直连等），只需：
 * 1. 在 PayTypeEnum 中添加新的枚举项（如 APPLE_PAY(3, "Apple Pay")）
 * 2. 修改 payPage 方法中的 if-else 分支，增加新类型的模板跳转
 * 3. 在 paySuccess 回调中增加对应平台的验签逻辑
 * 4. 创建新的支付模板文件（如 mall/applepay.html）
 * 5. 无需修改数据库表结构（只需确保 payType 字段能容纳新的整数值）
 *
 * ⚠️ 重要注意事项：
 * - 支付类型的变更（如从支付宝改为微信支付）发生在支付选择阶段，一旦支付成功不可更改
 * - payType 一旦确定，必须与后续支付平台的回调所携带的参数一致，否则验签会失败
 * - 测试环境建议使用支付宝沙箱和微信支付测试号，避免真实扣款
 * =====================================================================
 */
package ltd.newbee.mall.common;

/**
 * @author 13
 * @qq交流群 796794009
 * @email 2449207463@qq.com
 * @link https://github.com/newbee-ltd
 *
 * @apiNote 支付类型枚举
 *          定义用户可以选择的不同支付渠道，包括未支付、支付宝、微信支付等。
 */
public enum PayTypeEnum {

    DEFAULT(-1, "ERROR"),           // 默认/错误值，表示未设置或无效状态
    NOT_PAY(0, "无"),               // 尚未选择支付方式（订单创建阶段）
    ALI_PAY(1, "支付宝"),           // 支付宝支付通道
    WEIXIN_PAY(2, "微信支付");      // 微信支付通道

    private int payType; // 支付类型的数值编码（int型，对应数据库NewBeeMallOrder.payment_type字段）
    private String name; // 支付类型的中文名称（便于前端展示和日志记录）

    /**
     * 构造方法：初始化支付类型编码和名称
     *
     * @param payType 支付类型的整数值（-1/0/1/2），存储到数据库
     * @param name 支付类型的中文描述文本（如"支付宝""微信支付"）
     */
    PayTypeEnum(int payType, String name) {
        this.payType = payType;
        this.name = name;
    }

    /**
     * 【功能】根据支付类型编码获取对应的PayTypeEnum枚举对象
 *
 * 【用途】
 * ① 将数据库中的payType整数值转换为枚举对象进行业务判断
 * ② 在switch/case或if-else判断中选择不同支付通道的处理逻辑
 * ③ 安全兜底：无效的编码返回DEFAULT，防止空指针异常
 *
 * 【使用示例】
 * // 从订单对象获取支付类型
 * PayTypeEnum payType = PayTypeEnum.getPayTypeEnumByType(order.getPayType());
 *
 * // 判断支付方式并做不同处理
 * if (payType == PayTypeEnum.ALI_PAY) {
 *     // 处理支付宝相关的逻辑
 * } else if (payType == PayTypeEnum.WEIXIN_PAY) {
 *     // 处理微信支付相关的逻辑
 * }
 *
 * @param payType 待查找的支付类型编码（-1/0/1/2）
 * @return 对应的 PayTypeEnum 枚举对象，找不到返回 DEFAULT
 */
    public static PayTypeEnum getPayTypeEnumByType(int payType) {
        for (PayTypeEnum payTypeEnum : PayTypeEnum.values()) {
            if (payTypeEnum.getPayType() == payType) {
                return payTypeEnum;
            }
        }
        return DEFAULT; // 支付类型码无效，返回默认值
    }

    /**
     * 【功能】获取支付类型的数值编码
 *
 * 【用途】
 * ① 存入数据库时需要将枚举转为int值
 * ② 与前端传递的参数进行比较（如 payPage 方法中接收 payType 参数）
 * ③ switch-case判断的基础
 *
 * @return 支付类型的整数值
     */
    public int getPayType() {
        return payType;
    }

    /**
     * 【功能】设置支付类型的数值编码（仅供框架兼容使用）
 *
 * 【说明】枚举属性原则上不应被修改，set方法仅用于MyBatis等框架的反序列化反射赋值。
 * 业务代码中建议在创建新对象时就指定正确的类型，而不是先创建再set。
 */
    public void setPayType(int payType) {
        this.payType = payType;
    }

    /**
     * 【功能】获取支付类型的文本描述
 *
 * 【用途】
 * ① 在前端直接显示给用户（如"您选择的支付方式是：支付宝"）
 * ② 日志记录时输出可读的支付方式信息
 * ③ API响应中包含支付方式名称时使用
 *
 * @return 支付类型的中文名称（如"支付宝"）
     */
    public String getName() {
        return name;
    }

    /**
     * 【功能】设置支付类型的文本描述（仅供框架兼容使用）
 */
    public void setName(String name) {
        this.name = name;
    }
}
