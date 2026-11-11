package io.yuan.octopus.scenario.spi;

import java.util.Map;

/**
 * 编解码插件：请求方向 encode（逻辑对象 → 线上字节），响应方向
 * decode（线上字节 → 逻辑文档）。实现：ser.json、crypto.aesgcm、
 * 将来的 ser.form / ser.xml。
 */
public interface Codec {

    /** 工具名（IR tool call 的 type，如 "crypto.aesgcm"）。 */
    String name();

    /** 逻辑值 → 线上表示。 */
    Object encode(Object logical, Map<String, Object> params, Map<String, Object> scopeContext);

    /** 线上表示 → 逻辑值。 */
    Object decode(Object wire, Map<String, Object> params, Map<String, Object> scopeContext);
}
