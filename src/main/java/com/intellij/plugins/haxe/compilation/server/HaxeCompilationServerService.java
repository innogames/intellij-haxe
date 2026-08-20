/*
 * Copyright 2026 InnoGames GmbH
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
package com.intellij.plugins.haxe.compilation.server;

import com.intellij.execution.process.OSProcessHandler;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessListener;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.util.Key;
import com.intellij.plugins.haxe.HaxeBundle;
import com.intellij.plugins.haxe.config.HaxeProjectSettings;
import com.intellij.plugins.haxe.config.sdk.HaxeSdkAdditionalDataBase;
import com.intellij.plugins.haxe.util.HaxeSdkUtilBase;
import com.intellij.util.concurrency.AppExecutorUtil;
import lombok.CustomLog;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Owns the Haxe compilation servers this project builds and completes through.
 *
 * A server is keyed by SDK home path, so a worktree configured against a different Haxe SDK can
 * never end up compiling through a server running a different compiler version.  Servers belong
 * to the project (this is a project-level service), so worktrees opened side by side stay
 * independent: a restart in one leaves the others warm, and a long build in one does not stall
 * completion in another.
 *
 * Lifetime: a clean project close stops every server this service knows about, so nothing lingers
 * holding a multi-gigabyte module cache.  A server that outlives its IDE — the IDE crashed, or a
 * second IDE instance has the same project open — is adopted on the next start rather than left
 * as an orphan on a port nobody will ever look for again.
 */
@CustomLog
public final class HaxeCompilationServerService implements Disposable {

  private static final String HOST = "127.0.0.1";

  /**
   * How long to wait for a freshly spawned server to start listening.  This bounds startup only.
   * It is deliberately never applied to a request: a server handles one request at a time, so a
   * completion query fired during a build legitimately waits out a full cold compile.
   */
  private static final long STARTUP_TIMEOUT_MS = 15_000;

  private static final int PROBE_TIMEOUT_MS = 500;
  private static final long PROBE_INTERVAL_MS = 100;

  /** Long enough that nobody is surprised by a warm cache being thrown away. */
  private static final long IDLE_TIMEOUT_MS = TimeUnit.HOURS.toMillis(2);
  private static final long IDLE_CHECK_INTERVAL_MS = TimeUnit.MINUTES.toMillis(10);

  /**
   * Deliberately a single bare line, so {@code haxe --connect $(cat …)} works.  Everything the
   * service itself needs (pid, SDK) lives in the metadata file instead — a second line here would
   * make the shell substitution expand to "53412 81234" and break the flag.
   */
  private static final String PORT_FILE_NAME = "haxe-compilation-server.port";

  private static final String METADATA_DIR_NAME = "haxe-compilation-server";

  private final Project myProject;
  private final Object myLock = new Object();
  private final Map<String, ServerInstance> myInstances = new HashMap<>();
  private ScheduledFuture<?> myIdleCheck;

  public HaxeCompilationServerService(@NotNull Project project) {
    myProject = project;
  }

  public static HaxeCompilationServerService getInstance(@NotNull Project project) {
    return project.getService(HaxeCompilationServerService.class);
  }

  /**
   * Address to compile through, or null to compile cold.
   *
   * When the setting is on but no server can be reached, {@code warningSink} is told why and null
   * is returned so the caller falls back to a direct compile.  The sink must report a warning, not
   * an error: an error-category compiler message fails the whole build, which would turn a working
   * fallback compile into a reported failure.
   */
  @Nullable
  public String resolveAddressForBuild(@NotNull Module module, @NotNull Consumer<String> warningSink) {
    return resolveAddress(module, warningSink, true);
  }

  /**
   * Address for a compiler-completion query, or null to spawn a cold compiler.
   *
   * Deliberately will not start a server: completion runs while the user is typing, and waiting out
   * a server start there would stall the completion thread for seconds.  A build (or the restart
   * action) starts the server; completion adopts it once it is up.  Failures are logged rather than
   * surfaced, for the same reason.
   */
  @Nullable
  public String resolveAddressForCompletion(@Nullable Module module) {
    return module == null ? null : resolveAddress(module, log::info, false);
  }

