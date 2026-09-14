package com.godenglow.seckill.controller;

import com.godenglow.seckill.access.AccessLimit;
import com.godenglow.seckill.domain.SeckillOrder;
import com.godenglow.seckill.domain.SeckillUser;
import com.godenglow.seckill.rabbitmq.MQSender;
import com.godenglow.seckill.rabbitmq.SeckillMessage;
import com.godenglow.seckill.redis.*;
import com.godenglow.seckill.result.CodeMsg;
import com.godenglow.seckill.result.Result;
import com.godenglow.seckill.service.GoodsService;
import com.godenglow.seckill.service.OrderService;
import com.godenglow.seckill.service.SeckillService;
import com.godenglow.seckill.vo.GoodsVO;
import io.swagger.annotations.ApiImplicitParam;
import io.swagger.annotations.ApiImplicitParams;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import javax.imageio.ImageIO;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.awt.image.BufferedImage;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 秒杀核心接口。
 *
 * <p>下单链路的处理顺序：校验个人秒杀路径 → 查内存售罄标记 → 查是否已秒杀过 →
 * Redis 预减库存 → 投递消息到队列后立即返回。真正的减库存与写订单由
 * {@link com.godenglow.seckill.rabbitmq.MQReceiver} 异步完成，
 * 客户端通过 {@link #result} 轮询结果。
 *
 * <p>两处刻意的设计取舍：
 * <ul>
 *   <li><b>判重放在预减之前。</b>否则重复提交会白扣一份 Redis 库存，而那份库存在
 *       数据库里并没有对应订单，结果是少卖加上提前"售罄"。</li>
 *   <li><b>Redis 里的库存计数允许被扣成负数。</b>它只是流量闸门，不是剩余库存——
 *       被拒绝的请求也执行过 DECR。真实库存以数据库的 stock_count 为准。</li>
 * </ul>
 *
 * <p>防刷由 {@code @AccessLimit} 拦截器承担，秒杀地址隐藏由 {@link #getSeckillPath} 承担。
 */
@Controller
@RequestMapping("/seckill")
public class SeckillController implements InitializingBean {
    @Autowired
    private GoodsService goodsService;

    @Autowired
    private OrderService orderService;

    @Autowired
    private SeckillService seckillService;

    @Autowired
    private RedisService redisService;

    @Autowired
    private MQSender sender;

    /**
     * 秒杀数据重置接口的开关。只有显式配置 seckill.reset.enabled=true 才允许调用，
     * 默认关闭——该接口会重置库存并清空全部订单，不能在生产环境暴露。
     */
    @Value("${seckill.reset.enabled:false}")
    private boolean resetEnabled;

    /**
     * 内存售罄标记，用于在售罄后直接短路，省掉一次 Redis 访问。
     * 必须用并发容器：多个请求线程会并发读写这个 Map。
     */
    private Map<Long, Boolean> localOverMap = new ConcurrentHashMap<>();

    /**
     * 系统初始化
     *
     * @throws Exception
     */
    @Override
    public void afterPropertiesSet() throws Exception {
        List<GoodsVO> goodsVOList = goodsService.listGoodsVO();
        if (goodsVOList == null) {
            return;
        }

        for (GoodsVO goodsVO : goodsVOList) {
            redisService.set(GoodsKey.getSeckillGoodsStock, "" + goodsVO.getId(), goodsVO.getStockCount());
            localOverMap.put(goodsVO.getId(), false);
        }
    }

    @ApiOperation("db 数据重置接口")
    @GetMapping("/reset")
    @ResponseBody
    public Result<Boolean> reset() {
        //该接口会重置库存、删除全部订单并清空相关缓存，属于测试辅助功能，
        //默认关闭，只有配置 seckill.reset.enabled=true 时才允许调用。
        if (!resetEnabled) {
            return Result.error(CodeMsg.REQUEST_ILLEGAL);
        }
        List<GoodsVO> goodsVOList = goodsService.listGoodsVO();
        for (GoodsVO goodsVO : goodsVOList) {
            goodsVO.setStockCount(10);
            redisService.set(GoodsKey.getSeckillGoodsStock, "" + goodsVO.getId(), 10);
            localOverMap.put(goodsVO.getId(), false);
        }
        redisService.delete(OrderKey.getSeckillOrderByUidGid);
        redisService.delete(SeckillKey.isGoodsOver);
        seckillService.reset(goodsVOList);
        return Result.success(true);
    }

    @ApiOperation("秒杀接口")
    @ApiImplicitParams({
            @ApiImplicitParam(name = "goodsId", value = "商品ID", required = true, dataType = "Long"),
            @ApiImplicitParam(name = "path", value = "秒杀个人路径", required = true, dataType = "String", paramType = "path")
    })
    @PostMapping("/{path}/seckill")
    @ResponseBody
    @AccessLimit(seconds = 5, maxCount = 5)
    public Result<Integer> seckill(SeckillUser seckillUser,
                                   @RequestParam("goodsId") long goodsId,
                                   @PathVariable("path") String path) {

        //验证path
        boolean check = seckillService.checkPath(seckillUser, goodsId, path);
        if (!check) {
            return Result.error(CodeMsg.REQUEST_ILLEGAL);
        }
        //内存标记，减少redis访问。
        //用 Boolean.TRUE.equals 而不是直接拆箱：goodsId 若不在 map 中，get 返回 null，
        //直接赋给 boolean 会抛 NullPointerException（运行时新增的商品就会触发）。
        if (Boolean.TRUE.equals(localOverMap.get(goodsId))) {
            return Result.error(CodeMsg.SECKILL_OVER);
        }

        //判断是否已经秒杀到了。
        //必须在预减库存之前：否则重复提交会白白扣掉一份 Redis 库存，
        //而这份库存在数据库里并没有对应的订单，导致少卖和提前"售罄"。
        SeckillOrder seckillOrder = orderService.getSeckillOrderByUserIdGoodsId(seckillUser.getId(), goodsId);
        if (seckillOrder != null) {
            return Result.error(CodeMsg.REPEATE_SECKILL);
        }

        //预减库存
        long stock = redisService.decr(GoodsKey.getSeckillGoodsStock, "" + goodsId);
        if (stock < 0) {
            localOverMap.put(goodsId, true);
            return Result.error(CodeMsg.SECKILL_OVER);
        }

        //入队
        SeckillMessage message = new SeckillMessage();
        message.setSeckillUser(seckillUser);
        message.setGoodsId(goodsId);
        sender.sendSeckillMessage(message);

        //排队中
        return Result.success(0);
    }

    @ApiOperation("秒杀路径获取接口")
    @ApiImplicitParams({
            @ApiImplicitParam(name = "goodsId", value = "商品ID", required = true, dataType = "Long"),
            @ApiImplicitParam(name = "verifyCode", value = "验证码", required = true, dataType = "Integer")
    })
    @GetMapping("/path")
    @ResponseBody
    @AccessLimit(seconds = 5, maxCount = 5)
    public Result<String> getSeckillPath(HttpServletRequest request, SeckillUser seckillUser,
                                         @RequestParam("goodsId") long goodsId,
                                         @RequestParam(value = "verifyCode", defaultValue = "0") int verifyCode) {

        boolean check = seckillService.checkVerifyCode(seckillUser, goodsId, verifyCode);
        if (!check) {
            return Result.error(CodeMsg.REQUEST_ILLEGAL);
        }
        String path = seckillService.createSeckillPath(seckillUser, goodsId);
        return Result.success(path);
    }

    /**
     * orderId:成功
     * -1:秒杀失败
     * 0:排队中
     *
     * @param seckillUser
     * @param goodsId
     * @return
     */
    @ApiOperation("轮询秒杀是否成功接口")
    @ApiImplicitParam(name = "goodsId", value = "商品ID", required = true, dataType = "Long")
    @GetMapping("/result")
    @ResponseBody
    @AccessLimit(seconds = 5, maxCount = 5)
    public Result<Long> result(SeckillUser seckillUser,
                               @RequestParam("goodsId") long goodsId) {
        long result = seckillService.getSeckillResult(seckillUser.getId(), goodsId);
        return Result.success(result);
    }

    @ApiOperation("获取验证码接口")
    @ApiImplicitParam(name = "goodsId", value = "商品ID", required = true, dataType = "Long")
    @GetMapping("/verifyCode")
    @ResponseBody
    @AccessLimit(seconds = 5, maxCount = 5)
    public Result<String> getVerifyCode(HttpServletResponse response, SeckillUser seckillUser,
                                        @RequestParam("goodsId") long goodsId) {
        //这个接口返回的是 JPEG 图片流，不是 JSON。
        //原先声明成 application/json 是错的：浏览器 <img> 不看 Content-Type 所以能显示，
        //但对任何遵循该头部的客户端都是错误信息。
        response.setContentType("image/jpeg");
        BufferedImage image = seckillService.createVerifyCode(seckillUser, goodsId);
        try {
            OutputStream outputStream = response.getOutputStream();
            ImageIO.write(image, "JPEG", outputStream);
            outputStream.flush();
            outputStream.close();
            return null;
        } catch (Exception e) {
            e.printStackTrace();
            return Result.error(CodeMsg.SECKILL_FAIL);
        }
    }
}
