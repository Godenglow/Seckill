package com.godenglow.seckill.vo;

import com.godenglow.seckill.domain.Goods;
import lombok.Data;

import java.util.Date;

@Data
public class GoodsVO extends Goods {

    private Double seckillPrice;
    private Integer stockCount;
    private Date startTime;
    private Date endTime;

}
