package com.networkdisk.auth;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class LoginRequestValidationTest {
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    void validUsernameAndPasswordShouldPass() {
        assertThat(validator.validate(new LoginRequest("user001", "123456"))).isEmpty();
    }

    @Test
    void blankUsernameShouldFail() {
        assertThat(validator.validate(new LoginRequest("", "123456")))
                .anyMatch(violation -> violation.getPropertyPath().toString().equals("username"));
    }

    @Test
    void shortUsernameShouldFail() {
        assertThat(validator.validate(new LoginRequest("ab", "123456")))
                .anyMatch(violation -> violation.getPropertyPath().toString().equals("username"));
    }

    @Test
    void shortPasswordShouldFail() {
        assertThat(validator.validate(new LoginRequest("user001", "12345")))
                .anyMatch(violation -> violation.getPropertyPath().toString().equals("password"));
    }
}
