package com.stockmanager.account.repository;

import com.stockmanager.account.document.AccountSnapshot;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.time.LocalDateTime;
import java.util.Optional;

public interface AccountSnapshotRepository extends MongoRepository<AccountSnapshot, String> {
    List<AccountSnapshot> findTop1000ByAccountIdOrderBySnapshotTimeDesc(Long accountId);

    List<AccountSnapshot> findByAccountIdAndSnapshotTimeBetweenOrderBySnapshotTimeAsc(
            Long accountId, LocalDateTime from, LocalDateTime to);

    Optional<AccountSnapshot> findFirstByAccountIdAndSnapshotTimeGreaterThanEqualOrderBySnapshotTimeAsc(
            Long accountId, LocalDateTime from);

    Optional<AccountSnapshot> findFirstByAccountIdAndSnapshotTimeLessThanEqualOrderBySnapshotTimeDesc(
            Long accountId, LocalDateTime to);

    Optional<AccountSnapshot> findFirstByAccountIdOrderBySnapshotTimeDesc(Long accountId);

    Optional<AccountSnapshot> findFirstByAccountIdAndSnapshotTypeAndSnapshotTimeBetween(
            Long accountId, String snapshotType, LocalDateTime from, LocalDateTime to);
}
