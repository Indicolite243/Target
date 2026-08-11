package com.stockmanager.account.document;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@Document("account_snapshots")
@CompoundIndex(name = "idx_account_snapshot_time", def = "{'accountId': 1, 'snapshotTime': -1}")
public class AccountSnapshot {
    @Id
    private String id;
    private Long accountId;
    private LocalDateTime snapshotTime;
    private BigDecimal totalAsset;
    private BigDecimal cash;
    private BigDecimal marketValue;
    private String source;
    private String dataVersion;
    /** MANUAL, INTRADAY or DAILY. */
    private String snapshotType;
    private List<Map<String, String>> positions;
}
