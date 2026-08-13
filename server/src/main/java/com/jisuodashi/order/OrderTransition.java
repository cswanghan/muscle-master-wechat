package com.jisuodashi.order;

import java.util.List;

public record OrderTransition(
        OrderStatus from,
        OrderEvent event,
        OrderStatus to,
        List<OrderSide> sides
) {
    public OrderTransition {
        sides = sides == null ? List.of() : List.copyOf(sides);
    }

    static OrderTransition of(OrderStatus from, OrderEvent event, OrderStatus to, OrderSide... sides) {
        return new OrderTransition(from, event, to, List.of(sides));
    }

    public boolean hasSide(OrderSide side) {
        return sides.contains(side);
    }
}
