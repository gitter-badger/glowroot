/**
 * Copyright 2011 the original author or authors.
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
package org.informantproject.test;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

import java.util.List;

import org.informantproject.test.api.Nestable;
import org.informantproject.testkit.AppUnderTest;
import org.informantproject.testkit.Configuration.CoreConfiguration;
import org.informantproject.testkit.GetTracesResponse.Trace;
import org.informantproject.testkit.InformantContainer;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class MergedStackTreeTest {

    private static InformantContainer container;

    @BeforeClass
    public static void setUp() throws Exception {
        container = InformantContainer.newInstance();
    }

    @AfterClass
    public static void tearDown() throws Exception {
        container.close();
    }

    @Test
    public void shouldReadTraces() throws Exception {
        // given
        CoreConfiguration coreConfiguration = container.getInformant().getCoreConfiguration();
        coreConfiguration.setThresholdMillis(0);
        coreConfiguration.setStackTraceInitialDelayMillis(100);
        coreConfiguration.setStackTracePeriodMillis(10);
        container.getInformant().updateCoreConfiguration(coreConfiguration);
        // when
        container.executeAppUnderTest(ShouldGenerateTraceWithMergedStackTree.class);
        // then
        List<Trace> traces = container.getInformant().getAllTraces();
        assertThat(traces.get(0).getMergedStackTreeRootNodes().size(), is(1));
    }

    public static class ShouldGenerateTraceWithMergedStackTree implements AppUnderTest {
        public void execute() throws InterruptedException {
            int[] sleepTimings = new int[] { 50, 40, 30, 10, 10, 10 };
            new Nestable(new Nestable(sleepTimings), sleepTimings).call();
        }
    }
}