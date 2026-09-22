package com.fraud.project.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import com.fraud.project.entity.AppRole;
import com.fraud.project.entity.AppUser;
import com.fraud.project.repository.AppUserRepository;

@ExtendWith(MockitoExtension.class)
class AppUserDetailsServiceTest {

    @Mock private AppUserRepository appUserRepository;

    @Test
    void loadUserByUsername_knownUser_mapsToUserDetailsWithRolePrefixedAuthority() {
        AppUser appUser = AppUser.builder()
            .username("analyst")
            .passwordHash("hashed-value")
            .role(AppRole.ANALYST)
            .build();
        when(appUserRepository.findByUsername("analyst")).thenReturn(Optional.of(appUser));

        AppUserDetailsService service = new AppUserDetailsService(appUserRepository);
        UserDetails userDetails = service.loadUserByUsername("analyst");

        assertThat(userDetails.getUsername()).isEqualTo("analyst");
        assertThat(userDetails.getPassword()).isEqualTo("hashed-value");
        assertThat(userDetails.getAuthorities())
            .extracting(Object::toString)
            .containsExactly("ROLE_ANALYST");
    }

    @Test
    void loadUserByUsername_unknownUser_throwsUsernameNotFoundException() {
        when(appUserRepository.findByUsername("nobody")).thenReturn(Optional.empty());

        AppUserDetailsService service = new AppUserDetailsService(appUserRepository);

        assertThatThrownBy(() -> service.loadUserByUsername("nobody"))
            .isInstanceOf(UsernameNotFoundException.class);
    }
}
