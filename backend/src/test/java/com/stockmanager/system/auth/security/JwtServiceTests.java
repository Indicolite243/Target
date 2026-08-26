package com.stockmanager.system.auth.security;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTests {

    @Test
    void issuedTokenContainsJtiAndThirtyDayExpiration() {
        JwtService service = new JwtService("a".repeat(32), 2_592_000L);
        Instant before = Instant.now();

        Claims claims = service.parse(service.issue(7L, "alice", "USER"));

        assertThat(claims.getSubject()).isEqualTo("7");
        assertThat(claims.getId()).isNotBlank();
        assertThat(claims.getExpiration().toInstant()).isBetween(before.plusSeconds(2_591_999L),
                before.plusSeconds(2_592_001L));
    }
}
