/**
 * =====================================================================
 * NewBeeMallOrderServiceImpl.java - 订单业务逻辑实现类
 * 【项目背景】
 * 订单是电商系统的核心模块，涉及用户下单、支付、发货、退款等全流程。
 * NewBeeMallOrderServiceImpl 实现了订单服务的所有业务逻辑，与数据库持久层（Mapper）协作完成订单管理。
 *
 * 【核心功能】
 *
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │ 功能分类             │ 方法列表                                      │
 * ├─────────────────────────────────────────────────────────────────────────┤
 * │ 分页查询             │ getNewBeeMallOrdersPage()                     │
 * │ 订单信息更新         │ updateOrderInfo()                             │
 * │ 管理员操作 - 配货   │ checkDone()                                   │
 * │ 管理员操作 - 出库    │ checkOut()                                    │
 * │ 管理员操作 - 关闭    │ closeOrder()                                  │
 * │ 用户下单             │ saveOrder() ← 最复杂的核心方法               │
 * │ 订单详情查询         │ getOrderDetailByOrderNo()                     │
 * │ 获取订单基本信息     │ getNewBeeMallOrderByOrderNo()                 │
 * │ 我的订单列表         │ getMyOrders()                                 │
 * │ 取消订单             │ cancelOrder()                                 │
 * │ 确认收货             │ finishOrder()                                 │
 * │ 支付成功回调         │ paySuccess()                                  │
 * │ 订单项查询           │ getOrderItems()                               │
 * │ 库存恢复辅助         │ recoverStockNum()                             │
 * └─────────────────────────────────────────────────────────────────────────┘
 *
 * 【设计模式与特性】
 *
 * 1. @Transactional 注解事务管理：
 *    - saveOrder(), cancelOrder(), closeOrder() 等方法都使用事务保证原子性
 *    - 例如：下单时需同时扣减库存、删除购物车项、创建订单和订单项
 *      任何一个失败则全部回滚，避免数据不一致
 *
 * 2. 状态机校验：
 *    - 所有修改订单状态的方法都会检查当前状态是否允许该操作
 *    - 例如：cancelOrder() 只允许对非已完成/非已关闭的订单执行取消
 *
 * 3. 权限验证：
 *    - 每个操作前都验证 userId 是否匹配，防止用户查看他人订单
 *    - getOrderDetailByOrderNo() 中明确校验：!userId.equals(order.getUserId())
 *
 * 4. 异常处理：
 *    - 使用 NewBeeMallException.fail() 抛出业务异常
 *    - 配合全局异常处理器 NewBeeMallExceptionHandler 统一返回友好提示
 *
 * 【核心业务流程详解】
 *
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * 流程一：用户下单 saveOrder() ← 最核心的业务方法
 *━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 * 输入参数：
 *   - user: NewBeeMallUserVO（从Session获取，包含userId、address）
 *   - myShoppingCartItems: List<NewBeeMallShoppingCartItemVO>（购物车中的商品）
 *
 * 执行步骤：
 *
 * Step ①: 准备数据
 *   - itemIdList: 购物车项ID列表 → 用于后续批量删除
 *   - goodsIds: 商品ID列表 → 用于查询商品信息
 *
 * Step ②: 查询商品信息
 *   newBeeMallGoodsMapper.selectByPrimaryKeys(goodsIds)
 *   → 一次性查所有商品，避免N+1查询问题
 *   → 结果存入newBeeMallGoodsList
 *
 * Step ③: 商品下架检查
 *   过滤出 sellStatus != SELL_STATUS_UP 的商品
 *   如果有，立即抛异常：XXX已下架，无法生成订单
 *   ➤ 防止下单时临时下架商品导致失败
 *
 * Step ④: 构建商品Map (GoodsId -> Goods对象)
 *   Map<Long, NewBeeMallGoods> newBeeMallGoodsMap = ...
 *   用于快速查找商品信息和库存
 *
 * Step ⑤: 库存校验
 *   遍历购物项：
 *     a. 检查商品是否存在于map中（校验数据一致性）
 *     b. 检查购物数量 <= 商品库存 (stockNum)
 *     c. 任一失败即抛异常（SHOPPING_ITEM_ERROR / SHOPPING_ITEM_COUNT_ERROR）
 *   ➤ 防超卖的关键一步！
 *
 * Step ⑥: 删除购物车项（预扣）
 *   newBeeMallShoppingCartItemMapper.deleteBatch(itemIdList)
 *   ➤ 先删购物车，防止用户重复提交同一批商品
 *   ❗注意：这里只是删除记录，并未真正扣减库存
 *
 * Step ⑦: 扣减库存
 *   StockNumDTO列表：从shoppingCartItemVO拷贝得到
 *   newBeeMallGoodsMapper.updateStockNum(stockNumDTOS)
 *   → 实际执行 UPDATE newbee_mall_goods SET stock = stock - ? WHERE goods_id = ?
 *   ➤ 真正的库存扣减在这里完成
 *   → 如果扣减失败（影响行数<1），抛异常并回滚
 *
 * Step ⑧: 生成订单
 *   a. 生成唯一订单号: NumberUtil.genOrderNo()
 *      → 通常是时间戳+随机数组合，保证全局唯一
 *   b. 计算总价: sum(quantity * sellingPrice) [单位：分]
 *      → 重新计算而非从购物车取，防止价格被篡改
 *   c. 创建NewBeeMallOrder对象并设置属性：
 *        - orderNo, userId, address, totalPrice
 *        - extraInfo (预留字段，支付时用)
 *        - status = ORDER_PRE_PAY (待支付)
 *        - payType = NOT_PAY (未选择支付方式)
 *        - payStatus = PAY_ING (支付中)
 *   d. 插入订单: newBeeMallOrderMapper.insertSelective(order)
 *      → useGeneratedKeys=true，生成的主键可回写到orderId
 *
 * Step ⑨: 保存订单项（快照）
 *   遍历原始购物项，创建NewBeeMallOrderItem对象：
 *     - 复制goodsId, goodsCount, goodsName, sellingPrice, goodsCoverImg等
 *     - setOrderId(新生成的订单ID) ← 关键！关联订单
 *   批量插入: newBeeMallOrderItemMapper.insertBatch(items)
 *   ⚡ 为什么要存快照？因为商品价格和名称可能变化，
 *      历史订单需要保持当时的状态不变
 *
 * Step ⑩: 返回结果
 *   如果所有步骤成功，返回orderNo给Controller
 *   Controller跳转到 /orders/{orderNo} 详情页
 *   ➤ 如果任何中间步骤失败，NewBeeMallException会触发事务回滚
 *      之前扣减的库存会被恢复（因为delete和update在同一个事务中）
 *
 * ⚠️ 重要注意事项：
 * - 整个方法在一个@Transactional事务中，数据库操作的原子性有保证
 * - 但库存扣减使用的是普通UPDATE而非锁机制，在高并发下仍可能超卖
 *   （生产环境推荐Redis预扣库存 + 异步补偿方案）
 * - 购物车删除和库存扣减分开两个步骤，中间有时间窗口
 *   （理想做法：先扣减再删，或采用分布式锁）
 *
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * 流程二：订单详情查询 getOrderDetailByOrderNo()
 *━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 * 目的：为前台订单详情页提供完整数据（订单头+订单项+状态文本）
 *
 * 执行步骤：
 *   1. 根据orderNo查询NewBeeMallOrder对象
 *   2. 若不存在 → 抛 ORDER_NOT_EXIST_ERROR 异常
 *   3. 校验userId是否为当前登录用户（防越权访问）
 *   4. 查询该订单的所有订单项 (selectByOrderId)
 *   5. 若订单项为空 → 抛 ORDER_ITEM_NOT_EXIST_ERROR 异常
 *   6. 使用BeanUtil将OrderItem列表转为VO对象列表
 *   7. 创建OrderDetailVO，复制Order的基本属性
 *   8. 补充状态中文描述：
 *        - orderStatusString: 通过枚举转换得到（如"已支付"）
 *        - payTypeString: 通过PayTypeEnum转换得到（如"支付宝"）
 *   9. 设置订单项到新VO
 *   10. 返回完整的VO对象供前端渲染
 *
 * 为什么不用直接查询Order对象？因为：
 * - Order实体不包含状态文本，模板里需要显示中文
 * - 需要将orderItem映射到VO而不是直接暴露Entity
 * - 需要添加额外的payStatusString等信息
 *
 * 【与数据库交互的Mapper调用】
 *
 * ┌──────────────────────┬────────────────────────────────────────────┐
 * │ Mapper方法          │ SQL操作描述                              │
 * ├──────────────────────┼────────────────────────────────────────────┤
 * │ selectByOrderNo     │ SELECT * FROM newbee_mall_order WHERE order_no = ? │
 * │ selectByOrderId     │ SELECT * FROM newbee_mall_order_item WHERE order_id = ? │
 * └──────────────────────┴────────────────────────────────────────────┘
 *
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * 流程三：管理员后台订单操作 (checkDone/checkOut/closeOrder)
 *━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 * 这些方法是面向后台管理员的多状态流转控制，与用户的单步操作不同。
 *
 * checkDone(): 将支付成功的订单(状态=1)置为配货完成(状态=2)
 *   - 验证：订单未被删除且状态必须为1(PAY)
 *   - 调用mapper: checkDone(ids) → UPDATE order SET status=2, update_time=...
 *   - 返回SUCCESS或错误订单号列表
 *
 * checkOut(): 将配货完成的订单(状态=2)置为出库成功(状态=3)
 *   - 验证：订单未被删除且状态∈{1,2}(PACKAGED或EXPRESS前的状态)
 *   - 调用mapper: checkOut(ids) → UPDATE order SET status=3, ...
 *   - 支持状态连续流转的逻辑检查
 *
 * closeOrder(): 关闭订单（如缺货无法发货）
 *   - 验证：订单未被删除且状态不是CLOSED或SUCCESS
 *   - 双重操作：
 *      a) 更新订单状态 = ORDER_CLOSED_BY_JUDGE (-3)
 *      b) 调用recoverStockNum()恢复库存
 *   - 事务性：两个操作要么都成功，要么都失败
 *
 * recoverStockNum(): 库存恢复辅助方法
 *   - 查询订单的订单项，得到每个商品的ID和数量
 *   - 转换为StockNumDTO列表
 *   - 调用goodsMapper.recoverStockNum() 增加库存
 *   - 恢复公式: new_stock = old_stock + quantity
 *
 * 【事务边界的重要性】
 *
 * 以closeOrder为例，为什么必须用@Transactional？
 *
 * 如果没有事务：
 *   Step A: UPDATE order SET status = -3 ... 成功
 *   Step B: UPDATE goods SET stock = stock + qty ... 失败（如数据库连接断开）
 *   结果：订单显示"商家关闭"，但库存没有恢复 → 重复销售风险！
 *
 * 有了事务：
 *   Step A和Step B在同一事务块内
 *   Step B失败 → Step A自动回滚
 *   订单状态和库存保持初始一致状态
 *
 * 【代码细节与技术要点】
 *
 * 1. @Transactional 的传播属性默认是 REQUIRED
 *    - 如果当前已有事务则加入，否则新建一个
 *    - 对于saveOrder这种本身就需原子性的操作很合适
 *
 * 2. rollbackFor 未显式声明 → 默认只对 RuntimeException 回滚
 *    - NewBeeMallException extends RuntimeException → 会回滚
 *    - 如果需要捕获Checked Exception也回滚，应指定 rollbackFor=Exception.class
 *
 * 3. BeanUtil.copyProperties(...)
 *    - 工具类实现字段反射复制，简化VO向Entity的转换
 *    - 注意只复制同名字段，不复制空值（可选配置）
 *
 * 4. NumberUtil.genOrderNo()
 *    - 生成唯一的订单号策略，通常为时间戳+机器码+随机数
 *    - 保证全局唯一性和一定程度的排序性（便于分页查询）
 *
 * 5. Stream API的使用 (Java 8+)
 *    - stream().filter().collect(Collectors.toList())
 *    - 集合筛选、转换更简洁
 *    - groupingBy() 用于按order_id分组订单项
 *
 * 6. 防御性编程
 *    - CollectionUtils.isEmpty() 判空比 null != null 更安全
 *    - StringUtils.hasText() 判断字符串非null且非空白
 *    - 多重条件校验避免空指针
 *
 * 7. 状态码的硬编码与枚举对照
 *    - 多处直接使用数字常量（如1, 2, 3）而非枚举值
 *    - 建议改进：全部替换为 NewBeeMallOrderStatusEnum.ORDER_PAID.getOrderStatus()
 *      这样可读性更好，且不会因枚举值变更而出错
 *
 * 示例改进：
 * // 原代码：
 * if (newBeeMallOrder.getOrderStatus() != 1) { ... }
 * // 改进后：
 * if (newBeeMallOrder.getOrderStatus() != NewBeeMallOrderStatusEnum.ORDER_PAID.getOrderStatus()) { ... }
 *
 * 【性能优化建议】
 *
 * 1. 批量操作已使用 insertBatch/deleteBatch 等批量方法 ✓
 * 2. 查询使用分页 PageQueryUtil ✓
 * 3. 库存扣减未使用批处理，每次saveOrder都有单个update
 *    → 可以考虑合并为单次 UPDATE ... WHERE id IN (...) 语句
 * 4. 订单详情查询先查Order再查OrderItem，属于两次DB请求
 *    → 可以用JOIN一次性查询，但需要考虑序贯读取的清晰度
 *
 * 【测试用例参考】
 *
 * @Test
 * public void testSaveOrder_success() {
 *     // 准备工作：创建商品、填充购物车、登录用户
 *     String result = orderService.saveOrder(user, cartItems);
 *     assertNotNull(result); // 返回订单号
 *     assertTrue(StringUtils.hasText(result));
 *
 *     // 验证：数据库中订单状态为待支付
 *     NewBeeMallOrder order = orderMapper.selectByOrderNo(result);
 *     assertEquals(NewBeeMallOrderStatusEnum.ORDER_PRE_PAY.getOrderStatus(), order.getOrderStatus());
 *
 *     // 验证：库存减少
 *     NewBeeMallGoods goods = goodsMapper.selectByPrimaryKey(item.getGoodsId());
 *     assertExpectedStockDecremented(goods);
 * }
 *
 * @Test
 * public void testSaveOrder_insufficientStock() {
 *     // 设置购物车数量 > 商品库存
 *     NewBeeMallExceptionFailAssert.assertThrows(() -> {
 *         orderService.saveOrder(user, cartItems);
 *     }, ServiceResultEnum.SHOPPING_ITEM_COUNT_ERROR.getResult());
 * }
 * =====================================================================
 */
