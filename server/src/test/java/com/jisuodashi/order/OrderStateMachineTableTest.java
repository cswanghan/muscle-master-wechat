package com.jisuodashi.order;

import com.jisuodashi.common.ApiException;
import com.jisuodashi.common.ErrorCodes;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderStateMachineTableTest {

    private final OrderStateMachine machine = new OrderStateMachine();

    @Test
    void listedPairsMatchClosedTable() {
        assertThat(OrderStateMachine.transfers()).hasSize(22);
        assertPair(OrderStatus.PENDING_PAY, OrderEvent.PAY_SUCCESS, OrderStatus.BOOKED, OrderSide.CONFIRM_PAID);
        assertPair(OrderStatus.PENDING_PAY, OrderEvent.PAY_TIMEOUT, OrderStatus.CLOSED, OrderSide.RELEASE_LOCK);
        assertPair(OrderStatus.PENDING_PAY, OrderEvent.USER_CANCEL, OrderStatus.CLOSED, OrderSide.RELEASE_LOCK);
        assertPair(OrderStatus.BOOKED, OrderEvent.CHECK_IN, OrderStatus.CHECKED_IN, OrderSide.CHECKED_IN_AT);
        assertPair(OrderStatus.BOOKED, OrderEvent.CANCEL, OrderStatus.CANCELLED, OrderSide.RELEASE_UNCONSUMED_START);
        assertPair(OrderStatus.BOOKED, OrderEvent.REFUND, OrderStatus.CANCELLED, OrderSide.RELEASE_UNCONSUMED_START);
        assertPair(OrderStatus.BOOKED, OrderEvent.RESCHEDULE, OrderStatus.BOOKED, OrderSide.RESCHEDULE);
        assertPair(OrderStatus.BOOKED, OrderEvent.MARK_NO_SHOW, OrderStatus.NO_SHOW, OrderSide.RELEASE_UNCONSUMED_START);
        assertPair(OrderStatus.CHECKED_IN, OrderEvent.START_SERVICE, OrderStatus.IN_SERVICE, OrderSide.SERVICE_RECORD);
        assertPair(OrderStatus.CHECKED_IN, OrderEvent.SWAP_THERAPIST, OrderStatus.CHECKED_IN, OrderSide.SWAP_THERAPIST);
        assertPair(OrderStatus.CHECKED_IN, OrderEvent.REFUND, OrderStatus.CANCELLED, OrderSide.RELEASE_UNCONSUMED_START);
        assertPair(OrderStatus.IN_SERVICE, OrderEvent.COMPLETE_SERVICE, OrderStatus.COMPLETED, OrderSide.ENDED_AT);
        assertPair(OrderStatus.IN_SERVICE, OrderEvent.ADD_ON, OrderStatus.IN_SERVICE, OrderSide.NONE);
        assertPair(OrderStatus.IN_SERVICE, OrderEvent.ADD_ON_PAY_TIMEOUT, OrderStatus.IN_SERVICE, OrderSide.RELEASE_ADDON);
        assertPair(OrderStatus.IN_SERVICE, OrderEvent.SWAP_THERAPIST, OrderStatus.IN_SERVICE, OrderSide.SWAP_THERAPIST);
        assertPair(OrderStatus.IN_SERVICE, OrderEvent.ABORT, OrderStatus.ABNORMAL, OrderSide.RELEASE_UNCONSUMED_NOW);
        assertPair(OrderStatus.IN_SERVICE, OrderEvent.REFUND, OrderStatus.CANCELLED, OrderSide.RELEASE_UNCONSUMED_NOW);
        assertPair(OrderStatus.ABNORMAL, OrderEvent.RESOLVE_COMPLETE, OrderStatus.COMPLETED, OrderSide.NONE);
        assertPair(OrderStatus.ABNORMAL, OrderEvent.RESOLVE_CANCEL, OrderStatus.CANCELLED, OrderSide.RELEASE_UNCONSUMED_NOW);
        assertPair(OrderStatus.COMPLETED, OrderEvent.REVIEW, OrderStatus.REVIEWED, OrderSide.NONE);
        assertPair(OrderStatus.COMPLETED, OrderEvent.REFUND, OrderStatus.COMPLETED, OrderSide.REFUND);
        assertPair(OrderStatus.CANCELLED, OrderEvent.MARK_NO_SHOW, OrderStatus.NO_SHOW, OrderSide.NO_SHOW_COUNT);
    }

    @Test
    void aliasesMapToDesignEvents() {
        assertThat(OrderEvent.parse("START")).isEqualTo(OrderEvent.START_SERVICE);
        assertThat(OrderEvent.parse("COMPLETE")).isEqualTo(OrderEvent.COMPLETE_SERVICE);
        assertThat(OrderEvent.parse("NO_SHOW")).isEqualTo(OrderEvent.MARK_NO_SHOW);
        assertThat(machine.fire(OrderStatus.CHECKED_IN, OrderEvent.parse("START")).to())
                .isEqualTo(OrderStatus.IN_SERVICE);
        assertThat(machine.fire(OrderStatus.IN_SERVICE, OrderEvent.parse("COMPLETE")).to())
                .isEqualTo(OrderStatus.COMPLETED);
        assertThat(machine.fire(OrderStatus.BOOKED, OrderEvent.parse("NO_SHOW")).to())
                .isEqualTo(OrderStatus.NO_SHOW);
    }

    @Test
    void everyUnlistedPairIs40904() {
        Set<String> listed = new HashSet<>();
        for (OrderTransition t : OrderStateMachine.transfers()) {
            listed.add(t.from() + "+" + t.event());
        }
        int illegal = 0;
        for (OrderStatus from : OrderStatus.values()) {
            for (OrderEvent event : OrderEvent.values()) {
                if (listed.contains(from + "+" + event)) {
                    continue;
                }
                assertThatThrownBy(() -> machine.fire(from, event))
                        .isInstanceOf(ApiException.class)
                        .extracting(ex -> ((ApiException) ex).getCode())
                        .isEqualTo(ErrorCodes.ILLEGAL_TRANSITION);
                illegal++;
            }
        }
        int universe = OrderStatus.values().length * OrderEvent.values().length;
        assertThat(illegal).isEqualTo(universe - listed.size());
        assertThat(illegal).isGreaterThan(100);
        assertThatThrownBy(() -> machine.fire(OrderStatus.BOOKED, OrderEvent.PAY_TIMEOUT))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(40904);
        assertThatThrownBy(() -> machine.fire(OrderStatus.CLOSED, OrderEvent.PAY_SUCCESS))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(40904);
        assertThatThrownBy(() -> machine.fire(OrderStatus.COMPLETED, OrderEvent.CHECK_IN))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(40904);
    }

    @Test
    void orderPackageHasNoSetStatus() throws Exception {
        var dir = java.nio.file.Path.of("src/main/java/com/jisuodashi/order");
        if (!java.nio.file.Files.isDirectory(dir)) {
            dir = java.nio.file.Path.of("server/src/main/java/com/jisuodashi/order");
        }
        try (var walk = java.nio.file.Files.walk(dir)) {
            for (var file : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
                String src = java.nio.file.Files.readString(file);
                assertThat(src).as(file.toString()).doesNotContain("setStatus(");
            }
        }
    }

    private void assertPair(OrderStatus from, OrderEvent event, OrderStatus to, OrderSide side) {
        OrderTransition t = machine.fire(from, event);
        assertThat(t.to()).isEqualTo(to);
        assertThat(t.hasSide(side)).isTrue();
        assertThat(OrderStateMachine.listed(from, event)).isTrue();
    }
}
