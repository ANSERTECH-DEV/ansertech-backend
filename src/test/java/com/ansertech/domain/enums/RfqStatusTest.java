package com.ansertech.domain.enums;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class RfqStatusTest {

    @ParameterizedTest(name = "{0} → {1} = {2}")
    @CsvSource({
            // válidas
            "PENDING_REVIEW, QUOTING,        true",
            "PENDING_REVIEW, REJECTED,       true",
            "QUOTING,        QUOTED,         true",
            "QUOTING,        REJECTED,       true",
            // inválidas
            "PROCESSING,     PENDING_REVIEW, false", // transición automática del sistema, no vía controller
            "PROCESSING,     QUOTING,        false",
            "PENDING_REVIEW, QUOTED,         false",
            "PENDING_REVIEW, PROCESSING,     false",
            "QUOTING,        PENDING_REVIEW, false",
            "QUOTED,         PROCESSING,     false",
            "QUOTED,         QUOTING,        false",
            "QUOTED,         PENDING_REVIEW, false",
            "REJECTED,       QUOTING,        false",
            "REJECTED,       QUOTED,         false",
    })
    void canTransitionTo(RfqStatus from, RfqStatus target, boolean expected) {
        assertThat(from.canTransitionTo(target)).isEqualTo(expected);
    }
}
