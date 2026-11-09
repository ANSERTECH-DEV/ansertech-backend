package com.ansertech.service.parameter;

import com.ansertech.domain.entity.Parameter;
import com.ansertech.repository.ParameterRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ParameterServiceTest {

    private static final int FALLBACK = 2;

    private final ParameterRepository repository = mock(ParameterRepository.class);
    private final ParameterService service = new ParameterService(repository);

    private void stubValue(String value) {
        when(repository.findByName(ParameterService.EMAIL_COUNT))
                .thenReturn(Optional.of(Parameter.builder().name(ParameterService.EMAIL_COUNT).value(value).build()));
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
}
