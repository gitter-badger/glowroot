/**
 * Copyright 2012 the original author or authors.
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
package io.informant.test;

import static org.fest.assertions.api.Assertions.assertThat;
import io.informant.testkit.AppUnderTest;
import io.informant.testkit.InformantContainer;
import io.informant.testkit.Trace;

import java.util.Iterator;
import java.util.Map.Entry;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class TraceAttributesTest {

    private static InformantContainer container;

    @BeforeClass
    public static void setUp() throws Exception {
        container = InformantContainer.create();
    }

    @AfterClass
    public static void tearDown() throws Exception {
        container.close();
    }

    @After
    public void afterEachTest() throws Exception {
        container.getInformant().cleanUpAfterEachTest();
    }

    @Test
    public void shouldReadTraceAttributesInOrder() throws Exception {
        // given
        container.getInformant().setPersistenceThresholdMillis(0);
        // when
        container.executeAppUnderTest(ShouldGenerateTraceWithNestedSpans.class);
        // then
        Trace trace = container.getInformant().getLastTrace();
        Iterator<Entry<String, String>> i = trace.getAttributes().entrySet().iterator();
        Entry<String, String> entry = i.next();
        assertThat(entry.getKey()).isEqualTo("ax");
        assertThat(entry.getValue()).isEqualTo("bx");
        entry = i.next();
        assertThat(entry.getKey()).isEqualTo("z");
        assertThat(entry.getValue()).isEqualTo("zz");
        entry = i.next();
        assertThat(entry.getKey()).isEqualTo("y");
        assertThat(entry.getValue()).isEqualTo("yy");
        entry = i.next();
        assertThat(entry.getKey()).isEqualTo("x");
        assertThat(entry.getValue()).isEqualTo("xx");
        entry = i.next();
        assertThat(entry.getKey()).isEqualTo("w");
        assertThat(entry.getValue()).isEqualTo("ww");
    }

    public static class ShouldGenerateTraceWithNestedSpans implements AppUnderTest {
        public void executeApp() throws Exception {
            new LevelOne().call("ax", "bx");
        }
    }
}