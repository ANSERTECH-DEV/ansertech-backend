package com.ansertech.repository;

import com.ansertech.domain.entity.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
@Repository
public interface ProductRepository extends JpaRepository<Product, Long> {
    Optional<Product> findBySku(String sku);
    boolean existsBySku(String sku);

    @Query("SELECT p FROM Product p WHERE p.active = true AND " +
           "(:search IS NULL OR LOWER(p.name) LIKE LOWER(CONCAT('%', :search, '%')) " +
           "OR LOWER(p.sku) LIKE LOWER(CONCAT('%', :search, '%')))" +
           "AND (:category IS NULL OR p.category = :category)")
    Page<Product> searchProducts(@Param("search") String search,
                                  @Param("category") String category,
                                  Pageable pageable);

    @Query("SELECT p FROM Product p WHERE p.active = true AND " +
           "p.stockQuantity < p.minStockThreshold")
    List<Product> findLowStockProducts();

    @Query("SELECT p FROM Product p WHERE p.active = true AND " +
           "(LOWER(p.name) LIKE LOWER(CONCAT('%', :description, '%')) " +
           "OR LOWER(p.sku) LIKE LOWER(CONCAT('%', :description, '%'))) " +
           "ORDER BY p.stockQuantity DESC")
    List<Product> findByDescriptionLike(@Param("description") String description);

    @Query("SELECT COUNT(p) FROM Product p WHERE p.active = true AND p.stockQuantity < p.minStockThreshold")
    long countLowStock();
}
