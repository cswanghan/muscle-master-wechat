package com.jisuodashi.rbac;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jisuodashi.common.AppProperties;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class CaptchaFilterTest {

    @Test
    void defaultOffPassesBookingsWithoutToken() throws Exception {
        CaptchaFilter filter = filter(false);
        MockHttpServletRequest request = postBookings();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(request, response, chain);
        verify(chain).doFilter(request, response);
    }

    @Test
    void enabledWithoutTokenIs40001() throws Exception {
        CaptchaFilter filter = filter(true);
        MockHttpServletRequest request = postBookings();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(request, response, chain);
        verify(chain, never()).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains("40001").contains("缺少验证码");
    }

    @Test
    void enabledWithTokenPasses() throws Exception {
        CaptchaFilter filter = filter(true);
        MockHttpServletRequest request = postBookings();
        request.addHeader(CaptchaFilter.HEADER, "ok");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(request, response, chain);
        verify(chain).doFilter(request, response);
    }

    @Test
    void otherPathsAreIgnoredWhenEnabled() throws Exception {
        CaptchaFilter filter = filter(true);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/c/auth/wechat");
        request.setServletPath("/api/v1/c/auth/wechat");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(request, response, chain);
        verify(chain).doFilter(request, response);
    }

    @Test
    void matchesOnlyPostBookings() {
        assertThat(CaptchaFilter.matches(postBookings())).isTrue();
        MockHttpServletRequest get = new MockHttpServletRequest("GET", CaptchaFilter.PATH);
        get.setServletPath(CaptchaFilter.PATH);
        assertThat(CaptchaFilter.matches(get)).isFalse();
        MockHttpServletRequest slash = new MockHttpServletRequest("POST", CaptchaFilter.PATH + "/");
        slash.setServletPath(CaptchaFilter.PATH + "/");
        assertThat(CaptchaFilter.matches(slash)).isTrue();
        MockHttpServletRequest viaUri = new MockHttpServletRequest("POST", CaptchaFilter.PATH);
        viaUri.setServletPath("");
        viaUri.setRequestURI(CaptchaFilter.PATH);
        assertThat(CaptchaFilter.matches(viaUri)).isTrue();
    }

    private static CaptchaFilter filter(boolean enabled) {
        AppProperties props = new AppProperties();
        props.getBooking().getCaptcha().setEnabled(enabled);
        return new CaptchaFilter(props, new ObjectMapper());
    }

    private static MockHttpServletRequest postBookings() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", CaptchaFilter.PATH);
        request.setServletPath(CaptchaFilter.PATH);
        return request;
    }
}
