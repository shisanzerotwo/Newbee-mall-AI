-- 校园二手交易平台 · 演示数据（伊犁师范大学）
-- 用法：mysql --default-character-set=utf8mb4 -uroot -p newbee_mall_db < campus_second_hand_seed.sql
-- 幂等：可重复执行（主键冲突时更新）
--
-- ⚠️ 必须带 --default-character-set=utf8mb4。Windows 下 mysql 客户端默认走 GBK，
--    直接导入会报 ERROR 1366 (Incorrect string value) —— 中文分类名整条语句失败，
--    而且因为是「部分执行」，表面看只报一行错，实际分类树没进去。

-- ===== 1. 新增「校园二手」分类树（一级 + 六个二级） =====
INSERT INTO tb_newbee_mall_goods_category (category_id, category_name, category_level, parent_id, category_rank, is_deleted, create_user, create_time)
VALUES
  (900, '校园二手', 1, 0, 255, 0, 0, NOW())
ON DUPLICATE KEY UPDATE category_name = VALUES(category_name), is_deleted = 0;

INSERT INTO tb_newbee_mall_goods_category (category_id, category_name, category_level, parent_id, category_rank, is_deleted, create_user, create_time)
VALUES
  (901, '教材书籍', 2, 900, 1, 0, 0, NOW()),
  (902, '数码电子', 2, 900, 2, 0, 0, NOW()),
  (903, '生活宿舍', 2, 900, 3, 0, 0, NOW()),
  (904, '服饰鞋包', 2, 900, 4, 0, 0, NOW()),
  (905, '运动出行', 2, 900, 5, 0, 0, NOW()),
  (906, '票券卡务', 2, 900, 6, 0, 0, NOW())
ON DUPLICATE KEY UPDATE category_name = VALUES(category_name), parent_id = VALUES(parent_id), is_deleted = 0;

-- ===== 2. 校园二手商品（goods_id 90001 起；价格为「分」；sell_status=0 上架（本项目约定 0=上架，见 Constants.SELL_STATUS_UP）；stock_num>=1） =====
-- 语料红线：RAG 语料不含价格/库存（见 KnowledgeBuilder），价格走工具查询
INSERT INTO tb_newbee_mall_goods_info
  (goods_id, goods_name, goods_intro, goods_category_id, goods_cover_img, goods_detail_content,
   original_price, selling_price, stock_num, tag, goods_sell_status, create_user, create_time, update_user, update_time)
