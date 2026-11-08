package com.ansertech.service.auth;

import com.ansertech.domain.enums.AuditAction;
import com.ansertech.service.audit.AuditService;
import org.springframework.security.core.AuthenticationException;

import com.ansertech.domain.entity.User;
import com.ansertech.dto.request.LoginRequest;
import com.ansertech.dto.request.RegisterRequest;
import com.ansertech.dto.response.AuthResponse;
import com.ansertech.exception.BusinessException;
import com.ansertech.repository.UserRepository;
import com.ansertech.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final AuthenticationManager authManager;
    private final JwtTokenProvider tokenProvider;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;

    @Value("${jwt.expiration-ms}")
    private long expirationMs;

    public AuthResponse login(LoginRequest request) {
        Authentication auth;
        try {
            auth = authManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.getEmail(), request.getPassword())
            );
        } catch (AuthenticationException e) {
            Long userId = userRepository.findByEmail(request.getEmail()).map(User::getId).orElse(null);
            auditService.recordAs(request.getEmail(), AuditAction.LOGIN_FAILED, "USER", userId,
                    AuditService.details("motivo", e.getClass().getSimpleName(),
                            "usuarioExiste", userId != null));
            throw e;
        }

        String token = tokenProvider.generateToken(auth);
        User user = userRepository.findByEmail(request.getEmail()).orElseThrow();

        log.info("Login exitoso: {}", request.getEmail());
        auditService.recordAs(user.getEmail(), AuditAction.LOGIN_SUCCESS, "USER", user.getId(),
                AuditService.details("rol", user.getRole().name()));
        return AuthResponse.builder()
                .token(token)
                .email(user.getEmail())
                .fullName(user.getFullName())
                .role(user.getRole().name())
                .expiresIn(expirationMs / 1000)
                .build();
    }

    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new BusinessException("El correo electrónico ya está registrado");
        }

        User user = User.builder()
                .fullName(request.getFullName())
                .email(request.getEmail())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .role(request.getRole())
                .active(true)
                .build();

        String registeredBy = AuditService.currentUserEmail();
        userRepository.save(user);

        log.info("Usuario registrado: {}", request.getEmail());
        auditService.recordAs(registeredBy != null ? registeredBy : user.getEmail(),
                AuditAction.USER_REGISTERED, "USER", user.getId(),
                AuditService.details("email", user.getEmail(), "nombre", user.getFullName(),
                        "rol", user.getRole().name(), "autoregistro", registeredBy == null));
        
        Authentication auth = authManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getEmail(), request.getPassword())
        );

        String token = tokenProvider.generateToken(auth);

        return AuthResponse.builder()
                .token(token)
                .email(user.getEmail())
                .fullName(user.getFullName())
                .role(user.getRole().name())
                .expiresIn(expirationMs / 1000)
                .build();
    }
}
