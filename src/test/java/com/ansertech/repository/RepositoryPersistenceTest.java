package com.ansertech.repository;

import com.ansertech.domain.entity.Product;
import com.ansertech.domain.entity.Rfq;
import com.ansertech.domain.entity.RfqItem;
import com.ansertech.domain.entity.User;
import com.ansertech.domain.enums.RfqStatus;
import com.ansertech.domain.entity.Quotation;
import com.ansertech.domain.enums.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests de persistencia contra PostgreSQL real (no H2): la app usa consultas JPQL con
 * SUBSTRING/CAST específicas (ver QuotationRepository.findMaxSequenceForYear) cuya
 * traducción a SQL nativo difiere entre motores. disabledWithoutDocker = true: se salta
 * si el daemon de Docker no está activo.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class RepositoryPersistenceTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired private RfqRepository rfqRepository;
    @Autowired private QuotationRepository quotationRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private UserRepository userRepository;

    @Test
    void savingRfq_cascadesItemsAndPreservesOrder() {
        Rfq rfq = Rfq.builder().clientName("Cliente Test").status(RfqStatus.PROCESSING).build();
        RfqItem item1 = RfqItem.builder().rfq(rfq).productDescription("Sensor A")
                .quantity(new BigDecimal("2")).unit("unidad").build();
        RfqItem item2 = RfqItem.builder().rfq(rfq).productDescription("Sensor B")
                .quantity(new BigDecimal("1")).unit("unidad").build();
        rfq.setItems(List.of(item1, item2));

        Rfq saved = rfqRepository.saveAndFlush(rfq);

        Rfq fresh = rfqRepository.findById(saved.getId()).orElseThrow();
        assertThat(fresh.getItems()).hasSize(2);
        assertThat(fresh.getItems()).extracting(RfqItem::getProductDescription)
                .containsExactly("Sensor A", "Sensor B");
    }

    @Test
    void findByStatus_and_countByStatus_filterCorrectly() {
        rfqRepository.save(Rfq.builder().clientName("A").status(RfqStatus.PROCESSING).build());
        rfqRepository.save(Rfq.builder().clientName("B").status(RfqStatus.PENDING_REVIEW).build());
        rfqRepository.save(Rfq.builder().clientName("C").status(RfqStatus.PENDING_REVIEW).build());
        rfqRepository.save(Rfq.builder().clientName("D").status(RfqStatus.QUOTED).build());

        assertThat(rfqRepository.countByStatus(RfqStatus.PENDING_REVIEW)).isEqualTo(2);
        assertThat(rfqRepository.findByStatusOrderByCreatedAtDesc(RfqStatus.PROCESSING, PageRequest.of(0, 10))
                .getTotalElements()).isEqualTo(1);
        assertThat(rfqRepository.findByStatusInOrderByCreatedAtDesc(
                        List.of(RfqStatus.PROCESSING, RfqStatus.PENDING_REVIEW), PageRequest.of(0, 10))
                .getTotalElements()).isEqualTo(3);
    }

    @Test
    void findMaxSequenceForYear_returnsZero_whenNoQuotationsExistForThatYear() {
        int farFutureYear = 2999;
        Integer max = quotationRepository.findMaxSequenceForYear(farFutureYear);
        // La query usa COALESCE(..., 0): nunca debe devolver null.
        assertThat(max).isEqualTo(0);
    }

    @Test
    void findMaxSequenceForYear_extractsHighestSequenceFromQuotationNumberFormat() {
        quotationRepository.save(quotation("COT-2026-0001"));
        quotationRepository.save(quotation("COT-2026-0005"));
        quotationRepository.save(quotation("COT-2026-0003"));
        quotationRepository.save(quotation("COT-2025-0099")); // otro año, no debe contar

        Integer max = quotationRepository.findMaxSequenceForYear(2026);

        assertThat(max).isEqualTo(5);
    }

    @Test
    void productRepository_findLowStockProducts_onlyReturnsBelowThreshold() {
        productRepository.save(product("SKU-LOW", new BigDecimal("2"), new BigDecimal("5")));
        productRepository.save(product("SKU-OK", new BigDecimal("50"), new BigDecimal("5")));

        List<Product> lowStock = productRepository.findLowStockProducts();

        assertThat(lowStock).extracting(Product::getSku).containsExactly("SKU-LOW");
    }

    @Test
    void userRepository_findByEmail_locatesSeededUser() {
        userRepository.save(User.builder().email("operador@ansertech.pe")
                .passwordHash("hash").fullName("Operador Test").role(UserRole.OPERATOR).build());

        assertThat(userRepository.findByEmail("operador@ansertech.pe")).isPresent();
        assertThat(userRepository.findByEmail("no-existe@ansertech.pe")).isEmpty();
    }

    private Quotation quotation(String number) {
        return Quotation.builder()
                .quotationNumber(number).currency("PEN")
                .validUntil(LocalDate.now().plusDays(15))
                .subtotal(BigDecimal.ZERO).igv(BigDecimal.ZERO).total(BigDecimal.ZERO)
                .build();
    }

    private Product product(String sku, BigDecimal stock, BigDecimal minThreshold) {
        return Product.builder()
                .sku(sku).name("Producto " + sku)
                .stockQuantity(stock).minStockThreshold(minThreshold)
                .unitPrice(new BigDecimal("10.00")).build();
    }
}
