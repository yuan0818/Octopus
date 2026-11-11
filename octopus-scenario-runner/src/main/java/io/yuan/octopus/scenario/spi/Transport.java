package io.yuan.octopus.scenario.spi;

import java.util.Map;

/**
 * 传输插件：执行一次线上调用。P1 仅 HTTP 实现；为将来 gRPC 等留位。
 */
public interface Transport {

    String name(); // "http"

    /** 执行请求，返回响应（状态码 + 头 + 体）。 */
    TransportResponse execute(TransportRequest request, Map<String, Object> scopeContext);

    record TransportRequest(String method, String url, Map<String, String> headers, Object body) {}

    record TransportResponse(int status, Map<String, String> headers, Object body) {}
}
