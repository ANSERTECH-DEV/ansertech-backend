package com.ansertech.service.parameter;

import com.ansertech.domain.entity.Parameter;
import com.ansertech.exception.BusinessException;
import com.ansertech.exception.ResourceNotFoundException;
import com.ansertech.repository.ParameterRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ParameterServiceTest {

    private static final int FALLBACK = 2;

    private final ParameterRepository repository = mock(ParameterRepository.class);
    private final ParameterService service = new ParameterService(repository, FALLBACK);

    private void stubValue(String value) {
        when(repository.findByName(ParameterService.EMAIL_COUNT))
                .thenReturn(Optional.of(Parameter.builder().name(ParameterService.EMAIL_COUNT).value(value).build()));
    }

    private Parameter stubById(long id, String name, String value) {
        Parameter parameter = Parameter.builder().id(id).name(name).value(value).build();
        when(repository.findById(id)).thenReturn(Optional.of(parameter));
        when(repository.save(any(Parameter.class))).thenAnswer(inv -> inv.getArgument(0));
        return parameter;
    }

    @Test
    void usesTheStoredValue() {
        stubValue(" 15 ");

        assertThat(service.getEmailCount(FALLBACK)).isEqualTo(15);
    }

    @Test
    void usesFallbackWhenParameterIsMissing() {
        when(repository.findByName(ParameterService.EMAIL_COUNT)).thenReturn(Optional.empty());

        assertThat(service.getEmailCount(FALLBACK)).isEqualTo(FALLBACK);
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "", "0", "-3", "1001"})
    void usesFallbackWhenValueIsInvalid(String value) {
        stubValue(value);

        assertThat(service.getEmailCount(FALLBACK)).isEqualTo(FALLBACK);
    }

    @Test
    void noArgVersionUsesTheConfiguredDefault() {
        when(repository.findByName(ParameterService.EMAIL_COUNT)).thenReturn(Optional.empty());

        assertThat(service.getEmailCount()).isEqualTo(FALLBACK);
    }

    @Test
    void updateValueStoresTheTrimmedValue() {
        stubById(1L, ParameterService.EMAIL_COUNT, "2");

        Parameter updated = service.updateValue(1L, " 10 ");

        assertThat(updated.getValue()).isEqualTo("10");
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "0", "1001", "2.5"})
    void updateValueRejectsAnInvalidEmailCount(String value) {
        stubById(1L, ParameterService.EMAIL_COUNT, "2");

        assertThatThrownBy(() -> service.updateValue(1L, value)).isInstanceOf(BusinessException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void updateValueAcceptsFreeTextForOtherParameters() {
        stubById(2L, "Otro parámetro", "x");

        assertThat(service.updateValue(2L, "texto libre").getValue()).isEqualTo("texto libre");
    }

    @Test
    void updateValueRejectsABlankValue() {
        stubById(2L, "Otro parámetro", "x");

        assertThatThrownBy(() -> service.updateValue(2L, "   ")).isInstanceOf(BusinessException.class);
    }

    @Test
    void updateValueFailsForAnUnknownId() {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateValue(99L, "5")).isInstanceOf(ResourceNotFoundException.class);
    }
}
