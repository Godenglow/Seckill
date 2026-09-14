package com.godenglow.seckill.vo;

import com.godenglow.seckill.domain.SeckillUser;
import lombok.Data;

@Data
public class GoodsDetailVO {

    private int seckillStatus = 0;
    private int remainSeconds = 0;
    private GoodsVO goods;
    private SeckillUser seckillUser;

}
