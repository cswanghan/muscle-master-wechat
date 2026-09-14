package com.jisuodashi.order;

/**
 * 约课成功后的动作。由上层实现 —— order 是下层，不认识提醒和回访。
 *
 * <p>实现里抛异常不会回滚订单：单已经成了，提醒没排上是次要的。
 */
public interface BookingHooks {

    void onBooked(long orderId);
}
