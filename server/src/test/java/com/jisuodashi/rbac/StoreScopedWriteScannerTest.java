package com.jisuodashi.rbac;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StoreScopedWriteScannerTest {

    @Test
    void unscopedFaWriteIsReported() throws Exception {
        Method write = DummyUnscoped.class.getDeclaredMethod("write");
        assertThat(StoreScopedWriteScanner.isUnscopedWrite(
                List.of("/api/v1/f/orders"), List.of("POST"), write, DummyUnscoped.class))
                .isTrue();
    }

    @Test
    void annotatedWriteIsNotAViolation() throws Exception {
        Method write = DummyScoped.class.getDeclaredMethod("write");
        assertThat(StoreScopedWriteScanner.isUnscopedWrite(
                List.of("/api/v1/a/stores/1/status"), List.of("POST"), write, DummyScoped.class))
                .isFalse();
    }

    @Test
    void getDoesNotRequireAnnotation() throws Exception {
        Method list = DummyUnscoped.class.getDeclaredMethod("list");
        assertThat(StoreScopedWriteScanner.isUnscopedWrite(
                List.of("/api/v1/f/stores"), List.of("GET"), list, DummyUnscoped.class))
                .isFalse();
    }

    @Test
    void customerPathIsIgnored() throws Exception {
        Method write = DummyUnscoped.class.getDeclaredMethod("write");
        assertThat(StoreScopedWriteScanner.isUnscopedWrite(
                List.of("/api/v1/c/bookings"), List.of("POST"), write, DummyUnscoped.class))
                .isFalse();
    }

    @RequestMapping("/api/v1/f")
    static class DummyUnscoped {
        @PostMapping("/orders")
        void write() {
        }

        @GetMapping("/stores")
        void list() {
        }
    }

    static class DummyScoped {
        @PostMapping
        @StoreScoped
        void write() {
        }
    }
}
