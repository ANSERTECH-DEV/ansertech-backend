package com.ansertech.controller;

import com.ansertech.AbstractIntegrationTest;
import com.ansertech.domain.entity.Product;
import com.ansertech.domain.entity.User;
import com.ansertech.domain.enums.UserRole;
import com.ansertech.repository.ProductRepository;
import com.ansertech.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /api/inventory/import vía HTTP real (multipart + JWT real) contra PostgreSQL
 * real (Testcontainers): persistencia, dryRun sin efectos y control de acceso.
 */
class InventoryImportIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "Password123!";
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void seed() {
        userRepository.save(User.builder().email("admin.import@ansertech.pe")
                .passwordHash(passwordEncoder.encode(PASSWORD))
                .fullName("Admin Import").role(UserRole.ADMIN).active(true).build());
        userRepository.save(User.builder().email("operador.import@ansertech.pe")
                .passwordHash(passwordEncoder.encode(PASSWORD))
                .fullName("Operador Import").role(UserRole.OPERATOR).active(true).build());
        productRepository.save(Product.builder().sku("IMP-EXIST").name("Existente")
                .stockQuantity(BigDecimal.TEN).unitPrice(new BigDecimal("50.00")).build());
    }

    @Test
    void admin_import_persistsCreatesAndUpdates() throws Exception {
        String token = login("admin.import@ansertech.pe");

        mockMvc.perform(multipart("/api/inventory/import").file(sampleFile())
                        .param("dryRun", "false")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dryRun").value(false))
                .andExpect(jsonPath("$.data.created").value(1))
                .andExpect(jsonPath("$.data.updated").value(1))
                .andExpect(jsonPath("$.data.errors").value(1));

        assertThat(productRepository.findBySku("IMP-NEW")).isPresent();
        assertThat(productRepository.findBySku("IMP-EXIST").orElseThrow().getStockQuantity())
                .isEqualByComparingTo("99");
    }

    @Test
    void admin_dryRun_returnsPreviewWithoutPersisting() throws Exception {
        String token = login("admin.import@ansertech.pe");

        mockMvc.perform(multipart("/api/inventory/import").file(sampleFile())
                        .header("Authorization", "Bearer " + token))   // dryRun por defecto = true
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dryRun").value(true))
                .andExpect(jsonPath("$.data.rows[0].action").value("UPDATE"))
                .andExpect(jsonPath("$.data.rows[0].oldStock").value(10))
                .andExpect(jsonPath("$.data.rows[0].newStock").value(99))
                .andExpect(jsonPath("$.data.rows[1].action").value("CREATE"))
                .andExpect(jsonPath("$.data.rows[2].action").value("ERROR"));

        assertThat(productRepository.findBySku("IMP-NEW")).isEmpty();
        assertThat(productRepository.findBySku("IMP-EXIST").orElseThrow().getStockQuantity())
                .isEqualByComparingTo("10");
    }

    @Test
    void operator_cannotImport_forbidden() throws Exception {
        String token = login("operador.import@ansertech.pe");

        mockMvc.perform(multipart("/api/inventory/import").file(sampleFile())
                        .param("dryRun", "false")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());

        assertThat(productRepository.findBySku("IMP-NEW")).isEmpty();
    }

    @Test
    void nonXlsxFile_isRejectedWith400() throws Exception {
        String token = login("admin.import@ansertech.pe");
        MockMultipartFile csv = new MockMultipartFile("file", "productos.csv", "text/csv", "SKU,NOMBRE\nA,B".getBytes());

        mockMvc.perform(multipart("/api/inventory/import").file(csv)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    /** Fila 2: actualiza IMP-EXIST (stock 10→99). Fila 3: crea IMP-NEW. Fila 4: error (sin nombre ni precio). */
    private MockMultipartFile sampleFile() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Inventario");
            String[] headers = {"SKU", "NOMBRE", "STOCK", "PRECIO UNIT."};
            Row h = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) h.createCell(i).setCellValue(headers[i]);

            Row r1 = sheet.createRow(1);
            r1.createCell(0).setCellValue("IMP-EXIST");
            r1.createCell(2).setCellValue(99);

            Row r2 = sheet.createRow(2);
            r2.createCell(0).setCellValue("IMP-NEW");
            r2.createCell(1).setCellValue("Producto nuevo");
            r2.createCell(2).setCellValue(15);
            r2.createCell(3).setCellValue(80.5);

            Row r3 = sheet.createRow(3);
            r3.createCell(0).setCellValue("IMP-BAD");

            wb.write(out);
            return new MockMultipartFile("file", "inventario.xlsx", XLSX, out.toByteArray());
        }
    }

    private String login(String email) throws Exception {
        String body = objectMapper.createObjectNode()
                .put("email", email).put("password", PASSWORD).toString();
        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("data").path("token").asText();
    }
}
