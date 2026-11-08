package com.ansertech;

import com.ansertech.service.ai.GeminiService;
import com.ansertech.service.email.EmailSenderService;
import com.ansertech.service.quotation.QuotationPdfService;
import com.google.cloud.vertexai.VertexAI;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base de los tests @SpringBootTest de integración.
 *
 * Regla de oro: ningún test toca Vertex AI, Microsoft Graph ni Azure Blob real.
 * Se mockean tanto los clientes SDK crudos (VertexAI, GraphServiceClient) —para que
 * VertexAiConfig/GraphConfiguration nunca intenten autenticación real al levantar el
 * contexto— como los wrappers de más alto nivel (GeminiService, QuotationPdfService,
 * EmailSenderService) que usa QuotationService, para que el ciclo de vida completo del
 * RFQ se pueda ejercitar vía HTTP real sin llamadas externas.
 *
 * El contenedor NO lleva @Container: varias subclases concretas comparten este mismo
 * campo static (herencia), y @Testcontainers detiene un @Container al terminar CADA
 * clase concreta — con varias subclases eso apaga el contenedor entre una y otra.
 * Se arranca una sola vez en un bloque static (patrón "singleton container") y el
 * reaper de Testcontainers lo limpia solo al terminar la JVM.
 *
 * disabledWithoutDocker = true: si el daemon de Docker no está activo, estos tests se
 * saltan (no fallan) en vez de romper toda la suite.
 *
 * @Transactional: cada @Test corre en su propia transacción, revertida al final —
 * evita choques de datos (p. ej. emails de usuario duplicados) entre métodos de test
 * que comparten el mismo contenedor/contexto Spring cacheado.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@Testcontainers(disabledWithoutDocker = true)
public abstract class AbstractIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:15-alpine");

    static {
        POSTGRES.start();
    }

    @MockBean
    protected VertexAI vertexAI;

    @MockBean
    protected GraphServiceClient graphServiceClient;

    @MockBean
    protected GeminiService geminiService;

    @MockBean
    protected QuotationPdfService quotationPdfService;

    @MockBean
    protected EmailSenderService emailSenderService;
}
