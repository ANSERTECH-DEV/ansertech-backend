package com.ansertech.repository;

import com.ansertech.domain.entity.Rfq;
import com.ansertech.domain.enums.RfqStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
@Repository
public interface RfqRepository extends JpaRepository<Rfq, Long> {
    Page<Rfq> findByStatusOrderByCreatedAtDesc(RfqStatus status, Pageable pageable);
    Page<Rfq> findAllByOrderByCreatedAtDesc(Pageable pageable);

    @Query("SELECT COUNT(r) FROM Rfq r WHERE r.status = :status")
    long countByStatus(RfqStatus status);

    @Query("SELECT r FROM Rfq r ORDER BY r.createdAt DESC")
    List<Rfq> findRecentRfqs(Pageable pageable);
}
