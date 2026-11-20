package com.ansertech.repository;

import com.ansertech.domain.entity.Quotation;
import com.ansertech.domain.enums.QuotationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface QuotationRepository extends JpaRepository<Quotation, Long> {
    Optional<Quotation> findFirstByRfqIdOrderByCreatedAtAsc(Long rfqId);

    /**
     * Reporte Excel: primera cotización (con sus ítems) de cada RFQ, en una sola consulta para evitar N+1.
     * Si dos cotizaciones de un RFQ tienen el mismo created_at vienen ambas; quien llama se queda con la de menor id.
     */
    @Query("SELECT DISTINCT q FROM Quotation q LEFT JOIN FETCH q.items WHERE q.rfq.id IN :rfqIds "
            + "AND q.createdAt = (SELECT MIN(q2.createdAt) FROM Quotation q2 WHERE q2.rfq = q.rfq)")
    List<Quotation> findFirstWithItemsByRfqIdIn(@Param("rfqIds") Collection<Long> rfqIds);
    Optional<Quotation> findByQuotationNumber(String quotationNumber);
    Page<Quotation> findByStatusOrderByCreatedAtDesc(QuotationStatus status, Pageable pageable);
    Page<Quotation> findAllByOrderByCreatedAtDesc(Pageable pageable);

    @Query("SELECT COUNT(q) FROM Quotation q WHERE q.status = :status")
    long countByStatus(QuotationStatus status);

    @Query("SELECT COALESCE(MAX(CAST(SUBSTRING(q.quotationNumber, 10) AS int)), 0) " +
           "FROM Quotation q WHERE q.quotationNumber LIKE CONCAT('COT-', :year, '-%')")
    Integer findMaxSequenceForYear(int year);

    /** Primera cotización de cada RFQ, si fue creada en [from, to). Trae RFQ y correo para calcular tiempos. */
    @Query("SELECT q FROM Quotation q JOIN FETCH q.rfq r LEFT JOIN FETCH r.email "
            + "WHERE q.createdAt >= :from AND q.createdAt < :to "
            + "AND q.createdAt = (SELECT MIN(q2.createdAt) FROM Quotation q2 WHERE q2.rfq = q.rfq)")
    List<Quotation> findFirstPerRfqCreatedBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}
