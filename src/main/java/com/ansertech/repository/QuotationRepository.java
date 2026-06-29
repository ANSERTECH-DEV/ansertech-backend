package com.ansertech.repository;

import com.ansertech.domain.entity.Quotation;
import com.ansertech.domain.enums.QuotationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface QuotationRepository extends JpaRepository<Quotation, Long> {
    Optional<Quotation> findFirstByRfqIdOrderByCreatedAtAsc(Long rfqId);
    Optional<Quotation> findByQuotationNumber(String quotationNumber);
    Page<Quotation> findByStatusOrderByCreatedAtDesc(QuotationStatus status, Pageable pageable);
    Page<Quotation> findAllByOrderByCreatedAtDesc(Pageable pageable);

    @Query("SELECT COUNT(q) FROM Quotation q WHERE q.status = :status")
    long countByStatus(QuotationStatus status);

    @Query("SELECT COALESCE(MAX(CAST(SUBSTRING(q.quotationNumber, 10) AS int)), 0) " +
           "FROM Quotation q WHERE q.quotationNumber LIKE CONCAT('COT-', :year, '-%')")
    Integer findMaxSequenceForYear(int year);
}
