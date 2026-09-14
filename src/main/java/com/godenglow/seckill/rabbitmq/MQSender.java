package com.godenglow.seckill.rabbitmq;

import com.godenglow.seckill.redis.RedisService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class MQSender {

    @Autowired
    AmqpTemplate amqpTemplate;

    public void sendSeckillMessage(SeckillMessage message) {
        String msg = RedisService.beanToString(message);
        //不要把整条消息打进日志：SeckillMessage 里挂着完整的 SeckillUser 对象，
        //包含密码摘要与盐，而这条日志在每次秒杀时都会写一遍。只记录定位所需的标识。
        log.info("send seckill message: goodsId={}, userId={}",
                message.getGoodsId(),
                message.getSeckillUser() == null ? null : message.getSeckillUser().getId());
        amqpTemplate.convertAndSend(MQConfig.SECKILL_QUEUE, msg);
    }
}
