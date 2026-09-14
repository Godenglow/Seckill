# 秒杀系统 代码审查与本地化改造报告

> 审查对象：`D:\Projects\java-projects\seckill\seckill`（Git 仓库根目录，`main` 分支）
> 审查日期：2026-09-14
> 报告范围：项目架构分析、缺陷清单、本地化改造记录、本地运行手册、验证记录
> 本项目基于开源秒杀实现范例二次开发，代码遵循仓库中的 Apache-2.0 许可。

---

## 0. 结论摘要

高并发秒杀系统的后端实现，围绕"瞬时流量远大于库存、而数据库连接是稀缺资源"这一矛盾设计：Redis 预减库存把绝大多数无效流量挡在数据库之前，RabbitMQ 异步削峰，数据库唯一索引兜底防重。

**架构设计是合格的教科书答案**：Redis 预减库存、内存标记短路、RabbitMQ 异步下单、隐藏秒杀地址、算术验证码、接口限流、页面缓存与静态化——秒杀场景的七个经典问题都覆盖到了。

**但工程完成度与安全状况不合格**，本次审查发现 3 个会导致机制失效或造成实际损失的问题，以及一批正确性、安全性与可维护性缺陷。其中最严重的两个：

1. `OrderService` 把秒杀订单的 `user_id` **硬编码为 1**，使"是否已秒杀过"的数据库查询对除 1 号用户外的所有人永久失效；
2. 数据库凭据、Redis 密码、以及一台公网服务器的地址与登录密码**以明文提交进了公开仓库**。

本次已完成：**让项目在本机完整跑通**（真实 MySQL + Redis + RabbitMQ，无任何降级或 mock），**修复 8 处缺陷**，并**端到端验证 27 项全部通过**，其中包含 30 用户并发抢 10 件库存的实测：入队 10、售罄 20、DB 无超卖、无重复下单。

**特别注意**：本次改造**未提交 Git**，所有改动都在工作区。

---

## 1. 项目概览

| 项 | 值 |
|---|---|
| Maven 坐标 | `com.godenglow:seckill:1.0.0` |
| 打包方式 | `jar`（`spring-boot-maven-plugin`，可执行 fat jar） |
| 父 POM | `spring-boot-starter-parent:1.5.10.RELEASE`（2018 年发布，已 EOL） |
| Java 版本 | 1.8 |
| 源码规模 | 59 个 Java 文件 |
| 测试 | **无**。有 `spring-boot-starter-test` 依赖，但没有 `src/test` 目录 |
| 构建/部署自动化 | **无**。没有 Dockerfile、CI 配置、构建脚本，也没有 Maven Wrapper |

### 技术栈

| 层次 | 选型 | 版本 | 说明 |
|---|---|---|---|
| Web | Spring MVC | 4.3.14 | 随 Boot 1.5.10 |
| 模板 | Thymeleaf | 2.1.6 | 服务端渲染 + 手动渲染做页面缓存 |
| 持久层 | MyBatis | 1.3.1（starter） | **纯注解 Mapper，零 XML** |
| 连接池 | Druid | 1.0.5 | 见 §5 P1-4，参数曾全部失效 |
| 数据库 | MySQL | 驱动曾为 5.1.45 | 本次升至 8.0.33 |
| 缓存 | Jedis | 2.9.x | **裸用 Jedis，不是 spring-data-redis** |
| 消息队列 | Spring AMQP | 1.7.6 | 底层 `amqp-client 4.0.2` |
| 序列化 | fastjson | 1.2.38 | 用于 Redis 与 MQ 的对象序列化，见 §5 P2-3 |
| 接口文档 | springfox-swagger2 | 2.6.1 | `SwaggerConfig` 已启用 |

### 源码结构

```
com.godenglow.seckill
├── SeckillApplication            启动类
├── controller/  SeckillController, GoodsController, OrderController, LoginController
├── service/     SeckillService, GoodsService, OrderService, SeckillUserService
├── dao/         GoodsDao, OrderDao, SeckillUserDao   （全部注解式）
├── domain/      Goods, SeckillGoods, OrderInfo, SeckillOrder, SeckillUser
├── vo/          GoodsVO, GoodsDetailVO, OrderDetailVO, LoginVO
├── redis/       RedisService, RedisPoolFactory, RedisConfig, BasePrefix + 各 *Key
├── rabbitmq/    MQConfig, MQSender, MQReceiver, SeckillMessage
├── ai/          DeepSeekClient, AiToolKit, AiChatService   （Function Calling 助手，见 §4-6）
├── access/      AccessInterceptor, AccessLimit, UserContext   （限流 + 用户上下文）
├── config/      WebConfig, LoginInterceptor, UserArgumentResolver,
│                SwaggerConfig, DataSourceConfig
├── result/      Result, CodeMsg                                （统一返回信封）
├── exception/   GlobalException, GlobalExceptionHandler
├── validator/   IsMobile(+Validator), NeedLogin
└── util/        MD5Util, UUIDUtil, CookieUtil, ValidatorUtil
```

设计上值得一提的是：`Result`/`CodeMsg` 构成的统一返回信封、`BasePrefix` + `KeyPrefix` 的缓存键抽象、以及用拦截器 + `UserContext`（ThreadLocal）把认证与限流从业务代码里剥离出来，这几处比同类实现干净。

