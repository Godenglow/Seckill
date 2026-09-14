package com.godenglow.seckill.ai;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * DeepSeek 对话接口的极简客户端。
 *
 * <p><b>为什么手写而不用 Spring AI：</b>Spring AI 要求 Spring Boot 3.4+ / Java 17，
 * 而本项目是 Spring Boot 1.5.10 / Java 8，用不了。这里直接按 OpenAI 兼容协议发 HTTP，
 * 用 {@link HttpURLConnection} 拿到响应流后逐行解析 SSE，因此不需要引入任何新依赖
 * （JSON 用项目已有的 fastjson）。
 *
 * <p>两个方法分别对应两种调用形态：
 * <ul>
 *   <li>{@link #chat}：非流式。用于"判断是否要调用工具"的那几轮——
 *       这几轮的结果用户看不到，没有流式的必要，而且流式下拼接 tool_calls 分片很啰嗦。</li>
 *   <li>{@link #chatStream}：流式。用于最后一轮生成用户可见的回答，逐块回调。</li>
 * </ul>
 */
@Component
public class DeepSeekClient {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekClient.class);

    /** 连接/读取超时。大模型首字延迟通常在 1 秒上下，30 秒足够覆盖慢响应 */
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 30_000;

    @Value("${ai.deepseek.api-key:}")
    private String apiKey;

    @Value("${ai.deepseek.base-url:https://api.deepseek.com}")
    private String baseUrl;

    @Value("${ai.deepseek.model:deepseek-chat}")
    private String model;

    @Value("${ai.deepseek.temperature:0.7}")
    private double temperature;

    public boolean isConfigured() {
        return apiKey != null && !apiKey.trim().isEmpty();
    }

    /**
     * 非流式对话。返回完整的 assistant 消息对象（可能带 tool_calls）。
     *
     * @param messages 完整消息列表，格式与 OpenAI 协议一致
     * @param tools    工具声明；传 null 表示不启用工具
     */
    public JSONObject chat(List<Map<String, Object>> messages, JSONArray tools) {
        JSONObject payload = buildPayload(messages, tools, false);
        HttpURLConnection conn = null;
        try {
            conn = open("/chat/completions", payload);
            String body = readAll(conn.getInputStream());
            JSONObject resp = JSON.parseObject(body);
            JSONArray choices = resp.getJSONArray("choices");
            if (choices == null || choices.isEmpty()) {
                throw new IllegalStateException("DeepSeek 返回内容为空: " + body);
            }
            return choices.getJSONObject(0).getJSONObject("message");
        } catch (Exception e) {
            throw new IllegalStateException("调用 DeepSeek 失败: " + e.getMessage(), e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * 流式对话。每收到一个增量片段就回调一次 {@code onDelta}。
     *
     * <p>只处理 {@code delta.content}；工具调用不走这个方法（见类注释）。
     */
    public void chatStream(List<Map<String, Object>> messages, JSONArray tools, Consumer<String> onDelta) {
        JSONObject payload = buildPayload(messages, tools, true);
        HttpURLConnection conn = null;
        try {
            conn = open("/chat/completions", payload);
            InputStream in = conn.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring(5).trim();
                if (data.isEmpty() || "[DONE]".equals(data)) {
                    continue;
                }
                JSONObject chunk = JSON.parseObject(data);
                JSONArray choices = chunk.getJSONArray("choices");
                if (choices == null || choices.isEmpty()) {
                    continue;
                }
                JSONObject delta = choices.getJSONObject(0).getJSONObject("delta");
                if (delta == null) {
                    continue;
                }
                String content = delta.getString("content");
                if (content != null && !content.isEmpty()) {
                    onDelta.accept(content);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("流式调用 DeepSeek 失败: " + e.getMessage(), e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private JSONObject buildPayload(List<Map<String, Object>> messages, JSONArray tools, boolean stream) {
        JSONObject payload = new JSONObject(true);
        payload.put("model", model);
        payload.put("messages", messages);
        payload.put("temperature", temperature);
        payload.put("stream", stream);
        if (tools != null && !tools.isEmpty()) {
            payload.put("tools", tools);
        }
        return payload;
    }

    private HttpURLConnection open(String path, JSONObject payload) throws Exception {
        URL url = new URL(baseUrl + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setRequestProperty("Accept", "text/event-stream");
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setDoOutput(true);
        byte[] body = JSON.toJSONString(payload).getBytes(StandardCharsets.UTF_8);
        conn.setFixedLengthStreamingMode(body.length);
        try (OutputStream out = conn.getOutputStream()) {
            out.write(body);
        }
        int code = conn.getResponseCode();
        if (code != 200) {
            String err = readAll(conn.getErrorStream());
            throw new IllegalStateException("HTTP " + code + " " + err);
        }
        return conn;
    }

    private String readAll(InputStream in) throws Exception {
        if (in == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        }
        return sb.toString();
    }
}
