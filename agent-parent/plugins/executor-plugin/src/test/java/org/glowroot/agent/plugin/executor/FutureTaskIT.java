/*
 * Copyright 2016 the original author or authors.
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
package org.glowroot.agent.plugin.executor;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.agent.it.harness.AppUnderTest;
import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.Containers;
import org.glowroot.agent.it.harness.TransactionMarker;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.TransactionConfig;
import org.glowroot.wire.api.model.Proto.OptionalInt32;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.assertj.core.api.Assertions.assertThat;

public class FutureTaskIT {

    private static Container container;

    @BeforeClass
    public static void setUp() throws Exception {
        // tests only work with javaagent container because they need to weave bootstrap classes
        // that implement Executor and ExecutorService
        container = Containers.createJavaagent();
    }

    @AfterClass
    public static void tearDown() throws Exception {
        container.close();
    }

    @After
    public void afterEachTest() throws Exception {
        container.checkAndReset();
    }

    @Test
    public void shouldCaptureCallable() throws Exception {
        // given
        container.getConfigService().updateTransactionConfig(
                TransactionConfig.newBuilder()
                        .setProfilingIntervalMillis(OptionalInt32.newBuilder().setValue(20).build())
                        .build());
        // when
        Trace trace = container.execute(DoSomeCallableWork.class);
        // then
        checkTrace(trace);
    }

    @Test
    public void shouldCaptureRunnableAndCallable1() throws Exception {
        // given
        container.getConfigService().updateTransactionConfig(
                TransactionConfig.newBuilder()
                        .setProfilingIntervalMillis(OptionalInt32.newBuilder().setValue(20).build())
                        .build());
        // when
        Trace trace = container.execute(DoSomeRunnableAndCallableWork1.class);
        // then
        checkTrace(trace);
    }

    @Test
    public void shouldCaptureRunnableAndCallable2() throws Exception {
        // given
        container.getConfigService().updateTransactionConfig(
                TransactionConfig.newBuilder()
                        .setProfilingIntervalMillis(OptionalInt32.newBuilder().setValue(20).build())
                        .build());
        // when
        Trace trace = container.execute(DoSomeRunnableAndCallableWork1.class);
        // then
        checkTrace(trace);
    }

    @Test
    public void shouldNotCaptureTraceEntryForEmptyAuxThread() throws Exception {
        // given
        container.getConfigService().updateTransactionConfig(
                TransactionConfig.newBuilder()
                        .setProfilingIntervalMillis(OptionalInt32.newBuilder().setValue(20).build())
                        .build());
        // when
        Trace trace = container.execute(DoSomeSimpleRunnableWork.class);
        // then
        Trace.Header header = trace.getHeader();
        assertThat(header.getAuxThreadRootTimerCount()).isEqualTo(1);
        assertThat(header.getAsyncRootTimerCount()).isZero();
        assertThat(header.getAuxThreadRootTimer(0).getName()).isEqualTo("auxiliary thread");
        assertThat(header.getAuxThreadRootTimer(0).getCount()).isEqualTo(3);
        assertThat(header.getAuxThreadRootTimer(0).getTotalNanos())
                .isGreaterThanOrEqualTo(MILLISECONDS.toNanos(250));
        assertThat(header.getAuxThreadRootTimer(0).getChildTimerCount()).isZero();
        assertThat(trace.hasMainThreadProfile()).isTrue();
        assertThat(header.getMainThreadProfileSampleCount()).isGreaterThanOrEqualTo(1);
        assertThat(trace.hasAuxThreadProfile()).isTrue();
        assertThat(trace.hasMainThreadProfile()).isTrue();
        assertThat(trace.hasAuxThreadProfile()).isTrue();
        assertThat(header.getEntryCount()).isZero();
    }

    @Test
    public void shouldNotCaptureAlreadyCompletedFutureGet() throws Exception {
        // given
        // when
        Trace trace = container.execute(CallFutureGetOnAlreadyCompletedFuture.class);
        // then
        Trace.Header header = trace.getHeader();
        assertThat(header.getMainThreadRootTimer().getChildTimerCount()).isZero();
    }

    private static void checkTrace(Trace trace) {
        Trace.Header header = trace.getHeader();
        assertThat(header.getMainThreadRootTimer().getChildTimerCount()).isEqualTo(1);
        assertThat(header.getMainThreadRootTimer().getChildTimer(0).getName())
                .isEqualTo("wait on future");
        assertThat(header.getMainThreadRootTimer().getChildTimer(0).getCount())
                .isGreaterThanOrEqualTo(1);
        assertThat(header.getMainThreadRootTimer().getChildTimer(0).getCount())
                .isLessThanOrEqualTo(3);
        assertThat(header.getAuxThreadRootTimerCount()).isEqualTo(1);
        assertThat(header.getAsyncRootTimerCount()).isZero();
        assertThat(header.getAuxThreadRootTimer(0).getName()).isEqualTo("auxiliary thread");
        assertThat(header.getAuxThreadRootTimer(0).getCount()).isEqualTo(3);
        assertThat(header.getAuxThreadRootTimer(0).getTotalNanos())
                .isGreaterThanOrEqualTo(MILLISECONDS.toNanos(250));
        assertThat(header.getAuxThreadRootTimer(0).getChildTimerCount()).isEqualTo(1);
        assertThat(header.getAuxThreadRootTimer(0).getChildTimer(0).getName())
                .isEqualTo("mock trace marker");
        assertThat(trace.hasMainThreadProfile()).isTrue();
        assertThat(header.getMainThreadProfileSampleCount()).isGreaterThanOrEqualTo(1);
        assertThat(trace.hasAuxThreadProfile()).isTrue();
        assertThat(trace.hasMainThreadProfile()).isTrue();
        assertThat(trace.hasAuxThreadProfile()).isTrue();
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries).hasSize(3);
        checkEntry(entries.get(0));
        checkEntry(entries.get(1));
        checkEntry(entries.get(2));
    }

    private static void checkEntry(Trace.Entry entry) {
        assertThat(entry.getMessage()).isEqualTo("auxiliary thread");
        assertThat(entry.getChildEntryCount()).isEqualTo(1);
        assertThat(entry.getChildEntry(0).getMessage())
                .isEqualTo("trace marker / CreateTraceEntry");
    }

    public static class DoSomeCallableWork implements AppUnderTest, TransactionMarker {

        @Override
        public void executeApp() throws Exception {
            transactionMarker();
        }

        @Override
        public void transactionMarker() throws Exception {
            ExecutorService executor = Executors.newCachedThreadPool();
            FutureTask<Void> futureTask1 = new FutureTask<Void>(new Callable<Void>() {
                @Override
                public Void call() {
                    new CreateTraceEntry().transactionMarker();
                    return null;
                }
            });
            FutureTask<Void> futureTask2 = new FutureTask<Void>(new Callable<Void>() {
                @Override
                public Void call() {
                    new CreateTraceEntry().transactionMarker();
                    return null;
                }
            });
            FutureTask<Void> futureTask3 = new FutureTask<Void>(new Callable<Void>() {
                @Override
                public Void call() {
                    new CreateTraceEntry().transactionMarker();
                    return null;
                }
            });
            executor.execute(futureTask1);
            executor.execute(futureTask2);
            executor.execute(futureTask3);
            futureTask1.get();
            futureTask2.get();
            futureTask3.get();
        }
    }

    public static class DoSomeRunnableAndCallableWork1 implements AppUnderTest, TransactionMarker {

        @Override
        public void executeApp() throws Exception {
            transactionMarker();
        }

        @Override
        public void transactionMarker() throws Exception {
            ExecutorService executor = Executors.newCachedThreadPool();
            FutureTask<Void> futureTask1 = new FutureTask<Void>(new RunnableAndCallableWork());
            FutureTask<Void> futureTask2 = new FutureTask<Void>(new RunnableAndCallableWork());
            FutureTask<Void> futureTask3 = new FutureTask<Void>(new RunnableAndCallableWork());
            executor.execute(futureTask1);
            executor.execute(futureTask2);
            executor.execute(futureTask3);
            futureTask1.get();
            futureTask2.get();
            futureTask3.get();
        }
    }

    public static class DoSomeRunnableAndCallableWork2 implements AppUnderTest, TransactionMarker {

        @Override
        public void executeApp() throws Exception {
            transactionMarker();
        }

        @Override
        public void transactionMarker() throws Exception {
            ExecutorService executor = Executors.newCachedThreadPool();
            FutureTask<Void> futureTask1 =
                    new FutureTask<Void>(new RunnableAndCallableWork(), null);
            FutureTask<Void> futureTask2 =
                    new FutureTask<Void>(new RunnableAndCallableWork(), null);
            FutureTask<Void> futureTask3 =
                    new FutureTask<Void>(new RunnableAndCallableWork(), null);
            executor.execute(futureTask1);
            executor.execute(futureTask2);
            executor.execute(futureTask3);
            futureTask1.get();
            futureTask2.get();
            futureTask3.get();
        }
    }

    public static class DoSomeSimpleRunnableWork implements AppUnderTest, TransactionMarker {

        @Override
        public void executeApp() throws Exception {
            transactionMarker();
        }

        @Override
        public void transactionMarker() throws Exception {
            ExecutorService executor = Executors.newCachedThreadPool();
            FutureTask<Void> futureTask1 = new FutureTask<Void>(new Runnable() {
                @Override
                public void run() {
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException e) {
                    }
                }
            }, null);
            FutureTask<Void> futureTask2 = new FutureTask<Void>(new Runnable() {
                @Override
                public void run() {
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException e) {
                    }
                }
            }, null);
            FutureTask<Void> futureTask3 = new FutureTask<Void>(new Runnable() {
                @Override
                public void run() {
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException e) {
                    }
                }
            }, null);
            executor.execute(futureTask1);
            executor.execute(futureTask2);
            executor.execute(futureTask3);
            futureTask1.get();
            futureTask2.get();
            futureTask3.get();
        }
    }

    public static class CallFutureGetOnAlreadyCompletedFuture
            implements AppUnderTest, TransactionMarker {

        @Override
        public void executeApp() throws Exception {
            transactionMarker();
        }

        @Override
        public void transactionMarker() throws Exception {
            ExecutorService executor = Executors.newCachedThreadPool();
            FutureTask<Void> futureTask = new FutureTask<Void>(new Callable<Void>() {
                @Override
                public Void call() throws Exception {
                    return null;
                }
            });
            executor.execute(futureTask);
            while (!futureTask.isDone()) {
                Thread.sleep(1);
            }
            futureTask.get();
        }
    }

    private static class RunnableAndCallableWork implements Runnable, Callable<Void> {

        private final AtomicBoolean complete = new AtomicBoolean();

        @Override
        public Void call() {
            new CreateTraceEntry().transactionMarker();
            return null;
        }

        @Override
        public void run() {
            new CreateTraceEntry().transactionMarker();
            complete.set(true);
        }
    }

    private static class CreateTraceEntry implements TransactionMarker {

        @Override
        public void transactionMarker() {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
            }
        }
    }
}