---

## 2. 秒杀核心链路

```
启动   SeckillController.afterPropertiesSet
       └─ 把 seckill_goods.stock_count 读进 Redis（GoodsKey:gs<goodsId>）
       └─ 初始化内存标记 localOverMap[goodsId] = false

①  GET  /seckill/verifyCode?goodsId=N
       └─ 服务端生成算术题，AWT 画成 JPEG 返回
       └─ 答案存 Redis（SeckillKey:verifyCode<uid>,<goodsId>，TTL 300s）

②  GET  /seckill/path?goodsId=N&verifyCode=A
       └─ 校验验证码（校验通过即删除该 key，一次性使用）
       └─ 生成 md5(uuid+"123456") 作为个人秒杀路径，存 Redis TTL 60s
       └─ 返回路径字符串

③  POST /seckill/{path}/seckill?goodsId=N
       └─ 校验 path 是否与 Redis 中一致
       └─ 查内存标记 localOverMap，已售罄直接返回
       └─ 查 Redis 是否已秒杀过（OrderKey:soug<uid>_<goodsId>）
       └─ Redis DECR 预减库存，结果为负则标记售罄并返回
       └─ 投递 SeckillMessage 到 seckill.queue，立即返回 data=0（排队中）

④  MQReceiver.receive  ← @RabbitListener(queues = "seckill.queue")
       └─ 再查一次 DB 库存与重复订单
       └─ 调用 SeckillService.seckill（@Transactional）
            ├─ GoodsDao.reduceStock：原子 UPDATE，stock_count > 0 且处于时间窗口
            └─ OrderService.createOrder：写 order_info + seckill_order，并回写 Redis 订单缓存

⑤  GET  /seckill/result?goodsId=N                     ← 客户端轮询
       └─ Redis 有订单 → 返回 orderId
       └─ 否则 SeckillKey:go<goodsId> 存在 → 返回 -1（失败）
       └─ 否则返回 0（继续排队）
```

前端的轮询间隔是 **50ms**（`static/goods_detail.htm:132`），这是一个与限流规则冲突的取值，见 §5 P2-2。

---

## 3. 高并发设计逐项评价

| 手段 | 实现位置 | 评价 |
|---|---|---|
| Redis 预减库存 | `SeckillController.seckill` | 思路正确，用 `DECR` 的原子性把绝大多数请求挡在数据库之外。**但顺序有问题**：原实现先预减再判重，重复提交会白扣库存（已修复，见 §4-1） |
| 内存标记短路 | 同上 | 售罄后在 JVM 内直接返回，省掉一次 Redis 往返。**原用 `HashMap`**，多线程读写不安全；且取值时自动拆箱，遇到未预热的商品会 NPE（已修复） |
| RabbitMQ 削峰 | `MQSender` / `MQReceiver` | 把数据库写串行化，`prefetch=1` + 10 消费者。**注意**：`prefetch=1` 与 10 个并发消费者组合后，实际吞吐受队列分发策略影响，调优时值得复测 |
| 页面缓存 + 静态化 | `GoodsController.to_list` / `to_detail` | 手动调 Thymeleaf 引擎渲染 HTML 后整串存 Redis，代码注释里留了实测数据（QPS 1267 → 2884）。**但缓存键不含用户维度，而页面里有用户信息**，见 §5 P1-3 |
| 秒杀地址隐藏 | `SeckillService.createSeckillPath` | 下单接口拆成"取路径"和"带路径下单"两步，能挡住直接压下单接口的脚本。**路径在 TTL 60s 内可重复使用**，且服务端不做一次性消费，脚本拿到后仍可复用——属于设计取舍，非缺陷 |
| 数学验证码 | `SeckillService.createVerifyCode` | 用 `ScriptEngine` 求值，一次性使用。**防的是人不是机器**：算术题可以毫秒级 OCR 破解，它的价值在于分散请求，不是安全边界 |
| 接口限流 | `AccessInterceptor` + `@AccessLimit` | 拦截器 + 注解，对业务零侵入，是这套代码里设计最好的一处。**但它是当前唯一的认证入口**（见 §5 P1-1），且 5 秒 5 次的阈值与前端 50ms 轮询不匹配（见 §5 P2-2） |

**关于"不超卖"到底靠什么**：真正兜底的是 `GoodsDao.reduceStock` 的一条原子 UPDATE（`where stock_count > 0`）。Redis 预减只是前置过滤器，它本身**允许被扣成负数**——本次实测中 30 个请求抢 10 件库存后，Redis 计数停在了 `-18`，而数据库 `stock_count = 0`。所以 Redis 里那个值**不能当作"剩余库存"展示**，它只是闸门。这一点在代码里没有任何注释说明，容易误用。

---

## 4. 本次改造记录

### 4-1 缺陷修复

