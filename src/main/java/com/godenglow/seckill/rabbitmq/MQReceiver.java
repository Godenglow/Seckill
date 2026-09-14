package com.godenglow.seckill.rabbitmq;

import com.godenglow.seckill.domain.OrderInfo;
import com.godenglow.seckill.domain.SeckillOrder;
import com.godenglow.seckill.domain.SeckillUser;
import com.godenglow.seckill.redis.RedisService;
import com.godenglow.seckill.result.CodeMsg;
import com.godenglow.seckill.result.Result;
import com.godenglow.seckill.service.GoodsService;
import com.godenglow.seckill.service.OrderService;
import com.godenglow.seckill.service.SeckillService;
import com.godenglow.seckill.vo.GoodsVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class MQReceiver {
    @Autowired
    private GoodsService goodsService;

    @Autowired
    private OrderService orderService;

    @Autowired
    private SeckillService seckillService;

    @RabbitListener(queues = MQConfig.SECKILL_QUEUE)
    public void receive(String message) {
        SeckillMessage seckillMessage = RedisService.stringToBean(message, SeckillMessage.class);
        SeckillUser user = seckillMessage.getSeckillUser();
        long goodsId = seckillMessage.getGoodsId();
        //同 MQSender：消息体里含 SeckillUser（密码摘要 + 盐），不要整条打印
        log.info("receive seckill message: goodsId={}, userId={}",
                goodsId, user == null ? null : user.getId());

        //判断库存
        GoodsVO goods = goodsService.getGoodsVOById(goodsId);
        int stock = goods.getStockCount();
        if (stock < 1) {
            return;
        }

        //判断是否已经秒杀到了
        SeckillOrder order = orderService.getSeckillOrderByUserIdGoodsId(user.getId(), goodsId);
        if (order != null) {
            return;
        }

        //减库存 下订单 写入秒杀订单
        seckillService.seckill(user, goods);
    }
}
