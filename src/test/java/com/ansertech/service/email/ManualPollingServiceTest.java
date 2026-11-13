package com.ansertech.service.email;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class ManualPollingServiceTest {

    private final EmailPipelineScheduler pipeline = mock(EmailPipelineScheduler.class);
    private final List<Runnable> submitted = new ArrayList<>();
    private final ManualPollingService service = new ManualPollingService(pipeline, submitted::add);

    @Test
    void startReturnsWithoutRunningThePipelineInTheCallerThread() {
        assertThat(service.start()).isTrue();

        verify(pipeline, never()).runPipelineManually();
        submitted.get(0).run();
        verify(pipeline).runPipelineManually();
    }

    @Test
    void rejectsASecondStartWhileOneIsRunning() {
        assertThat(service.start()).isTrue();

        assertThat(service.start()).isFalse();
        assertThat(submitted).hasSize(1);
    }

    @Test
    void allowsANewStartAfterTheRunFinishes() {
        service.start();
        submitted.get(0).run();

        assertThat(service.start()).isTrue();
    }

    @Test
    void allowsANewStartAfterTheRunFails() {
        doThrow(new IllegalStateException("fallo")).when(pipeline).runPipelineManually();
        service.start();

        assertThatThrownBy(() -> submitted.get(0).run()).isInstanceOf(IllegalStateException.class);

        assertThat(service.start()).isTrue();
        verify(pipeline, times(1)).runPipelineManually();
    }

    @Test
    void allowsANewStartWhenTheExecutorRejectsTheTask() {
        ManualPollingService rejecting = new ManualPollingService(pipeline, task -> {
            throw new RejectedExecutionException();
        });

        assertThatThrownBy(rejecting::start).isInstanceOf(RejectedExecutionException.class);
        assertThatThrownBy(rejecting::start).isInstanceOf(RejectedExecutionException.class);
    }
}