| # | 位置 | 问题 | 修改 |
|---|---|---|---|
| 1 | `service/OrderService.java:52` | `seckillOrder.setUserId(1L)` **硬编码**。所有 `seckill_order` 行的 `user_id` 都写成 1，导致 `OrderDao.getSeckillOrderByUserIdGoodsId` 这条按 `(user_id, goods_id)` 的查询对除 1 号用户外的所有人永久返回空 | 改为 `seckillUser.getId()` |
| 2 | `controller/SeckillController.java:189` 附近 | 重复下单校验排在 Redis 预减**之后**，导致重复提交白扣一份 Redis 库存（只扣 Redis、无对应订单），造成少卖与提前售罄 | 把判重移到预减之前 |
| 3 | `controller/SeckillController.java:97` | `localOverMap` 是普通 `HashMap`，多请求线程并发读写 | 改为 `ConcurrentHashMap` |
| 4 | 同上 `:189` | `boolean over = localOverMap.get(goodsId)` 自动拆箱，`goodsId` 不在 map 中时 NPE | 改为 `Boolean.TRUE.equals(...)` |
| 5 | `controller/SeckillController.java:123` | `/seckill/reset` 无任何鉴权，任何人可调它重置库存并**删除全部订单** | 增加 `seckill.reset.enabled` 开关（默认 `false`），仅 local 配置打开 |
| 6 | `dao/GoodsDao.java:30` | 减库存的 UPDATE 只有 `stock_count > 0`，**不含时间窗口**。`start_time`/`end_time` 仅用于页面展示，过了结束时间照样能下单 | 补 `and start_time <= now() and end_time >= now()` |
| 7 | `service/OrderService.java:43` | `createOrder` 从不设置 `goodsCount`，该列永远为 NULL | 补 `setGoodsCount(1)` |
| 8 | `controller/SeckillController.java:269` | 验证码接口声明 `Content-Type: application/json`，实际写的是 JPEG 字节流。浏览器 `<img>` 不看该头部所以能显示，但对任何遵循头部的客户端都是错误信息 | 改为 `image/jpeg` |

### 4-2 安全清理

| 位置 | 问题 | 处置 |
|---|---|---|
| `application.properties` | MySQL `root/<REDACTED>`、Redis 密码 `<REDACTED>`、RabbitMQ `guest/guest`、公网地址 `<REDACTED-SERVER-IP>` | 全部移出共享配置，改为由 `application-local.properties` 提供 |
| `README.md` | 公开了线上演示地址与测试账号密码 | 删除，替换为本地运行说明 |
| `controller/LoginController.java:42`（原） | `log.info("【用户登录】" + loginVO.toString())`，把含 `password` 字段的对象整条打进日志 | 改为只记录手机号 |
| `service/SeckillService.java`（原 `generateVerifyCode`） | `System.out.println(exp)` 把验证码答案打到控制台 | 删除 |

> ⚠️ **凭据已泄露，清理仓库不等于安全**。那台 `<REDACTED-SERVER-IP>` 上的 MySQL、Redis、RabbitMQ 至今可能仍在使用仓库里的密码。请轮换这些密码，并确认该服务器是否还需要保留。仓库历史里仍留有这些凭据，若要让它们彻底消失需要重写 Git 历史（`git filter-repo`）并强制推送。

### 4-3 可运行性修复

| 位置 | 问题 | 处置 |
|---|---|---|
| `src/main/resources/seckill.sql` | 第 23、24、35 行的 `INSERT` **缺分号**，脚本整体无法执行 | 补全分号 |
| 同上 | 表列名 `crate_time`（拼写错误），而 `OrderDao.java:16` 的 INSERT 写的是 `create_time`——**照原脚本建库，下单必然报 Unknown column** | 列名统一为 `create_time` |
| 同上 | `seckill_order` 没有 `(user_id, goods_id)` 唯一约束，重复秒杀没有数据库层兜底 | 增加 `UNIQUE KEY uk_user_goods(user_id, goods_id)` |
| 同上 | 缺少 `CREATE DATABASE` / `USE` 语句 | 补全 |
| 同上 | 秒杀窗口 `end_time = 2018-02-13`，导入即"秒杀已结束" | 改为 `DATE_SUB(NOW(), INTERVAL 1 DAY)` 起、`2059-01-01` 止 |
| 同上 | 无 `seckill_user` 种子数据，建完库无法登录 | 补充 2 个测试用户（密码用项目自带 `MD5Util` 算法实际算出，非手写猜测） |
| 同上 | 中文 Windows 下 mysql 客户端默认按 GBK 读文件，中文商品名写入乱码 | 脚本内加 `SET NAMES utf8mb4`，并在注释里给带 `--default-character-set=utf8mb4` 的命令行 |
| `pom.xml` | 驱动为 Boot 1.5.10 默认的 `mysql-connector-java:5.1.45`，**无法完成 MySQL 8 的 `caching_sha2_password` 认证** | 覆盖 `<mysql.version>8.0.33</mysql.version>`，驱动类改 `com.mysql.cj.jdbc.Driver`，JDBC URL 加 `allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai` |
| `config/DataSourceConfig.java`（新增） | `spring.datasource.maxActive` 等 Druid 专有参数在 Spring Boot 1.5 下**根本不会绑定**，全部静默失效 | 新增配置类，把 `DruidDataSource` 直接交给 `@ConfigurationProperties(prefix="spring.datasource")` 绑定，参数才真正生效；同时把 `maxActive=1000 / initialSize=100 / minIdle=500` 改为本地合理的 `20/5/5`（MySQL 默认 `max_connections=151`，启动即申请 100 条连接会直接顶到上限） |
| `application-local.properties` + `.example`（新增） | 凭据需要外置 | 新增本地配置文件与模板，`application-local.properties` 加入 `.gitignore` |
| `util/DBUtil.java` | 该工具直接从 `application.properties` 读连接信息，配置外置后会读到 null | 改为依次加载 `application.properties` 与 `application-local.properties`（该文件随后在 §4-5 的脚手架清理中被整体删除） |