package ltd.newbee.mall.service.impl;

import ltd.newbee.mall.common.*;
import ltd.newbee.mall.controller.vo.*;
import ltd.newbee.mall.dao.NewBeeMallGoodsMapper;
import ltd.newbee.mall.dao.NewBeeMallOrderItemMapper;
import ltd.newbee.mall.dao.NewBeeMallOrderMapper;
import ltd.newbee.mall.dao.NewBeeMallShoppingCartItemMapper;
import ltd.newbee.mall.entity.NewBeeMallGoods;
import ltd.newbee.mall.entity.NewBeeMallOrder;
import ltd.newbee.mall.entity.NewBeeMallOrderItem;
import ltd.newbee.mall.entity.StockNumDTO;
import ltd.newbee.mall.service.NewBeeMallOrderService;
import ltd.newbee.mall.util.BeanUtil;
import ltd.newbee.mall.util.NumberUtil;
import ltd.newbee.mall.util.PageQueryUtil;
import ltd.newbee.mall.util.PageResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.groupingBy;

/**
 * @author 13
 * @qq交流群 796794009
 * @email 2449207463@qq.com
 * @link https://github.com/newbee-ltd
 *
 * @apiNote 订单业务逻辑实现类
 *          负责处理用户下单、订单详情查询、订单状态变更（支付、发货、确认、取消）等核心业务。
 *          所有与订单相关的数据操作都在这里集中实现，是订单模块的核心中枢。
 */
