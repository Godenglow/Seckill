package com.godenglow.seckill.ai;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.godenglow.seckill.domain.SeckillUser;
import com.godenglow.seckill.redis.AiChatKey;
import com.godenglow.seckill.redis.RedisService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 秒杀场景的 AI 助手。
 *
 * <p>解决的问题很具体：活动期间"还剩多少件""什么时候开始""我抢到了吗"这类咨询量暴增，
 * 而答案全都在业务系统里、大模型并不知道。所以这个助手不是一个通用聊天框，
 * 它的价值来自 {@link AiToolKit} —— 把库存、活动时间窗、用户订单三个真实数据源
 * 通过 Function Calling 接给模型，数字一律来自数据库，模型只负责组织语言。
 *
 * <p>会话记忆存在 Redis：把每一轮问答按 {@code AiChatKey:session<userId>:<sessionId>}
 * 存成 JSON 数组，下次请求整段回传给模型，从而支持"刚才说的那个商品还剩多少"这类追问。
 */
@Service
public class AiChatService {

    private static final Logger log = LoggerFactory.getLogger(AiChatService.class);

    /**
     * 回传给模型的历史消息上限。多轮对话越往后 token 越多、越贵也越慢，
     * 所以只保留最近 20 条（约 10 轮问答）；再往前的对话被丢弃。
     */
    private static final int MAX_HISTORY_MESSAGES = 20;

    private static final String SYSTEM_PROMPT =
            "你是秒杀平台的客服助手。用户会问商品库存、秒杀活动时间、自己的订单状态。"
                    + "凡是涉及库存数量、时间、订单的问题，必须先调用提供的工具获取真实数据，"
                    + "严禁凭印象编造任何数字。回答用中文，控制在两三句话内，直接给结论。";

    @Autowired
    private DeepSeekClient deepSeekClient;

    @Autowired
    private AiToolKit toolKit;

    @Autowired
    private RedisService redisService;

    @Value("${ai.enabled:false}")
    private boolean enabled;

    /**
     * SSE 的推送必须发生在请求线程之外：{@code SseEmitter} 在返回给 Spring 之前
     * 会把 send() 的内容先缓存起来，等于所有分片一起下发、流式效果就没了。
     * 所以这里用一个独立线程池把活儿挪出去。
     */
    private static final AtomicInteger THREAD_SEQ = new AtomicInteger();

