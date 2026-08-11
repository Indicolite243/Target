package com.stockmanager.system.auth.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

class CompatiblePasswordEncoderTests {

    private final CompatiblePasswordEncoder encoder = new CompatiblePasswordEncoder();

    @Test
    void newShortPasswordUsesPrehashedFormatAndRoundTrips() {
        String password = "123456";

        String encoded = encoder.encode(password);

        assertThat(encoded).startsWith(CompatiblePasswordEncoder.PREHASHED_PREFIX);
        assertThat(encoded).hasSizeLessThanOrEqualTo(100);
        assertThat(encoder.matches(password, encoded)).isTrue();
        assertThat(encoder.matches("wrong", encoded)).isFalse();
    }

    @Test
    void longUnicodeAndSpecialCharacterPasswordRoundTrips() {
        String password = "超长密码!@#$%^&*()_+-=[]{};':\",./<>?".repeat(30);

        String encoded = encoder.encode(password);

        assertThat(password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isGreaterThan(72);
        assertThat(encoder.matches(password, encoded)).isTrue();
    }

    @Test
    void existingRawBcryptHashRemainsValid() {
        String password = "Legacy123!";
        String legacyHash = new BCryptPasswordEncoder().encode(password);

        assertThat(encoder.matches(password, legacyHash)).isTrue();
        assertThat(encoder.matches("wrong", legacyHash)).isFalse();
        assertThat(encoder.upgradeEncoding(legacyHash)).isTrue();
    }
}