  @Nullable
  private String resolveAddress(@NotNull Module module, @NotNull Consumer<String> failureSink, boolean mayStart) {
    HaxeProjectSettings settings = HaxeProjectSettings.getInstance(myProject);
    if (!settings.isUseCompilationServer()) {
      return null;
    }

    String sdkHome = sdkHomePath(module);
    if (sdkHome == null) {
      failureSink.accept(HaxeBundle.message("haxe.compilation.server.no.sdk", module.getName()));
      return null;
    }
    String compilerPath = HaxeSdkUtilBase.getCompilerPathByFolderPath(sdkHome);
    if (compilerPath == null) {
      failureSink.accept(HaxeBundle.message("haxe.compilation.server.no.compiler", sdkHome));
      return null;
    }

    synchronized (myLock) {
      ServerInstance instance = myInstances.get(sdkHome);
      if (instance != null) {
        if (isPortAnswering(instance.port)) {
          instance.lastUsedMs = System.currentTimeMillis();
          return address(instance.port);
        }
        // Went away underneath us.  Drop it and try again from scratch.
        log.info("Haxe compilation server on port " + instance.port + " stopped responding; restarting");
        stopAndForget(instance);
      }

      instance = adopt(sdkHome, compilerPath);
      if (instance == null && mayStart) {
        instance = start(module, sdkHome, compilerPath, failureSink);
      }
      if (instance == null) {
        return null;
      }

      myInstances.put(sdkHome, instance);
      instance.lastUsedMs = System.currentTimeMillis();
      writePortFile(instance.port);
      scheduleIdleCheck();
      return address(instance.port);
    }
  }

  /** Port of the server most recently used by this project, for the copy-port action. */
  @Nullable
  public Integer getMostRecentlyUsedPort() {
    synchronized (myLock) {
      return myInstances.values().stream()
        .max((a, b) -> Long.compare(a.lastUsedMs, b.lastUsedMs))
        .map(instance -> instance.port)
        .orElse(null);
    }
  }

  /** Stop every server for this project.  The next build or completion starts a fresh one. */
  public void restart() {
    synchronized (myLock) {
      stopAll();
    }
  }

  // ---------------------------------------------------------------- adoption

  /**
   * Take over a server left behind by a crashed IDE, or shared with a second IDE instance.
   *
   * The pid is checked before the port on purpose.  Automatically assigned ports get recycled, so
   * by the time a stale metadata file is read that port may belong to something else entirely, and
   * sending an unrelated service a compilation request would at best hang.
   */
  @Nullable
  private ServerInstance adopt(@NotNull String sdkHome, @NotNull String compilerPath) {
    ServerMetadata metadata = readMetadata(sdkHome);
    if (metadata == null) {
      return null;
    }

    Optional<ProcessHandle> handle = ProcessHandle.of(metadata.pid);
    if (handle.isEmpty() || !handle.get().isAlive()) {
      log.debug("Haxe compilation server metadata names a dead pid " + metadata.pid + "; ignoring");
      deleteMetadata(sdkHome);
      return null;
    }

    if (!looksLikeOurServer(handle.get(), metadata.port)) {
      // The pid was reused by an unrelated process since the metadata was written.
      log.info("pid " + metadata.pid + " is not a Haxe compilation server on port " + metadata.port + "; ignoring");
      deleteMetadata(sdkHome);
      return null;
    }

    if (!isPortAnswering(metadata.port)) {
      // Our server, but wedged or shutting down.  Safe to reap: the command line matched.
      log.info("Reaping unresponsive Haxe compilation server pid " + metadata.pid + " on port " + metadata.port);
      handle.get().destroy();
      deleteMetadata(sdkHome);
      return null;
    }

    log.info("Adopted existing Haxe compilation server on port " + metadata.port + " (pid " + metadata.pid + ")");
    return new ServerInstance(sdkHome, compilerPath, metadata.port, metadata.pid, null);
  }

  /**
   * True when this pid really is one of our compilation servers, judged by its listen argument.
   * This is what makes pid reuse harmless: nothing but a Haxe server started by this plugin is
   * going to be running with {@code --wait 127.0.0.1:<that exact port>}.
   *
   * When the platform will not tell us the arguments at all we answer false, which costs a spare
   * server start but never destroys a process we cannot identify.
   */
  private static boolean looksLikeOurServer(@NotNull ProcessHandle handle, int port) {
    ProcessHandle.Info info = handle.info();

    Optional<String[]> arguments = info.arguments();
    if (arguments.isPresent()) {
      String[] args = arguments.get();
      String listenAddress = HOST + ":" + port;
      for (int i = 0; i + 1 < args.length; i++) {
        if ("--wait".equals(args[i]) && listenAddress.equals(args[i + 1])) {
          return true;
        }
      }
      return false;
    }

    Optional<String> commandLine = info.commandLine();
    if (commandLine.isPresent()) {
      return containsWaitArgument(commandLine.get(), port);
    }

    log.debug("Cannot read the arguments of pid " + handle.pid() + "; not treating it as a compilation server");
    return false;
  }

