package com.jisuodashi.order;

import java.util.List;

/**
 * Who/what is firing. Job/API only {@code fire(event, ctx)}; guards live here
 * so later payment/cancel paths do not grow a second {@code if (status==)}.
 */
public record FireContext(
        Actor actor,
        Long actorId,
        List<Long> scopedStoreIds,
        boolean frontDesk,
        boolean storeManager,
        Boolean paymentMatched,
        Boolean lockExpired,
        boolean rescheduleOk,
        boolean swapOk,
        boolean addOnPaid,
        Boolean addOnHoldExpired,
        boolean refundAfterStart,
        boolean reviewAllowed
) {
    public enum Actor {
        CUSTOMER, STAFF, JOB, SYSTEM
    }

    public FireContext {
        scopedStoreIds = scopedStoreIds == null ? List.of() : List.copyOf(scopedStoreIds);
    }

    public static FireContext system() {
        return new FireContext(
                Actor.SYSTEM, null, List.of(), false, false,
                null, null, false, false, false, null, false, false);
    }

    public static FireContext job() {
        return new FireContext(
                Actor.JOB, null, List.of(), false, false,
                null, true, false, false, false, true, false, false);
    }

    public static FireContext customer(long customerId) {
        return new FireContext(
                Actor.CUSTOMER, customerId, List.of(), false, false,
                null, null, false, false, false, null, false, false);
    }

    public static FireContext staff(long staffId, List<Long> storeIds) {
        return new FireContext(
                Actor.STAFF, staffId, storeIds, false, false,
                null, null, false, false, false, null, false, false);
    }

    public boolean privileged() {
        return actor == Actor.JOB || actor == Actor.SYSTEM;
    }

    public FireContext withPaymentMatched(boolean matched) {
        return new FireContext(
                actor, actorId, scopedStoreIds, frontDesk, storeManager,
                matched, lockExpired, rescheduleOk, swapOk, addOnPaid,
                addOnHoldExpired, refundAfterStart, reviewAllowed);
    }

    public FireContext withLockExpired(boolean expired) {
        return new FireContext(
                actor, actorId, scopedStoreIds, frontDesk, storeManager,
                paymentMatched, expired, rescheduleOk, swapOk, addOnPaid,
                addOnHoldExpired, refundAfterStart, reviewAllowed);
    }

    public FireContext withFrontDesk() {
        return new FireContext(
                actor, actorId, scopedStoreIds, true, storeManager,
                paymentMatched, lockExpired, rescheduleOk, swapOk, addOnPaid,
                addOnHoldExpired, refundAfterStart, reviewAllowed);
    }

    public FireContext withStoreManager() {
        return new FireContext(
                actor, actorId, scopedStoreIds, frontDesk, true,
                paymentMatched, lockExpired, rescheduleOk, swapOk, addOnPaid,
                addOnHoldExpired, refundAfterStart, reviewAllowed);
    }

    public FireContext withRescheduleOk() {
        return new FireContext(
                actor, actorId, scopedStoreIds, frontDesk, storeManager,
                paymentMatched, lockExpired, true, swapOk, addOnPaid,
                addOnHoldExpired, refundAfterStart, reviewAllowed);
    }

    public FireContext withSwapOk() {
        return new FireContext(
                actor, actorId, scopedStoreIds, frontDesk, storeManager,
                paymentMatched, lockExpired, rescheduleOk, true, addOnPaid,
                addOnHoldExpired, refundAfterStart, reviewAllowed);
    }

    public FireContext withAddOnPaid() {
        return new FireContext(
                actor, actorId, scopedStoreIds, frontDesk, storeManager,
                paymentMatched, lockExpired, rescheduleOk, swapOk, true,
                addOnHoldExpired, refundAfterStart, reviewAllowed);
    }

    public FireContext withRefundAfterStart() {
        return new FireContext(
                actor, actorId, scopedStoreIds, frontDesk, storeManager,
                paymentMatched, lockExpired, rescheduleOk, swapOk, addOnPaid,
                addOnHoldExpired, true, reviewAllowed);
    }

    public FireContext withReviewAllowed() {
        return new FireContext(
                actor, actorId, scopedStoreIds, frontDesk, storeManager,
                paymentMatched, lockExpired, rescheduleOk, swapOk, addOnPaid,
                addOnHoldExpired, refundAfterStart, true);
    }
}