    private final ExecutorService executor = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "ai-chat-" + THREAD_SEQ.incrementAndGet());
        t.setDaemon(true);
        return t;
    });

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
    }

    public boolean isAvailable() {
        return enabled && deepSeekClient.isConfigured();
    }

    /**
     * 发起一次对话，以 SSE 增量返回。
     *
     * @param user      当前登录用户
     * @param sessionId 会话标识，由前端生成并复用
     * @param question  用户这一轮的问题
     */
    public SseEmitter chat(SeckillUser user, String sessionId, String question) {
        SseEmitter emitter = new SseEmitter(5 * 60 * 1000L);
        String sessionKey = user.getId() + ":" + sessionId;

        executor.execute(() -> {
            try {
                answer(user, sessionKey, question, emitter);
            } catch (Exception e) {
                log.warn("AI 对话失败, userId={}, session={}", user.getId(), sessionId, e);
                sendQuietly(emitter, "err", "AI 服务暂时不可用，请稍后再试");
            } finally {
                // 必须显式发一个结束事件：SSE 连接一断，浏览器的 EventSource 会按规范
                // 自动重连，把这个请求再发一遍——用户会看到同一个问题被反复回答。
                // 前端收到 done 后主动 close，才是正确的收尾方式。
                sendQuietly(emitter, "done", "");
                emitter.complete();
            }
        });
        return emitter;
    }

    private void answer(SeckillUser user, String sessionKey, String question, SseEmitter emitter) throws Exception {
        List<Map<String, Object>> messages = loadHistory(sessionKey);
        messages.add(message("user", question));

        // 第一轮先用非流式调用：这一轮的目的是问模型"要不要查数据"，用户看不到过程，
        // 没有流式的必要；而且流式下拼接 tool_calls 的分片很啰嗦，容易出错。
        JSONObject first = deepSeekClient.chat(messages, toolKit.definitions());
        JSONArray toolCalls = first.getJSONArray("tool_calls");

        if (toolCalls == null || toolCalls.isEmpty()) {
            // 模型判断不需要查数据（问候、闲聊等），它给的就是最终答案，直接下发，省一次调用
            String content = first.getString("content");
            sendQuietly(emitter, "delta", content == null ? "" : content);
            saveHistory(sessionKey, messages, content);
            return;
        }

        // 只做一轮工具解析，不做多轮循环。理由：
        //  1. 模型可以在同一条响应里要求调用多个工具（tool_calls 是数组），
        //     "还剩几件、什么时候开始"这类复合问题一轮就能解析完；
        //  2. 解析完立刻转流式生成最终回答，避免"非流式先答一遍、流式再答一遍"的重复调用。
        //   代价是模型无法在看到工具结果后再要第二次工具调用——对这三个只读工具来说划算，
        //   同时也从根本上杜绝了工具调用的无限循环。
        messages.add(assistantToolCallMessage(first));
        for (int i = 0; i < toolCalls.size(); i++) {
            JSONObject call = toolCalls.getJSONObject(i);
            JSONObject fn = call.getJSONObject("function");
            String name = fn.getString("name");
            String args = fn.getString("arguments");

            // 把"正在查什么"作为独立事件推给前端：既能让用户看到进度，
            // 也是这个助手真的调用了业务系统（而不是模型编答案）的直接证据
            sendQuietly(emitter, "tool", name);
            log.info("AI 调用工具: userId={}, tool={}, args={}", user.getId(), name, args);

            String result = toolKit.execute(name, args, user);
            messages.add(toolResultMessage(call.getString("id"), result));
        }

        // 带着工具结果做流式生成。这里不再传 tools，避免模型又发起一轮调用
        StringBuilder answer = new StringBuilder();
        deepSeekClient.chatStream(messages, null, delta -> {
            answer.append(delta);
            sendQuietly(emitter, "delta", delta);
        });

        saveHistory(sessionKey, messages, answer.toString());
    }

    // ------------------------------------------------------------------
    // 会话记忆
    // ------------------------------------------------------------------

    private List<Map<String, Object>> loadHistory(String sessionKey) {
        List<Map<String, Object>> messages = new ArrayList<>();
        Map<String, Object> system = message("system", SYSTEM_PROMPT);
        messages.add(system);

        String json = redisService.get(AiChatKey.session, sessionKey, String.class);
        if (json != null && !json.isEmpty()) {
            JSONArray history = JSON.parseArray(json);
            for (int i = 0; i < history.size(); i++) {
                JSONObject item = history.getJSONObject(i);
                messages.add(message(item.getString("role"), item.getString("content")));
            }
        }
        return messages;
    }

    /**
     * 只持久化"用户问题 + 助手回答"，不存工具调用的中间消息。
     * 工具结果是某一时刻的数据快照，留在历史里会让模型误以为库存一直没变。
     */
    private void saveHistory(String sessionKey, List<Map<String, Object>> messages, String answerText) {
        JSONArray history = new JSONArray();
        for (Map<String, Object> m : messages) {
            Object role = m.get("role");
            if (!"user".equals(role) && !"assistant".equals(role)) {
                continue;   // 丢掉 system 与 tool
            }
            Object content = m.get("content");
            if (content == null || content.toString().isEmpty()) {
                continue;   // 丢掉只有 tool_calls、没有正文的 assistant 消息
            }
            JSONObject item = new JSONObject();
            item.put("role", role);
            item.put("content", content);
            history.add(item);
        }

        if (history.size() > MAX_HISTORY_MESSAGES) {
            JSONArray trimmed = new JSONArray();
            for (int i = history.size() - MAX_HISTORY_MESSAGES; i < history.size(); i++) {
                trimmed.add(history.get(i));
            }
            history = trimmed;
        }

        redisService.set(AiChatKey.session, sessionKey, history.toJSONString());
    }

    // ------------------------------------------------------------------
    // 消息构造与下发
    // ------------------------------------------------------------------

    private Map<String, Object> message(String role, String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    private Map<String, Object> assistantToolCallMessage(JSONObject assistantMsg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", "assistant");
        m.put("content", assistantMsg.getString("content"));
        m.put("tool_calls", assistantMsg.getJSONArray("tool_calls"));
        return m;
    }

    private Map<String, Object> toolResultMessage(String toolCallId, String result) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", "tool");
        m.put("tool_call_id", toolCallId);
        m.put("content", result);
        return m;
    }

    /**
     * 客户端可能已经断开（用户关页面），此时 send 会抛异常。
     * 这类异常不值得中断整个回答流程，记一条日志即可。
     */
    private void sendQuietly(SseEmitter emitter, String event, String data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
        } catch (Exception e) {
            log.debug("SSE 下发失败（客户端可能已断开）: event={}, {}", event, e.getMessage());
        }
    }
}
