/**
 * =====================================================================
 * NewBeeMallCategoryLevelEnum.java - 商品分类级别枚举类
 *
 * 【项目背景】
 * 电商系统的商品分类通常采用三级分类体系（一级-二级-三级），形成树形结构。
 * 例如：手机 → 智能手机 → iPhone系列；衣服 → 男装 → T恤。
 * NewBeeMallCategoryLevelEnum用来表示一个分类节点在树形结构中的层级位置。
 *
 * 【核心功能】
 * 1. 标识每个分类对象的层级（一级/二级/三级）
 * 2. 用于数据库约束，确保分类关系正确（只能有父一级分类的二级分类等）
 * 3. 前端渲染时使用不同层级做不同的展示逻辑（一级分类显示图标，三级分类显示名称）
 * 4. 递归构建树形结构时，通过level判断是否还有子节点需要展开
 *
 * 【枚举值详解】
 * level=0 (DEFAULT) : 默认/错误值，表示无效的分类级别
 *     • 使用场景：初始化对象、校验不通过时的占位值
 *
 * level=1 (LEVEL_ONE, "一级分类") : 顶级分类
 *     • 定义：分类树的根节点，没有父级或parentId为0/NULL
 *     • 特点：直接显示在首页导航栏，如"手机""电脑""女装"
 *     • 业务操作：管理员创建一级分类时，parent_id置空或为0
 *     • 示例："家电"下面可以有"冰箱"（二级）、"变频冰箱"（三级）
 *
 * level=2 (LEVEL_TWO, "二级分类") : 中间层分类
 *     • 定义：隶属于某个一级分类的子分类
 *     • 特点：在左侧筛选导航中作为可点击的二级筛选条件
 *     • 业务操作：创建二级分类时必须指定parent_id为某个一级分类的ID
 *     • 示例："手机"（一级）→ "智能手机"（二级）
 *
 * level=3 (LEVEL_THREE, "三级分类") : 叶子节点分类
 *     • 定义：最底层的分类，通常对应具体的商品集合
 *     • 特点：用户点击进入后看到的是该分类下的商品列表，不能再建子分类
 *     • 业务操作：三级分类不能再添加子分类（leaf=true标志）
 *     • 示例："智能手机"（二级）→ "iPhone 15系列"（三级）
 *
 * 【在系统中的实际应用】
 *
 * 1. 商品分类创建流程（后台 admin/categories/save）:
 *    - 管理员选择父分类（如果有）
 *    - 如果父分类是一级分类（level=1），则新分类为二级分类（level=2）
 *    - 如果父分类是二级分类（level=2），则新分类为三级分类（level=3）
 *    - level根据父节点的level自动计算：newLevel = parentLevel + 1
 *
 * 2. 首页分类展示（IndexController.indexPage()）:
 *    - 调用 CategoryService.getCategoriesForIndex() 构建三级分类树
 *    - 只查询 level=1 的一级分类作为入口菜单
 *    - 每个一级分类下包含其所有的二级和三级子分类（通过VO对象嵌套）
 *
 * 3. 搜索页分类筛选（GoodsController.searchPage()）:
 *    - SearchPageCategoryVO 构建完整的三级分类树，支持逐级筛选
 *    - 用户点击某一级分类 → 展示该分类下的二级分类
 *    - 点击某二级分类 → 展示该分类下的三级分类及商品
 *
 * 4. 分类层级校验：
 *    - NewBeeMallCategoryServiceImpl.saveNewBeeMallCategory() 中会检查
 *      当前节点的level是否与预期一致（不能有超过三级的深度）
 *
 * 【技术要点】
 * - getNewBeeMallCategoryLevelByLevel() 方法将数据库的int level值还原为枚举
 * - 与索引类似，提供了get/set方法供MyBatis等操作框架使用
 * - 枚举值中的中文描述name可用于前端直接显示，无需额外翻译映射
 * =====================================================================
 */
package ltd.newbee.mall.common;

