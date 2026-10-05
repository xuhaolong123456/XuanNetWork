package com.networkdisk.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.networkdisk.auth.User;
import com.networkdisk.auth.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

class DevAdminSeedTest {
    private final UserRepository users = mock(UserRepository.class);
    private final UserFileRepository files = mock(UserFileRepository.class);
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder();
    private final DevAdminSeed seed = new DevAdminSeed(users, files, passwords, "123456");

    @Test
    void createsHashedAdminAndOneFileOwnedOnlyByThatUser() {
        User existingAdmin = new User("admin@local.invalid", "admin", passwords.encode("123456"));
        ReflectionTestUtils.setField(existingAdmin, "id", 42L);
        when(users.findByNickName("admin")).thenReturn(Optional.empty()).thenReturn(Optional.of(existingAdmin));
        when(users.save(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            ReflectionTestUtils.setField(user, "id", 42L);
            return user;
        });
        when(files.existsByOwner_IdAndParentIsNullAndName(42L, DevAdminSeed.DEFAULT_FILE_NAME))
                .thenReturn(false, true);

        seed.run(null);
        seed.run(null);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(users, times(1)).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getNickName()).isEqualTo("admin");
        assertThat(userCaptor.getValue().getPasswordHash()).isNotEqualTo("123456");
        assertThat(passwords.matches("123456", userCaptor.getValue().getPasswordHash())).isTrue();

        ArgumentCaptor<UserFile> fileCaptor = ArgumentCaptor.forClass(UserFile.class);
        verify(files, times(1)).save(fileCaptor.capture());
        assertThat(fileCaptor.getValue().getOwner().getId()).isEqualTo(42L);
        assertThat(fileCaptor.getValue().getName()).isEqualTo(DevAdminSeed.DEFAULT_FILE_NAME);
        assertThat(fileCaptor.getValue().getNodeType()).isEqualTo(FileNodeType.FILE);
    }

    @Test
    void doesNotResetAnExistingAdminPassword() {
        User existing = new User("existing@example.com", "admin", passwords.encode("different"));
        when(users.findByNickName("admin")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> seed.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("密码与配置不一致");
        verify(users, never()).save(any(User.class));
        verify(files, never()).save(any(UserFile.class));
    }
}