### 4-4 改造文件清单（本地化与缺陷修复）

```
修改  .gitignore
修改  README.md                    （后于 §4-5 重写）
修改  pom.xml
修改  src/main/java/com/godenglow/seckill/controller/LoginController.java
修改  src/main/java/com/godenglow/seckill/controller/SeckillController.java
修改  src/main/java/com/godenglow/seckill/dao/GoodsDao.java
修改  src/main/java/com/godenglow/seckill/service/OrderService.java
修改  src/main/java/com/godenglow/seckill/service/SeckillService.java
修改  src/main/resources/application.properties
修改  src/main/resources/seckill.sql
新增  src/main/java/com/godenglow/seckill/config/DataSourceConfig.java
新增  src/main/resources/application-local.properties.example
新增  src/main/resources/application-local.properties   （已被 .gitignore 忽略，不会进入版本库）
```

### 4-5 工程整理

本地化改造之后又做了一轮面向交付的整理，目标是把项目变成一份自洽、可直接阅读的代码库。

| 动作 | 内容 |
|---|---|
| 统一包名与坐标 | Java 包名统一为 `com.godenglow.seckill`，同步更新全部包声明与 import、`SwaggerConfig` 的 `basePackage`、`application.properties` 的 `type-aliases-package`、`pom.xml` 的 `groupId` |
| 清理源码头部 | 移除源文件中遗留的 `@author` 标注（含只含该标注的整块 javadoc） |
| 删除脚手架代码 | `SimpleController`（6 个 `/demo/*` 接口）、`UserController`、`UserService`、`UserDao`、`User`、`UserKey`、`UserUtil`、`DBUtil`。其中 `/demo/db` 查询的是一张 `seckill.sql` 里根本不存在的 `user` 表，`UserUtil` 里硬编码了 `D:/tokens.txt` |
| 删除无用资源 | `templates/hello.html`、`templates/seckill_fail.html`、`templates/order_detail.html`（后两个只被注释掉的代码引用） |
| 精简 MQ 配置 | `MQConfig` 里 direct/topic/fanout/headers 四套演示拓扑没有任何生产者或消费者，只保留业务真正使用的 `seckill.queue`；同时清掉 broker 上残留的 4 个空队列与 3 个演示交换机 |
| 清理注释掉的旧实现 | `SeckillController` 中整段注释的 `/do_seckill` 流程、`MQSender`/`MQReceiver` 中注释的示例方法、`GoodsController` 中 `// return "..."` 视图返回 |
| 清理无用 import 与字段 | `SeckillController`（`OrderInfo`、`MD5Util`、`UUIDUtil`、`NeedLogin`、`HttpResponse`、`Model`）、`LoginController`（未使用的 `RedisService` 字段）、`SeckillApplication`（导入了却未继承的 `SpringBootServletInitializer`）、pom 里注释掉的 war 插件与 tomcat 依赖 |
| 修复失效页面 | `templates/goods_detail.html` 的表单原本提交到已删除的 `/seckill/do_seckill`，改为跳转静态页 `/goods_detail.htm`；`static/order_detail.htm` 里硬编码的假收货人与地址（项目没有地址模块，`deliveryAddrId` 恒为 0）已删除 |
| 重写文档 | README 由笔记体例改写为工程文档：设计说明、技术栈、下单链路、关键取舍、本地运行手册、接口表、并发验证结果 |

整理后源文件数 62 → 54，`mvn clean package` 通过，27 项端到端与并发验证全部复跑通过。

### 4-6 AI 助手模块（新增功能）

在整理完成后新增了一个基于 Function Calling 的客服助手，源文件数 54 → 59。

| 新增 | 说明 |
|---|---|
| `ai/DeepSeekClient.java` | 手写 DeepSeek（OpenAI 兼容）客户端：非流式 `chat` 用于工具解析轮，流式 `chatStream` 用于生成回答。**不用 Spring AI** —— 它要求 Boot 3.4+/Java 17，本项目是 Boot 1.5.10/Java 8。逐行解析 SSE，JSON 复用已有 fastjson，**未引入任何新依赖** |
| `ai/AiToolKit.java` | 三个只读工具（库存 / 活动时间窗 / 我的订单），全部直读现有 DAO；身份类工具由服务端注入当前用户，不接受模型传参 |
| `ai/AiChatService.java` | 两阶段调用（非流式解析工具 → 流式生成回答）、Redis 会话记忆、独立线程池推送 SSE |
| `controller/AiChatController.java` | `GET /ai/chat`，复用既有 `@AccessLimit` 做限流（每用户 10 次/分钟，因为每次对话都真实消耗 token） |
| `redis/AiChatKey.java` | 会话键 `AiChatKey:session<userId>:<sessionId>`，TTL 30 分钟 |
| `static/ai_chat.htm` | 演示页面，`EventSource` 接收 `tool`/`delta`/`err`/`done` 四类事件 |
| `dao/OrderDao.listRecentOrders` | 供"我的订单"工具使用 |

