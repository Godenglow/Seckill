package com.godenglow.seckill.rabbitmq;

import org.springframework.amqp.core.Queue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MQConfig {

    /**
     * 秒杀下单队列。请求校验通过后只投递一条消息就立即返回，
     * 由消费端异步完成"减库存 + 建订单"，把数据库写操作从请求线程上摘下来。
     */
    public static final String SECKILL_QUEUE = "seckill.queue";

    @Bean
    public Queue seckillQueue() {
        return new Queue(SECKILL_QUEUE, true);
    }
}
