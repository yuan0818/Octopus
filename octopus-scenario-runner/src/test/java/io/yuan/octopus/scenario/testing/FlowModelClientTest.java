package io.yuan.octopus.scenario.testing;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

class FlowModelClientTest {
    @Test void structuredUpstreamStatusIsPreservedWithoutCopyingProviderBodies() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/business-flow/step-suggest",exchange->{var b="{\"code\":\"MODEL_AUTH\",\"upstreamStatus\":401,\"message\":\"password=do-not-display\"}".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(503,b.length);exchange.getResponseBody().write(b);exchange.close();});server.start();
        try {
            var client=new FlowModelClient("http://127.0.0.1:"+server.getAddress().getPort());var error=assertThrows(FlowFailure.class,()->client.step("fixture",Map.of()));
            assertEquals(FlowFailure.Code.MODEL_AUTH,error.code);assertEquals(401,error.upstreamStatus);assertFalse(TestingJson.canonical(error.detail("GENERATING","first")).contains("do-not-display"));
        } finally {server.stop(0);}
    }
    @Test void timeoutIsDistinctFromMalformedSuccessfulResponse() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/business-flow/step-suggest",exchange->{try{Thread.sleep(200);}catch(InterruptedException e){Thread.currentThread().interrupt();}var b="not-json-fixture".getBytes(StandardCharsets.UTF_8);try{exchange.sendResponseHeaders(200,b.length);exchange.getResponseBody().write(b);}finally{exchange.close();}});server.start();
        try {
            String url="http://127.0.0.1:"+server.getAddress().getPort();
            var slow=new FlowModelClient(url,Duration.ofMillis(50));assertEquals(FlowFailure.Code.MODEL_TIMEOUT,assertThrows(FlowFailure.class,()->slow.step("fixture",Map.of())).code);
            var malformed=new FlowModelClient(url,Duration.ofSeconds(3));assertEquals(FlowFailure.Code.MODEL_RESPONSE_INVALID,assertThrows(FlowFailure.class,()->malformed.step("fixture",Map.of())).code);
        } finally {server.stop(0);}
    }
}
