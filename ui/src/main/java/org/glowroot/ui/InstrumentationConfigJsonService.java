/*
 * Copyright 2013-2016 the original author or authors.
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
package org.glowroot.ui;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import com.google.common.primitives.Ints;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.QueryStringDecoder;
import org.immutables.value.Value;

import org.glowroot.common.config.ImmutableInstrumentationConfig;
import org.glowroot.common.config.InstrumentationConfig;
import org.glowroot.common.config.InstrumentationConfig.CaptureKind;
import org.glowroot.common.config.InstrumentationConfig.MethodModifier;
import org.glowroot.common.live.LiveWeavingService;
import org.glowroot.common.live.LiveWeavingService.GlobalMeta;
import org.glowroot.common.live.LiveWeavingService.MethodSignature;
import org.glowroot.common.util.ObjectMappers;
import org.glowroot.storage.repo.ConfigRepository;

import static com.google.common.base.Preconditions.checkNotNull;

@JsonService
class InstrumentationConfigJsonService {

    private static final ObjectMapper mapper = ObjectMappers.create();

    private static final Ordering<InstrumentationConfig> ordering =
            new InstrumentationConfigOrdering();

    private final ConfigRepository configRepository;
    private final LiveWeavingService liveWeavingService;

    InstrumentationConfigJsonService(ConfigRepository configRepository,
            LiveWeavingService liveWeavingService) {
        this.configRepository = configRepository;
        this.liveWeavingService = liveWeavingService;
    }

    @GET("/backend/config/instrumentation")
    String getInstrumentationConfig(String queryString) throws Exception {
        InstrumentationConfigRequest request =
                QueryStrings.decode(queryString, InstrumentationConfigRequest.class);
        String serverId = request.serverId();
        Optional<String> version = request.version();
        if (version.isPresent()) {
            return getInstrumentationConfigInternal(serverId, version.get());
        } else {
            List<InstrumentationConfig> configs =
                    configRepository.getInstrumentationConfigs(serverId);
            configs = ordering.immutableSortedCopy(configs);
            List<InstrumentationConfigDto> dtos = Lists.newArrayList();
            for (InstrumentationConfig config : configs) {
                dtos.add(InstrumentationConfigDto.fromConfig(config));
            }
            GlobalMeta globalMeta = liveWeavingService.getGlobalMeta(serverId);
            return mapper.writeValueAsString(ImmutableInstrumentationListResponse.builder()
                    .addAllConfigs(dtos)
                    .jvmOutOfSync(globalMeta.jvmOutOfSync())
                    .jvmRetransformClassesSupported(globalMeta.jvmRetransformClassesSupported())
                    .build());
        }
    }

    // this is marked as @GET so it can be used without update rights (e.g. demo instance)
    @GET("/backend/config/preload-classpath-cache")
    void preloadClasspathCache(String queryString) throws Exception {
        final String serverId = getServerId(queryString);
        // HttpServer is configured with a very small thread pool to keep number of threads down
        // (currently only a single thread), so spawn a background thread to perform the preloading
        // so it doesn't block other http requests
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                liveWeavingService.preloadClasspathCache(serverId);
            }
        });
        thread.setDaemon(true);
        thread.setName("Glowroot-Temporary-Thread");
        thread.start();
    }

    @GET("/backend/config/matching-class-names")
    String getMatchingClassNames(String queryString) throws Exception {
        ClassNamesRequest request = QueryStrings.decode(queryString, ClassNamesRequest.class);
        return mapper.writeValueAsString(liveWeavingService.getMatchingClassNames(
                request.serverId(), request.partialClassName(), request.limit()));
    }

    @GET("/backend/config/matching-method-names")
    String getMatchingMethodNames(String queryString) throws Exception {
        MethodNamesRequest request = QueryStrings.decode(queryString, MethodNamesRequest.class);
        List<String> matchingMethodNames =
                liveWeavingService.getMatchingMethodNames(request.serverId(), request.className(),
                        request.partialMethodName(), request.limit());
        return mapper.writeValueAsString(matchingMethodNames);
    }

    @GET("/backend/config/method-signatures")
    String getMethodSignatures(String queryString) throws Exception {
        MethodSignaturesRequest request =
                QueryStrings.decode(queryString, MethodSignaturesRequest.class);
        List<MethodSignature> methodSignatures = liveWeavingService
                .getMethodSignatures(request.serverId(), request.className(), request.methodName());
        return mapper.writeValueAsString(methodSignatures);
    }

    @POST("/backend/config/instrumentation/add")
    String addInstrumentationConfig(String content) throws Exception {
        InstrumentationConfigDto configDto =
                mapper.readValue(content, ImmutableInstrumentationConfigDto.class);
        String serverId = checkNotNull(configDto.serverId());
        InstrumentationConfig config = configDto.toConfig();
        ImmutableList<String> errors = config.validationErrors();
        if (!errors.isEmpty()) {
            return mapper.writeValueAsString(
                    ImmutableInstrumentationErrorResponse.builder().addAllErrors(errors).build());
        }
        configRepository.insertInstrumentationConfig(serverId, config);
        return getInstrumentationConfigInternal(serverId, config.version());
    }

    @POST("/backend/config/instrumentation/update")
    String updateInstrumentationConfig(String content) throws Exception {
        InstrumentationConfigDto configDto =
                mapper.readValue(content, ImmutableInstrumentationConfigDto.class);
        String serverId = checkNotNull(configDto.serverId());
        InstrumentationConfig config = configDto.toConfig();
        String version = configDto.version();
        checkNotNull(version, "Missing required request property: version");
        configRepository.updateInstrumentationConfig(serverId, config, version);
        return getInstrumentationConfigInternal(serverId, config.version());
    }

    @POST("/backend/config/instrumentation/remove")
    void removeInstrumentationConfig(String content) throws IOException {
        InstrumentationConfigRequest request =
                mapper.readValue(content, ImmutableInstrumentationConfigRequest.class);
        configRepository.deleteInstrumentationConfig(request.serverId(), request.version().get());
    }

    private String getInstrumentationConfigInternal(String serverId, String version)
            throws JsonProcessingException {
        InstrumentationConfig config =
                configRepository.getInstrumentationConfig(serverId, version);
        if (config == null) {
            throw new JsonServiceException(HttpResponseStatus.NOT_FOUND);
        }
        List<MethodSignature> methodSignatures = liveWeavingService.getMethodSignatures(serverId,
                config.className(), config.methodName());
        return mapper.writeValueAsString(ImmutableInstrumentationConfigResponse.builder()
                .config(InstrumentationConfigDto.fromConfig(config))
                .addAllMethodSignatures(methodSignatures)
                .build());
    }

    private static String getServerId(String queryString) {
        return QueryStringDecoder.decodeComponent(queryString.substring("server-id".length() + 1));
    }

    @Value.Immutable
    interface InstrumentationConfigRequest {
        String serverId();
        Optional<String> version();
    }

    @Value.Immutable
    interface ClassNamesRequest {
        String serverId();
        String partialClassName();
        int limit();
    }

    @Value.Immutable
    interface MethodNamesRequest {
        String serverId();
        String className();
        String partialMethodName();
        int limit();
    }

    @Value.Immutable
    interface MethodSignaturesRequest {
        String serverId();
        String className();
        String methodName();
    }

    @Value.Immutable
    interface InstrumentationListResponse {
        ImmutableList<InstrumentationConfigDto> configs();
        boolean jvmOutOfSync();
        boolean jvmRetransformClassesSupported();
    }

    @Value.Immutable
    interface InstrumentationConfigResponse {
        InstrumentationConfigDto config();
        ImmutableList<MethodSignature> methodSignatures();
    }

    @Value.Immutable
    interface InstrumentationErrorResponse {
        abstract ImmutableList<String> errors();
    }

    @Value.Immutable
    @JsonInclude(value = Include.ALWAYS)
    abstract static class InstrumentationConfigDto {

        @JsonInclude(value = Include.NON_EMPTY)
        abstract @Nullable String serverId(); // only used in request
        abstract String className();
        abstract String classAnnotation();
        abstract String methodDeclaringClassName();
        abstract String methodName();
        abstract String methodAnnotation();
        abstract ImmutableList<String> methodParameterTypes();
        abstract String methodReturnType();
        abstract ImmutableList<MethodModifier> methodModifiers();
        abstract String nestingGroup();
        abstract CaptureKind captureKind();
        abstract String timerName();
        abstract String traceEntryMessageTemplate();
        abstract @Nullable Integer traceEntryStackThresholdMillis();
        abstract boolean traceEntryCaptureSelfNested();
        abstract String transactionType();
        abstract String transactionNameTemplate();
        abstract String transactionUserTemplate();
        abstract Map<String, String> transactionAttributeTemplates();
        abstract @Nullable Integer transactionSlowThresholdMillis();
        abstract String enabledProperty();
        abstract String traceEntryEnabledProperty();
        abstract @Nullable String version(); // absent for insert operations

        private static InstrumentationConfigDto fromConfig(InstrumentationConfig config) {
            return ImmutableInstrumentationConfigDto.builder()
                    .className(config.className())
                    .classAnnotation(config.classAnnotation())
                    .methodDeclaringClassName(config.methodDeclaringClassName())
                    .methodName(config.methodName())
                    .methodAnnotation(config.methodAnnotation())
                    .addAllMethodParameterTypes(config.methodParameterTypes())
                    .methodReturnType(config.methodReturnType())
                    .addAllMethodModifiers(config.methodModifiers())
                    .nestingGroup(config.nestingGroup())
                    .captureKind(config.captureKind())
                    .timerName(config.timerName())
                    .traceEntryMessageTemplate(config.traceEntryMessageTemplate())
                    .traceEntryStackThresholdMillis(config.traceEntryStackThresholdMillis())
                    .traceEntryCaptureSelfNested(config.traceEntryCaptureSelfNested())
                    .transactionType(config.transactionType())
                    .transactionNameTemplate(config.transactionNameTemplate())
                    .transactionUserTemplate(config.transactionUserTemplate())
                    .putAllTransactionAttributeTemplates(config.transactionAttributeTemplates())
                    .transactionSlowThresholdMillis(config.transactionSlowThresholdMillis())
                    .enabledProperty(config.enabledProperty())
                    .traceEntryEnabledProperty(config.traceEntryEnabledProperty())
                    .version(config.version())
                    .build();
        }

        private InstrumentationConfig toConfig() {
            return ImmutableInstrumentationConfig.builder()
                    .className(className())
                    .methodDeclaringClassName(methodDeclaringClassName())
                    .methodName(methodName())
                    .addAllMethodParameterTypes(methodParameterTypes())
                    .methodReturnType(methodReturnType())
                    .addAllMethodModifiers(methodModifiers())
                    .nestingGroup(nestingGroup())
                    .captureKind(captureKind())
                    .timerName(timerName())
                    .traceEntryMessageTemplate(traceEntryMessageTemplate())
                    .traceEntryStackThresholdMillis(traceEntryStackThresholdMillis())
                    .traceEntryCaptureSelfNested(traceEntryCaptureSelfNested())
                    .transactionType(transactionType())
                    .transactionNameTemplate(transactionNameTemplate())
                    .transactionUserTemplate(transactionUserTemplate())
                    .putAllTransactionAttributeTemplates(transactionAttributeTemplates())
                    .transactionSlowThresholdMillis(transactionSlowThresholdMillis())
                    .enabledProperty(enabledProperty())
                    .traceEntryEnabledProperty(traceEntryEnabledProperty())
                    .build();
        }
    }

    @VisibleForTesting
    static class InstrumentationConfigOrdering extends Ordering<InstrumentationConfig> {
        @Override
        public int compare(InstrumentationConfig left, InstrumentationConfig right) {
            int compare = left.className().compareToIgnoreCase(right.className());
            if (compare != 0) {
                return compare;
            }
            compare = left.methodName().compareToIgnoreCase(right.methodName());
            if (compare != 0) {
                return compare;
            }
            compare = Ints.compare(left.methodParameterTypes().size(),
                    right.methodParameterTypes().size());
            if (compare != 0) {
                return compare;
            }
            List<String> leftParameterTypes = left.methodParameterTypes();
            List<String> rightParameterTypes = right.methodParameterTypes();
            for (int i = 0; i < leftParameterTypes.size(); i++) {
                compare = leftParameterTypes.get(i).compareToIgnoreCase(rightParameterTypes.get(i));
                if (compare != 0) {
                    return compare;
                }
            }
            return 0;
        }
    }
}
