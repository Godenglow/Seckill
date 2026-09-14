-- ============================================================================
--  秒杀系统 - 数据库初始化脚本
-- ============================================================================
--  用法（Windows，MySQL 8.x）：
--      "C:\Program Files\MySQL\MySQL Server 8.3\bin\mysql.exe" -uroot -p --default-character-set=utf8mb4 < seckill.sql
--  脚本内已加 SET NAMES utf8mb4，即使客户端默认字符集为 GBK 也能正确写入中文；
--  但为稳妥起见，命令行仍建议显式指定 --default-character-set=utf8mb4。
--
--  ⚠️ 本脚本会 DROP 并重建 seckill 库下的 5 张表，库内现有数据会全部丢失。
--     它用于把本地演示环境重置到一份已知可用的初始状态，请勿用于生产库。
--
--  相对原始版本的修正点：
--    1. 补上原脚本缺失的 INSERT 结束分号（原脚本无法整体执行）
--    2. order_info 列名 crate_time -> create_time（与 OrderDao.insert 的 SQL 对齐，
--       原列名拼写错误会导致下单直接报 Unknown column 'create_time'）
--    3. seckill_order 增加 (user_id, goods_id) 唯一索引 —— 数据库层的重复秒杀兜底
--    4. 秒杀时间窗口改为相对 NOW() 计算，避免导入后立即显示"秒杀已结束"
--    5. 补充 seckill_user 种子数据（密码用项目 MD5Util 的加盐算法预先算好）
-- ============================================================================

CREATE DATABASE IF NOT EXISTS `seckill` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE `seckill`;

-- 强制本次会话使用 utf8mb4，避免客户端默认字符集（中文 Windows 下常为 gbk）导致中文乱码
SET NAMES utf8mb4;

DROP TABLE IF EXISTS `seckill_order`;
DROP TABLE IF EXISTS `order_info`;
DROP TABLE IF EXISTS `seckill_goods`;
DROP TABLE IF EXISTS `goods`;
DROP TABLE IF EXISTS `seckill_user`;

