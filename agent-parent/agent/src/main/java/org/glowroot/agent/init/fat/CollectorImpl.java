/*
 * Copyright 2015-2016 the original author or authors.
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
package org.glowroot.agent.init.fat;

import java.util.List;

import org.glowroot.storage.repo.AggregateRepository;
import org.glowroot.storage.repo.GaugeValueRepository;
import org.glowroot.storage.repo.ServerRepository;
import org.glowroot.storage.repo.TraceRepository;
import org.glowroot.storage.repo.helper.AlertingService;
import org.glowroot.wire.api.Collector;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig;
import org.glowroot.wire.api.model.AggregateOuterClass.AggregatesByType;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.GaugeValue;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.LogEvent;
import org.glowroot.wire.api.model.CollectorServiceOuterClass.ProcessInfo;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

class CollectorImpl implements Collector {

    private static final String SERVER_ID = "";

    private final ServerRepository serverRepository;
    private final AggregateRepository aggregateRepository;
    private final TraceRepository traceRepository;
    private final GaugeValueRepository gaugeValueRepository;
    private final AlertingService alertingService;

    CollectorImpl(ServerRepository serverRepository, AggregateRepository aggregateRepository,
            TraceRepository traceRepository, GaugeValueRepository gaugeValueRepository,
            AlertingService alertingService) {
        this.serverRepository = serverRepository;
        this.aggregateRepository = aggregateRepository;
        this.traceRepository = traceRepository;
        this.gaugeValueRepository = gaugeValueRepository;
        this.alertingService = alertingService;
    }

    @Override
    public void collectInit(ProcessInfo processInfo, AgentConfig agentConfig) throws Exception {
        serverRepository.storeProcessInfo(SERVER_ID, processInfo);
    }

    @Override
    public void collectAggregates(long captureTime, List<AggregatesByType> aggregatesByType)
            throws Exception {
        aggregateRepository.store(SERVER_ID, captureTime, aggregatesByType);
        alertingService.checkAlerts(captureTime);
    }

    @Override
    public void collectGaugeValues(List<GaugeValue> gaugeValues) throws Exception {
        gaugeValueRepository.store(SERVER_ID, gaugeValues);
    }

    @Override
    public void collectTrace(Trace trace) throws Exception {
        traceRepository.collect(SERVER_ID, trace);
    }

    @Override
    public void log(LogEvent logEvent) {
        // do nothing, already logging locally through ConsoleAppender and RollingFileAppender
    }
}
