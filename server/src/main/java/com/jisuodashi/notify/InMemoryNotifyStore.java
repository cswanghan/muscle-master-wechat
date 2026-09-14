package com.jisuodashi.notify;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Repository
@Profile("dev")
public class InMemoryNotifyStore implements NotifyStore {

    private final ConcurrentHashMap<Long, NotifyModels.Grant> grants = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, NotifyModels.Reminder> reminders = new ConcurrentHashMap<>();

    @Override
    public void insertGrant(NotifyModels.Grant g) {
        grants.put(g.id(), g);
    }

    @Override
    public void updateGrant(NotifyModels.Grant g) {
        grants.put(g.id(), g);
    }

    @Override
    public Optional<NotifyModels.Grant> findUsableGrant(long customerId) {
        return grants.values().stream()
                .filter(g -> g.customerId() == customerId && g.usable())
                // 先用早授权的，免得额度在手里过期。
                .min(Comparator.comparing(NotifyModels.Grant::grantedAt));
    }

    @Override
    public void insertReminder(NotifyModels.Reminder r) {
        reminders.put(r.id(), r);
    }

    @Override
    public void updateReminder(NotifyModels.Reminder r) {
        reminders.put(r.id(), r);
    }

    @Override
    public Optional<NotifyModels.Reminder> findReminderByOrder(long orderId) {
        return reminders.values().stream().filter(r -> r.orderId() == orderId).findFirst();
    }

    @Override
    public List<NotifyModels.Reminder> listDue(Instant now, int limit) {
        return reminders.values().stream()
                .filter(NotifyModels.Reminder::pending)
                .filter(r -> !r.fireAt().isAfter(now))
                .sorted(Comparator.comparing(NotifyModels.Reminder::fireAt))
                .limit(Math.max(1, limit))
                .toList();
    }
}
