package com.ansertech.repository;

import com.ansertech.domain.entity.Email;
import com.ansertech.domain.enums.EmailStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
@Repository
public interface EmailRepository extends JpaRepository<Email, Long> {
    boolean existsByExternalMessageId(String externalMessageId);
    Optional<Email> findByExternalMessageId(String externalMessageId);
    Page<Email> findByStatusOrderByCreatedAtDesc(EmailStatus status, Pageable pageable);
    Page<Email> findAllByOrderByCreatedAtDesc(Pageable pageable);

    @Query("SELECT COUNT(e) FROM Email e WHERE e.status = :status")
    long countByStatus(EmailStatus status);

    @Query("SELECT e FROM Email e ORDER BY e.createdAt DESC")
    List<Email> findTop10ByOrderByCreatedAtDesc(Pageable pageable);

    /**
     * Reporte Excel: correos cuya 'Fecha Gestión' (created_at, o processed_at si no la tiene) cae en [from, toExclusive).
     * Se filtra a propósito por la fecha de procesamiento y no por received_at, para que el reporte refleje lo que hizo
     * el pipeline en el periodo aunque el correo haya llegado a Outlook antes (p. ej. SPAM antiguo). Ver #20.
     */
    @Query("SELECT e FROM Email e WHERE COALESCE(e.createdAt, e.processedAt) >= :from "
            + "AND COALESCE(e.createdAt, e.processedAt) < :toExclusive ORDER BY e.createdAt ASC")
    List<Email> findForReport(@Param("from") LocalDateTime from, @Param("toExclusive") LocalDateTime toExclusive);

    /**
     * Correos que quedaron a medias (el proceso se cortó: reinicio, Cloud Run sin CPU...): siguen en PENDING o
     * PROCESSING, o quedaron VALID sin RFQ. Solo los creados en [since, before) para no tocar uno en curso.
     */
    @Query("SELECT e FROM Email e WHERE e.createdAt >= :since AND e.createdAt < :before AND ("
            + "e.status IN (com.ansertech.domain.enums.EmailStatus.PENDING, com.ansertech.domain.enums.EmailStatus.PROCESSING) "
            + "OR (e.status = com.ansertech.domain.enums.EmailStatus.VALID "
            + "AND NOT EXISTS (SELECT r.id FROM Rfq r WHERE r.email = e))) ORDER BY e.createdAt ASC")
    List<Email> findIncomplete(@Param("since") LocalDateTime since, @Param("before") LocalDateTime before,
                               org.springframework.data.domain.Pageable pageable);
}
