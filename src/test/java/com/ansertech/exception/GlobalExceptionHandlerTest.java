package com.ansertech.exception;

import com.ansertech.dto.response.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void credencialesIncorrectasResponden401ConMensaje() {
        ResponseEntity<ApiResponse<Void>> res = handler.handleBadCredentials(new BadCredentialsException("Bad credentials"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(res.getBody().isSuccess()).isFalse();
        assertThat(res.getBody().getMessage()).isEqualTo("Credenciales inválidas");
    }

    @Test
    void usuarioInactivoResponde401EnVezDe500() {
        ResponseEntity<ApiResponse<Void>> res = handler.handleDisabledUser(new DisabledException("User is disabled"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(res.getBody().isSuccess()).isFalse();
        assertThat(res.getBody().getMessage()).isEqualTo(GlobalExceptionHandler.INACTIVE_USER_MESSAGE);
    }

    @Test
    void usuarioBloqueadoResponde401() {
        ResponseEntity<ApiResponse<Void>> res = handler.handleDisabledUser(new LockedException("User account is locked"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(res.getBody().getMessage()).isEqualTo(GlobalExceptionHandler.INACTIVE_USER_MESSAGE);
    }
}
