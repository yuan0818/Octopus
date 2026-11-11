package io.yuan.octopus.review.service;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import io.yuan.octopus.review.adapter.*;
import io.yuan.octopus.review.dto.LLMConfig;
import io.yuan.octopus.review.model.*;
import io.yuan.octopus.review.controller.BusinessFlowSuggestionController;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.util.*;

class BusinessModelFailureTest {
    @Test void providerFailureFlowsThroughReviewBoundaryWithoutBodyAndWithoutHiddenRetry() throws Exception {
        for(int status:List.of(401,429,503)) {
            var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var calls=new java.util.concurrent.atomic.AtomicInteger();
            server.createContext("/chat/completions",exchange->{calls.incrementAndGet();var bytes="private-token-from-provider".getBytes();exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();});server.start();
            try {
                var registry=mock(ModelRegistry.class);var model=new ModelConfig("fixture","openai","fixture-model","http://127.0.0.1:"+server.getAddress().getPort(),1000,0.2);model.setApiKey("fixture-key");when(registry.getAllConfigs()).thenReturn(Map.of("fixture",model));
                var llm=new LLMIntegrationService(List.of(new HttpLLMAdapter()),registry);var service=new BusinessFlowSuggestionService(registry,llm);
                var e=assertThrows(ModelCallException.class,()->service.verification(new BusinessFlowSuggestionService.VerificationRequest("fixture",Map.of())));
                assertEquals(status,e.upstreamStatus);assertEquals(1,calls.get());var body=new BusinessFlowSuggestionController(service).modelFailure(e).getBody();assertFalse(new com.google.gson.Gson().toJson(body).contains("private-token"));
            } finally {server.stop(0);}
        }
    }
    @Test void timeoutCauseAndBadResponseHaveStableCategories() {
        assertEquals("MODEL_TIMEOUT",ModelCallException.classify(new RuntimeException(new java.net.http.HttpTimeoutException("private-url"))).code);
        assertEquals("MODEL_RESPONSE_INVALID",ModelCallException.classify(new com.google.gson.JsonSyntaxException("private-value")).code);
    }
}
