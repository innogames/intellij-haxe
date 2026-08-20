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

import com.intellij.testFramework.UsefulTestCase;
import org.junit.Test;

import java.net.InetAddress;
import java.net.ServerSocket;

/**
 * The port probe is what decides whether a server is healthy, whether a spawn succeeded, and
 * whether a configured port is free to bind, so it gets checked against a real socket.
 *
 * Motivating case: {@code haxe --wait} on a port that is already held dies immediately with
 * "Couldn't wait on host:port", but the port keeps answering because the process that owns it is
 * still there.  Anything that treats "the port answers" as "my server started" will hand builds to
 * a server it did not start.
 */
public class HaxeCompilationServerPortTest extends UsefulTestCase {

  @Test
  public void testHeldPortIsSeenAsInUse() throws Throwable {
    try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
      assertTrue("A bound loopback port should be reported as answering",
                 HaxeCompilationServerService.isPortAnswering(socket.getLocalPort()));
    }
  }

  @Test
  public void testReleasedPortIsSeenAsFree() throws Throwable {
    int port;
    try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
      port = socket.getLocalPort();
    }
    // Closed again, so nothing should answer on it.
    assertFalse("A closed port should not be reported as answering",
                HaxeCompilationServerService.isPortAnswering(port));
  }

  @Test
  public void testFindFreePortReturnsSomethingBindable() throws Throwable {
    int port = HaxeCompilationServerService.findFreePort();
    assertTrue("Expected a usable port, got " + port, port > 0);
    assertFalse("A freshly picked port should not already be answering",
                HaxeCompilationServerService.isPortAnswering(port));
  }
}
