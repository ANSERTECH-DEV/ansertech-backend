package com.ansertech.service.graph;

import com.ansertech.dto.response.GraphConnectionResponse;
import com.azure.identity.DeviceCodeInfo;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class GraphConnectionStateTest {

    private static final Instant NOW = Instant.parse("2026-11-08T22:00:00Z");

    private static DeviceCodeInfo challenge(int minutesValid) {
        return new DeviceCodeInfo("ABCD2345", "device-code", "https://microsoft.com/devicelogin",
                OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC).plusMinutes(minutesValid), "mensaje");
    }

    @Test
    void startsNotConnected() {
        GraphConnectionState state = new GraphConnectionState(Clock.fixed(NOW, ZoneId.of("UTC")));

        assertThat(state.snapshot().getStatus()).isEqualTo("NOT_CONNECTED");
        assertThat(state.hasLiveChallenge()).isFalse();
    }

    @Test
    void challengeIsPendingUntilConnected() {
        GraphConnectionState state = new GraphConnectionState(Clock.fixed(NOW, ZoneId.of("UTC")));
        state.onChallenge(challenge(15));

        GraphConnectionResponse pending = state.snapshot();
        assertThat(pending.getStatus()).isEqualTo("PENDING");
        assertThat(pending.getCode()).isEqualTo("ABCD2345");
        assertThat(pending.getUrl()).isEqualTo("https://microsoft.com/devicelogin");
        assertThat(state.hasLiveChallenge()).isTrue();

        state.onConnected();
        GraphConnectionResponse connected = state.snapshot();
        assertThat(connected.getStatus()).isEqualTo("CONNECTED");
        assertThat(connected.getCode()).isNull();
    }

    @Test
    void expiredChallengeIsReportedAsError() {
        GraphConnectionState state = new GraphConnectionState(Clock.fixed(NOW, ZoneId.of("UTC")));
        state.onChallenge(challenge(0));

        GraphConnectionResponse snapshot = state.snapshot();
        assertThat(snapshot.getStatus()).isEqualTo("ERROR");
        assertThat(snapshot.getCode()).isNull();
        assertThat(snapshot.getError()).contains("venció");
    }
}
