/**
 * =====================================================================
 * 严肃声明：
 * 开源版本请务必保留此注释头信息，若删除我方将保留所有法律责任追究！
 * 本系统已申请软件著作权，受国家版权局知识产权以及国家计算机软件著作权保护！
 * 可正常分享和学习源码，不得用于违法犯罪活动，违者必究！
 * Copyright (c) 2019-2020 十三 all rights reserved.
 * 版权所有，侵权必究！
 * =====================================================================
 */
package ltd.newbee.mall.common;

/**
 * =====================================================================
 * PayStatusEnum.java - 支付状态枚举类
 *
 * 【功能描述】
 * 本枚举类用于定义订单在整个支付流程中可能经历的各种支付状态。
 * 这是电商系统中核心业务状态之一，直接影响交易流程的正常进行。
 *
 * 【主要用处】
 * 1. 统一支付状态的标准化表示，避免使用魔法数字（如直接用0/1）
 * 2. 在数据库存储时作为字段值，提高数据可读性和一致性
 * 3. 在服务层逻辑判断中使用，例如判断是否需要发起退款、是否可以发货等
 * 4. 在前端展示时通过映射将编码转换为人性化的文字提示（"支付中"）
 * 5. 与第三方支付平台（支付宝/微信）回调交互时的状态映射
 *
 * 【使用场景举例】
 * - 用户提交订单后，状态设为PAY_ING(0)，等待第三方回调确认
 * - 支付成功后，回调接口更新为PAY_SUCCESS(1)
 * - 支付失败或超时时，设为DEFAULT(-1)或其他错误状态
 *
 * 【枚举值说明】
 * DEFAULT(-1, "支付失败")   : 默认错误状态，表示支付未成功或发生错误
 * PAY_ING(0, "支付中")     : 用户已发起支付，正在等待平台验证（如支付宝跳转页面后的回调中）
 * PAY_SUCCESS(1, "支付成功"): 支付验证已通过，款项已确认到账
 * =====================================================================
 */
public enum PayStatusEnum {

    DEFAULT(-1, "支付失败"),       // 默认值，表示支付失败或未设置有效状态
    PAY_ING(0, "支付中"),          // 支付进行中
    PAY_SUCCESS(1, "支付成功");    // 支付已完成且验证通过

    private int payStatus; // 支付状态的数值编码（整数类型，便于数据库存储和索引）
    private String name;   // 支付状态的文本描述（用于前端显示、日志记录等）

    /**
     * 构造方法：初始化支付状态的数值编码和文本描述
     *
     * @param payStatus 支付状态的整数编码（-1/0/1），这是存储到数据库的实际值
     * @param name 支付状态的中文文本描述（如"支付中""支付成功"），便于阅读和展示
     */
    PayStatusEnum(int payStatus, String name) {
        this.payStatus = payStatus;
        this.name = name;
    }

    /**
     * 【功能】根据支付状态数值编码获取对应的枚举对象
 * 【用途】在接收到数据库返回的整数状态码时，转换为枚举类型进行逻辑判断
 * 【用法示例】
 *   PayStatusEnum status = PayStatusEnum.getPayStatusEnumByStatus(order.getPayStatus());
 *   if (status == PayStatusEnum.PAY_SUCCESS) {
 *       // 执行发货逻辑
 *   }
 *
 * @param payStatus 待转换的支付状态编码（-1/0/1）
 * @return 对应的 PayStatusEnum 枚举对象。如果编码不存在，返回 DEFAULT（默认失败状态）
 */
    public static PayStatusEnum getPayStatusEnumByStatus(int payStatus) {
        for (PayStatusEnum payStatusEnum : PayStatusEnum.values()) {
            if (payStatusEnum.getPayStatus() == payStatus) {
                return payStatusEnum;
            }
        }
        return DEFAULT; // 找不到有效的状态，默认为失败
    }

    /**
     * 【功能】获取支付状态的数值编码
 * 【用途】当需要将枚举值存入数据库或传递给其他需要整数的系统时使用
 * 【用法示例】
 *   int statusInt = currentStatus.getPayStatus(); // 结果为 0/1 或 -1
 *
 * @return 支付状态的整数编码
     */
    public int getPayStatus() {
        return payStatus;
    }

    /**
     * 【功能】设置支付状态的数值编码（不推荐使用，枚举应是不可变的）
 * 【注意】枚举类型的属性通常是final且只读的，set方法仅出于兼容MyBatis反序列化需求而存在
 * 【实际使用中不建议调用此方法，直接通过枚举常量本身访问属性
     */
    public void setPayStatus(int payStatus) {
        this.payStatus = payStatus;
    }

    /**
     * 【功能】获取支付状态的文本描述
 * 【用途】在前端模板中显示给用户、记录日志、生成报告等需要人类可读文本的场景
 * 【用法示例】
 *   String statusText = currentStatus.getName(); // 返回 "支付中" / "支付成功"
 *   String msg = "您的订单当前状态为：" + statusText;
 *
 * @return 支付状态的中文名称
     */
    public String getName() {
        return name;
    }

    /**
     * 【功能】设置支付状态的文本描述（不推荐使用）
 * 【同上】枚举属性一般不应被修改，仅保留set方法是为了框架兼容性
     */
    public void setName(String name) {
        this.name = name;
    }
}
