-- 校园二手交易平台 · 演示数据（伊犁师范大学）
-- 用法：mysql -uroot -p newbee_mall_db < campus_second_hand_seed.sql
-- 幂等：可重复执行（主键冲突时更新）

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
