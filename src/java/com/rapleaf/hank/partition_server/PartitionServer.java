/**
 *  Copyright 2011 Rapleaf
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.rapleaf.hank.partition_server;

import com.rapleaf.hank.config.PartitionServerConfigurator;
import com.rapleaf.hank.config.yaml.YamlPartitionServerConfigurator;
import com.rapleaf.hank.coordinator.*;
import com.rapleaf.hank.util.CommandLineChecker;
import com.rapleaf.hank.util.HostUtils;
import org.apache.log4j.Logger;
import org.apache.log4j.PropertyConfigurator;
import org.apache.thrift.protocol.TCompactProtocol;
import org.apache.thrift.server.THsHaServer;
import org.apache.thrift.server.THsHaServer.Args;
import org.apache.thrift.server.TServer;
import org.apache.thrift.transport.TNonblockingServerSocket;
import org.apache.thrift.transport.TTransportException;

import java.io.IOException;

/**
 * The main class of the PartitionServer.
 */
public class PartitionServer implements HostCommandQueueChangeListener {

  private static final Logger LOG = Logger.getLogger(PartitionServer.class);
  private static final long MAIN_THREAD_STEP_SLEEP_MS = 1000;

  private final PartitionServerConfigurator configurator;
  private Thread dataServerThread;
  private TServer dataServer;
  private boolean stopping = false;
  private final PartitionServerAddress hostAddress;
  private final Host host;

  private Thread updateThread;

  private final RingGroup ringGroup;

  private final Ring ring;

  public PartitionServer(PartitionServerConfigurator configurator, String hostName) throws IOException {
    this.configurator = configurator;
    Coordinator coordinator = configurator.getCoordinator();
    hostAddress = new PartitionServerAddress(hostName, configurator.getServicePort());
    ringGroup = coordinator.getRingGroup(configurator.getRingGroupName());
    ring = ringGroup.getRingForHost(hostAddress);
    if (ring == null) {
      throw new RuntimeException("Could not get ring configuration for host: " + hostAddress);
    }
    host = ring.getHostByAddress(hostAddress);
    if (host == null) {
      throw new RuntimeException("Could not get host configuration for host: " + hostAddress);
    }
    host.setCommandQueueChangeListener(this);
  }

  public void run() throws IOException {
    setState(HostState.IDLE); // In case of exception, server will stop and state will be coherent.

    processCurrentCommand();
    while (!stopping) {
      try {
        Thread.sleep(MAIN_THREAD_STEP_SLEEP_MS);
      } catch (InterruptedException e) {
        LOG.debug("Interrupted in run loop. Exiting.", e);
        break;
      }
    }
    LOG.info("Partition server main thread is stopping.");
    // Shuting down
    stopServingData();
    if (updateThread != null) {
      // TODO: deal with the execute update thread
    }
    setState(HostState.OFFLINE); // In case of exception, server will stop and state will be coherent.
  }

  // Stop the partition server. Can be called from another thread.
  public synchronized void stop() {
    stopNotSynchronized();
  }

  // Stop the partition server
  private void stopNotSynchronized() {
    stopping = true;
  }

  protected IfaceWithShutdown getHandler() throws IOException {
    return new PartitionServerHandler(hostAddress, configurator);
  }

  protected IUpdateManager getUpdateManager() throws IOException {
    return new UpdateManager(configurator, host, ringGroup, ring);
  }

  @Override
  public void onCommandQueueChange(Host host) {
    processCurrentCommand();
  }

  private synchronized void processCurrentCommand() {
    try {
      HostCommand command = host.getCurrentCommand();
      // If there is no current command, move on to the next one.
      if (command == null) {
        command = host.nextCommand();
      }
      // If there is a command available, process it.
      if (command != null) {
        processCommand(command, host.getState());
      }
    } catch (IOException e) {
      LOG.error("Failed to process current command.", e);
      stopNotSynchronized();
    }
  }

  private synchronized void setState(HostState state) throws IOException {
    // In case of failure to set host state, stop the partition server and rethrow the exception.
    try {
      host.setState(state);
    } catch (IOException e) {
      stopNotSynchronized();
      throw e;
    }
  }

  private synchronized HostCommand nextCommand() throws IOException {
    // In case of failure to move on to next command, stop the partition server and rethrow the exception.
    try {
      return host.nextCommand();
    } catch (IOException e) {
      stopNotSynchronized();
      throw e;
    }
  }

  private void processCommand(HostCommand command, HostState state) throws IOException {
    switch (command) {
      case EXECUTE_UPDATE:
        processExecuteUpdate(state);
        break;
      case GO_TO_IDLE:
        processGoToIdle(state);
        break;
      case SERVE_DATA:
        processServeData(state);
        break;
    }
  }

  private void processServeData(HostState state) throws IOException {
    switch (state) {
      case IDLE:
        serveData();
        setState(HostState.SERVING);  // In case of exception, server will stop and state will be coherent.
        nextCommand(); // In case of exception, server will stop and state will be coherent.
        break;
      default:
        LOG.debug(ignoreIncompatibleCommandMessage(HostCommand.SERVE_DATA, state));
        nextCommand(); // In case of exception, server will stop and state will be coherent.
    }
  }

