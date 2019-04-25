package org.aion.harness.main.impl;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import org.aion.harness.main.LocalNode;
import org.aion.harness.main.NodeListener;
import org.aion.harness.main.event.Event;
import org.aion.harness.main.event.IEvent;
import org.aion.harness.main.event.OrEvent;
import org.aion.harness.main.global.SingletonFactory;
import org.aion.harness.main.Network;
import org.aion.harness.main.NodeConfigurations;
import org.aion.harness.main.impl.internal.NodeInitializer;
import org.aion.harness.misc.Assumptions;
import org.aion.harness.result.FutureResult;
import org.aion.harness.result.LogEventResult;
import org.aion.harness.result.Result;
import org.aion.harness.sys.LeveldbLockAwaiter;
import org.aion.harness.util.*;
import org.apache.commons.io.FileUtils;

import java.io.*;
import java.util.concurrent.TimeUnit;

/**
 * A node that wraps the Java kernel.
 *
 * A JavaNode is not thread-safe.
 */
public final class JavaNode implements LocalNode {
    private final SimpleLog log;
    private NodeConfigurations configurations = null;
    private LogReader logReader;
    private LogManager logManager;
    private final int ID;

    private NodeInitializer initializer;
    private boolean isInitialized = false;

    // The running instance of the kernel.
    private Process runningKernel = null;

    public JavaNode() {
        this.log = new SimpleLog(getClass().getName());
        this.logReader = new LogReader();
        this.logManager = new LogManager();
        this.ID = SingletonFactory.singleton().nodeWatcher().addReader(this.logReader);
    }

    private final Set<String> STARTUP_ERRORS = Set.of(
        "Shutdown due to failure to initialize repository",
        "Address already in use"
    );

    @Override
    public int getID() {
        return this.ID;
    }

    /**
     * Configures the node with the specified settings.
     *
     * This method must be called prior to any other actionable method in this class.
     *
     * @param configurations the configuration settings.
     */
    @Override
    public void configure(NodeConfigurations configurations) {
        if (isAlive()) {
            throw new IllegalStateException("Cannot set a node's configurations while it is running.");
        }

        if (configurations == null) {
            throw new NullPointerException("Cannot configure node with null configurations.");
        }

        this.configurations = configurations;
        this.initializer = new NodeInitializer(this.configurations);
    }

    @Override
    public Result initializeVerbose() throws IOException, InterruptedException {
        Result result = this.initializer.initializeJavaKernel(true);

        // If initialization was successful then set up the log files.
        if (result.isSuccess()) {
            result = this.logManager.setupLogFiles();
        }

        this.isInitialized = true;
        return result;
    }

    @Override
    public Result initialize() throws IOException, InterruptedException {
        Result result = this.initializer.initializeJavaKernel(false);

        // If initialization was successful then set up the log files.
        if (result.isSuccess()) {
            result = this.logManager.setupLogFiles();
        }

        this.isInitialized = true;
        return result;
    }

    /**
     * Starts a node.
     *
     * @throws IllegalStateException if the node is already started or the kernel does not exist.
     */
    @Override
    public Result start() throws IOException, InterruptedException {
        if (this.configurations == null) {
            throw new IllegalStateException("Node has not been configured yet! Cannot start kernel.");
        }
        if (isAlive()) {
            throw new IllegalStateException("there is already a kernel running.");
        }
        if (!this.isInitialized) {
            throw new IllegalStateException("This node has not been initialized yet!");
        }

        log.log(Assumptions.LOGGER_BANNER + "Starting Java kernel node...");

        ProcessBuilder builder = new ProcessBuilder("./aion.sh", "-n", this.configurations.getNetwork().string())
            .directory(this.configurations.getActualBuildLocation());

        File outputLog = this.logManager.getCurrentOutputLogFile();

        if (outputLog == null) {
            this.logManager.setupLogFiles();
            outputLog = this.logManager.getCurrentOutputLogFile();
        }

        builder.redirectOutput(outputLog);
        builder.redirectError(this.logManager.getCurrentErrorLogFile());

        new LeveldbLockAwaiter(this.configurations.getDatabase().getAbsolutePath()).await();
        this.runningKernel = builder.start();

        return waitForRpcReadyOrError(outputLog);
    }

