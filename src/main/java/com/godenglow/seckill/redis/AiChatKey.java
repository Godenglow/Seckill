package com.godenglow.seckill.redis;

/**
 * AI 助手多轮会话的缓存键。
 *
 * <p>真实键形如 {@code AiChatKey:session<userId>:<sessionId>}，把 userId 编进键里
 * 是为了让不同用户的会话天然隔离——否则任何人猜到一个 sessionId 就能读到别人的对话。
 *
 * <p>TTL 取 30 分钟：秒杀咨询是"问完即走"的一次性场景，没必要长期保存；
 * 同时也是对 Redis 内存的保护，避免会话无限堆积。
 */
public class AiChatKey extends BasePrefix {

    private AiChatKey(int expireSeconds, String prefix) {
        super(expireSeconds, prefix);
    }

    public static AiChatKey session = new AiChatKey(30 * 60, "session");
}