VALUES
  (90001, '九成新山地自行车 可校内试骑', '毕业急出，变速顺滑，附赠车锁打气筒，校内自提', 905, '/campus/bike.jpg',
   '大三购入，平时代步用，保养良好，21 速变速顺滑无异响。附赠车锁、打气筒、车篮。支持校内当面验货，东校区自提优先。原价 899 元，毕业急出。', 89900, 26000, 1, '急出,九成新,校内自提', 0, 6, NOW(), 0, NOW()),
  (90002, '考研数学全套资料 笔记工整', '数一三件套加历年真题，附赠手写错题本', 901, '/campus/math-book.jpg',
   '张宇高数、李永乐线代、余炳森概率全套，笔记工整、重点标色。附赠 2010-2025 真题册与手写错题本。购买者还可送网盘复习资料。', 18000, 4500, 1, '几乎全新,笔记工整', 0, 6, NOW(), 0, NOW()),
  (90003, '头戴式蓝牙耳机 主动降噪', '考研自用，续航正常，配件齐全', 902, '/campus/headphone.jpg',
   '备考期间自用，主动降噪效果良好，单次续航约 30 小时，耳罩无破损。原装充电线、收纳盒齐全。因换新出手。', 12900, 12000, 1, '轻微使用,配件齐全', 0, 6, NOW(), 0, NOW()),
  (90004, '宿舍护眼台灯 三档调光', '宿舍自用，无频闪，暖白光', 903, '/campus/lamp.jpg',
   '宿舍学习自用，三档亮度可调、无频闪护眼，暖白光不刺眼。灯臂可调角度，USB 供电，附赠充电线。', 5900, 1800, 1, '八成新,宿舍神器', 0, 6, NOW(), 0, NOW()),
  (90005, '同济第七版高等数学 上下册', '教材八成新，少量铅笔笔记，可擦除', 901, '/campus/tongji-math.jpg',
   '高数上下册同济第七版，八成新，仅有少量铅笔笔记可擦除，无缺页破损。适合大一新生或补考复习。', 7800, 2500, 1, '八成新,教材', 0, 6, NOW(), 0, NOW()),
  (90006, '斯伯丁室外篮球 7 号', '水泥地实战款，弹性良好', 905, '/campus/basketball.jpg',
   '斯伯丁 TF 系列室外篮球，7 号标准球，打过一学期水泥地，弹性与手感依然良好，球面无开胶。', 13900, 3900, 1, '八成新,运动', 0, 6, NOW(), 0, NOW()),
  (90007, 'iPad 平替电容笔 防误触', '几乎全新，带替换笔尖', 902, '/campus/stylus.jpg',
   'iPad 电容笔平替款，支持防误触与倾斜压感，适配 2018 年后大部分 iPad 型号。几乎全新，附替换笔尖两枚、充电线。', 12900, 5500, 1, '几乎全新,配件齐全', 0, 6, NOW(), 0, NOW()),
  (90008, '黑色羽绒服 M 码 仅穿一季', '冬季保暖款，无明显污渍', 904, '/campus/down-jacket.jpg',
   '黑色长款羽绒服 M 码（170/88A），仅穿一个冬季，保暖性好，已干洗无明显污渍，拉链完好。', 39900, 9900, 1, '九成新,保暖', 0, 6, NOW(), 0, NOW()),
  (90009, '四六级英语真题 2019-2025', '真题加详解，附赠高频词表', 901, '/campus/cet-book.jpg',
   '大学英语四六级历年真题 2019-2025，含详细解析与听力原文，附赠高频词表。真题全新未做，解析册有少量划线。', 4600, 1500, 1, '几乎全新,真题', 0, 6, NOW(), 0, NOW()),
  (90010, '宿舍小功率电煮锅 1.2L', '限电可用，煮面煮粥神器', 903, '/campus/cooker.jpg',
   '宿舍小功率电煮锅，1.2L 容量，300W 两档功率宿舍限电可用。煮面、煮粥、小火锅均可，内胆无划痕，配件齐全。', 6900, 2200, 1, '九成新,宿舍神器', 0, 6, NOW(), 0, NOW())
ON DUPLICATE KEY UPDATE
  goods_name = VALUES(goods_name), goods_intro = VALUES(goods_intro), goods_category_id = VALUES(goods_category_id),
  original_price = VALUES(original_price), selling_price = VALUES(selling_price), stock_num = VALUES(stock_num),
  tag = VALUES(tag), create_user = VALUES(create_user), goods_sell_status = VALUES(goods_sell_status), goods_detail_content = VALUES(goods_detail_content),
  update_time = NOW();

-- ===== 2a. 补录两件「由发布接口产生」的商品（90011 / 90012） =====
-- 这两件不是最初手写的种子，而是走 POST /api/goods 真实发布出来的。
-- 补进种子是为了让种子成为 90001-90045 的完整权威：换台机器 clone 后
-- 一条命令即可复现同样的演示数据，不会出现「库里有、种子里没有」的漂移。
INSERT INTO tb_newbee_mall_goods_info
  (goods_id, goods_name, goods_intro, goods_category_id, goods_cover_img, goods_detail_content,
   original_price, selling_price, stock_num, tag, goods_sell_status, create_user, create_time, update_user, update_time)