@Service  // Spring组件扫描，注册为Service Bean，被Controller注入使用
public class NewBeeMallOrderServiceImpl implements NewBeeMallOrderService {

    @Autowired  // Spring自动注入对应的Mapper实例，由容器管理生命周期
    private NewBeeMallOrderMapper newBeeMallOrderMapper;
    @Autowired
    private NewBeeMallOrderItemMapper newBeeMallOrderItemMapper;
    @Autowired
    private NewBeeMallShoppingCartItemMapper newBeeMallShoppingCartItemMapper;
    @Autowired
    private NewBeeMallGoodsMapper newBeeMallGoodsMapper;
    @Autowired
    private ltd.newbee.mall.util.OrderDelayQueue orderDelayQueue;

    /**
     * 【功能】获取订单分页列表（后台管理员用）
 *
 * 【用途】
 * ① 管理员后台订单列表页面展示的分页数据
 * ② 结合PageQueryUtil实现带条件的分页查询（可按状态、时间范围等筛选）
 *
 * @param pageUtil 封装了分页参数（page、limit）、筛选条件的工具对象
 * @return PageResult对象，包含订单列表、总记录数、每页条数、当前页码
 */
    @Override
    public PageResult getNewBeeMallOrdersPage(PageQueryUtil pageUtil) {
        // 查询符合分页条件的订单列表
        List<NewBeeMallOrder> newBeeMallOrders = newBeeMallOrderMapper.findNewBeeMallOrderList(pageUtil);
        // 查询符合条件的总记录数（用于分页计算总页数）
        int total = newBeeMallOrderMapper.getTotalNewBeeMallOrders(pageUtil);
        // 封装分页结果
        PageResult pageResult = new PageResult(newBeeMallOrders, total, pageUtil.getLimit(), pageUtil.getPage());
        return pageResult;
    }

