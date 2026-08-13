package com.jisuodashi.staff;

import com.jisuodashi.auth.AuthContext;
import com.jisuodashi.auth.Customer;
import com.jisuodashi.auth.CustomerRepository;
import com.jisuodashi.auth.JwtPrincipal;
import com.jisuodashi.auth.TokenType;
import com.jisuodashi.catalog.CatalogModels;
import com.jisuodashi.common.ApiException;
import com.jisuodashi.common.AppClock;
import com.jisuodashi.common.ErrorCodes;
import com.jisuodashi.inventory.SlotOccupyService;
import com.jisuodashi.inventory.SlotOccupyStore.BookingOrderRef;
import com.jisuodashi.inventory.SlotTimes;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

@Service
public class TodayBoardService {

    private static final Set<String> NEXT_STATUSES = Set.of("BOOKED", "CHECKED_IN", "IN_SERVICE");
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");

    private final StaffBoardStore board;
    private final StaffTherapistLookup therapists;
    private final CustomerRepository customers;
    private final SlotOccupyService occupy;
    private final AppClock clock;

    public TodayBoardService(
            StaffBoardStore board,
            StaffTherapistLookup therapists,
            CustomerRepository customers,
            SlotOccupyService occupy,
            AppClock clock) {
        this.board = board;
        this.therapists = therapists;
        this.customers = customers;
        this.occupy = occupy;
        this.clock = clock;
    }

    public StaffDtos.TodayBoard today() {
        JwtPrincipal principal = AuthContext.requireStaff();
        if (principal.typ() != TokenType.T) {
            throw new ApiException(ErrorCodes.FORBIDDEN, "无功能权限");
        }
        CatalogModels.Therapist therapist = therapists.requireTherapist(principal);
        var day = clock.today();
        List<BookingOrderRef> orders = board.listTherapistDayOrders(therapist.id(), day);
        StaffDtos.NextJob next = pickNext(orders);
        List<StaffDtos.TimelineSlot> timeline = board.listTherapistDaySlots(therapist.id(), day).stream()
                .map(slot -> new StaffDtos.TimelineSlot(
                        slot.slotNo(),
                        slot.state(),
                        slot.orderId() == null ? null : String.valueOf(slot.orderId())))
                .toList();
        return new StaffDtos.TodayBoard(next, timeline);
    }

    public StaffDtos.NextJob job(String orderIdRaw) {
        JwtPrincipal principal = AuthContext.requireStaff();
        if (principal.typ() != TokenType.T) {
            throw new ApiException(ErrorCodes.FORBIDDEN, "无功能权限");
        }
        CatalogModels.Therapist therapist = therapists.requireTherapist(principal);
        long orderId = StaffOrderService.parseOrderId(orderIdRaw);
        BookingOrderRef order = occupy.findOrderById(orderId);
        if (order == null || order.therapistId() != therapist.id()) {
            throw new ApiException(ErrorCodes.NOT_FOUND, "订单不存在");
        }
        return toCard(order);
    }

    private StaffDtos.NextJob pickNext(List<BookingOrderRef> orders) {
        List<BookingOrderRef> active = orders.stream()
                .filter(o -> NEXT_STATUSES.contains(o.status()))
                .toList();
        if (active.isEmpty()) {
            return null;
        }
        BookingOrderRef chosen = active.stream()
                .filter(o -> "IN_SERVICE".equals(o.status()))
                .findFirst()
                .orElseGet(() -> active.stream()
                        .min(Comparator.comparingInt(BookingOrderRef::startSlotNo)
                                .thenComparingLong(BookingOrderRef::id))
                        .orElseThrow());
        return toCard(chosen);
    }

    private StaffDtos.NextJob toCard(BookingOrderRef order) {
        int serviceEnd = Math.max(order.startSlotNo() + 1, order.endSlotNo() - order.bufferSlots());
        LocalDateTime start = LocalDateTime.of(order.serviceDate(), SlotTimes.toTime(order.startSlotNo()));
        LocalDateTime end = LocalDateTime.of(order.serviceDate(), SlotTimes.toTime(
                Math.min(serviceEnd, SlotTimes.SLOTS_PER_DAY - 1)));
        StaffBoardStore.RoomBedNames place = board.roomBed(order.roomId(), order.bedId());
        String project = board.firstProjectName(order.id());
        if (project == null || project.isBlank()) {
            project = "到店项目";
        }
        String name = customers.findById(order.customerId())
                .map(Customer::getNickname)
                .filter(n -> n != null && !n.isBlank())
                .orElse("客人");
        boolean isNew = board.countCompletedForCustomer(order.customerId()) == 0;
        long minutes = Duration.between(clock.now(), start).toMinutes();
        return new StaffDtos.NextJob(
                String.valueOf(order.id()),
                order.status(),
                HM.format(start),
                HM.format(end),
                project,
                place.roomName(),
                place.bedName(),
                name,
                isNew,
                minutes);
    }
}