**实测结果**（10 项断言全部通过）：问库存触发 `getGoodsStock` 并答出与数据库一致的 10 件；问时间触发 `getSeckillActivity` 并答出表里的结束时间；问订单触发 `getMyOrders` 并正确回答无订单；追问"那它的秒杀价是多少"（不再提商品名）能答出商品与价格，证明 Redis 会话上下文生效；会话在 Redis 中确有 7 条消息且 TTL=1800 秒；首字延迟 1993ms / 整体 2139ms，确认 SSE 增量下发而非一次性返回。

**已知取舍**：首字延迟几乎全部来自第一轮非流式的工具解析；回答长度受系统提示限制在两三句，因此流式在这个长度下收益有限。要压首字延迟需把工具轮也改成流式解析 `tool_calls` 分片，目前未做。

---

## 5. 尚未处理的问题清单

以下问题中，除标注**已修复**的以外，本次均**未修改**（改动面较大或涉及架构取舍），按优先级列出，供后续决策。

### P1（高危）

**P1-1　认证拦截器从未注册，`@NeedLogin` 完全无效**
`config/WebConfig.java:36` 的 `registry.addInterceptor(loginInterceptor)` 被注释掉了。实际生效的只有 `accessInterceptor`，而后者只在方法带 `@AccessLimit(needLogin=true)` 时才校验登录（`access/AccessInterceptor.java:52`）。
**后果**：`/seckill/reset`、`/goods/to_list`、`/goods/detail/{id}`、`/order/detail` 这些**没有** `@AccessLimit` 的接口实际不需要登录。认证被挂在了限流注解上，`@NeedLogin`、`LoginInterceptor` 是死代码。
**建议**：恢复注册 `LoginInterceptor`，并明确约定"需要登录"由 `@NeedLogin` 表达，而不是搭 `@AccessLimit` 的便车。

**P1-2　`seckill_order` 缺少唯一索引（已修复但需确认历史数据）**
本次已在 `seckill.sql` 中加入 `uk_user_goods`。**但线上已有库需要手动执行 `ALTER TABLE`**，并且要先清理已存在的重复 `(user_id, goods_id)` 记录（由于 `user_id` 曾被硬编码为 1，历史数据里几乎必然存在同一 `user_id=1` 对同一商品的多条记录，加索引会直接失败）。

**P1-3　商品列表页缓存会串用户信息**
`controller/GoodsController.java:72` 读缓存、`:88` 写缓存，缓存键是全局的（`GoodsKey:getGoodsList` + 空字符串），但渲染出的 HTML 里包含当前登录用户的昵称等信息。
**后果**：第一个访问者的页面会被缓存并返回给所有人。
**建议**：要么把用户信息从缓存页面里挪走（前端异步拉取），要么把 `userId` 纳入缓存键。

### P2（正确性与安全）

**P2-1　`RedisService.scanKeys` 用的是子串匹配而非前缀匹配**

```java
// redis/RedisService.java:142
sp.match("*" + key + "*");
```
调用方传进来的已经是形如 `OrderKey:soug` 的完整前缀，期望的是 `OrderKey:soug*`。当前写法会把**任何包含该子串**的键一并删除。目前各前缀恰好不会互相包含，所以没出问题，但这属于"靠命名巧合保证正确性"。
**建议**：改成 `sp.match(key + "*")`。

**P2-2　前端 50ms 轮询与接口限流规则冲突**
`static/goods_detail.htm:132` 每 50ms 轮询一次 `/seckill/result`，而该接口是 `@AccessLimit(seconds=5, maxCount=5)`——5 秒内最多 5 次，而 50ms 的间隔在 5 秒内会发出 100 次。
**后果**：订单生成慢一点，客户端就会收到 `ACCESS_LIMIT_REACHED(500103)`；而 `success` 回调只处理 `data.code == 0`，非 0 时**什么都不做**，于是轮询静默中断，用户永远停在 loading 遮罩上。
> 本次写验证脚本时，正是因为这条限制，把轮询间隔设成了 1.5 秒才能正常取到结果——这是实测到的，不是推测。
**建议**：把前端轮询间隔放宽到 1 秒以上，或在回调里处理非 0 返回码。

**P2-3　fastjson 1.2.38 存在已知反序列化漏洞**
`pom.xml` 指定 `com.alibaba:fastjson:1.2.38`（远低于修复 autotype 反序列化问题的 1.2.83）。它被 `RedisService.stringToBean` 用于把 Redis 里的值转成对象。
**建议**：升级到 1.2.83+，或换成 Jackson。

**P2-4　`GoodsDetailVO` 字段名与前端不一致，导致详情页"未登录"提示恒显示**
`vo/GoodsDetailVO.java` 暴露的字段名是 `seckillUser`，而 `static/goods_detail.htm:178` 读的是 `detail.user`。`user` 恒为 `undefined`，`$("#userTip").hide()` 永远不会执行。
**建议**：统一字段名。

