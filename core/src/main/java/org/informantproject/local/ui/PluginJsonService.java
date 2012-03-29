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
package org.informantproject.local.ui;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.ParserConfigurationException;

import org.informantproject.core.configuration.ConfigurationService;
import org.informantproject.core.configuration.PluginDescriptor;
import org.informantproject.core.configuration.PluginDescriptor.PropertyDescriptor;
import org.informantproject.core.util.XmlDocuments;
import org.informantproject.local.ui.HttpServer.JsonService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

import com.google.common.base.Charsets;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.gson.Gson;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.AsyncHttpClient.BoundRequestBuilder;

/**
 * Json service to read installed and installable plugins. Bound to url "/plugin" in HttpServer.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
public class PluginJsonService implements JsonService {

    private static final Logger logger = LoggerFactory.getLogger(PluginJsonService.class);

    private static final String INSTALLABLE_PLUGINS_URL = "oss.sonatype.org/content/repositories"
            + "/snapshots/org/informantproject/plugins/";

    private final ConfigurationService configurationService;
    private final AsyncHttpClient asyncHttpClient;

    // expire the list of installable plugins every hour in order to pick up new installable plugins
    private final Supplier<Collection<PluginDescriptor>> installablePlugins = Suppliers
            .memoizeWithExpiration(new Supplier<Collection<PluginDescriptor>>() {
                public Collection<PluginDescriptor> get() {
                    return readInstallablePlugins();
                }
            }, 3600, TimeUnit.SECONDS);

    @Inject
    public PluginJsonService(ConfigurationService configurationService,
            AsyncHttpClient asyncHttpClient) {

        this.configurationService = configurationService;
        this.asyncHttpClient = asyncHttpClient;
    }

    // called dynamically from HttpServer
    public String getPackagedPlugins() {
        // informant and a set of plugins can be packaged together in a single jar in order to
        // simplify distribution and installation. any plugins packaged with informant cannot be
        // uninstalled
        return new Gson().toJson(configurationService.getPackagedPluginDescriptors());
    }

    // called dynamically from HttpServer
    public String getInstalledPlugins() {
        return new Gson().toJson(configurationService.getInstalledPluginDescriptors());
    }

    // called dynamically from HttpServer
    public String getInstallablePlugins() {
        List<PluginDescriptor> notAlreadyInstalled = Lists.newArrayList(installablePlugins.get());
        // this works because Plugin.equals() is defined only in terms of groupId and artifactId
        Iterables.removeAll(notAlreadyInstalled,
                configurationService.getPackagedPluginDescriptors());
        Iterables.removeAll(notAlreadyInstalled,
                configurationService.getInstalledPluginDescriptors());
        return new Gson().toJson(notAlreadyInstalled);
    }

    private Collection<PluginDescriptor> readInstallablePlugins() {
        // TODO configure AsyncHttpClient to handle HTTPS
        BoundRequestBuilder request = asyncHttpClient.prepareGet("http://"
                + INSTALLABLE_PLUGINS_URL);
        String body;
        try {
            body = request.execute().get().getResponseBody();
        } catch (InterruptedException e) {
            logger.error(e.getMessage(), e);
            return Collections.emptyList();
        } catch (ExecutionException e) {
            logger.error(e.getMessage(), e.getCause());
            return Collections.emptyList();
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
            return Collections.emptyList();
        }
        Pattern pattern = Pattern.compile(INSTALLABLE_PLUGINS_URL + "([^/]+)/");
        Matcher matcher = pattern.matcher(body);
        List<PluginDescriptor> installablePlugins = new ArrayList<PluginDescriptor>();
        while (matcher.find()) {
            String artifactId = matcher.group(1);
            try {
                String metadataUrl = "http://" + INSTALLABLE_PLUGINS_URL + artifactId
                        + "/maven-metadata.xml";
                String metadata = asyncHttpClient.prepareGet(metadataUrl).execute().get()
                        .getResponseBody();
                String version = getVersionFromMetadata(metadata);
                installablePlugins.add(new PluginDescriptor(artifactId,
                        "org.informantproject.plugins",
                        artifactId, version, new ArrayList<PropertyDescriptor>()));
            } catch (IOException e) {
                logger.error(e.getMessage(), e);
            } catch (InterruptedException e) {
                logger.error(e.getMessage(), e);
            } catch (ExecutionException e) {
                logger.error(e.getMessage(), e.getCause());
            } catch (ParserConfigurationException e) {
                logger.error(e.getMessage(), e);
            } catch (SAXException e) {
                logger.error(e.getMessage(), e);
            }
        }
        Iterables.removeAll(installablePlugins,
                configurationService.getInstalledPluginDescriptors());
        return installablePlugins;
    }

    private static String getVersionFromMetadata(String metadata)
            throws ParserConfigurationException, SAXException, IOException {

        byte[] xmlBytes = metadata.getBytes(Charsets.UTF_8.name());
        Element root = XmlDocuments.getDocument(new ByteArrayInputStream(xmlBytes))
                .getDocumentElement();
        return root.getElementsByTagName("version").item(0).getTextContent();
    }
}