package com.godenglow.seckill.dao;

import com.godenglow.seckill.domain.OrderInfo;
import com.godenglow.seckill.domain.SeckillOrder;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface OrderDao {

    @Select("select * from seckill_order where user_id=#{userId} and goods_id=#{goodsId}")
    SeckillOrder getSeckillOrderByUserIdGoodsId(@Param("userId") Long userId, @Param("goodsId") long goodsId);

    @Insert("insert into order_info(user_id, goods_id, goods_name, goods_count, goods_price, order_channel, status, create_time)values("
            + "#{userId}, #{goodsId}, #{goodsName}, #{goodsCount}, #{goodsPrice}, #{orderChannel},#{status},#{createTime} )")
    @SelectKey(keyColumn = "id", keyProperty = "id", resultType = Long.class, before = false, statement = "SELECT LAST_INSERT_ID()")
    long insert(OrderInfo orderInfo);

    @Insert("insert into seckill_order(user_id,goods_id,order_id) values(#{userId},#{goodsId},#{orderId})")
    void insertSeckillOrder(SeckillOrder seckillOrder);

    @Select("select * from order_info where id = #{orderId}")
    OrderInfo getOrderById(long orderId);

    /**
     * 取某用户最近的若干笔订单，供 AI 助手的"查我的订单"工具使用。
     * 只取最近几条：助手是拿来回答"我抢到了吗"，不需要全量订单列表。
     */
    @Select("select * from order_info where user_id = #{userId} order by id desc limit #{limit}")
    List<OrderInfo> listRecentOrders(@Param("userId") long userId, @Param("limit") int limit);

    @Delete("delete from order_info")
    public void deleteOrders();

    @Delete("delete from seckill_order")
    public void deleteSeckillOrders();
}
