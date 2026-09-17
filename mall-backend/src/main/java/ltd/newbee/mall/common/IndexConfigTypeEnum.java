/**
 * =====================================================================
 * IndexConfigTypeEnum.java - 首页配置项类型枚举类
 *
 * 【项目背景】
 * 在电商系统中，"首页"是用户进入商城后看到的第一个页面，通常包含多个动态配置区域。
 * 这些配置需要管理员在后台管理页面进行设置（如轮播图、热销商品、新品推荐等）。
 * IndexConfigTypeEnum就是用来区分不同配置项类型的编码定义。
 *
 * 【核心功能】
 * 1. 唯一标识每一种首页展示内容的类型
 * 2. 与数据库中的IndexConfig表的type字段对应存储
 * 3. 方便前端和后端代码中进行类型判断和处理
 * 4. 提供根据类型编码反向查找枚举值的方法，用于从数据库数据还原业务逻辑
 *
 * 【完整配置项列表及用途】
 * type=0 (DEFAULT) : 错误/默认值，表示未配置或无效的配置项
 * type=1 (INDEX_SEARCH_HOTS) : 搜索框热搜关键词
 *     - 用法：当用户在前台首页搜索框点击时，下方显示热门搜索关键词列表
 *     - 数据库字段：config_type = 1, config_value = "iPhone15,华为Mate60,小米SU7"
 *
 * type=2 (INDEX_SEARCH_DOWN_HOTS) : 搜索下拉框热搜词
 *     - 用法：用户在搜索框输入时，实时下拉提示的搜索建议词
 *     - 与type=1的区别：type=1是独立展示的热门词列表，type=2是输入时的联想词
 *
 * type=3 (INDEX_GOODS_HOT) : 首页热销商品
 *     - 用法：在首页"热销商品"区域展示销量最高的N个商品（N由Constants.INDEX_GOODS_HOT_NUMBER决定）
 *     - 排序依据：通常是按销量descending order取前N条
 *
 * type=4 (INDEX_GOODS_NEW) : 首页新品上线
 *     - 用法：在首页"新品上架"区域展示最近N个新上架的商品
 *     - 排序依据：按创建时间/上架时间desc取最新N条
 *
 * type=5 (INDEX_GOODS_RECOMMOND) : 首页为你推荐
 *     - 用法：基于浏览历史、购买记录或相似商品的算法推荐（此处可能是人工配置）
 *     - 位置：通常在首页"猜你喜欢"或"为你推荐"板块
 *
 * 【在系统中的实际使用流程】
 * 1. 管理员在后台admin页面配置首页推荐内容 → 写入IndexConfig表，其中type字段存储枚举值
 * 2. IndexController.indexPage()获取首页数据时：
 *    - 通过 getIndexConfigTypeEnumByType(3) 获取热销商品类型
 *    - 调用 service.getConfigGoodsesForIndex(type, count) 获取对应商品列表
 * 3. Thymeleaf模板通过枚举值的name属性（如"INDEX_GOODS_HOT"）进行条件渲染
 *
 * 【技术要点】
 * - 枚举值包含两个字段：type（int型，便于数据库存储和比较）和 name（String型，便于调试日志）
 * - getIndexConfigTypeEnumByType()方法是工厂模式的一种实现，将数据库的int值还原为业务对象
 * - 所有配置项都对应到IndexConfig表的配置行，实现灵活配置而不需改代码
 * =====================================================================
 */
package ltd.newbee.mall.common;

/**
 * @author 13
 * @qq交流群 796794009
 * @email 2449207463@qq.com
 * @link https://github.com/newbee-ltd
 *
 * @apiNote 首页配置项枚举类型
 *          定义了前台首页各个模块对应的配置项类型编码
 *          每一个枚举值对应一种首页展示内容的类型
 */
public enum IndexConfigTypeEnum {

