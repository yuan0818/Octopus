package io.yuan.octopus.scenario.testing;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.*;

class VerificationControllerTest {
    ProjectContextService contexts = mock(ProjectContextService.class);
    FlowModelClient model = mock(FlowModelClient.class);
    VerificationController controller = new VerificationController(contexts, model);
    VerificationController.Request request() {
        var definition = new ProjectContextService.Definition("订单业务", "支付后状态 PAID", "https://do-not-send.example.com",
                List.of(new ProjectContextService.Connection("connection", "订单库", "mysql", "private-db", 3306, "private-dbname", "orders(id,status)")));
        when(contexts.get("repo", "module", "test")).thenReturn(new ProjectContextService.Resolved(null, definition, Map.of(), false, true));
        return new VerificationController.Request(DatastoreChecksTest.check("SQL", "order.id").connection(), "test", "支付后状态为 PAID", Map.of("order.id", "string"), "default");
    }
    @Test void modelGetsOnlySchemaAndDeclarationsAndReturnsReviewedDraft() {
        var request = request();
        when(model.verification(anyString(), anyMap())).thenReturn(new FlowModelClient.Response(TestingJson.canonical(new VerificationController.Draft(DatastoreChecksTest.check("SQL", "order.id"), List.of(), List.of())), "actual-model"));
        var result = controller.suggest(request);
        assertEquals("actual-model", result.model()); assertTrue(result.preview().contains("WHERE `id` = ?"));
        var capture = ArgumentCaptor.forClass(Map.class); verify(model).verification(eq("default"), capture.capture());
        String sent = TestingJson.canonical(capture.getValue());
        assertFalse(sent.contains("private-db")); assertFalse(sent.contains("https://")); assertTrue(sent.contains("orders(id,status)"));
        verify(contexts, never()).runtimeConnection(any(), any(), any());
    }
    @Test void missingSchemaRemainsQuestionAndInventedVariableIsRejected() {
        var request = request();
        when(model.verification(anyString(), anyMap())).thenReturn(new FlowModelClient.Response("{\"plan\":null,\"questions\":[\"请补充订单表字段\"],\"assumptions\":[]}", "model"));
        assertNull(controller.suggest(request).plan());
        when(model.verification(anyString(), anyMap())).thenReturn(new FlowModelClient.Response(TestingJson.canonical(new VerificationController.Draft(DatastoreChecksTest.check("SQL", "invented.id"), List.of(), List.of())), "model"));
        assertThrows(IllegalArgumentException.class, () -> controller.suggest(request));
    }
}
