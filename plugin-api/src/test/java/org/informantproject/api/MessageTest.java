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
package org.informantproject.api;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

import org.junit.Test;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class MessageTest {

    @Test
    public void shouldFormatConstant() {
        Message message = Message.of("constant");
        assertThat(message.getText(), is("constant"));
    }

    @Test
    public void shouldFormatSingle() {
        Message message = Message.of("{{one}}", "test");
        assertThat(message.getText(), is("test"));
    }

    @Test
    public void shouldFormatSinglePlus() {
        Message message = Message.of("one {{one}} two", "test");
        assertThat(message.getText(), is("one test two"));
    }

    @Test
    public void shouldFormatMultiple() {
        Message message = Message.of("one {{one}} two {{two}}{{three}}", "test", "2", "3");
        assertThat(message.getText(), is("one test two 23"));
    }
}