VALUES
  (90011, '九成新小米手环 4 带腕带', '去年买的，功能正常，电池续航还行，毕业出。', 902, '/campus/placeholder.jpg',
   '去年买的，功能正常，电池续航还行，毕业出。', 7500, 7500, 1, '九成新,校内自提,急出', 0, 1, NOW(), 0, NOW()),
  (90012, '八成新折叠伞 遮阳两用', '宿舍用伞，骨架完好，收起不占地方。', 903, '/campus/placeholder.jpg',
   '宿舍用伞，骨架完好，收起不占地方。', 1200, 1200, 1, '八成新,校内自提', 0, 1, NOW(), 0, NOW())
ON DUPLICATE KEY UPDATE
  goods_name = VALUES(goods_name), goods_intro = VALUES(goods_intro), goods_category_id = VALUES(goods_category_id),
  original_price = VALUES(original_price), selling_price = VALUES(selling_price), stock_num = VALUES(stock_num),
  tag = VALUES(tag), create_user = VALUES(create_user), goods_sell_status = VALUES(goods_sell_status), goods_detail_content = VALUES(goods_detail_content),
  update_time = NOW();

-- ===== 2b. 扩充商品池（goods_id 90013 起） =====
-- 为什么需要：首批只有 10 件，导致「教材」「真题」以外的绝大多数搜索词返回空，
-- 分类页也几乎无内容 —— 演示时看起来像功能坏了，实际是数据太薄。
-- 这里按六个二级分类补齐，并覆盖校园高频检索词（数据结构、操作系统、显示器、
-- 充电宝、羽毛球拍……），使搜索与分类浏览都有真实结果。
-- 编号从 90013 起：90011/90012 已被上面的真实发布商品占用，且 90013-90022
-- 曾是端到端测试留下的垃圾数据，这里用真实商品覆盖掉。
INSERT INTO tb_newbee_mall_goods_info
  (goods_id, goods_name, goods_intro, goods_category_id, goods_cover_img, goods_detail_content,
   original_price, selling_price, stock_num, tag, goods_sell_status, create_user, create_time, update_user, update_time)