    /**
     * 【功能】更新订单信息（如地址、价格等，仅限配货前修改）
 *
 * 【用途】
 * ① 管理员后台修改订单收货地址
 * ② 修正订单总金额（极少数情况）
 * 【限制】仅当订单状态处于配货前（status >= 0 且 < 3）时允许修改
 * 一旦出库或交易成功，不允许再修改，保证数据不可变
 *
 * @param newBeeMallOrder 待更新的订单对象（包含orderId、price、address等）
 * @return 操作结果字符串（成功"success"或错误码）
 */
    @Override
    @Transactional  // 开启事务，确保更新操作的原子性
    public String updateOrderInfo(NewBeeMallOrder newBeeMallOrder) {
        // 根据orderId先查询现有订单，检查状态是否可修改
        NewBeeMallOrder temp = newBeeMallOrderMapper.selectByPrimaryKey(newBeeMallOrder.getOrderId());
        // 不为空且orderStatus>=0且状态为出库之前可以修改部分信息
        // status 0/1/2 对应 待支付/已支付/配货完成，这些状态还可以调整
        if (temp != null && temp.getOrderStatus() >= 0 && temp.getOrderStatus() < 3) {
            temp.setTotalPrice(newBeeMallOrder.getTotalPrice());
            temp.setUserAddress(newBeeMallOrder.getUserAddress());
            temp.setUpdateTime(new Date());
            // 选择性更新（只更新非空字段）
            if (newBeeMallOrderMapper.updateByPrimaryKeySelective(temp) > 0) {
                return ServiceResultEnum.SUCCESS.getResult();
            }
            return ServiceResultEnum.DB_ERROR.getResult();
        }
        return ServiceResultEnum.DATA_NOT_EXIST.getResult();
    }

    /**
     * 【功能】批量订单配货完成（状态从1→2）
 *
 * 【用途】
 * ① 管理员在后台点击"配货完成"按钮
 * ② 将已支付订单标记为已打包，等待物流发出
 * 【状态流转】ORDER_PAID(1) → ORDER_PACKAGED(2)
 *
 * @param ids 订单ID数组（可多个同时操作）
 * @return 结果字符串（成功/错误或具体错误订单号列表）
 */
    @Override
    @Transactional
    public String checkDone(Long[] ids) {
        // 根据ID批量查询订单
        List<NewBeeMallOrder> orders = newBeeMallOrderMapper.selectByPrimaryKeys(Arrays.asList(ids));
        String errorOrderNos = "";  // 存储出错的订单号
        if (!CollectionUtils.isEmpty(orders)) {
            for (NewBeeMallOrder newBeeMallOrder : orders) {
                // 如果是逻辑删除的订单，跳过
                if (newBeeMallOrder.getIsDeleted() == 1) {
                    errorOrderNos += newBeeMallOrder.getOrderNo() + " ";
                    continue;
                }
                // 只有状态=1（已支付）才能配货，其他状态不行
                if (newBeeMallOrder.getOrderStatus() != 1) {
                    errorOrderNos += newBeeMallOrder.getOrderNo() + " ";
                }
            }
            // 如果没有错误订单，执行配货操作
            if (!StringUtils.hasText(errorOrderNos)) {
                if (newBeeMallOrderMapper.checkDone(Arrays.asList(ids)) > 0) {
                    return ServiceResultEnum.SUCCESS.getResult();
                } else {
                    return ServiceResultEnum.DB_ERROR.getResult();
                }
            } else {
                // 有错误的订单，返回具体提示
                if (errorOrderNos.length() > 0 && errorOrderNos.length() < 100) {
                    return errorOrderNos + "订单的状态不是支付成功无法执行出库操作";
                } else {
                    return "你选择了太多状态不是支付成功的订单，无法执行配货完成操作";
                }
            }
        }
        // 未查询到数据
        return ServiceResultEnum.DATA_NOT_EXIST.getResult();
    }

    /**
     * 【功能】批量订单出库（状态从2→3）
 *
 * 【用途】
 * ① 仓库打包完成后标记为已发出
 * ② 生成运单号，进入物流环节
 * 【状态流转】ORDER_PACKAGED(2) → ORDER_EXPRESS(3)
 *
 * @param ids 订单ID数组
 * @return 结果字符串
 */
    @Override
    @Transactional
    public String checkOut(Long[] ids) {
        List<NewBeeMallOrder> orders = newBeeMallOrderMapper.selectByPrimaryKeys(Arrays.asList(ids));
        String errorOrderNos = "";
        if (!CollectionUtils.isEmpty(orders)) {
            for (NewBeeMallOrder newBeeMallOrder : orders) {
                if (newBeeMallOrder.getIsDeleted() == 1) {
                    errorOrderNos += newBeeMallOrder.getOrderNo() + " ";
                    continue;
                }
                // 状态必须是1（已支付）或2（配货完成）才能出库
                // 已经出库的(3)或成功的(4)不能再出库
                if (newBeeMallOrder.getOrderStatus() != 1 && newBeeMallOrder.getOrderStatus() != 2) {
                    errorOrderNos += newBeeMallOrder.getOrderNo() + " ";
                }
            }
            if (!StringUtils.hasText(errorOrderNos)) {
                if (newBeeMallOrderMapper.checkOut(Arrays.asList(ids)) > 0) {
                    return ServiceResultEnum.SUCCESS.getResult();
                } else {
                    return ServiceResultEnum.DB_ERROR.getResult();
                }
            } else {
                if (errorOrderNos.length() > 0 && errorOrderNos.length() < 100) {
                    return errorOrderNos + "订单的状态不是支付成功或配货完成无法执行出库操作";
                } else {
                    return "你选择了太多状态不是支付成功或配货完成的订单，无法执行出库操作";
                }
            }
        }
        return ServiceResultEnum.DATA_NOT_EXIST.getResult();
    }

