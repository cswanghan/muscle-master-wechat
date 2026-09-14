package com.jisuodashi.notify;

import com.jisuodashi.growth.GrowthService;
import com.jisuodashi.inventory.SlotOccupyStore;
import com.jisuodashi.inventory.SlotOccupyStore.BookingOrderRef;
import com.jisuodashi.membership.MembershipModels;
import com.jisuodashi.membership.MembershipService;
import com.jisuodashi.order.BookingHooks;
import org.springframework.stereotype.Component;

/**
 * 约成之后：排一条课前提醒，再排一条课后回访待办。
 *
 * <p>回访待办在约课时就排，而不是等课上完 —— 上完课当天老师最忙，
 * 那会儿再生成待办容易被划掉。
 */
@Component
public class BookedNotifyHooks implements BookingHooks {

    private final NotifyService notify;
    private final GrowthService growth;
    private final MembershipService membership;
    private final SlotOccupyStore orders;

    public BookedNotifyHooks(
            NotifyService notify, GrowthService growth,
            MembershipService membership, SlotOccupyStore orders) {
        this.notify = notify;
        this.growth = growth;
        this.membership = membership;
        this.orders = orders;
    }

    @Override
    public void onBooked(long orderId) {
        BookingOrderRef order = orders.findOrderById(orderId);
        if (order == null) {
            return;
        }
        notify.scheduleBeforeClass(order);
        membership.profile(order.customerId())
                .map(MembershipModels.Profile::ownerTherapistId)
                .ifPresent(staffId ->
                        growth.scheduleAfterClass(order.customerId(), order.storeId(), staffId));
    }
}
