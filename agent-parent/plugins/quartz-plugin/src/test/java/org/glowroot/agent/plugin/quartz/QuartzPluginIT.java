/**
 * Copyright 2015 the original author or authors.
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
package org.glowroot.agent.plugin.quartz;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.Scheduler;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.quartz.impl.StdSchedulerFactory;

import org.glowroot.agent.it.harness.AppUnderTest;
import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.Containers;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static org.assertj.core.api.Assertions.assertThat;

public class QuartzPluginIT {

    private static Container container;

    @BeforeClass
    public static void setUp() throws Exception {
        container = Containers.create();
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
    public void shouldCaptureJobExecution() throws Exception {
        Trace trace = container.execute(ExecuteJob.class);
        Trace.Header header = trace.getHeader();
        assertThat(header.getTransactionType()).isEqualTo("Background");
        assertThat(header.getTransactionName()).isEqualTo("Quartz job: ajob");
        assertThat(header.getHeadline()).isEqualTo("Quartz job: ajob");
    }

    public static class ExecuteJob implements AppUnderTest {
        @Override
        public void executeApp() throws Exception {
            Scheduler scheduler = StdSchedulerFactory.getDefaultScheduler();
            scheduler.start();
            JobDetail job = JobBuilder.newJob(TestJob.class).withIdentity("ajob", "agroup").build();
            Trigger trigger = TriggerBuilder.newTrigger().withIdentity("atrigger", "agroup")
                    .startNow().build();
            scheduler.scheduleJob(job, trigger);
            Thread.sleep(1000);
            scheduler.shutdown();
        }
    }
}