  /**
   * Boundary-aware search for the listen argument in a flattened command line, so a server on port
   * 6000 is not mistaken for one on port 60001.
   */
  private static boolean containsWaitArgument(@NotNull String commandLine, int port) {
    String needle = waitArgument(port);
    for (int index = commandLine.indexOf(needle); index >= 0; index = commandLine.indexOf(needle, index + 1)) {
      int after = index + needle.length();
      if (after == commandLine.length() || !Character.isDigit(commandLine.charAt(after))) {
        return true;
      }
    }
    return false;
  }

  // ----------------------------------------------------------------- startup

  @Nullable
  private ServerInstance start(@NotNull Module module,
                               @NotNull String sdkHome,
                               @NotNull String compilerPath,
                               @NotNull Consumer<String> failureSink) {
    int configuredPort = HaxeProjectSettings.getInstance(myProject).getCompilationServerPort();
    // A port picked by ServerSocket(0) can be taken by someone else between closing the probe
    // socket and the compiler binding it, so an automatic port gets a few attempts.
    int attempts = configuredPort > 0 ? 1 : 3;
    String lastFailure = null;

    for (int attempt = 0; attempt < attempts; attempt++) {
      int port = configuredPort > 0 ? configuredPort : findFreePort();
      if (port <= 0) {
        lastFailure = HaxeBundle.message("haxe.compilation.server.no.free.port");
        continue;
      }

      // Never spawn onto a port that is already answering.  "haxe --wait" on a held port dies at
      // once, but the port keeps answering because whoever owns it is still there - so without this
      // check a dead spawn looks like a live server and builds would go to a process we did not
      // start and cannot vouch for.  Adoption is the only sanctioned way to use someone else's
      // server, and it has already been tried by the time we get here.
      if (isPortAnswering(port)) {
        log.info("Port " + port + " is already in use; not starting a Haxe compilation server on it");
        lastFailure = HaxeBundle.message("haxe.compilation.server.port.in.use", String.valueOf(port));
        continue;
      }

      ServerInstance instance = spawn(module, sdkHome, compilerPath, port);
      if (instance != null) {
        writeMetadata(instance);
        return instance;
      }
      lastFailure = HaxeBundle.message("haxe.compilation.server.start.failed", String.valueOf(port));
    }

    failureSink.accept(lastFailure != null
                       ? lastFailure
                       : HaxeBundle.message("haxe.compilation.server.start.failed", "?"));
    return null;
  }

  @Nullable
  private ServerInstance spawn(@NotNull Module module,
                               @NotNull String sdkHome,
                               @NotNull String compilerPath,
                               int port) {
    List<String> commandLine = new ArrayList<>();
    commandLine.add(compilerPath);
    // Always host-qualified.  A bare port makes the lix shim listen on every interface, and
    // parses as something else entirely there.
    commandLine.add("--wait");
    commandLine.add(HOST + ":" + port);

    HaxeSdkAdditionalDataBase sdkData = HaxeSdkUtilBase.getSdkData(module);
    // Same process builder the compiler itself is launched with, so the server inherits exactly
    // the environment a direct build gets.  -lib resolution and ${HAXE_LIBCACHE} expansion happen
    // inside the server, and an environment mismatch there surfaces as libraries failing to
    // resolve for no visible reason.
    File workingDirectory = projectBaseDirectory();

    try {
      Process process = HaxeSdkUtilBase.createProcessBuilder(commandLine, workingDirectory, sdkData).start();
      OSProcessHandler handler = new OSProcessHandler(process, String.join(" ", commandLine), Charset.defaultCharset());
      handler.addProcessListener(new ProcessListener() {
        @Override
        public void onTextAvailable(@NotNull ProcessEvent event, @NotNull Key outputType) {
          String text = event.getText().stripTrailing();
          if (!text.isEmpty()) {
            log.info("Haxe compilation server: " + text);
          }
        }

        @Override
        public void processTerminated(@NotNull ProcessEvent event) {
          log.info("Haxe compilation server on port " + port + " exited with " + event.getExitCode());
        }
      });
      handler.startNotify();

      if (!waitUntilListening(process, port)) {
        handler.destroyProcess();
        return null;
      }

      log.info("Started Haxe compilation server on port " + port + " (pid " + process.pid() + ")");
      return new ServerInstance(sdkHome, compilerPath, port, process.pid(), handler);
    }
    catch (IOException e) {
      log.warn("Could not start a Haxe compilation server on port " + port, e);
      return null;
    }
  }