VALUES
  -- 901 教材书籍
  (90013, '数据结构（C语言版）严蔚敏 考研指定教材', '计算机考研 408 指定教材，重点已标记', 901, '/campus/ds-book.jpg',
   '严蔚敏《数据结构（C语言版）》第 2 版，计算机考研 408 指定教材。书页完整无缺页，线性表、树、图等重点章节有少量荧光笔标记，附赠手写课后习题解答。适合课程学习与考研复习。', 4500, 1800, 1, '八成新,考研必备', 0, 6, NOW(), 0, NOW()),
  (90014, '计算机网络 自顶向下方法 第7版', '经典网络教材，课后题已做完', 901, '/campus/network-book.jpg',
   '《计算机网络：自顶向下方法》第 7 版中文版。应用层到链路层体系完整，课后习题已用铅笔做完（可擦除），附答案册。计算机专业必修课教材。', 8900, 3200, 1, '八成新,教材', 0, 6, NOW(), 0, NOW()),
  (90015, '计算机操作系统 汤小丹 第四版', '操作系统原理教材，无笔记干净', 901, '/campus/os-book.jpg',
   '汤小丹《计算机操作系统》第四版，操作系统课程主流教材。内页干净无笔记无划线，仅封面有轻微折痕。含进程管理、内存管理、文件系统全部章节。', 4200, 1600, 1, '九成新,教材', 0, 6, NOW(), 0, NOW()),
  (90016, '线性代数 同济第六版 附习题册', '正版教材，配套习题册一起出', 901, '/campus/linear-book.jpg',
   '同济大学《线性代数》第六版，理工科通用教材。配合同版习题册一并出售，习题册做过前四章。两本一起，不单卖。', 3800, 1200, 1, '八成新,教材', 0, 6, NOW(), 0, NOW()),
  (90017, '大学英语综合教程 1-4 册', '四册打包，适合大一新生', 901, '/campus/english-book.jpg',
   '新视野大学英语综合教程 1-4 册，四册打包出售。第一册有少量笔记，其余三册基本空白。适合大一新生提前准备，比单买省一半。', 15600, 4500, 1, '七成新,打包出', 0, 6, NOW(), 0, NOW()),
  (90018, '概率论与数理统计 浙大第四版', '理工科考研基础教材', 901, '/campus/prob-book.jpg',
   '浙大版《概率论与数理统计》第四版，理工科考研数学基础教材。书况良好，仅第一章有少量铅笔笔记，可擦除。', 3600, 1100, 1, '八成新,教材', 0, 6, NOW(), 0, NOW()),
  (90019, '考研英语一 黄皮书真题解析', '近十年真题，答案解析完整', 901, '/campus/english-kaoyan.jpg',
   '考研英语一黄皮书历年真题解析，覆盖近十年。真题册已做过部分（铅笔，可擦），解析册完整干净，长难句逐句拆解。适合二战或刚开始复习的同学。', 9800, 3600, 1, '七成新,考研', 0, 6, NOW(), 0, NOW()),
  (90020, '会计学原理 教材加习题册', '经管专业教材，附课堂笔记', 901, '/campus/accounting-book.jpg',
   '《会计学原理》教材与配套习题册，经管类专业基础课用书。附赠一整学期的课堂笔记，分录例题整理得很全。', 6200, 2000, 1, '八成新,附笔记', 0, 6, NOW(), 0, NOW()),

  -- 902 数码电子
  (90021, '罗技 M185 无线鼠标 静音款', '自用一年，按键灵敏无失灵', 902, '/campus/mouse.jpg',
   '罗技 M185 无线鼠标，2.4G 连接稳定，按键静音、滚轮顺滑。自用约一年，功能完好无失灵，附原装接收器与电池。', 6900, 2500, 1, '八成新,配件齐全', 0, 6, NOW(), 0, NOW()),
  (90022, '机械键盘 87 键 红轴', '手感顺滑，键帽无打油', 902, '/campus/keyboard.jpg',
   '87 键紧凑机械键盘，红轴手感顺滑、声音偏静，适合宿舍使用。键帽无明显打油，全键无失灵，附 Type-C 数据线。', 19900, 7900, 1, '九成新,宿舍可用', 0, 6, NOW(), 0, NOW()),
  (90023, '10000mAh 充电宝 双向快充', '支持 PD 快充，可上飞机', 902, '/campus/powerbank.jpg',
   '10000mAh 移动电源，支持 PD 双向快充，可给手机与平板供电，容量符合民航登机标准。使用次数不多，电池健康。', 12900, 4900, 1, '九成新,快充', 0, 6, NOW(), 0, NOW()),
  (90024, '64G U盘 USB3.0 金属外壳', '读写快，存课件够用', 902, '/campus/usb.jpg',
   '64G 金属外壳 U 盘，USB3.0 接口，读写速度稳定，存课件、论文资料完全够用。外壳有轻微使用痕迹，功能正常。', 4900, 1500, 1, '八成新,实用', 0, 6, NOW(), 0, NOW()),
  (90025, 'Kindle 青春版 电子书阅读器', '墨水屏不伤眼，含保护套', 902, '/campus/kindle.jpg',
   'Kindle 青春版，6 英寸墨水屏，自带阅读灯，长时间看不伤眼。屏幕无划痕、无亮点，电池续航正常。附原装保护套与数据线。', 65800, 26900, 1, '九成新,含保护套', 0, 6, NOW(), 0, NOW()),
  (90026, '24 寸 IPS 显示器 1080P', '宿舍外接屏，无坏点', 902, '/campus/monitor.jpg',
   '24 英寸 IPS 显示器，1920×1080 分辨率，75Hz 刷新率，HDMI 与 VGA 双接口。屏幕无坏点无亮线，适合宿舍写代码、看文献。附 HDMI 线。', 59900, 23900, 1, '九成新,无坏点', 0, 6, NOW(), 0, NOW()),
  (90027, '便携蓝牙音箱 防水款', '音质饱满，续航约 8 小时', 902, '/campus/speaker.jpg',
   '便携蓝牙音箱，蓝牙 5.0 连接稳定，音质饱满低音不闷，支持 IPX5 防水。续航约 8 小时，附充电线。宿舍、露营都能用。', 15900, 5900, 1, '九成新,防水', 0, 6, NOW(), 0, NOW()),

  -- 903 生活宿舍
  (90028, '宿舍折叠书桌 床上电脑桌', '可折叠收纳，不占地方', 903, '/campus/desk.jpg',
   '床上折叠电脑桌，桌面 60×40cm，可调节倾斜角度，带水杯凹槽与散热孔。用完可折叠塞床底，不占地方。桌面无划痕。', 8900, 3200, 1, '九成新,宿舍神器', 0, 6, NOW(), 0, NOW()),
  (90029, '真空压缩收纳袋 5 件套', '换季收被褥，省一半空间', 903, '/campus/storage-bag.jpg',
   '真空压缩收纳袋 5 件套（含手泵），换季收纳被褥、羽绒服可省一半空间。袋子无破损，密封条完好，手泵可用。', 3900, 1200, 1, '几乎全新,实用', 0, 6, NOW(), 0, NOW()),
  (90030, '静音小风扇 USB 桌面款', '三档风速，噪音很小', 903, '/campus/fan.jpg',
   'USB 桌面小风扇，三档风速可调，运行噪音很小不影响休息，可上下调节俯仰角度。扇叶已清洁，附 USB 线。', 4900, 1500, 1, '八成新,静音', 0, 6, NOW(), 0, NOW()),
  (90031, '316 不锈钢保温杯 500ml', '保温 12 小时，无异味', 903, '/campus/bottle.jpg',
   '316 食品级不锈钢保温杯，500ml 容量，实测保温 12 小时以上。杯内无茶垢无异味，密封圈完好不漏水。', 9900, 3500, 1, '九成新,保温好', 0, 6, NOW(), 0, NOW()),
  (90032, '宿舍遮光床帘 透气款', '遮光率好，含支架', 903, '/campus/curtain.jpg',
   '宿舍上下铺遮光床帘，双层布料遮光率约 90%，内层透气不闷。含支架与挂钩，已拆洗晾干。适合对光线敏感的同学。', 8900, 3000, 1, '八成新,含支架', 0, 6, NOW(), 0, NOW()),
  (90033, '静音加湿器 大容量', '冬季宿舍必备，出雾细', 903, '/campus/humidifier.jpg',
   '静音加湿器，3L 大容量可整夜使用，超声波出雾细腻，夜间几乎无声。水箱已彻底清洗，附电源线。北方暖气季很实用。', 7900, 2800, 1, '九成新,静音', 0, 6, NOW(), 0, NOW()),
  (90034, '落地折叠晾衣架 双层', '可折叠，晾被子也够', 903, '/campus/rack.jpg',
   '双层落地晾衣架，展开长度 1.8 米，可同时晾晒多件衣物或被单。不用时折叠靠墙，不占过道。支架稳固无变形。', 6900, 2200, 1, '八成新,可折叠', 0, 6, NOW(), 0, NOW()),

  -- 904 服饰鞋包
  (90035, '阿迪达斯运动鞋 42 码', '穿过几次，鞋底磨损轻', 904, '/campus/shoes.jpg',
   '阿迪达斯运动鞋，42 码（内长 260mm），正品可验。只穿过几次，鞋底磨损轻微，鞋面无明显折痕。已清洁晾干。', 49900, 15900, 1, '九成新,正品', 0, 6, NOW(), 0, NOW()),
  (90036, '加绒卫衣 男女同款 L 码', '秋冬保暖，无起球', 904, '/campus/hoodie.jpg',
   '加绒连帽卫衣，L 码，男女同款宽松版型，内里加绒保暖。洗过两三次，无起球无变形，颜色依旧鲜亮。', 19900, 5900, 1, '九成新,保暖', 0, 6, NOW(), 0, NOW()),
  (90037, '双肩包 15.6 寸电脑仓', '带电脑隔层，背负舒适', 904, '/campus/backpack.jpg',
   '双肩背包，主仓可放 15.6 寸笔记本，独立电脑隔层带防震棉。多隔层设计，水杯侧袋、防盗后背袋齐全。拉链顺滑，背负舒适。', 25900, 8900, 1, '九成新,可放电脑', 0, 6, NOW(), 0, NOW()),
  (90038, '羊毛围巾 米白色', '纯羊毛，柔软不扎', 904, '/campus/scarf.jpg',
   '纯羊毛围巾，米白色，尺寸 180×30cm，手感柔软不扎脖子。只戴过一个冬天，已干洗，无起球无勾丝。', 12900, 3900, 1, '九成新,纯羊毛', 0, 6, NOW(), 0, NOW()),

  -- 905 运动出行
  (90039, '尤尼克斯羽毛球拍 双拍套装', '含拍包与羽毛球，可直接打', 905, '/campus/badminton.jpg',
   '尤尼克斯羽毛球拍双拍套装，碳素材质，拍面完好无断线，握把胶已更换。附拍包、3 个羽毛球，买来可直接约球。', 29900, 9900, 1, '九成新,套装', 0, 6, NOW(), 0, NOW()),
  (90040, '加厚防滑瑜伽垫 183cm', '10mm 加厚，附绑带', 905, '/campus/yoga-mat.jpg',
   'TPE 材质瑜伽垫，183×61cm，10mm 加厚缓冲好，双面防滑纹理。用于宿舍健身与拉伸，无破损无变形，附收纳绑带。', 9900, 3200, 1, '九成新,加厚', 0, 6, NOW(), 0, NOW()),
  (90041, '滑板 初学者双翘板', '板面结实，轮子顺滑', 905, '/campus/skateboard.jpg',
   '初学者双翘滑板，7 层枫木板面结实，PU 轮顺滑适合校园路面。板面有正常使用划痕，砂纸磨损不严重，轴承转动顺畅。', 25900, 8900, 1, '八成新,初学者', 0, 6, NOW(), 0, NOW()),
  (90042, '登山包 40L 带防雨罩', '周末徒步够用，背负系统好', 905, '/campus/hiking-bag.jpg',
   '40L 户外登山包，带背部透气背负系统与腰带，分担肩部压力。含原装防雨罩。周末伊犁周边徒步、露营完全够用。', 35900, 12900, 1, '九成新,含防雨罩', 0, 6, NOW(), 0, NOW()),

  -- 906 票券卡务
  (90043, '伊宁市影院电影通兑券 2 张', '有效期到明年，任意场次', 906, '/campus/movie-ticket.jpg',
   '伊宁市合作影院电影通兑券 2 张，有效期至明年 6 月，任意场次任意影片可用，节假日不加价。因毕业离校转让。', 12000, 6000, 1, '全新未用,通兑', 0, 6, NOW(), 0, NOW()),
  (90044, '校园健身房月卡 未激活', '尚未开卡，可自选起始日', 906, '/campus/gym-card.jpg',
   '校内健身房月卡，尚未激活，可自行选择开卡日期。含器械区与团操课，就在体育馆一楼。因课表冲突转让。', 15000, 9000, 1, '未激活,校内', 0, 6, NOW(), 0, NOW()),
  (90045, '图书馆存包柜 一学期使用权', '考研党刚需，不用抢柜子', 906, '/campus/locker.jpg',
   '图书馆自习区存包柜一学期使用权，考研备考刚需，不用每天早起抢柜子。柜子位置在四楼自习区靠窗，已缴清费用。', 8000, 4500, 1, '考研党,省心', 0, 6, NOW(), 0, NOW())