/**
 * @author 13
 * @qq交流群 796794009
 * @email 2449207463@qq.com
 * @link https://github.com/newbee-ltd
 *
 * @apiNote 商品分类级别枚举
 *          定义分类体系中的层级位置，从一级到三级分类
 */
public enum NewBeeMallCategoryLevelEnum {

    DEFAULT(0, "ERROR"),              // 默认/错误值，表示未设置或无效的级别
    LEVEL_ONE(1, "一级分类"),         // 顶级分类，分类树的根节点
    LEVEL_TWO(2, "二级分类"),         // 中间层分类，隶属于一级分类
    LEVEL_THREE(3, "三级分类");       // 叶子分类，最底层，不能再有子分类

    private int level;   // 级别的数值编码（int型，对应数据库category_level字段）
    private String name; // 级别的文本描述（便于展示和调试）

    /**
     * 构造方法：初始化级别编码和名称
     *
     * @param level 级别数值（1/2/3对应一/二/三级，0表示默认/错误）
     * @param name 级别的中文名称描述
     */
    NewBeeMallCategoryLevelEnum(int level, String name) {
        this.level = level;
        this.name = name;
    }

    /**
     * 【功能】根据级别编码获取对应的分类级别枚举对象
 *
 * 【用途】
 * ① 从数据库读取分类数据时，将level字段的int值转换为枚举类型
 * ② 代码中做级别判断时使用：if (category.getLevel() == NewBeeMallCategoryLevelEnum.LEVEL_ONE)
 * ③ 安全兜底：无效级别返回DEFAULT，避免空指针
 *
 * 【使用示例】
 * // 从NewBeeMallCategory实体获取level后转换
 * NewBeeMallCategory cat = categoryMapper.getById(1L);
 * NewBeeMallCategoryLevelEnum levelEnum = NewBeeMallCategoryLevelEnum.getNewBeeMallCategoryLevelByLevel(cat.getLevel());
 *
 * // 直接通过枚举常量访问
 * if (levelEnum == NewBeeMallCategoryLevelEnum.LEVEL_ONE) {
 *     // 一级分类特殊处理（如显示为顶部导航）
 * }
 *
 * @param level 待转换的级别数值（0-3）
 * @return 对应的分类级别枚举对象，不存在返回DEFAULT
 */
    public static NewBeeMallCategoryLevelEnum getNewBeeMallCategoryLevelByLevel(int level) {
        for (NewBeeMallCategoryLevelEnum newBeeMallCategoryLevelEnum : NewBeeMallCategoryLevelEnum.values()) {
            if (newBeeMallCategoryLevelEnum.getLevel() == level) {
                return newBeeMallCategoryLevelEnum;
            }
        }
        return DEFAULT; // 级别无效，返回默认值
    }

    /**
     * 【功能】获取分类级别的数值编码
 *
 * 【用途】
 * ① 存入数据库时需要将枚举转为int值
 * ② 与其他模块进行数据交换时传递级别标识
 * ③ 进行比较判断：if (cat.getLevel() == 2)
 *
 * @return 级别的数值编码（1=一级，2=二级，3=三级，0=默认）
     */
    public int getLevel() {
        return level;
    }

    /**
     * 【功能】设置分类级别的数值编码（仅用于框架兼容性）
 *
 * 【注意】枚举类型的属性原则上不应被修改。这里提供set方法是为了：
 * ① MyBatis反序列化反射赋值需要
 * ② 单元测试中可能需要的临时修改
 *
 * 【实际业务代码中不建议调用此方法修改已存在的枚举对象】
 */
    public void setLevel(int level) {
        this.level = level;
    }

    /**
     * 【功能】获取分类级别的文本描述
 *
 * 【用途】
 * ① 在前端页面直接展示给用户的级别名称（如显示"一级分类"提示）
 * ② 日志记录时使用人类可读的描述
 * ③ 调试信息输出
 *
 * @return 级别的中文描述（如"一级分类"）
     */
    public String getName() {
        return name;
    }

    /**
     * 【功能】设置分类级别的文本描述（仅用于框架兼容性）
 *
 * 【同上】不建议在业务代码中调用
 */
    public void setName(String name) {
        this.name = name;
    }
}
