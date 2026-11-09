package com.ansertech.service.email;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Lanza el polling manual en segundo plano, con una sola ejecución a la vez. */
@Service
public class ManualPollingService {

    private final EmailPipelineScheduler pipeline;
    private final Executor executor;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public ManualPollingService(EmailPipelineScheduler pipeline,
                                @Qualifier("emailPollingExecutor") Executor executor) {
        this.pipeline = pipeline;
        this.executor = executor;
    }

    /** @return {@code false} si ya hay una lectura en curso; no espera a que termine. */
    public boolean start() {
        if (!running.compareAndSet(false, true)) {
            return false;
        }
        try {
            executor.execute(this::run);
        } catch (RejectedExecutionException e) {
            running.set(false);
            throw e;
        }
        return true;
    }

    private void run() {
        try {
            pipeline.runPipelineManually();
        } finally {
            running.set(false);
        }
    }
}
