package com.ansertech.repository;

import com.ansertech.domain.entity.Email;
import com.ansertech.domain.enums.EmailStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

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
}
