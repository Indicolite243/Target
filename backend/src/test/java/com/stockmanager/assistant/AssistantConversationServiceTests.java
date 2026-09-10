package com.stockmanager.assistant;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import com.stockmanager.common.exception.BusinessException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AssistantConversationServiceTests {
    @Test void preventsReadingAnotherUsersMessages() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq("other"), eq(7L))).thenReturn(0);
        var error = assertThrows(BusinessException.class,
                () -> new AssistantConversationService(jdbc).messages(7, "other"));
        assertEquals(404, error.getStatus().value());
        verify(jdbc).queryForObject(anyString(), eq(Integer.class), eq("other"), eq(7L));
        verifyNoMoreInteractions(jdbc);
    }
    @Test void rejectsBlankTitleBeforeDatabaseAccess() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        assertThrows(BusinessException.class, () -> new AssistantConversationService(jdbc).rename(7, "id", "  "));
        verifyNoInteractions(jdbc);
    }
    @Test void deletesOnlyWithinAuthenticatedUserScope() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        assertThrows(BusinessException.class, () -> new AssistantConversationService(jdbc).delete(7, "id"));
        verify(jdbc).update("DELETE FROM ai_conversation WHERE id=? AND user_id=?", "id", 7L);
        verifyNoMoreInteractions(jdbc);
    }
}
