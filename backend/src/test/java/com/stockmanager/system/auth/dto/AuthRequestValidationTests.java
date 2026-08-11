package com.stockmanager.system.auth.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuthRequestValidationTests {

    private static Validator validator;

    @BeforeAll
    static void createValidator() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    void registerAllowsAnyNonBlankUsernameAndPasswordCharactersOrLength() {
        String username = "用户-!@#$%^&*()_+ " + "x".repeat(180);
        String password = "密码-!@#$%^&*()_+ " + "p".repeat(180);
        RegisterRequest request = new RegisterRequest(username, password, password, username);

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void loginAllowsSingleCharacterCredentials() {
        LoginRequest request = new LoginRequest("!", "?", false);

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void credentialsStillMustNotBeBlank() {
        RegisterRequest registerRequest = new RegisterRequest(" ", "", " ", null);
        LoginRequest loginRequest = new LoginRequest("", " ", false);

        assertThat(validator.validate(registerRequest)).hasSize(3);
        assertThat(validator.validate(loginRequest)).hasSize(2);
    }
}
