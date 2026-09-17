/**
 * =====================================================================
 * ServiceResultEnum.java - 服务结果状态枚举类
 * 【项目背景】
 * 在电商系统的各个模块（商品、订单、购物车、用户管理等）中，各种操作都会产生不同的结果：
 * - 成功（如注册成功、支付成功）
 * - 失败但原因明确（如库存不足、用户名已存在、验证码错误）
 * - 系统错误（如数据库连接失败）
 *
 * 为了统一这些返回结果的信息格式和项目各部门的沟通语言，引入了 ServiceResultEnum。
 * 这个枚举定义了数百种业务场景的结果状态码和对应消息，实现"一处定义，处处使用"。
 *
 * 【核心设计理念】
 * 1. 统一性：所有业务操作返回的结果都通过这个枚举定义的消息进行标准化
 * 2. 可维护性：需要修改错误提示文字时，只需改枚举值，无需到处搜索替换
 * 3. 国际化友好：枚举名是英文，消息是中文，未来容易扩展为多语言版本
 * 4. 类型安全：相比使用字符串常量，枚举提供了编译时的检查
 *
 * 【枚举值的分类详解】
 *
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │ 分类一：基础状态标识                                                 │
 * ├─────────────────────────────────────────────────────────────────────────┤
 * │ ERROR("error")         : 通用错误标识                                  │
 * │  用途：返回JSON结果的result字段，表示操作失败                         │
 * │  对应前端：显示红色警告                                             │
 * │                                                                  │
 * │ SUCCESS("success")     : 通用成功标识                                │
 * │  用途：表示操作成功完成                                             │
 * │  对应前端：显示绿色提示                                             │
 * └─────────────────────────────────────────────────────────────────────────┘
 *
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │ 分类二：数据查询相关                                                │
 * ├─────────────────────────────────────────────────────────────────────────┤
 * │ DATA_NOT_EXIST("未查询到记录！") ：                                 │
 * │  用途：查询操作找不到对应数据时使用                                 │
 * │  典型场景：删除不存在的分类、查询不存在的订单详情                    │
 * │                                                                  │
 * │ GOODS_CATEGORY_ERROR("分类数据异常！"):                               │
 * │  用途：分类层级关系出现异常时使用（如二级分类指向不存在的父分类）     │
 * │                                                                  │
 * │ GOODS_NOT_EXIST("商品不存在！"):                                     │
 * │  用途：查询商品ID无效或商品已被删除时使用                            │
 * └─────────────────────────────────────────────────────────────────────────┘
 *
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │ 分类三：唯一性校验（重复检测）                                      │
 * ├─────────────────────────────────────────────────────────────────────────┤
 * │ SAME_CATEGORY_EXIST("已存在同级同名的分类！"):                        │
 * │  用途：创建/修改分类时，检查是否已有同级别且同名的分类                │
 * │  业务逻辑：同一父分类下不允许有两个名称相同的子分类                   │
 * │                                                                  │
 * │ SAME_LOGIN_NAME_EXIST("用户名已存在！"):                             │
 * │  用途：用户注册时检查用户名是否已被占用                              │
 * │  位置：UserService.register() 中调用                                │
 * │                                                                  │
 * │ SAME_GOODS_EXIST("已存在相同的商品信息！"):                          │
 * │  用途：添加商品时检查SKU重复（如条形码、商品编码等唯一键）            │
 * │                                                                  │
 * │ SAME_INDEX_CONFIG_EXIST("已存在相同的首页配置项！"):                  │
 * │  用途：添加首页配置时检查type是否已存在（每种type只能有一条配置）     │
 * └─────────────────────────────────────────────────────────────────────────┘
 *
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │ 分类四：登录与权限验证                                              │
 * ├─────────────────────────────────────────────────────────────────────────┤
 * │ LOGIN_NAME_NULL("请输入登录名！"):                                   │
 * │  用途：用户登录时用户名未填写的校验错误                              │
 * │                                                                  │
 * │ LOGIN_PASSWORD_NULL("请输入密码！"):                                 │
 * │  用途：用户登录时密码未填写的校验错误                                │
 * │                                                                  │
 * │ LOGIN_VERIFY_CODE_NULL("请输入验证码！"):                            │
 * │  用途：登录/注册时验证码未填写的错误                                │
 * │                                                                  │
 * │ LOGIN_VERIFY_CODE_ERROR("验证码错误！"):                             │
 * │  用途：用户输入的验证码与session中存储的不匹配                       │
 * │                                                                  │
 * │ LOGIN_ERROR("登录失败！"):                                           │
 * │  用途：用户名密码组合错误的通用错误（具体原因已记录日志）             │
 * │                                                                  │
 * │ LOGIN_USER_LOCKED("用户已被禁止登录！"):                             │
 * │  用途：管理员冻结了该用户的登录权限                                 │
 * │                                                                  │
 * │ NO_PERMISSION_ERROR("无权限！"):                                     │
 * │  用途：当前用户没有执行该操作的权限（如普通用户访问后台API）          │
 * │  典型场景：非admin用户访问/admin/*接口，或查看他人订单                │
 * └─────────────────────────────────────────────────────────────────────────┘
 *
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │ 分类五：购物车与商品数量限制                                        │
 * ├─────────────────────────────────────────────────────────────────────────┤
 * │ SHOPPING_CART_ITEM_LIMIT_NUMBER_ERROR("超出单个商品的最大购买数量！"):│
 * │  用途：添加到购物车的数量超过了单个商品限制（Constants.SHOPPING_...）│
 * │                                                                  │
 * │ SHOPPING_CART_ITEM_TOTAL_NUMBER_ERROR("超出购物车最大容量！"):       │
 * │  用途：购物车中的商品种类总数超过了限制（最多13种）                 │
 * └─────────────────────────────────────────────────────────────────────────┘
 *
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │ 分类六：订单相关操作                                                │
 * ├─────────────────────────────────────────────────────────────────────────┤
 * │ ORDER_NOT_EXIST_ERROR("订单不存在！"):                               │
 * │  用途：查询订单时订单号无效或已被删除                               │
 * │                                                                  │
 * │ ORDER_ITEM_NOT_EXIST_ERROR("订单项不存在！"):                        │
 * │  用途：获取订单详情时某个订单项丢失                                 │
 * │                                                                  │
 * │ ORDER_PRICE_ERROR("订单价格异常！"):                                 │
 * │  用途：计算订单总价时发现价格不一致（商品售价变动等）               │
 * │                                                                  │
 * │ ORDER_GENERATE_ERROR("生成订单异常！"):                              │
 * │  用途：创建订单过程中发生错误（如扣减库存失败）                     │
 * │                                                                  │
 * │ ORDER_STATUS_ERROR("订单状态异常！"):                                │
 * │  用途：在当前状态下无法执行该操作（如对已完成的订单再次取消）        │
 * │                                                                  │
 * │ CLOSE_ORDER_ERROR("关闭订单失败！"):                                 │
 * │  用途：尝试关闭订单但失败（可能因状态不允许或数据库更新失败）        │
 * └─────────────────────────────────────────────────────────────────────────┘
 *
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │ 分类七：地址与库存                                                  │
 * ├─────────────────────────────────────────────────────────────────────────┤
 * │ NULL_ADDRESS_ERROR("地址不能为空！"):                                │
 * │  用途：用户下单前未设置收货地址                                     │
 * │  触发点：OrderController.saveOrder() 中检查 user.getAddress()          │
 * │                                                                  │
 * │ SHOPPING_ITEM_COUNT_ERROR("库存不足！"):                             │
 * │  用途：下单时商品实际库存少于请求数量                               │
 * │  关键校验：生成订单前先扣减库存，如果扣减失败则抛此异常              │
 * └─────────────────────────────────────────────────────────────────────────┘
 *
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │ 分类八：通用操作错误                                                │
 * ├─────────────────────────────────────────────────────────────────────────┤
 * │ OPERATE_ERROR("操作失败！"):                                         │
 * │  用途：各种增删改操作返回的通用失败消息（原因可能是DB约束、网络等）  │
 * │                                                                  │
 * │ DB_ERROR("database error"):                                          │
 * │  用途：数据库层面发生的严重错误（如SQL语法错误、表不存在等）         │
 * │  通常不应直接给用户看到，会在Handler中被捕获并转换为更友好的信息     │
 * └─────────────────────────────────────────────────────────────────────────┘
 *
 * 【实际使用代码示例】
 *
 // 示例1：在Service层抛出业务异常（购物车服务）
 public String saveNewBeeMallCartItem(NewBeeMallShoppingCartItem item) {
     int userId = item.getUserId();
     int goodsId = item.getGoodsId();
     int count = item.getCount();

     // 检查单个商品最大购买数量限制
     if (count > Constants.SHOPPING_CART_ITEM_LIMIT_NUMBER) {
         return ServiceResultEnum.SHOPPING_CART_ITEM_LIMIT_NUMBER_ERROR.getResult();
     }

     // 检查购物车总种类数
     int itemCount = shoppingCartMapper.countByUserIdAndGoodsId(userId, goodsId);
     if (itemCount >= Constants.SHOPPING_CART_ITEM_TOTAL_NUMBER) {
         return ServiceResultEnum.SHOPPING_CART_ITEM_TOTAL_NUMBER_ERROR.getResult();
     }

     // ...执行保存逻辑
     if (insertSuccess) {
         return ServiceResultEnum.SUCCESS.getResult();
     } else {
         return ServiceResultEnum.OPERATE_ERROR.getResult();
     }
 }

 // 示例2：在Controller层检查结果
 @PostMapping("/shop-cart")
 @ResponseBody
 public Result saveItem(@RequestBody NewBeeMallShoppingCartItem item, HttpSession session) {
     String result = shoppingCartService.saveNewBeeMallCartItem(item);

     if (ServiceResultEnum.SUCCESS.getResult().equals(result)) {
         return ResultGenerator.genSuccessResult();
     } else {
         // result 就是枚举的message，如"库存不足！"
         return ResultGenerator.genFailResult(result);
     }
 }

 // 示例3：结合NewBeeMallException使用
 public void checkout(HttpServletRequest request, HttpSession session) {
     NewBeeMallUserVO user = getUserFromSession(session);
     if (StringUtils.isEmpty(user.getAddress())) {
         // 直接抛出异常，被全局异常处理器捕获
         NewBeeMallException.fail(ServiceResultEnum.NULL_ADDRESS_ERROR.getResult());
     }
 }
 *
 * 【调用链关系图】
 * Service层 (业务逻辑)
 *   ↓ 调用
 * ServiceResultEnum.getResult() → 获取字符串消息
 *   ↓
 * Controller层 (返回给前端)
 *   ↓ ResultGenerator.genFailResult(message)
 *   ↓
 * NewBeeMallExceptionHandler (@RestControllerAdvice)
 *   ↓ 判断是否为NewBeeMallException
 *   ↓ 返回JSON或错误页面
 *
 * 【最佳实践建议】
 * 1. 优先使用具体的枚举值（如LOGIN_NAME_NULL），而不是通用的OPERATE_ERROR
 * 2. 枚举消息应该是用户友好的描述，不要暴露技术细节
 * 3. 在Service层返回消息字符串，让Controller决定如何包装返回
 * 4. 对于需要详细日志记录的错误，应该在抛异常前先log.error()记录堆栈
 * =====================================================================
 */