**P2-5　`templates/goods_detail.html` 指向已被删除的接口（已修复）**
该模板的购买按钮原本提交到 `/seckill/do_seckill`，而该映射早已被删除（历史遗留），页面实际不可用——真正能走通的是静态页 `static/goods_detail.htm`。
**处置**：已改为跳转静态页 `/goods_detail.htm`（见 §4-5）。

### P3（可维护性）

- **无任何测试**。有测试依赖但没有 `src/test`，`mvn test` 不执行任何用例。秒杀这种强依赖并发正确性的场景，恰恰最需要测试。
- **无 CI、无 Dockerfile、无构建脚本、无 Maven Wrapper**。`.gitignore` 里写了 `.mvn/wrapper/maven-wrapper.jar`，但项目里没有 wrapper。
- **技术栈整体 EOL**：Spring Boot 1.5.10（2018）、Druid 1.0.5、springfox 2.6.1。
- **`.idea/` 被提交进仓库**，且 `misc.xml` 里 `project-jdk-name="21"`、`languageLevel="JDK_21"` 与本项目的 `java.version=1.8` 直接冲突（实测 JDK 21 无法可靠编译这套 2018 年的依赖树）。
- **`mybatis.mapperLocations=classpath:.../dao/*.xml` 指向不存在的路径**——所有 Mapper 都是注解式的，没有任何 XML。该配置是死的（本次已注释并说明）。
- **日志体系缺失**。没有 logback/log4j 配置；54 个类里只有 4 个有 `@Slf4j`；异常处理用 `e.printStackTrace()`（`GlobalExceptionHandler`、`RedisService.delete`、`SeckillService.calc`）。
- **`LoginInterceptor` + `@NeedLogin` 是死代码**（呼应 P1-1：拦截器从未注册，注解无人消费）。
- **`/seckill/reset` 把库存硬编码为 10**（`SeckillController`），忽略数据库里的真实值，且不重置 `localOverMap` 之外的任何内存状态——测试辅助代码直接长在了业务 Controller 上。
- **Redis 库存计数会被扣成负数**（实测 30 请求 / 10 库存后停在 `-18 ~ -20`）。这是"先减后判"设计的必然结果，但代码里没有任何注释说明该值不可用于展示。
- **`Result.error(codeMsg)` 在 `codeMsg == null` 时会返回一个"成功"响应**。`result/Result.java:21-27` 的构造器在 `codeMsg == null` 时直接 `return`，此 `code` 保持为默认值 `0`——而 `0` 在这套协议里恰恰代表**成功**（`data` 为 null、`msg` 为 null）。现有调用点都没有传 null，所以尚未触发，但这是一个高危埋雷：任何一次"本该报错却传了 null"的调用，都会静默变成业务成功。
- **仓库体积与 `.git` 历史**：本地曾存在一条包含早期开发过程的长历史，已清理为单一 `main` 分支（详见 §4-5 之后的工程整理记录）。

---

## 6. 本地运行手册

### 6.1 本机环境（已实测确认）

| 组件 | 状态 | 端点 / 凭据 |
|---|---|---|
| MySQL | ✅ 本地 8.3.0，Windows 服务 `MySQL83` | `localhost:3306`，`root` / 见本地配置 |
| Redis | ✅ 跑在 VMware 的 CentOS 7 虚拟机里（**不在宿主机上**） | `<REDACTED-REDIS-HOST>:6379`，Redis 7.2.5，密码 `<REDACTED>` |
| RabbitMQ | ✅ 本地原生安装 4.3.5 + Erlang/OTP 27.3.4.17 | `localhost:5672`，`guest/guest`；管理台 `http://localhost:15672` |
| JDK | ✅ Amazon Corretto 8u452 | `C:\Users\29074\.jdks\corretto-1.8.0_452` |
| Maven | ✅ 3.8.6 | `M2_HOME` 已配置；`.m2` 仓库曾为空，首次构建需联网下载全部依赖 |
| 应用端口 | 8088（`application-local.properties` 中配置） | |

> ⚠️ **运行前必须确认那台 VMware 虚拟机是开机的**。应用启动时 `SeckillController.afterPropertiesSet` 会把库存写入 Redis，Redis 不可达会导致**应用启动直接失败**（而不是降级运行）。

### 6.2 建库

```bat
"C:\Program Files\MySQL\MySQL Server 8.3\bin\mysql.exe" -uroot -p --default-character-set=utf8mb4 ^
    < "D:\Projects\java-projects\seckill\seckill\src\main\resources\seckill.sql"
```

脚本会 `DROP` 并重建 `seckill` 库下的 5 张表（`seckill_user`、`goods`、`seckill_goods`、`order_info`、`seckill_order`），并写入 2 个测试用户与 2 件秒杀商品。**库内现有数据会全部丢失**，不要对生产库执行。

种子测试账号：`13000000000`、`13000000001`，密码均为 `123456`。

### 6.3 配置

```bat
copy src\main\resources\application-local.properties.example ^
     src\main\resources\application-local.properties
```

