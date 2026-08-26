package com.stockmanager.quanttask.service;

import com.stockmanager.quanttask.entity.QuantTask;
import com.stockmanager.quanttask.mapper.QuantTaskMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QuantTaskServiceTests {
    @Test
    void stateClaimsAndPendingCancellationUseConditionalUpdates() {
        QuantTaskMapper mapper = mock(QuantTaskMapper.class);
        QuantTaskService service = new QuantTaskService(mapper, new ObjectMapper());

        when(mapper.update(any(QuantTask.class), any())).thenReturn(1, 0);

        assertTrue(service.markRunning(11L));
        assertFalse(service.cancelPending(11L));
    }

    @Test
    void restartRecoveryReportsNumberOfInterruptedTasks() {
        QuantTaskMapper mapper = mock(QuantTaskMapper.class);
        QuantTaskService service = new QuantTaskService(mapper, new ObjectMapper());

        when(mapper.update(any(QuantTask.class), any())).thenReturn(3);

        assertTrue(service.failInterruptedAfterRestart() == 3);
    }
}
