package io.yuan.octopus.scenario.testing;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

class TestingJsonTest {
    @Test
    void preservesLargeIntegersAndHashesNumericRoundTripsConsistently() {
        var binding =
                TestingJson.read(
                        "{\"source\":\"literal\",\"value\":9007199254740993}",
                        TestingModel.Binding.class);
        assertEquals(new BigDecimal("9007199254740993"), binding.value());
        assertEquals(
                TestingJson.hash(Map.of("status", 200)),
                TestingJson.hash(Map.of("status", new BigDecimal("200.0"))));
        assertFalse(IrValidator.matches(new BigDecimal("9007199254740993.01"), "integer"));
    }
}