然后按自己机器的情况修改 `application-local.properties`。该文件已在 `.gitignore` 中，凭据不会进入版本库。

`application.properties` 是共享配置（提交进仓库，不含任何凭据），其中 `spring.profiles.active=local`。

### 6.4 构建与启动

```bat
set "JAVA_HOME=C:\Users\29074\.jdks\corretto-1.8.0_452"
set "PATH=%JAVA_HOME%\bin;%PATH%"

:: 打包
mvn -DskipTests clean package

:: 启动
"%JAVA_HOME%\bin\java.exe" -jar target\seckill-1.0.0.jar
```

启动成功的标志：

```
Tomcat started on port(s): 8088 (http)
Started SeckillApplication in 9.432 seconds
```

> 若 Maven Central 下载过慢，可用 `-s` 指定一个带镜像的 settings 文件，无需修改全局 `~/.m2/settings.xml`。本次使用了一个项目外的 `maven-settings-aliyun.xml`（不在此仓库内）。

### 6.5 接口调用顺序

浏览器直接访问 `http://localhost:8088/login/to_login` 走完整流程最省事。若要脚本化：

| 步骤 | 请求 | 说明 |
|---|---|---|
| 1 | `GET /seckill/reset` | 重置库存与订单（需 `seckill.reset.enabled=true`） |
| 2 | `POST /login/do_login`（`mobile`、`password`） | `password` 是**客户端摘要**，不是明文，见下 |
| 3 | `GET /goods/detail/1` | 拿 `seckillStatus`，应为 1（进行中） |
| 4 | `GET /seckill/verifyCode?goodsId=1` | 返回 JPEG；答案存在 Redis |
| 5 | `GET /seckill/path?goodsId=1&verifyCode=<答案>` | 拿隐藏秒杀路径 |
| 6 | `POST /seckill/{path}/seckill?goodsId=1` | 返回 `data=0` 表示已入队 |
| 7 | `GET /seckill/result?goodsId=1` | 轮询；`>0` 成功，`-1` 失败，`0` 继续等 |
| 8 | `GET /order/detail?orderId=<id>` | 查订单详情 |

**关于登录密码**：客户端提交的是 `md5( salt[0] + salt[2] + 明文 + salt[5] + salt[4] )`，其中 `salt = "1a2b3c4d"`（来自 `static/js/common.js` 的 `g_passsword_salt`）。明文 `123456` 对应的摘要是 `d3b1294a61a07da49b49b6e22b2cbd7f9`。
> 建议在脚本里**从明文现算**而不要硬编码这串 32 位摘要——服务端的 `@Length(min=32)` 只校验长度下限，抄错一位会一路通过参数校验、最后才以"密码错误"失败，很难排查。本次审查过程中就实际踩过这个坑。

### 6.6 常见报错

| 现象 | 原因与处置 |
|---|---|
| 启动报 `ClassNotFoundException: com.mysql.cj.jdbc.Driver` | 打出的 jar 里还是 5.1.x 驱动。改完 `pom.xml` 后需要**重新 package**（Maven 在启动时读取 pom，改完必须重建） |
| 启动报 `Public Key Retrieval is not allowed` | JDBC URL 缺 `allowPublicKeyRetrieval=true`（MySQL 8 的 `caching_sha2_password` 在非 SSL 连接下需要） |
| 启动报 `The server time zone value ... is unrecognized` | JDBC URL 缺 `serverTimezone` |
| 启动时卡住或失败 | Redis 不可达。确认 VMware 虚拟机已开机、`<REDACTED-REDIS-HOST>:6379` 可达 |
| 登录返回 `500215 密码错误！` | 客户端摘要算错（见 6.5）。也可直接查库比对：`select password, salt from seckill_user where id=13000000000;` |
| 接口返回 `500103 请求过于频繁` | 命中 `@AccessLimit`。注意它的窗口是**每个用户 + 每个 URI** 各 5 秒 5 次 |
| 接口返回 `500102 请求非法！` | 秒杀 path 校验失败（路径过期或错误），或验证码已被使用（验证码是一次性的） |
| `seckill_goods` 导入后显示"秒杀已结束" | 用的是旧版 SQL。新脚本的时间窗口是相对 `NOW()` 的 |
| 中文商品名乱码 | mysql 客户端字符集。用 `--default-character-set=utf8mb4`，脚本内已有 `SET NAMES utf8mb4` |

---

## 7. 验证记录

验证脚本位于仓库之外：`D:\Projects\java-projects\seckill\verify-seckill.ps1`（不进版本库）。

### 7.1 构建

```
[INFO] Compiling 62 source files to ...\target\classes
[INFO] BUILD SUCCESS
```

Java 版本确认为 `openjdk version "1.8.0_452"`（Corretto）。

### 7.2 环境与兼容性

- **RabbitMQ 兼容性（改造前的最大风险）**：Spring Boot 1.5.10 自带的是 2016 年的 `amqp-client 4.0.2`，需要连 2025 年的 RabbitMQ 4.3.5。实测**连接成功**：

  ```
  Created new connection: rabbitConnectionFactory#6b44881f:0/SimpleConnection@61bce36f
    [delegate=amqp://guest@127.0.0.1:5672/, localPort= 56229]
  ```
  管理 API 确认 `rabbitmq_version: 4.3.5`，且 `seckill.queue` 已由应用启动时声明。