package ltd.newbee.mall.common;

/**
 * @author 13
 * @qq交流群 796794009
 * @email 2449207463@qq.com
 * @link https://github.com/newbee-ltd
 *
 * @apiNote 服务结果枚举类
 *          定义了系统各模块返回的结果状态码和消息，用于统一API响应格式。
 *          每个枚举值代表一种特定的业务处理结果场景。
 */
public enum ServiceResultEnum {

    ERROR("error"),                           // 通用失败状态标识（小写，用于JSON响应）
    SUCCESS("success"),                       // 通用成功状态标识（小写，用于JSON响应）
    DATA_NOT_EXIST("未查询到记录！"),         // 查询操作没有找到目标数据
    SAME_CATEGORY_EXIST("已存在同级同名的分类！"),// 创建/修改分类时检测到重复
    SAME_LOGIN_NAME_EXIST("用户名已存在！"),   // 注册或修改用户名时检测到重复
    LOGIN_NAME_NULL("请输入登录名！"),         // 登录时用户名字段为空
    LOGIN_PASSWORD_NULL("请输入密码！"),       // 登录时密码字段为空
    LOGIN_VERIFY_CODE_NULL("请输入验证码！"),  // 登录或注册时验证码为空
    LOGIN_VERIFY_CODE_ERROR("验证码错误！"),   // 提交的验证码与session中不匹配
    SAME_INDEX_CONFIG_EXIST("已存在相同的首页配置项！"),// 添加首页配置时type重复
    GOODS_CATEGORY_ERROR("分类数据异常！"),    // 分类体系数据不一致或逻辑错误
    SAME_GOODS_EXIST("已存在相同的商品信息！"),// 添加商品时SKU重复
    GOODS_NOT_EXIST("商品不存在！"),           // 商品查询不到或已被删除
    GOODS_PUT_DOWN("商品已下架！"),            // 商品已下架不可访问
    SHOPPING_CART_ITEM_LIMIT_NUMBER_ERROR("超出单个商品的最大购买数量！"),// 购物车单品数量超限
    SHOPPING_CART_ITEM_TOTAL_NUMBER_ERROR("超出购物车最大容量！"),      // 购物车商品种类总数超限
    LOGIN_ERROR("登录失败！"),                 // 用户名或密码不正确
    LOGIN_USER_LOCKED("用户已被禁止登录！"),   // 用户账号被禁用
    ORDER_NOT_EXIST_ERROR("订单不存在！"),      // 订单号无效或已删除
    ORDER_ITEM_NOT_EXIST_ERROR("订单项不存在！"),// 订单中没有该订单项
    NULL_ADDRESS_ERROR("地址不能为空！"),      // 用户未设置收货地址
    ORDER_PRICE_ERROR("订单价格异常！"),       // 商品价格计算不一致
    ORDER_GENERATE_ERROR("生成订单异常！"),     // 创建订单过程出错
    SHOPPING_ITEM_ERROR("购物车数据异常！"),   // 购物车数据不一致或损坏
    SHOPPING_ITEM_COUNT_ERROR("库存不足！"),    // 商品库存不足以支持购买
    ORDER_STATUS_ERROR("订单状态异常！"),      // 当前订单状态不支持该操作
    CLOSE_ORDER_ERROR("关闭订单失败！"),       // 关闭订单操作失败
    OPERATE_ERROR("操作失败！"),                // 通用操作失败（增删改失败）
    NO_PERMISSION_ERROR("无权限！"),             // 权限不足（未登录或非授权角色）
    DB_ERROR("database error");                 // 数据库底层错误

    private String result; // 结果标识符（小写字符串，用于JSON响应体）

    /**
     * 构造方法：初始化结果字符串
     *
     * @param result 结果标识符，通常是英文小写（success/error）或中文提示消息
 * 注意：SUCCESS和ERROR返回英文小写，方便前端程序判断；其他枚举返回中文提示
     */
    ServiceResultEnum(String result) {
        this.result = result;
    }

    /**
     * 【功能】获取结果标识字符串
 *
 * 【用途】
 * ① 从Service层返回给Controller层的判断依据
 * ② 直接作为API响应的错误消息内容
 * ③ 与其他系统进行状态比对
 *
 * @return 该枚举对应的结果字符串（如"success""error""库存不足！"）
     */
    public String getResult() {
        return result;
    }

    /**
     * 【功能】设置结果字符串（仅供框架兼容使用）
 *
 * 【说明】枚举的属性原则上不可变，set方法仅用于MyBatis等框架的反序列化反射赋值。
 * 业务代码中不应调用此方法修改枚举属性。
 */
    public void setResult(String result) {
        this.result = result;
    }
}
