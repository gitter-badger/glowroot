/*
 * Copyright 2011-2016 the original author or authors.
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
package org.glowroot.agent.it.harness.impl;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.base.StandardSystemProperty;
import com.google.common.base.Stopwatch;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.grpc.ManagedChannel;
import io.grpc.netty.NegotiationType;
import io.grpc.netty.NettyChannelBuilder;
import io.netty.channel.EventLoopGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.AgentPremain;
import org.glowroot.agent.it.harness.AppUnderTest;
import org.glowroot.agent.it.harness.ConfigService;
import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.TempDirs;
import org.glowroot.agent.it.harness.grpc.JavaagentServiceGrpc;
import org.glowroot.agent.it.harness.grpc.JavaagentServiceGrpc.JavaagentServiceBlockingClient;
import org.glowroot.agent.it.harness.grpc.JavaagentServiceOuterClass.AppUnderTestClassName;
import org.glowroot.agent.it.harness.grpc.JavaagentServiceOuterClass.Void;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.SECONDS;

public class JavaagentContainer implements Container {

    private static final boolean XDEBUG = Boolean.getBoolean("glowroot.test.xdbug");

    private static final Logger logger = LoggerFactory.getLogger(JavaagentContainer.class);

    private final File baseDir;
    private final boolean deleteBaseDirOnClose;

    private final ServerSocket heartbeatListenerSocket;
    private final ExecutorService heartbeatListenerExecutor;
    private final @Nullable GrpcServerWrapper server;
    private final EventLoopGroup eventLoopGroup;
    private final ExecutorService executor;
    private final ManagedChannel channel;
    private final @Nullable TraceCollector traceCollector;
    private final JavaagentServiceBlockingClient javaagentService;
    private final ExecutorService consolePipeExecutor;
    private final Process process;
    private final ConsoleOutputPipe consoleOutputPipe;
    private final @Nullable ConfigService configService;
    private final Thread shutdownHook;

    public static JavaagentContainer create() throws Exception {
        return new JavaagentContainer(null, false, false, ImmutableList.<String>of());
    }

    public static JavaagentContainer create(File baseDir) throws Exception {
        return new JavaagentContainer(baseDir, false, false, ImmutableList.<String>of());
    }

    public static JavaagentContainer createWithExtraJvmArgs(List<String> extraJvmArgs)
            throws Exception {
        return new JavaagentContainer(null, false, false, extraJvmArgs);
    }

    public JavaagentContainer(@Nullable File baseDir, boolean fat, boolean captureConsoleOutput,
            List<String> extraJvmArgs) throws Exception {
        if (baseDir == null) {
            this.baseDir = TempDirs.createTempDir("glowroot-test-basedir");
            deleteBaseDirOnClose = true;
        } else {
            this.baseDir = baseDir;
            deleteBaseDirOnClose = false;
        }

        // need to start heartbeat socket listener before spawning process
        heartbeatListenerSocket = new ServerSocket(0);
        heartbeatListenerExecutor = Executors.newSingleThreadExecutor();
        heartbeatListenerExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    Socket socket = heartbeatListenerSocket.accept();
                    ObjectInputStream objectIn = new ObjectInputStream(socket.getInputStream());
                    while (true) {
                        objectIn.readObject();
                    }
                } catch (Exception e) {
                }
            }
        });
        int collectorPort;
        if (fat) {
            collectorPort = 0;
            traceCollector = null;
            server = null;
        } else {
            collectorPort = LocalContainer.getAvailablePort();
            traceCollector = new TraceCollector();
            server = new GrpcServerWrapper(traceCollector, collectorPort);
        }
        int javaagentServicePort = LocalContainer.getAvailablePort();
        List<String> command = buildCommand(heartbeatListenerSocket.getLocalPort(), collectorPort,
                javaagentServicePort, this.baseDir, extraJvmArgs);
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);
        final Process process = processBuilder.start();
        consolePipeExecutor = Executors.newSingleThreadExecutor();
        InputStream in = process.getInputStream();
        // process.getInputStream() only returns null if ProcessBuilder.redirectOutput() is used
        // to redirect output to a file
        checkNotNull(in);
        consoleOutputPipe = new ConsoleOutputPipe(in, System.out, captureConsoleOutput);
        consolePipeExecutor.submit(consoleOutputPipe);
        this.process = process;

        eventLoopGroup = EventLoopGroups.create("Glowroot-grpc-worker-ELG");
        executor = Executors.newCachedThreadPool(
                new ThreadFactoryBuilder()
                        .setDaemon(true)
                        .setNameFormat("Glowroot-grpc-executor-%d")
                        .build());
        channel = NettyChannelBuilder.forAddress("localhost", javaagentServicePort)
                .eventLoopGroup(eventLoopGroup)
                .executor(executor)
                .negotiationType(NegotiationType.PLAINTEXT)
                .build();

        Stopwatch stopwatch = Stopwatch.createStarted();
        // this can take a while on slow travis-ci build machines
        while (stopwatch.elapsed(SECONDS) < 30) {
            try {
                JavaagentServiceBlockingClient javaagentService =
                        JavaagentServiceGrpc.newBlockingStub(channel);
                javaagentService.ping(Void.getDefaultInstance());
                break;
            } catch (Exception e) {
                logger.debug(e.getMessage(), e);
            }
            Thread.sleep(100);
        }
        javaagentService = JavaagentServiceGrpc.newBlockingStub(channel);
        javaagentService.ping(Void.getDefaultInstance());
        if (server == null) {
            configService = null;
        } else {
            configService = new ConfigServiceImpl(server, true);
        }
        // this is used to set slowThresholdMillis=0
        javaagentService.resetAllConfig(Void.getDefaultInstance());
        shutdownHook = new ShutdownHookThread(javaagentService);
        // unfortunately, ctrl-c during maven test will kill the maven process, but won't kill the
        // forked surefire jvm where the tests are being run
        // (http://jira.codehaus.org/browse/SUREFIRE-413), and so this hook won't get triggered by
        // ctrl-c while running tests under maven
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    @Override
    public ConfigService getConfigService() {
        checkNotNull(configService);
        return configService;
    }

    @Override
    public void addExpectedLogMessage(String loggerName, String partialMessage) throws Exception {
        checkNotNull(traceCollector);
        traceCollector.addExpectedLogMessage(loggerName, partialMessage);
    }

    @Override
    public Trace execute(Class<? extends AppUnderTest> appClass) throws Exception {
        checkNotNull(traceCollector);
        executeInternal(appClass);
        Trace trace = traceCollector.getCompletedTrace(10, SECONDS);
        traceCollector.clearTrace();
        return trace;
    }

    @Override
    public void executeNoExpectedTrace(Class<? extends AppUnderTest> appClass) throws Exception {
        executeInternal(appClass);
        // give a short time to see if trace gets collected
        Thread.sleep(10);
        if (traceCollector != null && traceCollector.hasTrace()) {
            throw new IllegalStateException("Trace was collected when none was expected");
        }
    }

    @Override
    public void interruptAppUnderTest() throws Exception {
        javaagentService.interruptApp(Void.getDefaultInstance());
    }

    @Override
    public Trace getCollectedPartialTrace() throws InterruptedException {
        checkNotNull(traceCollector);
        return traceCollector.getPartialTrace(10, SECONDS);
    }

    @Override
    public void checkAndReset() throws Exception {
        javaagentService.resetAllConfig(Void.getDefaultInstance());
        if (traceCollector != null) {
            traceCollector.checkAndResetLogMessages();
        }
    }

    @Override
    public void close() throws Exception {
        javaagentService.shutdown(Void.getDefaultInstance());
        javaagentService.kill(Void.getDefaultInstance());
        channel.shutdown();
        if (!channel.awaitTermination(10, SECONDS)) {
            throw new IllegalStateException("Could not terminate gRPC channel");
        }
        executor.shutdown();
        if (!executor.awaitTermination(10, SECONDS)) {
            throw new IllegalStateException("Could not terminate gRPC executor");
        }
        if (!eventLoopGroup.shutdownGracefully(0, 0, SECONDS).await(10, SECONDS)) {
            throw new IllegalStateException("Could not terminate gRPC event loop group");
        }
        if (server != null) {
            server.close();
        }
        cleanup();
    }

    public List<String> getUnexpectedConsoleLines() {
        List<String> unexpectedLines = Lists.newArrayList();
        Splitter splitter = Splitter.on(Pattern.compile("\r?\n")).omitEmptyStrings();
        String capturedOutput = consoleOutputPipe.getCapturedOutput();
        if (capturedOutput == null) {
            throw new IllegalStateException("Cannot check console lines unless JavaagentContainer"
                    + " was created with captureConsoleOutput=true");
        }
        for (String line : splitter.split(capturedOutput)) {
            if (line.contains("Glowroot started") || line.contains("Glowroot listening")
                    || line.contains("Glowroot plugins loaded")) {
                continue;
            }
            if (line.matches("objc\\[\\d+\\]: Class JavaLaunchHelper is implemented in both .*")) {
                // OSX jvm loves to emit this
                continue;
            }
            unexpectedLines.add(line);
        }
        return unexpectedLines;
    }

    private void cleanup() throws Exception {
        process.waitFor();
        consolePipeExecutor.shutdownNow();
        if (!consolePipeExecutor.awaitTermination(10, SECONDS)) {
            throw new IllegalStateException("Could not terminate executor");
        }
        heartbeatListenerExecutor.shutdownNow();
        if (!heartbeatListenerExecutor.awaitTermination(10, SECONDS)) {
            throw new IllegalStateException("Could not terminate executor");
        }
        heartbeatListenerSocket.close();
        Runtime.getRuntime().removeShutdownHook(shutdownHook);
        if (deleteBaseDirOnClose) {
            TempDirs.deleteRecursively(baseDir);
        }
    }

    private void executeInternal(Class<? extends AppUnderTest> appUnderTestClass) throws Exception {
        javaagentService.executeApp(AppUnderTestClassName.newBuilder()
                .setValue(appUnderTestClass.getName())
                .build());
    }

    private static List<String> buildCommand(int heartbeatPort, int collectorPort,
            int javaagentServicePort, File baseDir, List<String> extraJvmArgs) throws Exception {
        List<String> command = Lists.newArrayList();
        String javaExecutable = StandardSystemProperty.JAVA_HOME.value() + File.separator + "bin"
                + File.separator + "java";
        command.add(javaExecutable);
        command.addAll(extraJvmArgs);
        // it is important for jacoco javaagent to be prior to glowroot javaagent so that jacoco
        // will use original class bytes to form its class id at runtime which will then match up
        // with the class id at analysis time
        command.addAll(getJacocoArgsFromCurrentJvm());
        String classpath = Strings.nullToEmpty(StandardSystemProperty.JAVA_CLASS_PATH.value());
        List<String> bootPaths = Lists.newArrayList();
        List<String> paths = Lists.newArrayList();
        List<String> maybeShadedInsideAgentJars = Lists.newArrayList();
        File javaagentJarFile = null;
        for (String path : Splitter.on(File.pathSeparatorChar).split(classpath)) {
            File file = new File(path);
            String name = file.getName();
            if (name.matches("glowroot-agent-[0-9.]+(-SNAPSHOT)?.jar")) {
                javaagentJarFile = file;
            } else if (name.matches("glowroot-common-[0-9.]+(-SNAPSHOT)?.jar")
                    || name.matches("glowroot-wire-api-[0-9.]+(-SNAPSHOT)?.jar")
                    || name.matches("glowroot-agent-api-[0-9.]+(-SNAPSHOT)?.jar")
                    || name.matches("glowroot-agent-plugin-api-[0-9.]+(-SNAPSHOT)?.jar")) {
                // these artifacts should not be present since glowroot-agent shades them
                // but maven 3.3.1/3.3.3 are not using the dependency reduced pom during downstream
                // module builds, which causes the glowroot artifacts to be included
                // when running "mvn clean install" from the project root, see MSHADE-206
                maybeShadedInsideAgentJars.add(path);
            } else if (name.matches("glowroot-agent-it-harness-[0-9.]+(-SNAPSHOT)?\\.jar")) {
                paths.add(path);
            } else if (file.getAbsolutePath().contains(File.separator + "it-harness"
                    + File.separator + "target" + File.separator + "classes")) {
                paths.add(path);
            } else if (file.isDirectory() && name.equals("test-classes")) {
                paths.add(path);
            } else if (name.matches("tomcat-.*\\.jar") || name.matches("jsf-.*\\.jar")
                    || name.matches("hibernate-.*\\.jar")) {
                // ideally all test dependencies would be in system classpath, but not sure how to
                // differentiate here
                // so just hard-coding test dependencies as necessary
                paths.add(path);
            } else {
                bootPaths.add(path);
            }
        }
        if (!maybeShadedInsideAgentJars.isEmpty() && javaagentJarFile != null) {
            JarInputStream jarIn = new JarInputStream(new FileInputStream(javaagentJarFile));
            JarEntry jarEntry;
            boolean shaded = false;
            while ((jarEntry = jarIn.getNextJarEntry()) != null) {
                if (jarEntry.getName().startsWith("org/glowroot/agent/shaded/")) {
                    shaded = true;
                    break;
                }
            }
            jarIn.close();
            if (!shaded) {
                bootPaths.addAll(maybeShadedInsideAgentJars);
            }
        }
        command.add("-Xbootclasspath/a:" + Joiner.on(File.pathSeparatorChar).join(bootPaths));
        command.add("-classpath");
        command.add(Joiner.on(File.pathSeparatorChar).join(paths));
        if (javaagentJarFile == null) {
            // create jar file in data dir since that gets cleaned up at end of test already
            javaagentJarFile = DelegatingJavaagent.createDelegatingJavaagentJarFile(baseDir);
            command.add("-javaagent:" + javaagentJarFile);
            command.add("-DdelegateJavaagent=" + AgentPremain.class.getName());
        } else {
            command.add("-javaagent:" + javaagentJarFile);
        }
        command.add("-Dglowroot.base.dir=" + baseDir.getAbsolutePath());
        if (collectorPort != 0) {
            command.add("-Dglowroot.collector.host=localhost");
            command.add("-Dglowroot.collector.port=" + collectorPort);
        }
        // this is used inside low-entropy docker containers
        String sourceOfRandomness = System.getProperty("java.security.egd");
        if (sourceOfRandomness != null) {
            command.add("-Djava.security.egd=" + sourceOfRandomness);
        }
        command.add("-Xmx" + Runtime.getRuntime().maxMemory());
        for (Entry<Object, Object> entry : System.getProperties().entrySet()) {
            Object keyObject = entry.getKey();
            if (!(keyObject instanceof String)) {
                continue;
            }
            String key = (String) keyObject;
            if (key.startsWith("glowroot.internal.") || key.startsWith("glowroot.test.")) {
                command.add("-D" + key + "=" + entry.getValue());
            }
        }
        if (XDEBUG) {
            command.add("-Xdebug");
            command.add("-agentlib:jdwp=transport=dt_socket,address=8000,server=y,suspend=y");
        }
        command.add(JavaagentMain.class.getName());
        command.add(Integer.toString(heartbeatPort));
        command.add(Integer.toString(javaagentServicePort));
        return command;
    }

    private static List<String> getJacocoArgsFromCurrentJvm() {
        RuntimeMXBean runtimeMXBean = ManagementFactory.getRuntimeMXBean();
        List<String> arguments = runtimeMXBean.getInputArguments();
        List<String> jacocoArgs = Lists.newArrayList();
        for (String argument : arguments) {
            if (argument.startsWith("-javaagent:") && argument.contains("jacoco")) {
                jacocoArgs.add(argument + ",inclbootstrapclasses=true,includes=org.glowroot.*");
                break;
            }
        }
        return jacocoArgs;
    }

    private static class ShutdownHookThread extends Thread {

        private final JavaagentServiceBlockingClient javaagentService;

        private ShutdownHookThread(JavaagentServiceBlockingClient javaagentService) {
            this.javaagentService = javaagentService;
        }

        @Override
        public void run() {
            try {
                javaagentService.kill(Void.getDefaultInstance());
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
            }
        }
    }

    private static class ConsoleOutputPipe implements Runnable {

        private final InputStream in;
        private final OutputStream out;
        // the one place ever that StringBuffer's synchronization is useful :-)
        private final @Nullable StringBuffer capturedOutput;

        private ConsoleOutputPipe(InputStream in, OutputStream out, boolean captureOutput) {
            this.in = in;
            this.out = out;
            if (captureOutput) {
                capturedOutput = new StringBuffer();
            } else {
                capturedOutput = null;
            }
        }

        @Override
        public void run() {
            byte[] buffer = new byte[100];
            try {
                while (true) {
                    int n = in.read(buffer);
                    if (n == -1) {
                        break;
                    }
                    if (capturedOutput != null) {
                        // intentionally using platform default charset
                        capturedOutput.append(new String(buffer, 0, n));
                    }
                    out.write(buffer, 0, n);
                }
            } catch (IOException e) {
                logger.error(e.getMessage(), e);
            }
        }

        private @Nullable String getCapturedOutput() {
            return capturedOutput == null ? null : capturedOutput.toString();
        }
    }
}
