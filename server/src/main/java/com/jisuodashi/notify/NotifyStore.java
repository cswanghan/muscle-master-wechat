package com.jisuodashi.notify;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface NotifyStore {

    void insertGrant(NotifyModels.Grant grant);

    void updateGrant(NotifyModels.Grant grant);

    /** 取一条还能用的额度。没有就返回空，调用方据此跳过而不是报错。 */
    Optional<NotifyModels.Grant> findUsableGrant(long customerId);

    void insertReminder(NotifyModels.Reminder reminder);

    void updateReminder(NotifyModels.Reminder reminder);

    Optional<NotifyModels.Reminder> findReminderByOrder(long orderId);

    /** job 扫这个：到点且还没推的。 */
    List<NotifyModels.Reminder> listDue(Instant now, int limit);
}
