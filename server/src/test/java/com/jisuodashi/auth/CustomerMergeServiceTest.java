package com.jisuodashi.auth;

import com.jisuodashi.common.ApiException;
import com.jisuodashi.common.AppProperties;
import com.jisuodashi.common.ErrorCodes;
import com.jisuodashi.common.SnowflakeIdGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CustomerMergeServiceTest {

    private InMemoryCustomerRepository customers;
    private InMemoryRelatedRecordsRepository related;
    private CustomerMergeService merge;

    @BeforeEach
    void setUp() {
        AppProperties props = new AppProperties();
        Clock clock = Clock.fixed(Instant.parse("2026-08-14T04:00:00Z"), ZoneOffset.UTC);
        SnowflakeIdGenerator ids = new SnowflakeIdGenerator(props);
        customers = new InMemoryCustomerRepository();
        related = new InMemoryRelatedRecordsRepository(ids, clock);
        merge = new CustomerMergeService(customers, related, ids, clock);
    }

    @Test
    void bothMissingInsertsNew() {
        Customer c = merge.merge("o1", "h1", new byte[]{1});
        assertThat(c.getId()).isPositive();
        assertThat(c.getWxOpenid()).isEqualTo("o1");
        assertThat(c.getPhoneHash()).isEqualTo("h1");
        assertThat(customers.findByOpenid("o1")).isPresent();
    }

    @Test
    void openidOnlyLoginReturnsA() {
        Customer first = merge.merge("o1", null, null);
        Customer again = merge.merge("o1", null, null);
        assertThat(again.getId()).isEqualTo(first.getId());
        assertThat(again.getPhoneHash()).isNull();
    }

    @Test
    void attachPhoneToOpenidOnlyRow() {
        Customer a = merge.merge("o1", null, null);
        Customer updated = merge.merge("o1", "h1", new byte[]{9});
        assertThat(updated.getId()).isEqualTo(a.getId());
        assertThat(updated.getPhoneHash()).isEqualTo("h1");
        assertThat(updated.getPhoneCipher()).containsExactly((byte) 9);
    }

    @Test
    void walkInReusedAndBindsOpenid() {
        Customer walkIn = merge.merge(null, "h1", new byte[]{2});
        assertThat(walkIn.getWxOpenid()).isNull();
        Customer bound = merge.merge("o1", "h1", new byte[]{2});
        assertThat(bound.getId()).isEqualTo(walkIn.getId());
        assertThat(bound.getWxOpenid()).isEqualTo("o1");
    }

    @Test
    void walkInReuseWithoutOpenid() {
        Customer walkIn = merge.merge(null, "h1", new byte[]{2});
        Customer again = merge.merge(null, "h1", new byte[]{2});
        assertThat(again.getId()).isEqualTo(walkIn.getId());
    }

    @Test
    void walkInWithSameOpenidReturnsB() {
        merge.merge("o1", "h1", new byte[]{2});
        Customer again = merge.merge("o1", "h1", new byte[]{2});
        assertThat(again.getWxOpenid()).isEqualTo("o1");
    }

    @Test
    void collisionWhenPhoneAlreadyBoundToOtherOpenid() {
        merge.merge("o-other", "h1", new byte[]{2});
        assertThatThrownBy(() -> merge.merge("o-new", "h1", new byte[]{2}))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCodes.CUSTOMER_COLLISION);
        assertThat(related.humanTasks()).hasSize(1);
        assertThat(related.humanTasks().getFirst().getBizKey()).isEqualTo("collide:h1");
        assertThat(related.humanTasks().getFirst().getTaskType()).isEqualTo("CUSTOMER_COLLISION");
    }

    @Test
    void sameRowWithBothKeysReturnsA() {
        Customer created = merge.merge("o1", "h1", new byte[]{1});
        Customer again = merge.merge("o1", "h1", new byte[]{1});
        assertThat(again.getId()).isEqualTo(created.getId());
    }

    @Test
    void mergeOpenidOnlyIntoWalkInSurvivesAsB() {
        Customer a = merge.merge("o1", null, null);
        Customer b = merge.merge(null, "h1", new byte[]{3});
        related.addBooking(11L, a.getId());
        related.addSession(12L, a.getId());
        related.addServiceRecord(13L, a.getId());

        Customer survivor = merge.merge("o1", "h1", new byte[]{3});
        assertThat(survivor.getId()).isEqualTo(b.getId());
        assertThat(survivor.getWxOpenid()).isEqualTo("o1");
        assertThat(survivor.getPhoneHash()).isEqualTo("h1");
        assertThat(customers.findById(a.getId())).isEmpty();
        assertThat(customers.findByOpenid("o1")).get().extracting(Customer::getId).isEqualTo(b.getId());
        assertThat(related.bookingCustomerIds()).containsExactly(b.getId());
        assertThat(related.sessionSubjectIds()).containsExactly(b.getId());
        assertThat(related.serviceRecordCustomerIds()).containsExactly(b.getId());
        assertThat(related.audits()).hasSize(1);
        assertThat(related.audits().getFirst().action()).isEqualTo("CUSTOMER_MERGE");
    }

    @Test
    void mergeConflictWhenBAlreadyHasDifferentOpenid() {
        merge.merge("oA", null, null);
        merge.merge("oB", "h1", new byte[]{1});
        assertThatThrownBy(() -> merge.merge("oA", "h1", new byte[]{1}))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCodes.CUSTOMER_COLLISION);
        assertThat(related.humanTasks()).extracting(HumanTask::getBizKey).contains("collide:h1");
    }
}
