package com.godenglow.seckill.ai;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.godenglow.seckill.dao.OrderDao;
import com.godenglow.seckill.domain.OrderInfo;
import com.godenglow.seckill.domain.SeckillUser;
import com.godenglow.seckill.service.GoodsService;
import com.godenglow.seckill.vo.GoodsVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * 提供给大模型调用的业务工具集（Function Calling）。
 *
 * <p>这三个工具是这个 AI 模块存在的理由：大模型不知道"现在还剩几件""活动开始了没有"
 * "我到底抢到没有"，而这些恰恰是秒杀用户唯一关心的问题。工具直接读数据库里的
 * 真实状态，模型负责把结果组织成人话——<b>数字全部来自业务系统，模型不允许自己编</b>。
 *
 * <p>身份相关的工具（查我的订单）不接受用户 id 作为参数，而是由服务端从已登录的
 * {@link SeckillUser} 注入。否则模型可能被诱导去查别人的订单。
 */
@Component
public class AiToolKit {

    private static final Logger log = LoggerFactory.getLogger(AiToolKit.class);
    private static final int RECENT_ORDER_LIMIT = 3;

    @Autowired
    private GoodsService goodsService;

    @Autowired
    private OrderDao orderDao;

    /** 工具声明，直接放进请求体的 tools 字段 */
    public JSONArray definitions() {
        JSONArray tools = new JSONArray();

        tools.add(function("getGoodsStock",
                "查询某件秒杀商品当前的库存数量和秒杀价。用户问“还剩多少件”“还有货吗”时调用。",
                param("goodsId", "integer", "商品ID，例如 1 表示 iPhone X")));

        tools.add(function("getSeckillActivity",
                "查询某件秒杀商品的活动时间窗口与当前状态（未开始/进行中/已结束）以及距开始或结束的秒数。"
                        + "用户问“什么时候开始”“还有多久结束”“为什么不能买”时调用。",
                param("goodsId", "integer", "商品ID")));

        tools.add(function("getMyOrders",
                "查询当前登录用户最近的秒杀订单。用户问“我抢到了吗”“我的订单呢”“怎么还没发货”时调用。"
                        + "不需要任何参数，服务端会自动限定为当前登录用户。",
                null));

        return tools;
    }

    /**
     * 执行工具调用。
     *
     * @param name      工具名
     * @param arguments 模型给出的参数 JSON 字符串
     * @param user      当前登录用户（用于身份相关工具）
     * @return 结果的 JSON 字符串，会作为 role=tool 的消息回传给模型
     */
    public String execute(String name, String arguments, SeckillUser user) {
        try {
            JSONObject args = (arguments == null || arguments.trim().isEmpty())
                    ? new JSONObject() : JSONObject.parseObject(arguments);
            switch (name) {
                case "getGoodsStock":
                    return getGoodsStock(args.getLongValue("goodsId"));
                case "getSeckillActivity":
                    return getSeckillActivity(args.getLongValue("goodsId"));
                case "getMyOrders":
                    return getMyOrders(user);
                default:
                    return error("未知的工具: " + name);
            }
        } catch (Exception e) {
            log.warn("工具 {} 执行失败, arguments={}", name, arguments, e);
            return error("工具执行失败: " + e.getMessage());
        }
    }

    private String getGoodsStock(long goodsId) {
        GoodsVO goods = goodsService.getGoodsVOById(goodsId);
        if (goods == null) {
            return error("商品不存在: goodsId=" + goodsId);
        }
        JSONObject data = new JSONObject();
        data.put("goodsId", goods.getId());
        data.put("goodsName", goods.getGoodsName());
        data.put("stockCount", goods.getStockCount());
        data.put("seckillPrice", goods.getSeckillPrice());
        data.put("soldOut", goods.getStockCount() == null || goods.getStockCount() <= 0);
        return data.toJSONString();
    }

    private String getSeckillActivity(long goodsId) {
        GoodsVO goods = goodsService.getGoodsVOById(goodsId);
        if (goods == null) {
            return error("商品不存在: goodsId=" + goodsId);
        }
        long now = System.currentTimeMillis();
        long startAt = goods.getStartTime().getTime();
        long endAt = goods.getEndTime().getTime();

        String status;
        long remainSeconds;
        if (now < startAt) {
            status = "未开始";
            remainSeconds = (startAt - now) / 1000;
        } else if (now > endAt) {
            status = "已结束";
            remainSeconds = -1;
        } else {
            status = "进行中";
            remainSeconds = (endAt - now) / 1000;
        }

        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        JSONObject data = new JSONObject();
        data.put("goodsId", goods.getId());
        data.put("goodsName", goods.getGoodsName());
        data.put("startTime", fmt.format(goods.getStartTime()));
        data.put("endTime", fmt.format(goods.getEndTime()));
        data.put("status", status);
        data.put("remainSeconds", remainSeconds);
        return data.toJSONString();
    }

    private String getMyOrders(SeckillUser user) {
        if (user == null) {
            return error("用户未登录");
        }
        List<OrderInfo> orders = orderDao.listRecentOrders(user.getId(), RECENT_ORDER_LIMIT);
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

        JSONArray list = new JSONArray();
        for (OrderInfo o : orders) {
            JSONObject item = new JSONObject();
            item.put("orderId", o.getId());
            item.put("goodsName", o.getGoodsName());
            item.put("goodsPrice", o.getGoodsPrice());
            item.put("status", orderStatusText(o.getStatus()));
            item.put("createTime", o.getCreateTime() == null ? null : fmt.format(o.getCreateTime()));
            list.add(item);
        }

        JSONObject data = new JSONObject();
        data.put("userId", user.getId());
        data.put("orderCount", list.size());
        data.put("orders", list);
        if (list.isEmpty()) {
            data.put("hint", "该用户没有任何秒杀订单");
        }
        return data.toJSONString();
    }

    private String orderStatusText(Integer status) {
        if (status == null) {
            return "未知";
        }
        switch (status) {
            case 0: return "新建未支付";
            case 1: return "已支付";
            case 2: return "已发货";
            case 3: return "已收货";
            case 4: return "已退款";
            case 5: return "已完成";
            default: return "未知";
        }
    }

    private String error(String message) {
        JSONObject err = new JSONObject();
        err.put("error", message);
        return err.toJSONString();
    }

    private JSONObject function(String name, String description, JSONObject goodsIdProp) {
        JSONObject properties = new JSONObject();
        JSONArray required = new JSONArray();
        if (goodsIdProp != null) {
            properties.put("goodsId", goodsIdProp);
            required.add("goodsId");
        }

        JSONObject schema = new JSONObject();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);

        JSONObject fn = new JSONObject();
        fn.put("name", name);
        fn.put("description", description);
        fn.put("parameters", schema);

        JSONObject tool = new JSONObject();
        tool.put("type", "function");
        tool.put("function", fn);
        return tool;
    }

    private JSONObject param(String name, String type, String description) {
        JSONObject p = new JSONObject();
        p.put("type", type);
        p.put("description", description);
        return p;
    }
}