  private static boolean waitUntilListening(@NotNull Process process, int port) {
    long deadline = System.currentTimeMillis() + STARTUP_TIMEOUT_MS;
    while (System.currentTimeMillis() < deadline) {
      if (!process.isAlive()) {
        return false;
      }
      // Liveness is re-checked after the probe as well: a spawn that loses a bind race dies while
      // the winner keeps answering, and only the second check can tell those apart.
      if (isPortAnswering(port) && process.isAlive()) {
        return true;
      }
      try {
        Thread.sleep(PROBE_INTERVAL_MS);
      }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      }
    }
    return false;
  }

  /**
   * Health-check by connecting, rather than by trusting a stored pid — a live pid says nothing
   * about whether the server is still accepting work.
   */
  static boolean isPortAnswering(int port) {
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress(HOST, port), PROBE_TIMEOUT_MS);
      return true;
    }
    catch (IOException e) {
      return false;
    }
  }

  static int findFreePort() {
    try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
      return socket.getLocalPort();
    }
    catch (IOException e) {
      log.warn("Could not find a free port for a Haxe compilation server", e);
      return -1;
    }
  }

  // ---------------------------------------------------------------- stopping

  private void stopAll() {
    for (ServerInstance instance : new ArrayList<>(myInstances.values())) {
      stopAndForget(instance);
    }
  }

  private void stopAndForget(@NotNull ServerInstance instance) {
    stop(instance);
    myInstances.remove(instance.sdkHomePath);
    deleteMetadata(instance.sdkHomePath);
    deletePortFile(instance.port);
  }

  /**
   * Adopted servers have no process handler, so stopping has to work from the pid as well —
   * otherwise the case where stopping matters most, an adopted orphan, would be the one case
   * that cannot be stopped.
   */
  private static void stop(@NotNull ServerInstance instance) {
    if (instance.processHandler != null) {
      instance.processHandler.destroyProcess();
      return;
    }
    ProcessHandle.of(instance.pid)
      .filter(ProcessHandle::isAlive)
      .filter(handle -> looksLikeOurServer(handle, instance.port))
      .ifPresent(ProcessHandle::destroy);
  }

  private void scheduleIdleCheck() {
    if (myIdleCheck != null) {
      return;
    }
    myIdleCheck = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(
      this::stopIdleServers, IDLE_CHECK_INTERVAL_MS, IDLE_CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS);
  }

  private void stopIdleServers() {
    synchronized (myLock) {
      long cutoff = System.currentTimeMillis() - IDLE_TIMEOUT_MS;
      for (ServerInstance instance : new ArrayList<>(myInstances.values())) {
        if (instance.lastUsedMs < cutoff) {
          log.info("Stopping idle Haxe compilation server on port " + instance.port);
          stopAndForget(instance);
        }
      }
    }
  }

  @Override
  public void dispose() {
    synchronized (myLock) {
      if (myIdleCheck != null) {
        myIdleCheck.cancel(false);
        myIdleCheck = null;
      }
      stopAll();
    }
  }

  // ------------------------------------------------------------------- files

  /**
   * The convenience file, for pointing a terminal build or a script at the same server.  It always
   * names the server most recently used by this project; adoption never reads it, so a project
   * with modules on two different SDKs stays correct even though only one port shows up here.
   */
  @Nullable
  private Path portFile() {
    String basePath = myProject.getBasePath();
    if (basePath == null) {
      return null;
    }
    Path ideaDirectory = Path.of(basePath, ".idea");
    if (!Files.isDirectory(ideaDirectory)) {
      // A .ipr project, or a project directory we cannot write to.  The port is still in the log
      // and in the command line echoed to the Build window.
      return null;
    }
    return ideaDirectory.resolve(PORT_FILE_NAME);
  }

  private void writePortFile(int port) {
    Path file = portFile();
    if (file == null) {
      return;
    }
    try {
      Files.writeString(file, port + System.lineSeparator(), StandardCharsets.UTF_8);
    }
    catch (IOException e) {
      log.info("Could not write " + file + ": " + e.getMessage());
    }
  }

  /**
   * Only removes the file when it still describes the server being stopped.  A project with modules
   * on two different SDKs has two servers but one convenience file, and stopping one of them must
   * not delete a file that points at the other.
   */
  private void deletePortFile(int port) {
    Path file = portFile();
    if (file == null || !Files.isRegularFile(file)) {
      return;
    }
    try {
      if (!Files.readString(file, StandardCharsets.UTF_8).trim().equals(String.valueOf(port))) {
        return;
      }
      Files.deleteIfExists(file);
    }
    catch (IOException e) {
      log.info("Could not delete " + file + ": " + e.getMessage());
    }
  }

  /** Per (project, SDK), so adoption is never ambiguous. */
  private Path metadataFile(@NotNull String sdkHomePath) {
    String name = Integer.toHexString(String.valueOf(myProject.getBasePath()).hashCode())
                  + "-" + Integer.toHexString(sdkHomePath.hashCode()) + ".properties";
    return Path.of(PathManager.getSystemPath(), METADATA_DIR_NAME, name);
  }

  private void writeMetadata(@NotNull ServerInstance instance) {
    Path file = metadataFile(instance.sdkHomePath);
    Properties properties = new Properties();
    properties.setProperty("port", String.valueOf(instance.port));
    properties.setProperty("pid", String.valueOf(instance.pid));
    properties.setProperty("sdkHome", instance.sdkHomePath);
    properties.setProperty("compiler", instance.compilerPath);
    try {
      Files.createDirectories(file.getParent());
      try (OutputStream out = Files.newOutputStream(file)) {
        properties.store(out, "Haxe compilation server for " + myProject.getName());
      }
    }
    catch (IOException e) {
      log.info("Could not write " + file + ": " + e.getMessage());
    }
  }

  @Nullable
  private ServerMetadata readMetadata(@NotNull String sdkHomePath) {
    Path file = metadataFile(sdkHomePath);
    if (!Files.isRegularFile(file)) {
      return null;
    }
    Properties properties = new Properties();
    try (InputStream in = Files.newInputStream(file)) {
      properties.load(in);
    }
    catch (IOException e) {
      log.info("Could not read " + file + ": " + e.getMessage());
      return null;
    }
    try {
      int port = Integer.parseInt(properties.getProperty("port", "-1"));
      long pid = Long.parseLong(properties.getProperty("pid", "-1"));
      if (port <= 0 || pid <= 0) {
        return null;
      }
      return new ServerMetadata(port, pid);
    }
    catch (NumberFormatException e) {
      return null;
    }
  }

  private void deleteMetadata(@NotNull String sdkHomePath) {
    try {
      Files.deleteIfExists(metadataFile(sdkHomePath));
    }
    catch (IOException e) {
      log.info("Could not delete metadata for " + sdkHomePath + ": " + e.getMessage());
    }
  }

  // ------------------------------------------------------------------ shared

  private static String address(int port) {
    return HOST + ":" + port;
  }

  private static String waitArgument(int port) {
    return "--wait " + HOST + ":" + port;
  }

  @Nullable
  private static String sdkHomePath(@NotNull Module module) {
    Sdk sdk = ModuleRootManager.getInstance(module).getSdk();
    return sdk == null ? null : sdk.getHomePath();
  }

  @Nullable
  private File projectBaseDirectory() {
    String basePath = myProject.getBasePath();
    return basePath == null ? null : new File(basePath);
  }

  private static final class ServerInstance {
    final String sdkHomePath;
    final String compilerPath;
    final int port;
    final long pid;
    /** Null when this server was adopted rather than spawned by this IDE. */
    @Nullable final OSProcessHandler processHandler;
    volatile long lastUsedMs;

    ServerInstance(String sdkHomePath, String compilerPath, int port, long pid, @Nullable OSProcessHandler processHandler) {
      this.sdkHomePath = sdkHomePath;
      this.compilerPath = compilerPath;
      this.port = port;
      this.pid = pid;
      this.processHandler = processHandler;
    }
  }

  private record ServerMetadata(int port, long pid) { }
}
