package com.jisuodashi.order;

public record FireResult(
        long orderId,
        OrderStatus from,
        OrderEvent event,
        OrderStatus to
) {
}
