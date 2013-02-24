/**
 * Copyright 2012-2013 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.informant.core;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import io.informant.config.ConfigService;
import io.informant.config.FineProfilingConfig;
import io.informant.util.DaemonExecutors;
import io.informant.util.Singleton;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Ticker;

/**
 * Owns the thread (via a single threaded scheduled executor) that captures fine-grained thread
 * dumps for a percentage of traces.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
class FineGrainedProfiler {

    private static final Logger logger = LoggerFactory.getLogger(FineGrainedProfiler.class);

    private final ScheduledExecutorService scheduledExecutor = DaemonExecutors
            .newSingleThreadScheduledExecutor("Informant-FineGrainedProfiler");

    private final ConfigService configService;
    private final Ticker ticker;

    FineGrainedProfiler(ConfigService configService, Ticker ticker) {
        this.configService = configService;
        this.ticker = ticker;
    }

    void close() {
        logger.debug("close()");
        scheduledExecutor.shutdownNow();
    }

    // schedules the first stack collection for configured interval after trace start (or
    // immediately, if trace duration already exceeds configured collection interval)
    void scheduleProfiling(Trace trace) {
        FineProfilingConfig config = configService.getFineProfilingConfig();
        // extra half interval at the end to make sure the final stack trace is grabbed if it aligns
        // on total (e.g. 100ms interval, 1 second total should result in exactly 10 stack traces)
        long endTick = trace.getStartTick() + SECONDS.toNanos(config.getTotalSeconds())
                + MILLISECONDS.toNanos(config.getIntervalMillis()) / 2;
        CollectStackCommand command = new CollectStackCommand(trace, endTick, true, ticker);
        long initialDelay = Math.max(0,
                config.getIntervalMillis() - NANOSECONDS.toMillis(trace.getDuration()));
        ScheduledFuture<?> scheduledFuture = scheduledExecutor.scheduleAtFixedRate(command,
                initialDelay, config.getIntervalMillis(), MILLISECONDS);
        trace.setFineProfilingScheduledFuture(scheduledFuture);
    }
}