    DEFAULT(0, "DEFAULT"),                  // 默认值，表示无效或未设置的配置类型
    INDEX_SEARCH_HOTS(1, "INDEX_SEARCH_HOTS"),       // 搜索框下方的热门搜索词列表
    INDEX_SEARCH_DOWN_HOTS(2, "INDEX_SEARCH_DOWN_HOTS"), // 搜索输入时的下拉联想关键词
    INDEX_GOODS_HOT(3, "INDEX_GOODS_HOTS"),         // 首页热销商品区（按销量排序）
    INDEX_GOODS_NEW(4, "INDEX_GOODS_NEW"),          // 首页新品上线区（按上架时间排序）
    INDEX_GOODS_RECOMMOND(5, "INDEX_GOODS_RECOMMOND");   // 首页推荐商品区（人工或算法推荐）

    private int type;        // 配置项类型编码（int类型，对应数据库IndexConfig表的type字段）
    private String name;     // 配置项类型的名称字符串（便于调试和日志输出）

    /**
     * 构造方法：初始化类型编码和名称
     *
     * @param type 配置项的类型编码（0-5），这是实际存储到数据库中的值
     * @param name 配置项的名称标识符（英文常量名），便于程序内部识别
     */
    IndexConfigTypeEnum(int type, String name) {
        this.type = type;
        this.name = name;
    }

    /**
     * 【功能】根据类型编码（int值）查找对应的IndexConfigTypeEnum枚举对象
 *
 * 【用途】
 * ① 从数据库读取配置数据时，将type字段的int值转换为枚举对象
 * ② 在其他业务逻辑中根据编码判断配置类型
 * ③ 安全兜底：如果编码不存在的枚举，返回DEFAULT（避免空指针异常）
 *
 * 【使用场景举例】
 * // 从数据库取出type=3，获取对应的枚举
 * IndexConfigTypeEnum typeEnum = IndexConfigTypeEnum.getIndexConfigTypeEnumByType(3);
 * if (typeEnum == IndexConfigTypeEnum.INDEX_GOODS_HOT) {
 *     // 处理热销商品的逻辑
 * }
 *
 * // 在循环中遍历所有配置
 * for (IndexConfig config : indexConfigs) {
 *     IndexConfigTypeEnum configType = IndexConfigTypeEnum.getIndexConfigTypeEnumByType(config.getType());
 *     // 根据configType做不同的业务处理
 * }
 *
 * @param type 待查找的配置项类型编码（整数，范围0-5）
 * @return 对应的索引配置枚举对象；如果找不到，返回DEFAULT（安全兜底）
 */
    public static IndexConfigTypeEnum getIndexConfigTypeEnumByType(int type) {
        for (IndexConfigTypeEnum indexConfigTypeEnum : IndexConfigTypeEnum.values()) {
            if (indexConfigTypeEnum.getType() == type) {
                return indexConfigTypeEnum;
            }
        }
        return DEFAULT; // 类型码无效或不存在，返回默认值，防止null引用
    }

    /**
     * 【功能】获取该配置项的类型编码
 *
 * 【用途】
 * ① 存入数据库时需要将枚举转换为int值
 * ② 与其他系统进行数据交互时传输类型标识
 * ③ 业务逻辑中使用编码进行比较判断
 *
 * @return 配置项的类型编码（0-5之间的整数）
     */
    public int getType() {
        return type;
    }

    /**
     * 【功能】设置配置项的类型编码
 *
 * 【注意】枚举作为单例对象，其属性原则上应该是不可变的。这里提供set方法仅为了：
 * ① MyBatis反序列化时的兼容需求（反射赋值需要set方法）
 * ② 测试用例中可能需要的修改
 *
 * 【实际业务中不建议调用此方法修改枚举属性，应在创建新的枚举实例时使用构造函数】
 */
    public void setType(int type) {
        this.type = type;
    }

    /**
     * 【功能】获取配置项的名称字符串
 *
 * 【用途】
 * ① 日志输出时打印可读性更强的名称
 * ② API响应中包含配置类型信息时返回
 * ③ 调试过程中查看当前对象代表哪种配置
 *
 * @return 配置项的名称标识符（如"INDEX_GOODS_HOT"）
     */
    public String getName() {
        return name;
    }

    /**
     * 【功能】设置配置项的名称字符串
 *
 * 【同上】仅为框架兼容性而设，业务代码不应修改枚举的属性值
 */
    public void setName(String name) {
        this.name = name;
    }
}
