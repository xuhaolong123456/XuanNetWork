package com.networkdisk.auth;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class RegisterRequestValidationTest {
    
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @AfterAll
    static void closeValidator() {
        validator = null;
    }

    @Test
    void validRegisterRequestShouldPass() {
        RegisterRequest request = request("user@example.com", "user001", "123456");

        assertThat(validator.validate(request)).isEmpty();
    }

    @ParameterizedTest(name = "邮箱等价类：{0}")
    @MethodSource("invalidEmailCases")
    void invalidEmailEquivalenceClassesShouldFail(String email) {
        RegisterRequest request = request(email, "user001", "123456");

        assertThat(validator.validateProperty(request, "email")).isNotEmpty();
    }

    static Stream<Arguments> invalidEmailCases() {
        return Stream.of(
                Arguments.of((String) null),
                Arguments.of(""),
                Arguments.of("   "),
                Arguments.of("user-example.com"),
                Arguments.of("user@.example")
        );
    }

    @ParameterizedTest(name = "昵称边界：长度 {0}")
    @MethodSource("nickNameBoundaryCases")
    void nickNameBoundaryValuesShouldBeHandled(int length, boolean valid) {
        RegisterRequest request = request("user@example.com", "a".repeat(length), "123456");

        assertThat(validator.validateProperty(request, "nickName").isEmpty()).isEqualTo(valid);
    }

    static Stream<Arguments> nickNameBoundaryCases() {
        return Stream.of(
                Arguments.of(2, false),
                Arguments.of(3, true),
                Arguments.of(32, true),
                Arguments.of(33, false)
        );
    }

    @ParameterizedTest(name = "密码边界：长度 {0}")
    @MethodSource("passwordBoundaryCases")
    void passwordBoundaryValuesShouldBeHandled(int length, boolean valid) {
        RegisterRequest request = request("user@example.com", "user001", "a".repeat(length));

        assertThat(validator.validateProperty(request, "password").isEmpty()).isEqualTo(valid);
    }

    static Stream<Arguments> passwordBoundaryCases() {
        return Stream.of(
                Arguments.of(5, false),
                Arguments.of(6, true),
                Arguments.of(64, true),
                Arguments.of(65, false)
        );
    }

    private static RegisterRequest request(String email, String nickName, String password) {
        return new RegisterRequest(email, nickName, password, null, null);
    }
}
