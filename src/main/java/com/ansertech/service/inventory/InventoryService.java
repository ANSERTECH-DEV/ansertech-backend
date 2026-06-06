package com.ansertech.service.inventory;

import com.ansertech.domain.entity.Product;
import com.ansertech.domain.enums.AvailabilityStatus;
import com.ansertech.dto.request.ProductRequest;
import com.ansertech.dto.request.StockUpdateRequest;
import com.ansertech.dto.response.ProductResponse;
import com.ansertech.exception.BusinessException;
import com.ansertech.exception.ResourceNotFoundException;
import com.ansertech.repository.ProductRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryService {

    private final ProductRepository productRepository;
    private final ObjectMapper objectMapper;

    public Page<ProductResponse> search(String search, String category, Pageable pageable) {
        return productRepository.searchProducts(search, category, pageable)
                .map(this::toResponse);
    }

    public ProductResponse getById(Long id) {
        return toResponse(findOrThrow(id));
    }

    @Transactional
    public ProductResponse create(ProductRequest req) {
        if (productRepository.existsBySku(req.getSku())) {
            throw new BusinessException("SKU ya existe: " + req.getSku());
        }
        Product product = Product.builder()
                .sku(req.getSku()).name(req.getName()).category(req.getCategory())
                .description(req.getDescription()).unit(req.getUnit())
                .stockQuantity(req.getStockQuantity())
                .minStockThreshold(req.getMinStockThreshold() != null
                        ? req.getMinStockThreshold() : BigDecimal.valueOf(5))
                .unitPrice(req.getUnitPrice())
                .currency(req.getCurrency() != null ? req.getCurrency() : "PEN")
                .supplierName(req.getSupplierName()).brand(req.getBrand())
                .active(true).build();
        return toResponse(productRepository.save(product));
    }

    @Transactional
    public ProductResponse update(Long id, ProductRequest req) {
        Product product = findOrThrow(id);
        product.setName(req.getName());
        product.setCategory(req.getCategory());
        product.setDescription(req.getDescription());
        product.setUnit(req.getUnit());
        product.setUnitPrice(req.getUnitPrice());
        product.setSupplierName(req.getSupplierName());
        product.setBrand(req.getBrand());
        return toResponse(productRepository.save(product));
    }

    @Transactional
    public ProductResponse updateStock(Long id, StockUpdateRequest req) {
        Product product = findOrThrow(id);
        product.setStockQuantity(req.getQuantity());
        log.info("Stock actualizado — SKU {} → {}", product.getSku(), req.getQuantity());
        return toResponse(productRepository.save(product));
    }

    public List<ProductResponse> getLowStock() {
        return productRepository.findLowStockProducts().stream()
                .map(this::toResponse).collect(Collectors.toList());
    }

    public String checkAvailabilityJson(String description, double quantityNeeded) {
        List<Product> matches = productRepository.findByDescriptionLike(description);
        if (matches.isEmpty()) {
            return "{\"found\": false, \"message\": \"Producto no encontrado en inventario\"}";
        }
        Product product = matches.get(0);
        AvailabilityStatus status = product.getStockQuantity()
                .compareTo(BigDecimal.valueOf(quantityNeeded)) >= 0
                ? AvailabilityStatus.IN_STOCK : AvailabilityStatus.OUT_OF_STOCK;

        try {
            return objectMapper.writeValueAsString(Map.of(
                    "found", true,
                    "sku", product.getSku(),
                    "name", product.getName(),
                    "stock_quantity", product.getStockQuantity(),
                    "unit_price", product.getUnitPrice(),
                    "currency", product.getCurrency(),
                    "availability_status", status.name(),
                    "quantity_needed", quantityNeeded
            ));
        } catch (Exception e) {
            return "{\"found\": false, \"error\": \"serialization_error\"}";
        }
    }

    private Product findOrThrow(Long id) {
        return productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Producto", id));
    }

    private ProductResponse toResponse(Product p) {
        return ProductResponse.builder()
                .id(p.getId()).sku(p.getSku()).name(p.getName())
                .category(p.getCategory()).description(p.getDescription())
                .unit(p.getUnit()).stockQuantity(p.getStockQuantity())
                .minStockThreshold(p.getMinStockThreshold())
                .unitPrice(p.getUnitPrice()).currency(p.getCurrency())
                .supplierName(p.getSupplierName()).brand(p.getBrand())
                .active(p.getActive())
                .lowStock(p.getStockQuantity().compareTo(p.getMinStockThreshold()) < 0)
                .updatedAt(p.getUpdatedAt())
                .build();
    }
}
