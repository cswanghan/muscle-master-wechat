package com.jisuodashi.inventory;

import com.jisuodashi.common.ApiException;
import com.jisuodashi.common.AppProperties;
import com.jisuodashi.common.ErrorCodes;
import com.jisuodashi.job.ForceReleaseJob;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InternalForceReleaseControllerTest {

    @Test
    void disabledIs403AndDoesNotRelease() {
        ForceReleaseJob job = mock(ForceReleaseJob.class);
        InternalForceReleaseController controller = controller(job, false, "secret");
        MockHttpServletRequest request = loopback();
        request.addHeader(InternalForceReleaseController.TOKEN_HEADER, "secret");
        assertThatThrownBy(() -> controller.forceRelease(1L, "secret", request))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCodes.FORBIDDEN);
        verify(job, never()).run(1L);
    }

    @Test
    void enabledWithoutTokenIs401() {
        ForceReleaseJob job = mock(ForceReleaseJob.class);
        InternalForceReleaseController controller = controller(job, true, "secret");
        assertThatThrownBy(() -> controller.forceRelease(1L, null, loopback()))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCodes.UNAUTHORIZED);
        verify(job, never()).run(1L);
    }

    @Test
    void nonLoopbackIs403EvenWithToken() {
        ForceReleaseJob job = mock(ForceReleaseJob.class);
        InternalForceReleaseController controller = controller(job, true, "secret");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.9");
        assertThatThrownBy(() -> controller.forceRelease(1L, "secret", request))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCodes.FORBIDDEN);
        verify(job, never()).run(1L);
    }

    @Test
    void enabledLoopbackTokenFreesLockedHold() {
        ForceReleaseJob job = mock(ForceReleaseJob.class);
        when(job.run(9L)).thenReturn(new ReleaseResult(9L, ReleaseResult.ORPHAN_FREED, 5, 0, 5));
        InternalForceReleaseController controller = controller(job, true, "secret");
        var body = controller.forceRelease(9L, "secret", loopback());
        assertThat(body.code()).isEqualTo(0);
        assertThat(body.data().freed()).isTrue();
        verify(job).run(9L);
    }

    @Test
    void noLockedRowsIs40901() {
        ForceReleaseJob job = mock(ForceReleaseJob.class);
        when(job.run(9L)).thenReturn(new ReleaseResult(9L, ReleaseResult.IDEMPOTENT, 0, 0, 0));
        InternalForceReleaseController controller = controller(job, true, "secret");
        assertThatThrownBy(() -> controller.forceRelease(9L, "secret", loopback()))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCodes.SLOT_UNAVAILABLE);
    }

    @Test
    void blankConfiguredTokenIs403() {
        ForceReleaseJob job = mock(ForceReleaseJob.class);
        InternalForceReleaseController controller = controller(job, true, "");
        assertThatThrownBy(() -> controller.forceRelease(1L, "anything", loopback()))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCodes.FORBIDDEN);
    }

    private static InternalForceReleaseController controller(
            ForceReleaseJob job, boolean enabled, String token) {
        AppProperties props = new AppProperties();
        props.getInternal().getForceRelease().setEnabled(enabled);
        props.getInternal().getForceRelease().setToken(token);
        return new InternalForceReleaseController(job, props);
    }

    private static MockHttpServletRequest loopback() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        return request;
    }
}
