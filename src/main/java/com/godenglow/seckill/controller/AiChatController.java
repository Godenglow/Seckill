package com.godenglow.seckill.controller;

import com.godenglow.seckill.access.AccessLimit;
import com.godenglow.seckill.ai.AiChatService;
import com.godenglow.seckill.domain.SeckillUser;
import com.godenglow.seckill.util.UUIDUtil;
import io.swagger.annotations.ApiImplicitParam;
import io.swagger.annotations.ApiImplicitParams;
import io.swagger.annotations.ApiOperation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * AI 助手接口。
 *
 * <p>用 SSE（{@code text/event-stream}）把回答边生成边推给前端，前端用
 * {@code EventSource} 接收。相比等整段回答生成完再一次性返回，首字延迟从
 * "整段生成时间"降到"首个 token 时间"。
 *
 * <p>四条事件：{@code tool} 表示正在调用哪个业务工具、{@code delta} 是回答的增量文本、
 * {@code err} 表示出错、{@code done} 表示本轮结束（前端据此关闭 EventSource，
 * 否则浏览器会自动重连并重复本次提问）。
 */
@Controller
@RequestMapping("/ai")
public class AiChatController {

    private static final Logger log = LoggerFactory.getLogger(AiChatController.class);

    @Autowired
    private AiChatService aiChatService;

    @ApiOperation("AI 助手对话（SSE 流式）")
    @ApiImplicitParams({
            @ApiImplicitParam(name = "message", value = "用户问题", required = true, dataType = "String"),
            @ApiImplicitParam(name = "sessionId", value = "会话ID，同一会话复用同一个值", required = true, dataType = "String")
    })
    @GetMapping(value = "/chat", produces = "text/event-stream;charset=UTF-8")
    @ResponseBody
    @AccessLimit(seconds = 60, maxCount = 10)
    public SseEmitter chat(SeckillUser user,
                           @RequestParam("message") String message,
                           @RequestParam(value = "sessionId", required = false) String sessionId) {

        // 每次对话都要真实调用一次大模型，既有成本也有延迟，必须限流。
        // 复用既有的 @AccessLimit：每用户每分钟最多 10 次。
        if (!aiChatService.isAvailable()) {
            return errorEmitter("AI 助手未启用：请在 application-local.properties 中配置 ai.enabled=true 与 ai.deepseek.api-key");
        }
        if (message == null || message.trim().isEmpty()) {
            return errorEmitter("问题不能为空");
        }

        if (sessionId == null || sessionId.trim().isEmpty()) {
            sessionId = UUIDUtil.uuid();
        }
        log.info("AI 对话: userId={}, session={}, question={}", user.getId(), sessionId, message);
        return aiChatService.chat(user, sessionId, message.trim());
    }

    /**
     * 出错时也要用 SSE 的形式回答，否则前端 EventSource 拿到的是 JSON，
     * onerror 里读不到具体原因，只能显示一个笼统的"连接失败"。
     */
    private SseEmitter errorEmitter(String reason) {
        SseEmitter emitter = new SseEmitter(0L);
        try {
            emitter.send(SseEmitter.event().name("error").data(reason));
        } catch (Exception e) {
            log.debug("下发错误事件失败: {}", e.getMessage());
        }
        emitter.complete();
        return emitter;
    }
}