  private void processGoToIdle(HostState state) throws IOException {
    switch (state) {
      case SERVING:
        stopServingData();
        setState(HostState.IDLE); // In case of exception, server will stop and state will be coherent.
        nextCommand(); // In case of exception, server will stop and state will be coherent.
        break;
      default:
        LOG.debug(ignoreIncompatibleCommandMessage(HostCommand.GO_TO_IDLE, state));
        nextCommand(); // In case of exception, server will stop and state will be coherent.
    }
  }

  private void processExecuteUpdate(HostState state) throws IOException {
    switch (state) {
      case IDLE:
        setState(HostState.UPDATING); // In case of exception, server will stop and state will be coherent.
        executeUpdate();
        // Next command is set by the updater thread
        break;
      default:
        LOG.debug(ignoreIncompatibleCommandMessage(HostCommand.EXECUTE_UPDATE, state));
        nextCommand(); // In case of exception, server will stop and state will be coherent.
    }
  }

  private void executeUpdate() {
    if (updateThread != null) {
      LOG.error("Update got called while one is already running!");
      return;
    }
    Runnable updateRunnable = new Runnable() {
      @Override
      public void run() {
        try {
          IUpdateManager updateManager = getUpdateManager();
          updateManager.update();
          LOG.info("Update succeeded.");
        } catch (Throwable e) {
          LOG.fatal("Update failed. Updater encountered a fatal error:", e);
        }
        // Go back to IDLE even in case of failure
        try {
          setState(HostState.IDLE); // In case of exception, server will stop and state will be coherent.
        } catch (IOException e) {
          LOG.fatal("Failed to record state change.", e);
        }
        // Move on to next command
        try {
          nextCommand(); // In case of exception, server will stop and state will be coherent.
        } catch (IOException e) {
          LOG.fatal("Failed to move on to next command.", e);
        }
        updateThread = null;
      }
    };
    updateThread = new Thread(updateRunnable, "Update manager thread");
    updateThread.start();
  }

  /**
   * Start serving the thrift server. doesn't return.
   *
   * @throws TTransportException
   * @throws IOException
   * @throws InterruptedException
   */
  private void startThriftServer() throws TTransportException, IOException, InterruptedException {
    // set up the service handler
    IfaceWithShutdown handler = getHandler();

    // launch the thrift server
    TNonblockingServerSocket serverSocket = new TNonblockingServerSocket(configurator.getServicePort());
    Args options = new Args(serverSocket);
    options.processor(new com.rapleaf.hank.generated.PartitionServer.Processor(handler));
    options.workerThreads(configurator.getNumThreads());
    options.protocolFactory(new TCompactProtocol.Factory());
    dataServer = new THsHaServer(options);
    LOG.debug("Launching Thrift server...");
    dataServer.serve();
    LOG.debug("Thrift server exited.");
    handler.shutDown();
    LOG.debug("Handler shutdown.");
  }

  /**
   * Start serving the data. Returns when the server is up.
   */
  private void serveData() {
    if (dataServer != null) {
      LOG.info("Data server is already running.");
      return;
    }
    Runnable r = new Runnable() {
      @Override
      public void run() {
        try {
          startThriftServer();
        } catch (Exception e) {
          // TODO deal with exception. server is probably going down unexpectedly
          LOG.fatal("Data server thread encountered a fatal exception.", e);
        }
      }
    };
    dataServerThread = new Thread(r, "PartitionServer Thrift data server thread");
    LOG.info("Launching data server thread...");
    dataServerThread.start();
    try {
      while (dataServer == null || !dataServer.isServing()) {
        LOG.debug("Data server isn't online yet. Waiting...");
        Thread.sleep(1000);
      }
      LOG.info("Data server online and serving.");
    } catch (InterruptedException e) {
      throw new RuntimeException("Interrupted waiting for data server thread to start", e);
    }
  }

  /**
   * Block until thrift server is down
   */
  private void stopServingData() {
    if (dataServer == null) {
      return;
    }
    LOG.info("Stopping data server thread.");
    dataServer.stop();
    try {
      dataServerThread.join();
    } catch (InterruptedException e) {
      LOG.debug("Interrupted while waiting for data server thread to stop. Continuing.", e);
    }
    dataServer = null;
    dataServerThread = null;
  }

  private String ignoreIncompatibleCommandMessage(HostCommand command, HostState state) {
    return String.format("Ignoring command %s because it is incompatible with state %s.", command, state);
  }

  public static void main(String[] args) throws Throwable {
    try {
      CommandLineChecker.check(args, new String[]{"configuration_file_path", "log4j_properties_file_path"}, PartitionServer.class);
      String configPath = args[0];
      String log4jprops = args[1];

      PartitionServerConfigurator configurator = new YamlPartitionServerConfigurator(configPath);
      PropertyConfigurator.configure(log4jprops);

      new PartitionServer(configurator, HostUtils.getHostName()).run();
    } catch (Throwable t) {
      System.err.println("usage: bin/start_partition_server.sh <path to config.yml> <path to log4j properties>");
      throw t;
    }
  }
}
