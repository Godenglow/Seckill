package com.godenglow.seckill.dao;

import com.godenglow.seckill.domain.SeckillGoods;
import com.godenglow.seckill.vo.GoodsVO;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface GoodsDao {

    @Select("select g.*, sg.seckill_price, sg.stock_count, sg.start_time, sg.end_time from seckill_goods sg left join goods g on sg.goods_id=g.id")
    List<GoodsVO> listGoodsVO();

    @Select("select g.*, sg.seckill_price, sg.stock_count, sg.start_time, sg.end_time from seckill_goods sg left join goods g on sg.goods_id=g.id where g.id=#{goodsId}")
    GoodsVO getGoodsVOByGoodsId(@Param("goodsId") long goodsId);

    /**
     * 原子减库存。stock_count > 0 保证不会超卖，
     * start_time / end_time 保证活动时间窗口之外无法下单
     * ——时间窗口过去只用于页面展示，数据库层没有任何约束，过了结束时间照样能买。
     */
    @Update("update seckill_goods set stock_count=stock_count-1 " +
            "where goods_id=#{goodsId} and stock_count > 0 " +
            "and start_time <= now() and end_time >= now()")
    int reduceStock(SeckillGoods g);

    @Update("update seckill_goods set stock_count = #{stockCount} where goods_id = #{goodsId}")
    int resetStock(SeckillGoods g);
}
