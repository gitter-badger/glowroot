/*
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
package org.glowroot.agent.plugin.httpclient;

import java.lang.reflect.Method;
import java.net.URI;

import javax.annotation.Nullable;

import org.glowroot.agent.plugin.api.Agent;
import org.glowroot.agent.plugin.api.Logger;

public class RequestInvoker {

    private static final Logger logger = Agent.getLogger(RequestInvoker.class);

    private final @Nullable Method getMethodMethod;
    private final @Nullable Method getUrlMethod;
    private final @Nullable Method getURIMethod;

    public RequestInvoker(Class<?> clazz) {
        Class<?> requestClass = getRequestClass(clazz);
        getMethodMethod = Invokers.getMethod(requestClass, "getMethod");
        getUrlMethod = Invokers.getMethod(requestClass, "getUrl");
        // in async-http-client versions from 1.7.12 up until just prior to 1.9.0, getUrl() stripped
        // trailing "/"
        // in these versions only there was method getURI that returned the non-stripped URI
        Method getURIMethod = null;
        if (requestClass != null) {
            try {
                getURIMethod = requestClass.getMethod("getURI");
            } catch (Exception e) {
                // log exception at debug level
                logger.debug(e.getMessage(), e);
            }
        }
        this.getURIMethod = getURIMethod;
    }

    String getMethod(Object request) {
        return Invokers.invoke(getMethodMethod, request, "");
    }

    // TODO report checker framework issue that occurs without this warning suppression
    @SuppressWarnings("assignment.type.incompatible")
    String getUrl(Object request) {
        if (getURIMethod == null) {
            return Invokers.invoke(getUrlMethod, request, "");
        }
        URI uri = Invokers.invoke(getURIMethod, request, null);
        return uri == null ? "" : uri.toString();
    }

    private static @Nullable Class<?> getRequestClass(Class<?> clazz) {
        try {
            return Class.forName("com.ning.http.client.Request", false, clazz.getClassLoader());
        } catch (ClassNotFoundException e) {
            logger.warn(e.getMessage(), e);
        }
        return null;
    }
}