ON DUPLICATE KEY UPDATE
  goods_name = VALUES(goods_name), goods_intro = VALUES(goods_intro), goods_category_id = VALUES(goods_category_id),
  original_price = VALUES(original_price), selling_price = VALUES(selling_price), stock_num = VALUES(stock_num),
  tag = VALUES(tag), create_user = VALUES(create_user), goods_sell_status = VALUES(goods_sell_status), goods_detail_content = VALUES(goods_detail_content),
  update_time = NOW();

-- ===== 3. 「想要（收藏）」表（C2C 直买模式替代购物车） =====
CREATE TABLE IF NOT EXISTS tb_campus_want (
  id          bigint NOT NULL AUTO_INCREMENT,
  user_id     bigint NOT NULL COMMENT '用户ID',
  goods_id    bigint NOT NULL COMMENT '商品ID',
  create_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_goods (user_id, goods_id),
  KEY idx_user (user_id, create_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '校园二手·我想要（收藏）';

-- ===== 4. 私聊消息表（M4 IM；会话 id 规则 c_{小uid}_{大uid}_{goodsId}） =====
CREATE TABLE IF NOT EXISTS tb_chat_message (
  id              bigint NOT NULL AUTO_INCREMENT,
  conversation_id varchar(64) NOT NULL COMMENT '会话ID',
  sender_id       bigint NOT NULL COMMENT '发送者',
  receiver_id     bigint NOT NULL COMMENT '接收者',
  goods_id        bigint DEFAULT NULL COMMENT '关联商品（可空=纯聊天）',
  content         varchar(500) NOT NULL COMMENT '消息内容',
  is_read         tinyint NOT NULL DEFAULT 0 COMMENT '0未读 1已读',
  create_time     datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_conv (conversation_id, id),
  KEY idx_receiver (receiver_id, is_read)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '校园二手·私聊消息';

-- ===== 5. 校园二手订单表（M5 支付链路；状态 0待支付 1已支付待自提 2完成 3取消；金额单位分） =====
CREATE TABLE IF NOT EXISTS tb_campus_order (
  order_no     varchar(32) NOT NULL COMMENT '订单号',
  buyer_id     bigint NOT NULL COMMENT '买家',
  seller_id    bigint NOT NULL COMMENT '卖家',
  goods_id     bigint NOT NULL COMMENT '商品ID',
  goods_title  varchar(200) NOT NULL COMMENT '下单时商品标题快照',
  price_fen    int NOT NULL COMMENT '成交金额（分）',
  pay_type     varchar(20) DEFAULT NULL COMMENT '支付方式',
  status       tinyint NOT NULL DEFAULT 0 COMMENT '0待支付 1已支付待自提 2完成 3取消',
  pickup_point varchar(120) DEFAULT NULL COMMENT '自提点',
  create_time  datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  pay_time     datetime DEFAULT NULL,
  PRIMARY KEY (order_no),
  KEY idx_buyer (buyer_id, create_time),
  KEY idx_goods (goods_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '校园二手·订单';

-- ===== 6. 清理端到端测试留下的数据 =====
-- ops/e2e_test.py 的发布链路会真的往库里写商品（发布接口目前没有删除能力），
-- 每跑一次就多一条。实测已累积 10 条同名数据，并开始挤占推荐流首页
-- （测试脚本自己在挑私聊对象时不得不加注释绕开这个问题，见 e2e_test.py [5] 段）。
-- 这里按标题前缀清理：该前缀是测试脚本专用的，不会误伤真实商品。
DELETE FROM tb_newbee_mall_goods_info WHERE goods_name LIKE 'E2E 测试%';
