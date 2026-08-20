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
package com.intellij.plugins.haxe.util;

import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.plugins.haxe.config.sdk.HaxeSdkAdditionalDataBase;
import com.intellij.plugins.haxe.config.sdk.impl.HaxeSdkAdditionalDataBaseImpl;
import com.intellij.testFramework.UsefulTestCase;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The compiler resolves {@code -lib} by running whichever {@code haxelib} it finds on PATH, so the
 * PATH handed to compiler processes decides which haxelib wins.
 *
 * Motivating case: a project whose libraries live in {@code haxe_libraries/} (lix in scoped mode)
 * only resolves through lix's own haxelib shim.  The stock haxelib that always sits in the SDK home
 * cannot see those libraries and fails with "Library x is not installed", so a haxelib the user
 * configured has to be found first.
 */
public class HaxeSdkEnvironmentPathTest extends UsefulTestCase {

  private static final String PATH_VAR = SystemInfo.isWindows ? "Path" : "PATH";
  private static final String PATH_SEP = SystemInfo.isWindows ? ";" : ":";
  private static final String ORIGINAL_PATH = "/usr/bin" + PATH_SEP + "/bin";

  private static final String SDK_HOME = "/opt/haxe";
  private static final String NEKO_DIR = "/opt/neko";
  private static final String SHIM_DIR = "/projects/client/node_modules/.bin";

  private static HaxeSdkAdditionalDataBase sdkData(String sdkHome, String haxelibPath, String nekoBinPath) {
    HaxeSdkAdditionalDataBaseImpl data = new HaxeSdkAdditionalDataBaseImpl(sdkHome, "4.3.0");
    data.setHaxelibPath(haxelibPath);
    data.setNekoBinPath(nekoBinPath);
    return data;
  }

  /** The directories the patch puts in front of PATH, in order. */
  private static List<String> prependedDirectories(HaxeSdkAdditionalDataBase sdkData) {
    Map<String, String> env = new HashMap<>();
    env.put(PATH_VAR, ORIGINAL_PATH);

    String patched = HaxeSdkUtilBase.patchEnvironment(env, sdkData).get(PATH_VAR);
    assertTrue("The original PATH must be kept, got: " + patched, patched.endsWith(ORIGINAL_PATH));

    String prefix = patched.substring(0, patched.length() - ORIGINAL_PATH.length());
    if (prefix.isEmpty()) {
      return List.of();
    }
    assertTrue("Prepended entries must end with a separator, got: " + prefix, prefix.endsWith(PATH_SEP));
    return Arrays.asList(prefix.substring(0, prefix.length() - PATH_SEP.length()).split(PATH_SEP));
  }

  /**
   * A haxelib configured outside the SDK home has to be found before the stock one in the SDK home,
   * or the setting can never take effect.
   */
  @Test
  public void testConfiguredHaxelibComesBeforeSdkHome() throws Throwable {
    List<String> dirs = prependedDirectories(sdkData(SDK_HOME, SHIM_DIR + "/haxelib", ""));

    assertEquals(List.of(SHIM_DIR, SDK_HOME), dirs);
  }

  /**
   * SDK detection writes {@code <sdkHome>/haxelib} as the default, and that must not change PATH
   * for anyone running a stock install.
   */
  @Test
  public void testDefaultHaxelibInSdkHomeAddsNothingExtra() throws Throwable {
    List<String> dirs = prependedDirectories(sdkData(SDK_HOME, SDK_HOME + "/haxelib", ""));

    assertEquals(List.of(SDK_HOME), dirs);
  }

  /**
   * The same directory spelled differently is still the same directory, so it must appear once.
   * Which of the two spellings survives does not matter, only that there is one entry.
   */
  @Test
  public void testSdkHomeIsNotDuplicatedWhenSpelledDifferently() throws Throwable {
    List<String> dirs = prependedDirectories(sdkData(SDK_HOME + "/", SDK_HOME + "/./haxelib", ""));

    assertEquals("Expected a single entry, got: " + dirs, 1, dirs.size());
    assertTrue("Expected the SDK home, got: " + dirs.get(0), FileUtil.pathsEqual(SDK_HOME, dirs.get(0)));
  }

  /** The neko directory still follows, as it always has. */
  @Test
  public void testNekoDirectoryFollows() throws Throwable {
    List<String> dirs = prependedDirectories(sdkData(SDK_HOME, SHIM_DIR + "/haxelib", NEKO_DIR + "/neko"));

    assertEquals(List.of(SHIM_DIR, SDK_HOME, NEKO_DIR), dirs);
  }

  /** Without an SDK home the configured haxelib is all there is -- unchanged from before. */
  @Test
  public void testHaxelibDirectoryIsUsedWhenSdkHomeIsEmpty() throws Throwable {
    List<String> dirs = prependedDirectories(sdkData("", SHIM_DIR + "/haxelib", ""));

    assertEquals(List.of(SHIM_DIR), dirs);
  }

  /** A bare command name has no directory to add, and must not put "null" on PATH. */
  @Test
  public void testBareHaxelibCommandAddsNoDirectory() throws Throwable {
    List<String> dirs = prependedDirectories(sdkData(SDK_HOME, "haxelib", ""));

    assertEquals(List.of(SDK_HOME), dirs);
  }

  @Test
  public void testNoSdkDataLeavesPathAlone() throws Throwable {
    Map<String, String> env = new HashMap<>();
    env.put(PATH_VAR, ORIGINAL_PATH);

    assertEquals(ORIGINAL_PATH, HaxeSdkUtilBase.patchEnvironment(env, null).get(PATH_VAR));
  }
}
