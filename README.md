# Seckill 秒杀系统

基于 Spring Boot 的秒杀练手项目，使用 Redis 预减库存、RabbitMQ 异步下单和数据库唯一索引处理高并发请求。

## 主要功能

- 用户登录、商品列表与详情展示
- 秒杀下单、排队结果查询与订单查询
- 秒杀地址隐藏、算术验证码与接口限流
- 可选 AI 客服，支持查询库存、活动时间和本人订单

## 技术栈

Java 8、Spring Boot 1.5、MyBatis、MySQL、Redis、RabbitMQ、Thymeleaf、jQuery、Bootstrap。

## 项目结构

| 目录 | 说明 |
| --- | --- |
| `src/main/java` | 后端业务源码 |
| `src/main/resources/templates` | 服务端页面模板 |
| `src/main/resources/static` | 静态页面与前端资源 |
| `src/main/resources/seckill.sql` | 数据库初始化脚本 |

## 本地运行

需要 JDK 8、Maven、MySQL、Redis 和 RabbitMQ。

1. 启动数据库、缓存和消息队列，导入 `src/main/resources/seckill.sql`。
2. 将 [配置模板](src/main/resources/application-local.properties.example) 复制为同目录下的 `application-local.properties`，填写本机连接信息。
3. 在 JDK 8 环境中构建并启动：

```bash
mvn package
java -jar target/seckill-1.0.0.jar
```

访问 [登录页面](http://localhost:8088/login/to_login)。秒杀前需在数据库中配置有效的活动时间和库存。

AI 客服为可选功能；在本机配置中设置 `ai.enabled=true` 和 `ai.deepseek.api-key` 后启用，未配置时可设为 `false`。

基于开源秒杀示例二次开发，遵循 [Apache-2.0](LICENSE) 许可。
