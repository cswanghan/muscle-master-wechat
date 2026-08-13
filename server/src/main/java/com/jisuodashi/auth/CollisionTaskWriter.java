package com.jisuodashi.auth;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * D19 collision must survive the merge TX rollback that follows {@code throw 40908}.
 */
@Service
public class CollisionTaskWriter {

    private final RelatedRecordsRepository related;

    public CollisionTaskWriter(RelatedRecordsRepository related) {
        this.related = related;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String phoneHash) {
        related.insertCollisionTask(phoneHash);
    }
}
