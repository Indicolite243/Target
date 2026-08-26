package com.stockmanager.quanttask.vo;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class QuantTaskViewJsonTests {

    @Test
    void serializesSnowflakeIdentifiersAsStringsForJavaScriptClients() throws Exception {
        QuantTaskView view = new QuantTaskView(2092481069495476225L, 2084946502790262786L,
                "BACKTEST", "SUCCEEDED", 100, "已完成", "BACKTEST", 2092481069495476226L,
                Map.of(), null, null, LocalDateTime.now(), null, null);

        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(view);

        assertTrue(json.contains("\"id\":\"2092481069495476225\""));
        assertTrue(json.contains("\"accountId\":\"2084946502790262786\""));
        assertTrue(json.contains("\"resultId\":\"2092481069495476226\""));
    }
}
