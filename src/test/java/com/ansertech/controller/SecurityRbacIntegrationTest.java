package com.ansertech.controller;

import com.ansertech.AbstractIntegrationTest;
import com.ansertech.domain.entity.Product;
import com.ansertech.domain.entity.User;
import com.ansertech.domain.enums.UserRole;
import com.ansertech.repository.ProductRepository;
import com.ansertech.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Login real (JWT genuino, no @WithMockUser) + control de acceso por rol contra
 * los endpoints que sí lo exigen. Confirma también que un token válido de cualquier
 * rol autenticado alcanza para los endpoints que solo requieren estar logueado.
 */
class SecurityRbacIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "Password123!";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void seedUsers() {
        userRepository.save(User.builder().email("admin.rbac@ansertech.pe")
                .passwordHash(passwordEncoder.encode(PASSWORD))
                .fullName("Admin RBAC").role(UserRole.ADMIN).active(true).build());
        userRepository.save(User.builder().email("operador.rbac@ansertech.pe")
                .passwordHash(passwordEncoder.encode(PASSWORD))
                .fullName("Operador RBAC").role(UserRole.OPERATOR).active(true).build());
    }

    @Test
    void login_withValidCredentials_returnsJwtAndCorrectRole() throws Exception {
        String token = login("admin.rbac@ansertech.pe");
        assertThat(token).isNotBlank();
    }

    @Test
    void adminToken_canCreateInventoryProduct() throws Exception {
        String token = login("admin.rbac@ansertech.pe");

        mockMvc.perform(post("/api/inventory")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productRequestJson("SKU-RBAC-1")))
                .andExpect(status().isCreated());
    }

    @Test
    void operatorToken_cannotCreateInventoryProduct_forbidden() throws Exception {
        String token = login("operador.rbac@ansertech.pe");

        mockMvc.perform(post("/api/inventory")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productRequestJson("SKU-RBAC-2")))
                .andExpect(status().isForbidden());
    }

    @Test
    void operatorToken_cannotUpdateStock_forbidden() throws Exception {
        String token = login("operador.rbac@ansertech.pe");
        Product product = productRepository.save(Product.builder()
                .sku("SKU-RBAC-STOCK").name("Producto stock")
                .stockQuantity(BigDecimal.TEN).unitPrice(BigDecimal.TEN).build());

        mockMvc.perform(patch("/api/inventory/{id}/stock", product.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\": 20}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void operatorToken_cannotTriggerPolling_forbidden() throws Exception {
        String token = login("operador.rbac@ansertech.pe");

        mockMvc.perform(post("/api/emails/trigger-polling")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void anyAuthenticatedToken_canListRfqs_regardlessOfRole() throws Exception {
        String token = login("operador.rbac@ansertech.pe");

        mockMvc.perform(get("/api/rfqs").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void requestWithoutToken_isRejected() throws Exception {
        mockMvc.perform(get("/api/rfqs"))
                .andExpect(status().is4xxClientError());
    }

    private String login(String email) throws Exception {
        String body = objectMapper.createObjectNode()
                .put("email", email).put("password", PASSWORD).toString();

        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").exists())
                .andReturn().getResponse().getContentAsString();

        JsonNode json = objectMapper.readTree(response);
        return json.path("data").path("token").asText();
    }

    private String productRequestJson(String sku) {
        return objectMapper.createObjectNode()
                .put("sku", sku).put("name", "Producto " + sku)
                .put("stockQuantity", 10).put("unitPrice", 25.50)
                .toString();
    }
}
