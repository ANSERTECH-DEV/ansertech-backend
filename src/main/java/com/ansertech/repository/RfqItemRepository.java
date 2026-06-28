package com.ansertech.repository;

import com.ansertech.domain.entity.RfqItem;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RfqItemRepository extends JpaRepository<RfqItem, Long> {}