    /**
     * 【功能】批量关闭订单（恢复库存）
 *
 * 【用途】
 * ① 因缺货、违规等原因商家强制关闭订单
 * ② 关闭状态为ORDER_CLOSED_BY_JUDGE (-3)，并调用recoverStockNum恢复库存
 * 【状态流转】任意有效状态 → ORDER_CLOSED_BY_JUDGE (-3) + 库存回滚
 *
 * @param ids 订单ID数组
 * @return 结果字符串
 */
    @Override
    @Transactional
    public String closeOrder(Long[] ids) {
        List<NewBeeMallOrder> orders = newBeeMallOrderMapper.selectByPrimaryKeys(Arrays.asList(ids));
        String errorOrderNos = "";
        if (!CollectionUtils.isEmpty(orders)) {
            for (NewBeeMallOrder newBeeMallOrder : orders) {
                // isDeleted=1表示已删除，跳过
                if (newBeeMallOrder.getIsDeleted() == 1) {
                    errorOrderNos += newBeeMallOrder.getOrderNo() + " ";
                    continue;
                }
                // 已关闭(-1/-2/-3)或已完成的订单(4)不能再次关闭
                if (newBeeMallOrder.getOrderStatus() == 4 || newBeeMallOrder.getOrderStatus() < 0) {
                    errorOrderNos += newBeeMallOrder.getOrderNo() + " ";
                }
            }
            if (!StringUtils.hasText(errorOrderNos)) {
                // 双重操作：关闭订单 + 恢复库存，必须都成功才算成功
                if (newBeeMallOrderMapper.closeOrder(Arrays.asList(ids), NewBeeMallOrderStatusEnum.ORDER_CLOSED_BY_JUDGE.getOrderStatus()) > 0
                        && recoverStockNum(Arrays.asList(ids))) {
                    return ServiceResultEnum.SUCCESS.getResult();
                } else {
                    return ServiceResultEnum.DB_ERROR.getResult();
                }
            } else {
                if (errorOrderNos.length() > 0 && errorOrderNos.length() < 100) {
                    return errorOrderNos + "订单不能执行关闭操作";
                } else {
                    return "你选择的订单不能执行关闭操作";
                }
            }
        }
        return ServiceResultEnum.DATA_NOT_EXIST.getResult();
    }

    /**
     * 【功能】生成正式订单（最核心、最复杂的业务方法）
 *
 * 【业务场景】用户在购物车页面点击"去结算"，系统生成订单并跳转到订单详情页
 * 【整体流程】详见上方的详细流程分析
 *
 * @param user 当前登录用户信息（从Session注入，含userId和address）
 * @param myShoppingCartItems 购物车中的所有商品项（每件商品的数量）
 * @return 生成的订单编号(orderNo)，若失败则抛异常
 */
    @Override
    @Transactional  // 整条链路在一个事务中，保证原子性
    public String saveOrder(NewBeeMallUserVO user, List<NewBeeMallShoppingCartItemVO> myShoppingCartItems) {
        // 提取购物车项ID和商品ID列表，用于后续批量操作
        List<Long> itemIdList = myShoppingCartItems.stream().map(NewBeeMallShoppingCartItemVO::getCartItemId).collect(Collectors.toList());
        List<Long> goodsIds = myShoppingCartItems.stream().map(NewBeeMallShoppingCartItemVO::getGoodsId).collect(Collectors.toList());

        // 一次性查出所有相关商品信息（避免N+1查询）
        List<NewBeeMallGoods> newBeeMallGoods = newBeeMallGoodsMapper.selectByPrimaryKeys(goodsIds);

        // ========== 阶段1： ==========
        // 检查是否有下架商品
        List<NewBeeMallGoods> goodsListNotSelling = newBeeMallGoods.stream()
                .filter(g -> g.getGoodsSellStatus() != Constants.SELL_STATUS_UP)
                .collect(Collectors.toList());
        if (!CollectionUtils.isEmpty(goodsListNotSelling)) {
            // 只要有任意一件下架，立即失败
            NewBeeMallException.fail(goodsListNotSelling.get(0).getGoodsName() + "已下架，无法生成订单");
        }

        // 构建商品ID->Goods对象的快速查找Map
        Map<Long, NewBeeMallGoods> newBeeMallGoodsMap = newBeeMallGoods.stream()
                .collect(Collectors.toMap(NewBeeMallGoods::getGoodsId, Function.identity(), (entity1, entity2) -> entity1));

        // ========== 阶段2： ==========
        // 遍历每个购物车项，做库存和存在性检查
        for (NewBeeMallShoppingCartItemVO shoppingCartItemVO : myShoppingCartItems) {
            // 检查商品是否存在（数据完整性校验）
            if (!newBeeMallGoodsMap.containsKey(shoppingCartItemVO.getGoodsId())) {
                NewBeeMallException.fail(ServiceResultEnum.SHOPPING_ITEM_ERROR.getResult());
            }

            // 检查库存是否充足（防超卖关键校验）
            if (shoppingCartItemVO.getGoodsCount() > newBeeMallGoodsMap.get(shoppingCartItemVO.getGoodsId()).getStockNum()) {
                NewBeeMallException.fail(ServiceResultEnum.SHOPPING_ITEM_COUNT_ERROR.getResult());
            }
        }

        // ========== 阶段3： ==========
        // 执行删除购物车项（先清空购物车，减少用户重复提交影响）
        if (!CollectionUtils.isEmpty(itemIdList) && !CollectionUtils.isEmpty(goodsIds) && !CollectionUtils.isEmpty(newBeeMallGoods)) {
            if (newBeeMallShoppingCartItemMapper.deleteBatch(itemIdList) > 0) {
                // 准备库存扣减的DTO对象
                List<StockNumDTO> stockNumDTOS = BeanUtil.copyList(myShoppingCartItems, StockNumDTO.class);

                // 扣减库存
                int updateStockNumResult = newBeeMallGoodsMapper.updateStockNum(stockNumDTOS);
                if (updateStockNumResult < 1) {
                    NewBeeMallException.fail(ServiceResultEnum.SHOPPING_ITEM_COUNT_ERROR.getResult());
                }

                // ========== 阶段4： ==========
                // 生成订单主体
                String orderNo = NumberUtil.genOrderNo();  // 唯一订单号

                // 计算总价（重新计算防篡改，不从购物车拿历史价格）
                int priceTotal = 0;
                for (NewBeeMallShoppingCartItemVO item : myShoppingCartItems) {
                    priceTotal += item.getGoodsCount() * item.getSellingPrice();
                }
                if (priceTotal < 1) {
                    NewBeeMallException.fail(ServiceResultEnum.ORDER_PRICE_ERROR.getResult());
                }

                // 创建订单对象
                NewBeeMallOrder newBeeMallOrder = new NewBeeMallOrder();
                newBeeMallOrder.setOrderNo(orderNo);
                newBeeMallOrder.setUserId(user.getUserId());
                newBeeMallOrder.setUserAddress(user.getAddress());
                newBeeMallOrder.setTotalPrice(priceTotal);
                newBeeMallOrder.setExtraInfo("");  // 预留字段
                newBeeMallOrder.setCreateTime(new Date());
                newBeeMallOrder.setOrderStatus((byte) NewBeeMallOrderStatusEnum.ORDER_PRE_PAY.getOrderStatus());  // 待支付
                newBeeMallOrder.setPayType((byte) PayTypeEnum.NOT_PAY.getPayType());
                newBeeMallOrder.setPayStatus((byte) PayStatusEnum.PAY_ING.getPayStatus());
                newBeeMallOrder.setUpdateTime(new Date());

                // 插入订单
                if (newBeeMallOrderMapper.insertSelective(newBeeMallOrder) > 0) {
                    // 生成订单项快照
                    List<NewBeeMallOrderItem> newBeeMallOrderItems = new ArrayList<>();
                    for (NewBeeMallShoppingCartItemVO item : myShoppingCartItems) {
                        NewBeeMallOrderItem orderItem = new NewBeeMallOrderItem();
                        BeanUtil.copyProperties(item, orderItem);  // 复制基本属性
                        orderItem.setOrderId(newBeeMallOrder.getOrderId());  // 设置外键关联
                        orderItem.setCreateTime(new Date());
                        newBeeMallOrderItems.add(orderItem);
                    }

                    // 批量插入订单项
                    if (newBeeMallOrderItemMapper.insertBatch(newBeeMallOrderItems) > 0) {
                        // 订单创建成功,加入超时延迟队列(到期未支付则自动关闭)
                        orderDelayQueue.add(orderNo, Constants.ORDER_PAY_EXPIRE_SECONDS);
                        // 一切成功，返回订单号让Controller跳转至详情页
                        return orderNo;
                    }
                    // 订单项插入失败，事务会自动回滚（库存已扣减，但订单没建完，实际还需要补偿逻辑）
                    NewBeeMallException.fail(ServiceResultEnum.ORDER_PRICE_ERROR.getResult());
                }
                // 订单插入失败
                NewBeeMallException.fail(ServiceResultEnum.DB_ERROR.getResult());
            }
            // 删除购物车项失败
            NewBeeMallException.fail(ServiceResultEnum.DB_ERROR.getResult());
        }
        // 购物车为空或其他异常情况
        NewBeeMallException.fail(ServiceResultEnum.SHOPPING_ITEM_ERROR.getResult());
        return ServiceResultEnum.SHOPPING_ITEM_ERROR.getResult();
    }

