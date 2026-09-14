package com.jisuodashi.notify;

import com.jisuodashi.common.AppClock;
import com.jisuodashi.common.SnowflakeIdGenerator;
import com.jisuodashi.inventory.SlotOccupyStore.BookingOrderRef;
import com.jisuodashi.inventory.SlotTimes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 课前提醒。
 *
 * <p>受微信订阅消息的硬约束：**一次授权只能推一条**。所以这里的模型是
 * 「额度 + 待推」两张表：约课时前端换一条额度进来，到点了 job 找一条额度把消息推掉。
 * 没有额度就跳过（记 SKIPPED），不是失败 —— 用户没授权是正常情况，不该反复重试。
 */
@Service
public class NotifyService {

    private static final Logger log = LoggerFactory.getLogger(NotifyService.class);

    private final NotifyStore store;
    private final SubscribeMessageSender sender;
    private final SnowflakeIdGenerator ids;
    private final AppClock clock;

    public NotifyService(
            NotifyStore store, SubscribeMessageSender sender,
            SnowflakeIdGenerator ids, AppClock clock) {
        this.store = store;
        this.sender = sender;
        this.ids = ids;
        this.clock = clock;
    }

    /** 前端拿到用户「允许」后回调这里，换一条额度。 */
    public NotifyDtos.GrantResponse grant(long customerId, String templateId, String orderId) {
        NotifyModels.Grant g = new NotifyModels.Grant(
                ids.nextId(), customerId,
                templateId == null || templateId.isBlank() ? "BEFORE_CLASS" : templateId,
                orderId == null || orderId.isBlank() ? null : Long.parseLong(orderId),
                NotifyModels.STATUS_GRANTED, Instant.now(clock.clock()), null, null);
        store.insertGrant(g);
        return new NotifyDtos.GrantResponse(String.valueOf(g.id()), true);
    }

    /** 约课成功后排一条课前提醒。同一单只排一次。 */
    public void scheduleBeforeClass(BookingOrderRef order) {
        if (store.findReminderByOrder(order.id()).isPresent()) {
            return;
        }
        LocalDateTime start = order.serviceDate().atTime(SlotTimes.toTime(order.startSlotNo()));
        Instant fireAt = start.minusHours(NotifyModels.LEAD_HOURS)
                .atZone(clock.clock().getZone()).toInstant();
        store.insertReminder(new NotifyModels.Reminder(
                ids.nextId(), order.customerId(), order.id(), order.storeId(),
                NotifyModels.KIND_BEFORE_CLASS, order.serviceDate(), fireAt,
                NotifyModels.STATUS_PENDING, null, null, Instant.now(clock.clock())));
    }

    /**
     * 到点推送。返回实际推出去的条数。
     *
     * <p>没额度就记 SKIPPED 而不是 FAILED：用户没授权是正常情况，
     * 标成失败会让告警一直响，而这件事系统侧无能为力。
     */
    public int fireDue(int limit) {
        Instant now = Instant.now(clock.clock());
        int sent = 0;
        for (NotifyModels.Reminder r : store.listDue(now, limit)) {
            Optional<NotifyModels.Grant> grant = store.findUsableGrant(r.customerId());
            if (grant.isEmpty()) {
                store.updateReminder(withStatus(r, NotifyModels.STATUS_SKIPPED, now, "无订阅额度"));
                continue;
            }
            Map<String, String> data = new LinkedHashMap<>();
            data.put("date", r.serviceDate().toString());
            data.put("tip", "请提前 10 分钟到店");
            boolean ok = sender.send(r.customerId(), grant.get().templateId(),
                    "/pages/mine/mine", data);
            if (ok) {
                NotifyModels.Grant g = grant.get();
                // 额度用一条销一条 —— 这是平台规则，不是我们的节流。
                store.updateGrant(new NotifyModels.Grant(
                        g.id(), g.customerId(), g.templateId(), g.orderId(),
                        NotifyModels.STATUS_USED, g.grantedAt(), now, null));
                store.updateReminder(withStatus(r, NotifyModels.STATUS_SENT, now, null));
                sent++;
            } else {
                // 可重试的失败：额度不销，下一轮再试。
                store.updateReminder(withStatus(r, NotifyModels.STATUS_PENDING, null, "发送失败"));
                log.warn("subscribe send failed order={}", r.orderId());
            }
        }
        return sent;
    }

    private static NotifyModels.Reminder withStatus(
            NotifyModels.Reminder r, String status, Instant at, String reason) {
        return new NotifyModels.Reminder(
                r.id(), r.customerId(), r.orderId(), r.storeId(), r.kind(),
                r.serviceDate(), r.fireAt(), status, at, reason, r.createdAt());
    }
}
