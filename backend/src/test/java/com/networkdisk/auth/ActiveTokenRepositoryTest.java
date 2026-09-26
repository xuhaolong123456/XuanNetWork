package com.networkdisk.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class ActiveTokenRepositoryTest {
    @Test
    void tokenIsStoredWithTtlAndCanBeRevokedWithoutExposingItsValueAsKey() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        ActiveTokenRepository repository = new ActiveTokenRepository(redis);

        repository.activate("signed.jwt.value", 42L, 7200);
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(values).set(key.capture(), eq("42"), eq(Duration.ofSeconds(7200)));
        assertThat(key.getValue()).startsWith("auth:token:").doesNotContain("signed.jwt.value");

        when(values.get(key.getValue())).thenReturn("42");
        assertThat(repository.isActive("signed.jwt.value", 42L)).isTrue();
        assertThat(repository.isActive("signed.jwt.value", 43L)).isFalse();

        repository.activate("another.signed.jwt", 42L, 7200);
        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(values, org.mockito.Mockito.times(2)).set(keys.capture(), eq("42"), eq(Duration.ofSeconds(7200)));
        assertThat(keys.getAllValues().get(1)).isNotEqualTo(key.getValue());

        repository.revoke("signed.jwt.value");
        verify(redis).delete(key.getValue());
        verify(redis, never()).delete(keys.getAllValues().get(1));
    }
}