    /**
     * 【功能】获取订单详情（前台用户使用）
 *
 * 【流程】
 * 1. 根据orderNo查询订单 → 若不存在抛异常
 * 2. 校验userId是否为当前登录用户 → 否则无权限
 * 3. 查询该订单的所有订单项 → 若无抛异常
 * 4. 构建VO，填充订单基本信息+订单项+状态文本描述
 *
 * @param orderNo 订单编号
 * @param userId 当前登录用户ID
 * @return NewBeeMallOrderDetailVO，包含订单详情和订单项列表
 */
    @Override
    public NewBeeMallOrderDetailVO getOrderDetailByOrderNo(String orderNo, Long userId) {
        NewBeeMallOrder newBeeMallOrder = newBeeMallOrderMapper.selectByOrderNo(orderNo);
        if (newBeeMallOrder == null) {
            NewBeeMallException.fail(ServiceResultEnum.ORDER_NOT_EXIST_ERROR.getResult());
        }
        // 权限校验：当前用户必须是自己订单的owner
        if (!userId.equals(newBeeMallOrder.getUserId())) {
            NewBeeMallException.fail(ServiceResultEnum.NO_PERMISSION_ERROR.getResult());
        }
        // 查询订单项
        List<NewBeeMallOrderItem> orderItems = newBeeMallOrderItemMapper.selectByOrderId(newBeeMallOrder.getOrderId());
        if (CollectionUtils.isEmpty(orderItems)) {
            NewBeeMallException.fail(ServiceResultEnum.ORDER_ITEM_NOT_EXIST_ERROR.getResult());
        }
        // 转成VO列表
        List<NewBeeMallOrderItemVO> newBeeMallOrderItemVOS = BeanUtil.copyList(orderItems, NewBeeMallOrderItemVO.class);

        // 构建详情VO
        NewBeeMallOrderDetailVO newBeeMallOrderDetailVO = new NewBeeMallOrderDetailVO();
        BeanUtil.copyProperties(newBeeMallOrder, newBeeMallOrderDetailVO);  // 复制基础字段

        // 补充中文描述（前端直接用，无需再去转码）
        newBeeMallOrderDetailVO.setOrderStatusString(
            NewBeeMallOrderStatusEnum.getNewBeeMallOrderStatusEnumByStatus(newBeeMallOrderDetailVO.getOrderStatus()).getName());
        newBeeMallOrderDetailVO.setPayTypeString(
            PayTypeEnum.getPayTypeEnumByType(newBeeMallOrderDetailVO.getPayType()).getName());

        newBeeMallOrderDetailVO.setNewBeeMallOrderItemVOS(newBeeMallOrderItemVOS);
        return newBeeMallOrderDetailVO;
    }

    /**
     * 【功能】根据订单号查询订单基本信息（用于支付回调等场景）
 * @param orderNo 订单号
 * @return NewBeeMallOrder对象，不存在返回null
 */
    @Override
    public NewBeeMallOrder getNewBeeMallOrderByOrderNo(String orderNo) {
        return newBeeMallOrderMapper.selectByOrderNo(orderNo);
    }

