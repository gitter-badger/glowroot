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

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class JsonCharSequence implements CharSequence {

    private final CharSequence csq;
    private final int escapedSize;
    private StringBuilder escaped;

    private JsonCharSequence(CharSequence csq, int escapedSize) {
        this.csq = csq;
        this.escapedSize = escapedSize;
    }

    public int length() {
        return escapedSize;
    }

    public char charAt(int index) {
        if (escaped == null) {
            escaped = escaped(csq);
        }
        return escaped.charAt(index);
    }

    public CharSequence subSequence(int start, int end) {
        if (escaped == null) {
            escaped = escaped(csq);
        }
        return escaped.subSequence(start, end);
    }

    public void getChars(int srcBegin, int srcEnd, char dst[], int dstBegin) {
        if (escaped == null) {
            escaped = escaped(csq);
        }
        escaped.getChars(srcBegin, srcEnd, dst, dstBegin);
    }

    public static CharSequence escapeJson(CharSequence csq) {
        if (csq instanceof LargeCharSequence) {
            return ((LargeCharSequence) csq).escapeJson();
        }
        int escapedSize = escapedSize(csq);
        if (escapedSize == csq.length()) {
            return csq;
        } else {
            return new JsonCharSequence(csq, escapedSize);
        }
    }

    private static int escapedSize(CharSequence csq) {
        int len = 0;
        for (int i = 0; i < csq.length(); i++) {
            char c = csq.charAt(i);
            // see list of control characters that need escaping at http://json.org
            if (c == '"' || c == '\\' || c == '\b' || c == '\f' || c == '\n' || c == '\r'
                    || c == '\t') {
                len += 2;
            } else {
                len++;
            }
        }
        return len;
    }

    private static StringBuilder escaped(CharSequence csq) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < csq.length(); i++) {
            char c = csq.charAt(i);
            // see list of control characters that need escaping at http://json.org
            if (c == '"') {
                sb.append('\\');
                sb.append('"');
            } else if (c == '\\') {
                sb.append('\\');
                sb.append('\\');
            } else if (c == '\b') {
                sb.append('\\');
                sb.append('b');
            } else if (c == '\f') {
                sb.append('\\');
                sb.append('f');
            } else if (c == '\n') {
                sb.append('\\');
                sb.append('n');
            } else if (c == '\r') {
                sb.append('\\');
                sb.append('r');
            } else if (c == '\t') {
                sb.append('\\');
                sb.append('t');
            } else {
                sb.append(c);
            }
        }
        return sb;
    }
}