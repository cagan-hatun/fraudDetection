package com.fraud.project.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import tools.jackson.databind.ObjectMapper;

class LoginRateLimitFilterTest {

    private LoginRateLimitFilter filter;

    @BeforeEach
    void setUp() {
        filter = new LoginRateLimitFilter(new ObjectMapper());
    }

    private MockHttpServletRequest loginRequest(String remoteAddr) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        request.setRemoteAddr(remoteAddr);
        return request;
    }

    @Test
    void nonLoginRequest_bypassesRateLimitingEntirely() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/demo/scenarios");
        request.setRemoteAddr("10.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void firstFiveLoginAttemptsFromSameIp_passThrough() throws Exception {
        for (int i = 1; i <= 5; i++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(loginRequest("192.168.1.1"), response, chain);

            assertThat(chain.getRequest()).as("attempt %d should pass through", i).isNotNull();
        }
    }

    @Test
    void sixthLoginAttemptFromSameIpWithinWindow_isRejectedWith429() throws Exception {
        for (int i = 1; i <= 5; i++) {
            filter.doFilter(loginRequest("192.168.1.2"), new MockHttpServletResponse(), new MockFilterChain());
        }

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(loginRequest("192.168.1.2"), response, chain);

        assertThat(chain.getRequest()).as("6th attempt must not reach the real login handler").isNull();
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getContentAsString()).contains("Too Many Requests");
    }

    @Test
    void differentIps_haveIndependentLimits() throws Exception {
        for (int i = 1; i <= 5; i++) {
            filter.doFilter(loginRequest("10.0.0.5"), new MockHttpServletResponse(), new MockFilterChain());
        }

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(loginRequest("10.0.0.6"), response, chain);

        assertThat(chain.getRequest()).as("a different IP's exhausted bucket must not affect this one").isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
    }
}