    /**
     * Stops the node if it is currently running.
     */
    @Override
    public Result stop() throws IOException, InterruptedException {

        Result result;

        if (isAlive()) {
            log.log(Assumptions.LOGGER_BANNER + "Stopping Java kernel node...");

            this.runningKernel.destroy();
            boolean shutdown = this.runningKernel.waitFor(1, TimeUnit.MINUTES);
            this.runningKernel = null;
            this.logReader.stopReading();

            result = (shutdown) ? Result.successful() : Result.unsuccessfulDueTo("Timed out waiting for node to shut down!");

            log.log(Assumptions.LOGGER_BANNER + "Java kernel node stopped.");

        } else {
            result = Result.unsuccessfulDueTo("Node is not currently alive!");
        }

        if (this.isInitialized) {
            // Finds the kernel and kills it (above we are killing the aion.sh script,
            // which is not guaranteed to kill the kernel). We find these processes because we know the
            // directory of the executable, so we can hunt it down precisely.
            String executableDir = NodeFileManager.getExecutableDirectoryOf(this.configurations.getActualBuildLocation());
            ProcessHandle.allProcesses()
                .filter(process -> process.info().command().toString().contains(executableDir))
                .forEach(kernel -> kernel.destroy());
        }

        return result;
    }

    /**
     * Returns true if the node is currently running.
     */
    @Override
    public boolean isAlive() {
        return ((this.runningKernel != null) && (this.runningKernel.isAlive()));
    }

    /**
     * Resets the state of the node.
     *
     * @throws IllegalStateException if the node is currently running or there is no kernel.
     */
    @Override
    public Result resetState() throws IOException {
        if (this.configurations == null) {
            throw new IllegalStateException("Node has not been configured yet! Cannot reset kernel state.");
        }
        if (isAlive()){
            throw new IllegalStateException("Cannot reset state while the node is running.");
        }
        if (!this.isInitialized) {
            throw new IllegalStateException("Node has not been initialized yet!");
        }

        log.log(Assumptions.LOGGER_BANNER + "Resetting the state of the Java kernel node...");

        File database = this.configurations.getDatabase();
        if (database.exists()) {
            FileUtils.deleteDirectory(database);
        }
        return Result.successful();
    }

    /**
     * Returns the network that this node will attempt to connect to when {@code start()} is invoked,
     * or, if the node is running, then the network it has connected to.
     *
     * Returns null if no network has been configured yet.
     *
     * @return the network the node is on.
     */
    @Override
    public Network getNetwork() {
        return (this.configurations == null) ? null : this.configurations.getNetwork();
    }

    /**
     * Block until logs indicate that either RPC server started or an error happened
     */
    private Result waitForRpcReadyOrError(File outputLog) throws InterruptedException {
        // We wait for the rpc event to know we are ok to return. There is a chance that we will miss
        // this event and start listening too late. That is why we timeout after 20 seconds, which
        // should be more than sufficient for the server to activate, and then we check if the node
        // is still live.
        // See issue #1 relating to this decision, which will be refactored in the future.

        if (isAlive()) {
            // We wait for the Rpc event or else 20 seconds, in case we come too late and never see it.
            IEvent rpcEvent = new Event("rpc-server - (UNDERTOW) started");

            Result result = this.logReader.startReading(outputLog);
            if (!result.isSuccess()) {
                return result;
            }

            try {
                NodeListener.listenTo(this)
                    .listenForEvent(rpcEvent, 20, TimeUnit.SECONDS)
                    .get(20, TimeUnit.SECONDS);
            } catch (TimeoutException te) {
                log.log("RPC Server did not start.");
                Optional<String> maybeError = findError(outputLog);
                if(maybeError.isPresent()) {
                    return Result.unsuccessfulDueTo(maybeError.get());
                } else {
                    return Result.unsuccessfulDueTo(
                        "Did not see RPC started message in logs, but also could not find any error message.");
                }
            }

            return Result.successful();
        } else {
            return Result.unsuccessfulDueTo("Node failed to start!");
        }
    }

    private Optional<String> findError(File file) {
        try {
            if(FileUtils.sizeOf(file) > FileUtils.ONE_MB) {
                log.log("Will not try to find error because error file size too large");
                return Optional.empty();
            } else {
                return FileUtils.readLines(file, "UTF-8").stream().filter(
                    line -> STARTUP_ERRORS.stream().anyMatch(err -> line.contains(err))
                ).findFirst();
            }
        } catch (IOException ioe) {
            log.log("Will not try to find error because error file could not be opened");
            return Optional.empty();
        }
    }
}