-- ---------------------------------------------------------------------------
-- 用户表：id 直接用手机号，不额外自增
-- password = MD5( MD5(明文+固定salt) + 用户salt )，即项目里的 inputPassToDBPass
-- ---------------------------------------------------------------------------
CREATE TABLE `seckill_user` (
    `id`              bigint(20)   NOT NULL COMMENT '用户ID，手机号码',
    `nickname`        varchar(255) NOT NULL COMMENT '昵称',
    `password`        varchar(32)  DEFAULT NULL COMMENT 'MD5(MD5(pass明文+固定salt)+salt)',
    `salt`            varchar(10)  DEFAULT NULL COMMENT '用户级随机盐',
    `head`            varchar(128) DEFAULT NULL COMMENT '头像，云存储的ID',
    `register_date`   datetime     DEFAULT NULL COMMENT '注册时间',
    `last_login_date` datetime     DEFAULT NULL COMMENT '上次登录时间',
    `login_count`     int(11)      DEFAULT '0' COMMENT '登录次数',
    PRIMARY KEY (`id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- ---------------------------------------------------------------------------
-- 商品表
-- ---------------------------------------------------------------------------
CREATE TABLE `goods` (
    `id`           bigint(20)     NOT NULL AUTO_INCREMENT COMMENT '商品ID',
    `goods_name`   varchar(16)    DEFAULT NULL COMMENT '商品名称',
    `goods_title`  varchar(64)    DEFAULT NULL COMMENT '商品标题',
    `goods_img`    varchar(64)    DEFAULT NULL COMMENT '商品的图片',
    `goods_detail` longtext COMMENT '商品的详情介绍',
    `goods_price`  decimal(10, 2) DEFAULT '0.00' COMMENT '商品单价',
    `goods_stock`  int(11)        DEFAULT '0' COMMENT '商品库存，-1表示没有限制',
    PRIMARY KEY (`id`)
) ENGINE = InnoDB AUTO_INCREMENT = 3 DEFAULT CHARSET = utf8mb4;

-- ---------------------------------------------------------------------------
-- 秒杀商品表：秒杀价 / 秒杀库存 / 秒杀时间窗口
-- start_time / end_time 用相对 NOW() 的表达式，脚本在任何时间导入都处于"进行中"
-- 且留出充足余量（结束时间 2059 年），避免演示时窗口过期
-- ---------------------------------------------------------------------------
CREATE TABLE `seckill_goods` (
    `id`            bigint(20)     NOT NULL AUTO_INCREMENT COMMENT '秒杀商品ID',
    `goods_id`      bigint(20)     DEFAULT NULL COMMENT '商品ID',
    `seckill_price` decimal(10, 2) DEFAULT '0.00' COMMENT '秒杀价',
    `stock_count`   int(11)        DEFAULT NULL COMMENT '秒杀库存数量',
    `start_time`    datetime       DEFAULT NULL COMMENT '秒杀开始时间',
    `end_time`      datetime       DEFAULT NULL COMMENT '秒杀结束时间',
    PRIMARY KEY (`id`),
    KEY `idx_goods_id` (`goods_id`)
) ENGINE = InnoDB AUTO_INCREMENT = 3 DEFAULT CHARSET = utf8mb4;

-- ---------------------------------------------------------------------------
-- 订单表
-- ---------------------------------------------------------------------------
CREATE TABLE `order_info` (
    `id`               bigint(20)     NOT NULL AUTO_INCREMENT,
    `user_id`          bigint(20)     DEFAULT NULL COMMENT '用户ID',
    `goods_id`         bigint(20)     DEFAULT NULL COMMENT '商品ID',
    `delivery_addr_id` bigint(20)     DEFAULT NULL COMMENT '收货地址ID',
    `goods_name`       varchar(16)    DEFAULT NULL COMMENT '冗余过来的商品名称',
    `goods_count`      int(11)        DEFAULT '1' COMMENT '商品数量',
    `goods_price`      decimal(10, 2) DEFAULT '0.00' COMMENT '商品单价',
    `order_channel`    tinyint(4)     DEFAULT '0' COMMENT '1pc, 2android, 3ios',
    `status`           tinyint(4)     DEFAULT '0' COMMENT '订单状态，0新建未支付，1已支付，2已发货，3已收货，4已退款，5已完成',
    `create_time`      datetime       DEFAULT NULL COMMENT '订单的创建时间',
    `pay_time`         datetime       DEFAULT NULL COMMENT '支付时间',
    PRIMARY KEY (`id`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_goods_id` (`goods_id`)
) ENGINE = InnoDB AUTO_INCREMENT = 12 DEFAULT CHARSET = utf8mb4;

-- ---------------------------------------------------------------------------
-- 秒杀订单表：同一用户对同一商品只能有一条记录
-- uk_user_goods 是防止重复秒杀的最后一道防线（应用层的 Redis 判重在缓存丢失时不可靠）
-- ---------------------------------------------------------------------------
CREATE TABLE `seckill_order` (
    `id`       bigint(20) NOT NULL AUTO_INCREMENT,
    `user_id`  bigint(20) DEFAULT NULL COMMENT '用户ID',
    `goods_id` bigint(20) DEFAULT NULL COMMENT '商品ID',
    `order_id` bigint(20) DEFAULT NULL COMMENT '订单ID',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_goods` (`user_id`, `goods_id`)
) ENGINE = InnoDB AUTO_INCREMENT = 3 DEFAULT CHARSET = utf8mb4;

-- ===========================================================================
--  种子数据
-- ===========================================================================

-- 测试用户，密码均为 123456
-- password 由项目工具类 MD5Util.inputPassToDBPass("123456", "1a2b3c4d") 实际计算得出
INSERT INTO `seckill_user` (`id`, `nickname`, `password`, `salt`, `register_date`, `last_login_date`, `login_count`)
VALUES (13000000000, '测试用户A', 'b7797cce01b4b131b433b6acf4add449', '1a2b3c4d', NOW(), NOW(), 0),
       (13000000001, '测试用户B', 'b7797cce01b4b131b433b6acf4add449', '1a2b3c4d', NOW(), NOW(), 0);

INSERT INTO `goods` (`id`, `goods_name`, `goods_title`, `goods_img`, `goods_detail`, `goods_price`, `goods_stock`)
VALUES (1, 'iPhone X', 'Apple iPhone X (A1865) 64GB 深空灰色 移动联通电信4G手机', '/img/iphonex.png',
        'Apple iPhone X (A1865) 64GB 深空灰色 移动联通电信4G手机', 8388.00, 8888),
       (2, 'MacBook Pro', 'Apple MacBook Pro 15.4英寸笔记本电脑 银色', '/img/macbookpro.png',
        'i7处理器，大容量固态硬盘，外设接口丰富，配备绚丽的retina显示屏，强大而专业！选购AppleCare Protection Plan，获得长达3年来自Apple的额外硬件服务选项。购买勾选：保障服务、原厂保3年。',
        13599.00, 6666);

-- 秒杀窗口：开始时间设为 1 天前，结束时间设为 2059 年（恒处于"进行中"状态）
INSERT INTO `seckill_goods` (`id`, `goods_id`, `seckill_price`, `stock_count`, `start_time`, `end_time`)
VALUES (1, 1, 0.01, 4, DATE_SUB(NOW(), INTERVAL 1 DAY), '2059-01-01 00:00:00'),
       (2, 2, 0.01, 9, DATE_SUB(NOW(), INTERVAL 1 DAY), '2059-01-01 00:00:00');