    /**
     * 【功能】获取当前用户的订单分页列表（我的订单页面）
 *
 * 【特点】
 * - 不仅列出订单基本信息，还关联了订单项
 * - 使用stream和groupingBy将订单项按订单ID分组
 * - 每个订单VO中都包含其所有的订单项列表
 * - 状态中文文本一并转换好，方便前端直接展示
 *
 * @param pageUtil 分页工具对象（含userId条件）
 * @return PageResult，包含订单VO列表
 */
    @Override
    public PageResult getMyOrders(PageQueryUtil pageUtil) {
        int total = newBeeMallOrderMapper.getTotalNewBeeMallOrders(pageUtil);
        List<NewBeeMallOrder> newBeeMallOrders = newBeeMallOrderMapper.findNewBeeMallOrderList(pageUtil);
        List<NewBeeMallOrderListVO> orderListVOS = new ArrayList<>();

        if (total > 0) {
            // 转VO
            orderListVOS = BeanUtil.copyList(newBeeMallOrders, NewBeeMallOrderListVO.class);
            // 补全状态中文描述
            for (NewBeeMallOrderListVO vo : orderListVOS) {
                vo.setOrderStatusString(NewBeeMallOrderStatusEnum.getNewBeeMallOrderStatusEnumByStatus(vo.getOrderStatus()).getName());
            }

            // 获取所有订单ID，一次性查出所有订单项
            List<Long> orderIds = newBeeMallOrders.stream().map(NewBeeMallOrder::getOrderId).collect(Collectors.toList());
            if (!CollectionUtils.isEmpty(orderIds)) {
                List<NewBeeMallOrderItem> orderItems = newBeeMallOrderItemMapper.selectByOrderIds(orderIds);
                // 按orderId分组：Map<orderId, List<OrderItem>>
                Map<Long, List<NewBeeMallOrderItem>> itemByOrderIdMap = orderItems.stream()
                        .collect(groupingBy(NewBeeMallOrderItem::getOrderId));

                // 为每个订单补充订单项列表
                for (NewBeeMallOrderListVO vo : orderListVOS) {
                    if (itemByOrderIdMap.containsKey(vo.getOrderId())) {
                        List<NewBeeMallOrderItem> itemList = itemByOrderIdMap.get(vo.getOrderId());
                        List<NewBeeMallOrderItemVO> itemVOS = BeanUtil.copyList(itemList, NewBeeMallOrderItemVO.class);
                        vo.setNewBeeMallOrderItemVOS(itemVOS);
                    }
                }
            }
        }
        return new PageResult(orderListVOS, total, pageUtil.getLimit(), pageUtil.getPage());
    }

    /**
     * 【功能】取消订单（用户主动取消）
 *
 * 【约束条件】
 * - 只能取消待支付、已支付、未出库状态的订单
 * - 不能取消已关闭(-1,-2,-3)或已成功(4)的订单
 * - 成功后恢复库存
 *
 * @param orderNo 订单号
 * @param userId 当前登录用户ID
 * @return 结果字符串（成功orderNo或错误码）
 */
    @Override
    @Transactional
    public String cancelOrder(String orderNo, Long userId) {
        NewBeeMallOrder newBeeMallOrder = newBeeMallOrderMapper.selectByOrderNo(orderNo);
        if (newBeeMallOrder != null) {
            // 权限检查
            if (!userId.equals(newBeeMallOrder.getUserId())) {
                NewBeeMallException.fail(ServiceResultEnum.NO_PERMISSION_ERROR.getResult());
            }
            // 状态检查：成功或已关闭的订单不能取消
            if (newBeeMallOrder.getOrderStatus().intValue() == NewBeeMallOrderStatusEnum.ORDER_SUCCESS.getOrderStatus()
                    || newBeeMallOrder.getOrderStatus().intValue() == NewBeeMallOrderStatusEnum.ORDER_CLOSED_BY_MALLUSER.getOrderStatus()
                    || newBeeMallOrder.getOrderStatus().intValue() == NewBeeMallOrderStatusEnum.ORDER_CLOSED_BY_EXPIRED.getOrderStatus()
                    || newBeeMallOrder.getOrderStatus().intValue() == NewBeeMallOrderStatusEnum.ORDER_CLOSED_BY_JUDGE.getOrderStatus()) {
                return ServiceResultEnum.ORDER_STATUS_ERROR.getResult();
            }
            // 关闭订单并恢复库存（都在同一个事务中）
            if (newBeeMallOrderMapper.closeOrder(Collections.singletonList(newBeeMallOrder.getOrderId()), NewBeeMallOrderStatusEnum.ORDER_CLOSED_BY_MALLUSER.getOrderStatus()) > 0
                    && recoverStockNum(Collections.singletonList(newBeeMallOrder.getOrderId()))) {
                return ServiceResultEnum.SUCCESS.getResult();
            } else {
                return ServiceResultEnum.DB_ERROR.getResult();
            }
        }
        return ServiceResultEnum.ORDER_NOT_EXIST_ERROR.getResult();
    }

    /**
     * 【功能】用户确认收货（将出库状态改为交易成功）
 *
 * 【约束】
 * - 仅当订单状态为ORDER_EXPRESS(3,出库成功)时才允许确认收货
 * - 确认后将状态变为ORDER_SUCCESS(4)
 *
 * @param orderNo 订单号
 * @param userId 当前用户ID
 * @return 结果字符串（成功"success"或错误码）
 */
    @Override
    public String finishOrder(String orderNo, Long userId) {
        NewBeeMallOrder newBeeMallOrder = newBeeMallOrderMapper.selectByOrderNo(orderNo);
        if (newBeeMallOrder != null) {
            if (!userId.equals(newBeeMallOrder.getUserId())) {
                return ServiceResultEnum.NO_PERMISSION_ERROR.getResult();
            }
            // 只能是出库状态才能确认收货
            if (newBeeMallOrder.getOrderStatus().intValue() != NewBeeMallOrderStatusEnum.ORDER_EXPRESS.getOrderStatus()) {
                return ServiceResultEnum.ORDER_STATUS_ERROR.getResult();
            }
            newBeeMallOrder.setOrderStatus((byte) NewBeeMallOrderStatusEnum.ORDER_SUCCESS.getOrderStatus());
            newBeeMallOrder.setUpdateTime(new Date());
            if (newBeeMallOrderMapper.updateByPrimaryKeySelective(newBeeMallOrder) > 0) {
                return ServiceResultEnum.SUCCESS.getResult();
            } else {
                return ServiceResultEnum.DB_ERROR.getResult();
            }
        }
        return ServiceResultEnum.ORDER_NOT_EXIST_ERROR.getResult();
    }

