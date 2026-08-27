package com.jisuodashi.order;

/**
 * What the customer submitted, carried on {@link FireContext} so the review row lands in the
 * same transaction as the {@code COMPLETED -> REVIEWED} CAS (D8: side effects never run after).
 *
 * <p>Payload only. {@code positive} is derived and frozen by the write side, not sent by the
 * client — see {@code ReviewPolicy}.
 */
public record ReviewDraft(int score, String tags, String content, boolean anonymous) {
}
