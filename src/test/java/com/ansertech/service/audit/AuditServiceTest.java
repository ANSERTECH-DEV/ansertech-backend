package com.ansertech.service.audit;

import com.ansertech.domain.entity.AuditLog;
import com.ansertech.domain.entity.User;
import com.ansertech.domain.enums.AuditAction;
import com.ansertech.repository.AuditLogRepository;
import com.ansertech.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditServiceTest {

    @Mock AuditLogRepository auditLogRepository;
    @Mock UserRepository userRepository;
    @Mock PlatformTransactionManager transactionManager;

    AuditService service;

    @BeforeEach
    void setUp() {
        lenient().when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new AuditService(auditLogRepository, userRepository, new ObjectMapper(), transactionManager);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void recordsAuthenticatedUserWithIpAndDetails() {
        User user = User.builder().id(7L).email("ana@ansertech.pe").build();
        when(userRepository.findByEmail("ana@ansertech.pe")).thenReturn(Optional.of(user));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "ana@ansertech.pe", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "179.6.29.229, 10.0.0.1");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        service.record(AuditAction.RFQ_REJECTED, "RFQ", 172L,
                AuditService.details("estadoAnterior", "PENDING_REVIEW", "nota", null));

        AuditLog saved = captureSaved();
        assertThat(saved.getAction()).isEqualTo("RFQ_REJECTED");
        assertThat(saved.getEntityType()).isEqualTo("RFQ");
        assertThat(saved.getEntityId()).isEqualTo(172L);
        assertThat(saved.getUser()).isSameAs(user);
        assertThat(saved.getDetails())
                .isEqualTo("{\"actor\":\"ana@ansertech.pe\",\"ip\":\"179.6.29.229\","
                        + "\"estadoAnterior\":\"PENDING_REVIEW\",\"nota\":null}");
    }

    @Test
    void systemActionsHaveNoUser() {
        service.recordSystem(AuditAction.EMAIL_CLASSIFIED, "EMAIL", 463L, AuditService.details("clasificacion", "VALID"));

        AuditLog saved = captureSaved();
        assertThat(saved.getUser()).isNull();
        assertThat(saved.getDetails()).isEqualTo("{\"actor\":\"SISTEMA\",\"clasificacion\":\"VALID\"}");
        verify(userRepository, never()).findByEmail(any());
    }

    @Test
    void failedLoginKeepsAttemptedEmailEvenIfUserDoesNotExist() {
        when(userRepository.findByEmail("intruso@x.com")).thenReturn(Optional.empty());

        service.recordAs("intruso@x.com", AuditAction.LOGIN_FAILED, "USER", null,
                AuditService.details("motivo", "BadCredentialsException"));

        AuditLog saved = captureSaved();
        assertThat(saved.getUser()).isNull();
        assertThat(saved.getDetails()).contains("\"actor\":\"intruso@x.com\"");
    }

    @Test
    void auditFailureNeverBreaksTheCaller() {
        when(auditLogRepository.save(any())).thenThrow(new RuntimeException("BD caída"));

        assertThatCode(() -> service.recordSystem(AuditAction.RFQ_CREATED, "RFQ", 1L, null))
                .doesNotThrowAnyException();
    }

    private AuditLog captureSaved() {
        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        return captor.getValue();
    }
}