    /**
     * 【功能】支付成功回调处理（第三方支付平台异步通知）
 *
 * 【流程】
 * 1. 查订单，确认存在且属于待支付状态
 * 2. 更新订单状态为已支付，记录支付时间和类型
 * 3. 返回成功结果给支付平台
 *
 * @param orderNo 订单号
 * @param payType 支付方式（1=支付宝，2=微信）
 * @return 结果字符串
 */
    @Override
    public String paySuccess(String orderNo, int payType) {
        NewBeeMallOrder newBeeMallOrder = newBeeMallOrderMapper.selectByOrderNo(orderNo);
        if (newBeeMallOrder != null) {
            // 只能对"待支付"状态的订单进行支付确认
            if (newBeeMallOrder.getOrderStatus().intValue() != NewBeeMallOrderStatusEnum.ORDER_PRE_PAY.getOrderStatus()) {
                return ServiceResultEnum.ORDER_STATUS_ERROR.getResult();
            }
            newBeeMallOrder.setOrderStatus((byte) NewBeeMallOrderStatusEnum.ORDER_PAID.getOrderStatus());
            newBeeMallOrder.setPayType((byte) payType);  // 记录支付方式
            newBeeMallOrder.setPayStatus((byte) PayStatusEnum.PAY_SUCCESS.getPayStatus());
            newBeeMallOrder.setPayTime(new Date());
            newBeeMallOrder.setUpdateTime(new Date());
            if (newBeeMallOrderMapper.updateByPrimaryKeySelective(newBeeMallOrder) > 0) {
                // 支付成功,从超时延迟队列移除该订单(已支付无需再自动关闭)
                orderDelayQueue.remove(orderNo);
                return ServiceResultEnum.SUCCESS.getResult();
            } else {
                return ServiceResultEnum.DB_ERROR.getResult();
            }
        }
        return ServiceResultEnum.ORDER_NOT_EXIST_ERROR.getResult();
    }

    /**
     * 【功能】超时自动关闭订单(由定时任务调用)
     *
     * 【流程】
     * 1. 根据订单号查询订单
     * 2. 校验:必须是"待支付"状态才允许关闭(幂等,防止刚支付的订单被误关)
     * 3. 关闭订单(状态置为 -2 超时关闭)并恢复库存,同一事务
     *
     * 【为什么先查库校验状态?】
     * 延迟队列的到期判断基于 Redis,但"是否已支付"以数据库为准。
     * 用户可能在订单刚到期时恰好支付成功,如果直接关闭会误杀已支付订单。
     * 先查库确认仍是待支付,即使"支付"和"超时关闭"并发,也只有一个生效。
     *
     * @param orderNo 订单号
     * @return true=成功关闭(定时任务将其移出延迟队列), false=无需处理或处理失败(下轮重试)
     */
    @Override
    @Transactional
    public boolean closeTimeoutOrder(String orderNo) {
        NewBeeMallOrder newBeeMallOrder = newBeeMallOrderMapper.selectByOrderNo(orderNo);
        // 订单不存在或已不是待支付状态(已支付/已关闭等) → 无需处理
        if (newBeeMallOrder == null
                || newBeeMallOrder.getOrderStatus().intValue() != NewBeeMallOrderStatusEnum.ORDER_PRE_PAY.getOrderStatus()) {
            return true; // 状态已变化,从队列移除即可(不是失败,不用重试)
        }
        // 关闭订单并恢复库存(都在同一个事务中,与 cancelOrder 相同的范式)
        if (newBeeMallOrderMapper.closeOrder(Collections.singletonList(newBeeMallOrder.getOrderId()), NewBeeMallOrderStatusEnum.ORDER_CLOSED_BY_EXPIRED.getOrderStatus()) > 0
                && recoverStockNum(Collections.singletonList(newBeeMallOrder.getOrderId()))) {
            return true;
        } else {
            // 关闭或恢复库存失败,保留在延迟队列中,下轮定时任务重试
            return false;
        }
    }

    /**
     * 【功能】根据订单ID获取订单项列表（仅供内部调用）
 * @param id 订单ID
 * @return NewBeeMallOrderItemVO列表，若订单不存在则返回null
 */
    @Override
    public List<NewBeeMallOrderItemVO> getOrderItems(Long id) {
        NewBeeMallOrder newBeeMallOrder = newBeeMallOrderMapper.selectByPrimaryKey(id);
        if (newBeeMallOrder != null) {
            List<NewBeeMallOrderItem> orderItems = newBeeMallOrderItemMapper.selectByOrderId(newBeeMallOrder.getOrderId());
            if (!CollectionUtils.isEmpty(orderItems)) {
                List<NewBeeMallOrderItemVO> newBeeMallOrderItemVOS = BeanUtil.copyList(orderItems, NewBeeMallOrderItemVO.class);
                return newBeeMallOrderItemVOS;
            }
        }
        return null;
    }

    /**
     * 【功能】辅助方法：恢复订单取消时的库存
 *
 * 【作用】
 * 当订单被关闭（无论是用户手动取消还是商家关闭）时，需要将被占用的库存释放回去，
 * 让其他用户可以购买该商品。这个操作与关闭订单在同一个事务中，保证原子性。
 *
 * @param orderIds 需要恢复库存的订单ID列表
 * @return true表示恢复成功，false表示失败
 */
    public Boolean recoverStockNum(List<Long> orderIds) {
        // 查询这些订单下的所有订单项
        List<NewBeeMallOrderItem> newBeeMallOrderItems = newBeeMallOrderItemMapper.selectByOrderIds(orderIds);
        // 转换成StockNumDTO，包含goodsId和goodsCount（需要恢复的数量）
        List<StockNumDTO> stockNumDTOS = BeanUtil.copyList(newBeeMallOrderItems, StockNumDTO.class);
        // 执行恢复库存的操作（实际是增加库存）
        int updateStockNumResult = newBeeMallGoodsMapper.recoverStockNum(stockNumDTOS);
        if (updateStockNumResult < 1) {
            NewBeeMallException.fail(ServiceResultEnum.CLOSE_ORDER_ERROR.getResult());
            return false;
        } else {
            return true;
        }
    }
}
