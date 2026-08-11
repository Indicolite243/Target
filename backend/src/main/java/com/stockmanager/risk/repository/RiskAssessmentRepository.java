package com.stockmanager.risk.repository;

import com.stockmanager.risk.document.RiskAssessment;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface RiskAssessmentRepository extends MongoRepository<RiskAssessment, String> {
    Optional<RiskAssessment> findFirstByAccountIdOrderByCalculatedAtDesc(Long accountId);
}
