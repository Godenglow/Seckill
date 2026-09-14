                                                               __   _ ____
                                               ________  _____/ /__(_) / /
                                              / ___/ _ \/ ___/ //_/ / / / 
                                             (__  )  __/ /__/ ,< / / / /  
                                            /____/\___/\___/_/|_/_/_/_/
<br/>

## 高并发的瓶颈在数据库

> 原 README 在此处公开了一台线上演示服务器的 IP、登录手机号和密码。该服务器与那套
> 凭据已从本仓库移除（公开凭据一旦进入版本库就无法真正撤回）。
> 请按下文"本地运行"一节在自己的机器上搭建环境。

### 本地运行

1. 准备 MySQL / Redis / RabbitMQ，并导入 `src/main/resources/seckill.sql`
   （脚本现在自带建库语句，可直接执行）。
2. 复制 `src/main/resources/application-local.properties.example` 为
   `application-local.properties`，填入自己环境的连接信息。
   该文件已在 `.gitignore` 中忽略，凭据不会进入版本库。
3. 用 JDK 8 启动：`mvn spring-boot:run`（默认端口 8088）。
4. 测试账号见 `seckill.sql` 末尾的种子数据。登录前先访问一次
   `http://localhost:8088/seckill/reset` 重置库存与订单。

> ⚠️ `/seckill/reset` 会清空全部订单并重置库存，仅在
> `application-local.properties` 中显式设置 `seckill.reset.enabled=true` 后才可用。

<hr>
<br>


### 减少数据库访问
思路：<br>
1. 系统初始化，把商品库存数量加载到Redis<br>
2. 收到请求，Redis预减库存，库存不足，直接返回，否则进入3<br>
3. 请求入队，立即返回排队中<br>
4. 请求出队，生成订单，减少库存<br>
5. 客户端轮询，是否秒杀成功
<hr>
<br>



### 项目框架
1. Spring Boot环境搭建<br>
2. 集成Thymeleaf，Result结果封装<br>
3. 集成Mybatis+Druid<br>
4. 集成Jedis+Redis安装+通用缓存Key封装

### 页面优化技术
1. 页面缓存+URL缓存+对象缓存<br>
2. 页面静态化，前后端分离

### 接口优化
1. Redis预减库存减少数据库访问<br>
2. 内存标记减少Redis访问<br>
3. RabbitMQ队列缓冲，异步下单，增强用户体验

### 安全优化
#### 1.秒杀接口地址隐藏
秒杀开始之前，先去请求接口获取秒杀地址<br>
思路：<br>
  (1) 接口改造，带上PathVariable参数<br>
  (2) 添加生成地址的接口<br>
  (3) 秒杀收到请求，先验证PathVariable<br>

#### 2.数学公式验证码
点击秒杀之后，先输入验证码，分散用户请求<br>
思路：<br>
  (1) 添加生成验证码的接口<br>
  (2) 在获取秒杀路径的时候，验证验证码<br>
  (3) 使用ScriptEngine<br>

#### 3.接口防刷
对接口做限流<br>
思路：
* 用拦截器减少对业务侵入


