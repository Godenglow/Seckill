# 秒杀系统（Seckill）

高并发秒杀场景的后端实现：Redis 预减库存挡住无效流量、RabbitMQ 异步削峰下单、
数据库唯一索引兜底防重，配合隐藏秒杀地址、算术验证码与接口限流三层防刷。

另附一个**基于 Function Calling 的 AI 客服助手**：把库存、活动时间、用户订单三个
真实数据源接给大模型，让它能回答业务问题而不是凭空编造。

## 目录

- [核心设计](#核心设计)
- [技术栈](#技术栈)
- [下单链路](#下单链路)
- [关键取舍](#关键取舍)
- [AI 助手（Function Calling + 会话记忆）](#ai-助手function-calling--会话记忆)
- [本地运行](#本地运行)
- [接口一览](#接口一览)
- [并发验证](#并发验证)

## 核心设计

秒杀场景要同时解决两个矛盾：瞬时流量远大于库存，而数据库连接是稀缺资源。
本项目把请求处理拆成三级，让绝大多数请求在离数据库最远的地方就被拒绝。

| 层级 | 手段 | 作用 |
|---|---|---|
| 第一级 | 本地售罄标记（`ConcurrentHashMap`） | 售罄后直接在 JVM 内返回，不访问任何外部依赖 |
| 第二级 | Redis 预减库存 | 用 `DECR` 的原子性把库存判定挡在数据库之前 |
| 第三级 | 数据库原子 UPDATE | `where stock_count > 0` 是唯一真正的正确性保证 |

配套的三层防刷：

1. **秒杀地址隐藏** —— 下单接口拆成"取路径"和"带路径下单"两步，路径由 `MD5(UUID + 固定盐)` 生成、存 Redis 且 60 秒过期，脚本无法直接压下单接口。
2. **算术验证码** —— 服务端生成算式并渲染成图片，答案存 Redis 且校验一次即删除。它的价值在于分散请求、拉平流量峰值，不是安全屏障。
3. **接口限流** —— 自定义 `@AccessLimit(seconds, maxCount)` 注解 + 拦截器实现，按"用户 + URI"维度在 Redis 计数，对业务代码零侵入。

页面侧采用"页面缓存 + 静态化"：列表页与详情页手动调 Thymeleaf 引擎渲染成 HTML 字符串后整串缓存进 Redis；秒杀交互页做成纯静态 `.htm`，由前端直接调 JSON 接口，前后端分离。

## 技术栈

| 层次 | 选型 |
|---|---|
| 框架 | Spring Boot 1.5.10 / Spring MVC 4.3 |
| 模板 | Thymeleaf（服务端渲染 + 手动渲染做页面缓存） |
| 持久层 | MyBatis（纯注解 Mapper，无 XML）、Druid 连接池 |
| 数据库 | MySQL 8 |
| 缓存 | Redis（Jedis 直连，含通用缓存 Key 前缀抽象） |
| 消息队列 | RabbitMQ（消费端 `prefetch=1`，10 并发消费者） |
| 序列化 | fastjson |
| 其他 | Lombok、Swagger（接口文档）、Hibernate Validator |

## 下单链路

```
启动     预热：把 seckill_goods.stock_count 读进 Redis，初始化本地售罄标记

① GET  /seckill/verifyCode?goodsId=N
       服务端生成算术题 → AWT 渲染 JPEG 返回；答案存 Redis（TTL 300s）

② GET  /seckill/path?goodsId=N&verifyCode=A
       校验验证码（通过即删除，一次性使用）→ 生成个人秒杀路径（TTL 60s）

③ POST /seckill/{path}/seckill?goodsId=N
       校验 path → 查本地售罄标记 → 查是否已秒杀过 → Redis 预减库存
       → 投递消息到 seckill.queue → 立即返回 data=0（排队中）

④ MQ 消费端
       再查一次数据库库存与重复订单 → 事务内：原子减库存 + 写 order_info
       + 写 seckill_order + 回写 Redis 订单缓存

⑤ GET  /seckill/result?goodsId=N
       客户端轮询：orderId 表示成功，-1 表示已售罄失败，0 表示继续等待
```

## 关键取舍

**判重必须放在预减库存之前。** 如果先扣 Redis 再判重，重复提交会白扣一份库存，而这份库存在数据库里没有对应订单——结果是少卖，同时提前显示"售罄"。

**Redis 里的库存计数允许被扣成负数，它不是"剩余库存"。** 被拒绝的请求也执行过 `DECR`，所以并发结束后这个值会比 0 更小。它只是流量闸门；真实库存以数据库的 `stock_count` 为准，不要把它读出来做展示。

**数据库唯一索引是防重的最后一道防线。** `seckill_order` 上有 `(user_id, goods_id)` 唯一约束。应用层的 Redis 判重在缓存丢失（重启、被清空）时不可靠，唯一索引保证即使应用层判断失效也不会产生重复订单。

**减库存的 SQL 带时间窗口条件。** `start_time <= now() and end_time >= now()` 写在 UPDATE 里，而不是只在页面上判断——时间窗口必须由数据库把关，否则过了结束时间仍可下单。

**秒杀路径在 TTL 内可重复使用。** 这是有意的取舍：路径用于阻挡"不知道地址就盲目压接口"的脚本，而不是做成一次性的防重凭证（防重由第 ③ 步的判重和唯一索引承担）。

## AI 助手（Function Calling + 会话记忆）

活动期间"还剩多少件""什么时候开始""我抢到了吗"这类咨询量会暴增，而答案全在业务系统里、大模型并不知道。所以这里没有做一个通用聊天框，而是把三个**真实数据源**通过 Function Calling 接给模型：

| 工具 | 数据来源 | 回答什么 |
|---|---|---|
| `getGoodsStock(goodsId)` | `seckill_goods.stock_count` | 还剩多少件 |
| `getSeckillActivity(goodsId)` | `seckill_goods` 时间窗 | 什么时候开始 / 结束 |
| `getMyOrders()` | `order_info`（用户身份由服务端注入，不接受模型传参） | 我抢到了吗 |

**数字一律来自数据库，模型只负责组织语言**，系统提示里明确禁止凭印象编造库存与时间。回答以 SSE 增量下发，前端边收边渲染。

三个工程决策：

1. **分两阶段调用。** 第一轮非流式（判断要不要查数据，用户看不到过程），第二轮流式（生成用户可见的回答）。这样既避开流式下拼接 `tool_calls` 分片的复杂度，也不让工具轮白耗一次流式调用。**只做一轮工具解析**：模型能在同一条响应里批量要求调用多个工具，"还剩几件、什么时候开始"一轮就够；代价是无法在看到工具结果后再要第二次调用，但这也从根本上杜绝了工具调用的无限循环。
2. **会话只存问答，不存工具结果。** 工具返回的是某一时刻的数据快照，留在历史里会让模型误以为库存一直没变；因此只持久化 user / assistant 两条，超过 20 条（约 10 轮）即裁剪。会话键为 `AiChatKey:session<userId>:<sessionId>`，把 userId 编进键里以避免猜中 sessionId 就能读到别人的对话，TTL 30 分钟。
3. **手写客户端，不用 Spring AI。** Spring AI 要求 Spring Boot 3.4+ / Java 17，本项目是 Boot 1.5.10 / Java 8，用不了。因此直接按 OpenAI 兼容协议发 HTTP、逐行解析 SSE，JSON 复用项目已有的 fastjson —— **没有引入任何新依赖**。

> **实测与已知取舍**：首字延迟约 1.9 秒、整段回答约 2.1 秒。延迟几乎全部来自第一轮非流式的工具解析；想再压下去可以把工具轮也改成流式解析 `tool_calls` 分片，这是目前没做的优化。另外回答按提示控制在两三句，所以流式在这个长度下收益有限——它的价值在长回答。

## 本地运行

### 前置依赖

- JDK 8（Spring Boot 1.5 系列不支持更高版本运行）
- MySQL 8
- Redis
- RabbitMQ 3.8+（本项目在 RabbitMQ 4.3.5 上实测通过）

### 初始化数据库

```bash
mysql -uroot -p --default-character-set=utf8mb4 < src/main/resources/seckill.sql
```

脚本会建库、建 5 张表并写入种子数据（2 件秒杀商品 + 2 个测试账号）。

### 配置

```bash
cp src/main/resources/application-local.properties.example \
   src/main/resources/application-local.properties
```

然后按本机情况填写数据库、Redis、RabbitMQ 的连接信息。该文件已在 `.gitignore` 中，凭据不会进入版本库；`application.properties` 只放不含凭据的公共配置。

**启用 AI 助手（可选）**：在同一文件里配置

```properties
ai.enabled=true
ai.deepseek.api-key=sk-你的key
```

不配置也能正常启动，只是 `/ai/chat` 会返回"未启用"的提示。

### 启动

```bash
mvn -DskipTests clean package
java -jar target/seckill-1.0.0.jar
```

默认端口 8088。确认启动成功的标志：

```
Tomcat started on port(s): 8088 (http)
Started SeckillApplication in 15.047 seconds
```

> ⚠️ 应用启动时会往 Redis 写库存，**Redis 不可达会导致启动直接失败**（不是降级运行）。

### 重置测试数据

`GET /seckill/reset` 会重置库存并清空全部订单，属于测试辅助接口。默认关闭，只有配置 `seckill.reset.enabled=true` 才可用。

## 接口一览

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/login/to_login` | 登录页 |
| POST | `/login/do_login` | 登录，返回 token（同时写 cookie） |
| GET | `/goods/to_list` | 商品列表页（页面缓存） |
| GET | `/goods/detail/{id}` | 商品详情 JSON，含秒杀状态与倒计时 |
| GET | `/goods/to_detail/{id}` | 商品详情页（URL 级页面缓存） |
| GET | `/seckill/verifyCode` | 生成算术验证码图片 |
| GET | `/seckill/path` | 校验验证码后下发个人秒杀路径 |
| POST | `/seckill/{path}/seckill` | 提交秒杀，立即返回排队中 |
| GET | `/seckill/result` | 轮询秒杀结果 |
| GET | `/order/detail` | 订单详情 |
| GET | `/seckill/reset` | 重置库存与订单（默认关闭） |
| GET | `/ai_chat.htm` | AI 助手独立页面 |
| GET | `/ai/chat` | AI 对话（SSE）。事件：`tool` / `delta` / `err` / `done` |

商品列表页与商品详情页右下角有一个**悬浮入口**（`static/js/ai-widget.js`）：点一下弹出对话面板。
面板用 iframe 复用 `/ai_chat.htm`（`?embed=1` 切换紧凑布局），只有首次展开才创建 iframe，因此不影响页面本身的加载性能。

> 登录接口收的 `password` 是**客户端摘要**而非明文：`md5(salt[0]+salt[2]+明文+salt[5]+salt[4])`，客户端固定盐见 `static/js/common.js`。服务端再做一次加盐哈希后比对。

## 并发验证

`CODE_REVIEW.md` 记录了完整的设计评审与问题清单。并发正确性方面，实测场景为
**库存 10 件、30 个用户同时提交**（各自持有独立 token 与独立秒杀路径）：

| 指标 | 结果 |
|---|---|
| 入队（判定成功） | 10 |
| 返回已售罄 | 20 |
| 异常响应 | 0 |
| 数据库 `stock_count` | 0 |
| `order_info` / `seckill_order` 行数 | 10 / 10 |
| 同一用户的重复记录 | 0 |

即：既没有超卖，也没有少卖，且没有产生重复订单。

---

> 本项目基于开源秒杀实现范例二次开发：整体架构思路来自公开范例，缺陷修复、配置外置、
> 本地化运行与并发验证为本项目新增。代码遵循仓库中的 Apache-2.0 许可。