- **Druid 连接池**：Bean 创建成功（说明 Connector/J 8.0.33 + `caching_sha2_password` 认证打通，且连接池参数已能绑定）。
- **Redis**：应用启动时成功写入库存，实测 `GoodsKey:gs1` 存在。

### 7.3 端到端 + 并发验证（27 项全部通过）

| 阶段 | 用例 | 结果 |
|---|---|---|
| A | Redis 可连接 | PASS（`PING -> PONG`） |
| A | 启动预热写入库存 | PASS |
| A | RabbitMQ 已声明 `seckill.queue` | PASS |
| B | 重置接口可用 | PASS |
| B | 登录返回 token | PASS |
| B | 商品详情状态正确 | PASS（`seckillStatus=1`） |
| B | 秒杀处于进行中 | PASS |
| B | 商品列表页可渲染 | PASS（HTTP 200） |
| B | 验证码返回 JPEG 字节流（校验魔数 `FF D8 FF`） | PASS（1893 字节） |
| B | 验证码 `Content-Type: image/jpeg` | PASS（修复项） |
| B | 验证码答案写入 Redis | PASS |
| B | 取得隐藏秒杀地址 | PASS |
| B | 验证码一次性使用 | PASS（重复提交返回 500102） |
| B | 提交秒杀返回排队中 | PASS（`data=0`） |
| B | 轮询拿到订单号（异步下单成功） | PASS（`orderId=15`） |
| B | 订单详情可查询 | PASS（iPhone X，秒杀价 0.01，数量 1） |
| B | 同一用户重复秒杀被拒绝 | PASS（500501） |
| B | **`seckill_order.user_id` 记录真实用户** | PASS（`user_id=13000000000`，硬编码 bug 已修复） |
| C | 创建 30 个并发测试用户 | PASS |
| C | 重置后库存基线 = 10 | PASS |
| C | 30 个用户各自取得隐藏地址 | PASS（30/30） |
| C | **并发抢购结果** | **入队 10、售罄 20、其它 0** |
| C | 未超卖：DB 库存未变负 | PASS（`stock_count=0`） |
| C | 未超卖：订单数不超过初始库存 | PASS（两张表各 10 条） |
| C | 库存与订单数守恒 | PASS（`0 + 10 = 10`） |
| C | 无重复下单 | PASS（重复用户数 0） |
| C | `order_info` 与 `seckill_order` 数量一致 | PASS |

**并发实测的关键数字**：库存 10，30 个用户同时提交（各自持有独立 token 与独立秒杀路径，通过 .NET `HttpClient` 并发发出），结果为 10 个请求入队、20 个收到"已售罄"、0 个异常；数据库最终 `seckill_goods.stock_count = 0`、`seckill_order` 与 `order_info` 各 10 条、无任何用户出现两条记录。

**同时实测到的一个设计特性**：抢购结束后 Redis 里的库存计数为 `-18`，而数据库为 `0`。原因是"先 DECR 再判断"——被拒绝的请求也已经执行了扣减。这印证了 §3 的结论：**Redis 那个值只是闸门，不是剩余库存**，不应对外展示。

### 7.4 关于验证脚本本身的三个坑（供复用者参考）

审查过程中，验证脚本自身出过 3 个错，都会表现为"应用有问题"的假象，记录在此以免重复踩：

1. **PowerShell 5.1 在中文 Windows 下按 GBK 读取无 BOM 的 `.ps1`**，中文注释与字符串会乱码并导致解析失败。脚本必须存为 **UTF-8 with BOM**。
2. **PowerShell 逗号运算符优先级高于 `+`**。`@('GET', 'key' + $id + ',1')` 会被拆成 4 个参数，Redis 返回 `-ERR wrong number of arguments for 'get' command`。必须先在单独语句里拼好键名。
3. **不要把 Redis 错误回复当成取值返回**。脚本的 RESP 解析若对 `-ERR` 直接 fallthrough 返回原文，这条错误会被拼进 URL 参数，最终表现为服务端 500（`NumberFormatException`），极难定位。解析函数应当对 `-` 开头的回复显式报错。

---

## 8. 建议的后续工作（按优先级）

1. **轮换已泄露的凭据**，并评估线上演示服务器是否仍需保留（优先级最高，与代码无关）。
2. 恢复 `LoginInterceptor` 注册，让认证与限流职责分离（P1-1）。
3. 为存量数据库补 `uk_user_goods` 唯一索引——**先清理重复数据**（P1-2）。
4. 修正页面缓存的用户维度问题（P1-3）。
5. 升级 fastjson 至 1.2.83+，并把 `GoodsDetailVO` / 前端字段名对齐（P2-3、P2-4）。
6. 把前端轮询间隔从 50ms 放宽，或在回调中处理非 0 返回码（P2-2）。
7. 补并发测试用例，把"不超卖、不重复下单"固化成回归测试——目前这两个性质只靠人工验证保障。
8. 把提交进仓库的 `.idea/` 移出版本控制，并把 IDE 的 JDK 从 21 改回 1.8。
