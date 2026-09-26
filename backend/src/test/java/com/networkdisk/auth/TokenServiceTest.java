package com.networkdisk.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class TokenServiceTest {
    private static final String SECRET = Base64.getEncoder().encodeToString(
            "unit-test-key-longer-than-thirty-two-bytes".getBytes(StandardCharsets.UTF_8));

    @Test
    void validSignedTokenReturnsUserId() {
        TokenService tokens = new TokenService(SECRET, 3600);
        String token = tokens.create(42L, "hash-one");
        var claims = tokens.verify(token);
        assertThat(claims).isPresent();
        assertThat(claims.orElseThrow().userId()).isEqualTo(42L);
        assertThat(tokens.hasCurrentPassword(claims.orElseThrow(), "hash-one")).isTrue();
        assertThat(tokens.hasCurrentPassword(claims.orElseThrow(), "hash-two")).isFalse();
        assertThat(tokens.create(42L, "hash-one")).isNotEqualTo(token);
    }

    @Test
    void tamperedPayloadOrSignatureIsRejected() {
        TokenService tokens = new TokenService(SECRET, 3600);
        String[] parts = tokens.create(42L, "hash-one").split("\\.");
        String changedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"sub\":\"43\",\"exp\":9999999999}".getBytes(StandardCharsets.UTF_8));

        assertThat(tokens.verify(parts[0] + "." + changedPayload + "." + parts[2])).isEmpty();
        assertThat(tokens.verify(parts[0] + "." + parts[1] + "." + "AAAA")).isEmpty();
    }

    @Test
    void expiredMalformedAndWrongKeyTokensAreRejected() {
        TokenService tokens = new TokenService(SECRET, -1);
        TokenService otherKey = new TokenService(Base64.getEncoder().encodeToString(
                "another-unit-test-key-longer-than-32-bytes".getBytes(StandardCharsets.UTF_8)), 3600);

        assertThat(tokens.verify(tokens.create(42L, "hash-one"))).isEmpty();
        assertThat(tokens.verify("garbage")).isEmpty();
        assertThat(tokens.verify(otherKey.create(42L, "hash-one"))).isEmpty();
    }
}
