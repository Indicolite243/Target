package com.stockmanager.system.auth.security;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * Removes BCrypt's raw-input length limitation without invalidating existing
 * BCrypt hashes. New hashes are explicitly marked and contain BCrypt(SHA-256(password)).
 */
public final class CompatiblePasswordEncoder implements PasswordEncoder {

    static final String PREHASHED_PREFIX = "{bcrypt-sha256}";
    private static final String DELEGATING_BCRYPT_PREFIX = "{bcrypt}";

    private final BCryptPasswordEncoder bcrypt;

    public CompatiblePasswordEncoder() {
        this(new BCryptPasswordEncoder());
    }

    CompatiblePasswordEncoder(BCryptPasswordEncoder bcrypt) {
        this.bcrypt = bcrypt;
    }

    @Override
    public String encode(CharSequence rawPassword) {
        if (rawPassword == null) {
            throw new IllegalArgumentException("rawPassword cannot be null");
        }
        return PREHASHED_PREFIX + bcrypt.encode(prehash(rawPassword));
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
        if (rawPassword == null || encodedPassword == null || encodedPassword.isBlank()) {
            return false;
        }

        try {
            if (encodedPassword.startsWith(PREHASHED_PREFIX)) {
                return bcrypt.matches(
                        prehash(rawPassword),
                        encodedPassword.substring(PREHASHED_PREFIX.length())
                );
            }

            String legacyHash = encodedPassword.startsWith(DELEGATING_BCRYPT_PREFIX)
                    ? encodedPassword.substring(DELEGATING_BCRYPT_PREFIX.length())
                    : encodedPassword;
            return bcrypt.matches(rawPassword, legacyHash);
        } catch (IllegalArgumentException exception) {
            // An overlong password cannot match a legacy BCrypt(raw) hash.
            return false;
        }
    }

    @Override
    public boolean upgradeEncoding(String encodedPassword) {
        if (encodedPassword == null || !encodedPassword.startsWith(PREHASHED_PREFIX)) {
            return true;
        }
        return bcrypt.upgradeEncoding(encodedPassword.substring(PREHASHED_PREFIX.length()));
    }

    private static String prehash(CharSequence rawPassword) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(rawPassword.toString().getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
