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

import com.intellij.plugins.haxe.config.HaxeConfiguration;
import com.intellij.plugins.haxe.config.HaxeTarget;
import com.intellij.plugins.haxe.config.sdk.HaxeSdkAdditionalDataBase;
import com.intellij.plugins.haxe.module.HaxeModuleSettingsBase;
import com.intellij.plugins.haxe.module.impl.HaxeModuleSettingsBaseImpl;
import com.intellij.testFramework.LightPlatformTestCase;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.Test;

import java.util.Collections;
import java.util.List;

/**
 * Checks where {@code --connect} lands in the generated command lines when a compilation
 * server is in use, and that the paths which do not talk to the compiler directly are
 * left alone.
 *
 * This test lives in {@code com.intellij.plugins.haxe.util} so it can reach
 * {@link HaxeCommonCompilerUtil#generateCommandLines} without that method having to be public.
 *
 * A platform Application is needed even though no project is: the user-properties path runs through
 * {@code calculateOutputPath}, which calls {@code HaxeFileUtil.isAbsolutePath} and therefore
 * {@code VirtualFileManager.getInstance()}.
 */
public class HaxeCompilationServerCommandLineTest extends LightPlatformTestCase {

  private static final String SDK_HOME = "/opt/haxe";
  private static final String ADDRESS = "127.0.0.1:6000";

  /**
   * The SDK compiler is looked up on disk, so on a machine without /opt/haxe the path
   * resolves to null. That is fine here: the tests care about what follows the executable.
   */
  private static final String COMPILER = HaxeSdkUtilBase.getCompilerPathByFolderPath(SDK_HOME);

  private static List<String> singleCommandLine(HaxeConfiguration config, @Nullable String serverAddress) {
    List<List<String>> commandLines = generate(config, serverAddress);
    assertEquals("Expected exactly one command line", 1, commandLines.size());
    return commandLines.get(0);
  }

  private static List<List<String>> generate(HaxeConfiguration config, @Nullable String serverAddress) {
    return HaxeCommonCompilerUtil.generateCommandLines(new TestCompilationContext(config, serverAddress));
  }

  @Test
  public void testHxmlConnectFollowsCompiler() throws Throwable {
    List<String> commandLine = singleCommandLine(HaxeConfiguration.HXML, ADDRESS);

    assertEquals(COMPILER, commandLine.get(0));
    assertEquals("--connect", commandLine.get(1));
    assertEquals(ADDRESS, commandLine.get(2));
    assertEquals("build.hxml", commandLine.get(3));
    assertEquals(4, commandLine.size());
  }

  @Test
  public void testHxmlWithoutServerIsUnchanged() throws Throwable {
    List<String> commandLine = singleCommandLine(HaxeConfiguration.HXML, null);

    assertEquals(2, commandLine.size());
    assertEquals(COMPILER, commandLine.get(0));
    assertEquals("build.hxml", commandLine.get(1));
    assertFalse(commandLine.contains("--connect"));
  }

  @Test
  public void testUserPropertiesConnectFollowsCompiler() throws Throwable {
    List<String> commandLine = singleCommandLine(HaxeConfiguration.CUSTOM, ADDRESS);

    assertEquals(COMPILER, commandLine.get(0));
    assertEquals("--connect", commandLine.get(1));
    assertEquals(ADDRESS, commandLine.get(2));
    // The rest of the command line is built exactly as before.
    assertEquals("-main", commandLine.get(3));
    assertEquals("Main", commandLine.get(4));
    assertTrue(commandLine.contains("-neko"));
  }

  @Test
  public void testUserPropertiesWithoutServerIsUnchanged() throws Throwable {
    List<String> commandLine = singleCommandLine(HaxeConfiguration.CUSTOM, null);

    assertEquals(COMPILER, commandLine.get(0));
    assertEquals("-main", commandLine.get(1));
    assertFalse(commandLine.contains("--connect"));
  }

  /**
   * NME and OpenFL builds run {@code haxelib run nme|lime}, which assembles its own haxe
   * invocation, so --connect must not be injected there.
   */
  @Test
  public void testNmeIsNeverGivenConnect() throws Throwable {
    assertFalse(singleCommandLine(HaxeConfiguration.NMML, ADDRESS).contains("--connect"));
    assertFalse(singleCommandLine(HaxeConfiguration.NMML, null).contains("--connect"));
  }

  @Test
  public void testOpenFLIsNeverGivenConnect() throws Throwable {
    for (List<String> commandLine : generate(HaxeConfiguration.OPENFL, ADDRESS)) {
      assertFalse(commandLine.contains("--connect"));
    }
  }

  private static class TestCompilationContext implements HaxeCommonCompilerUtil.CompilationContext {
    private final HaxeModuleSettingsBase mySettings;
    private final String myServerAddress;
    private String myErrorRoot;

    TestCompilationContext(HaxeConfiguration config, @Nullable String serverAddress) {
      HaxeModuleSettingsBaseImpl settings = new HaxeModuleSettingsBaseImpl();
      settings.setBuildConfig(config.asBuildConfigValue());
      settings.setHxmlPath("build.hxml");
      settings.setNmmlPath("project.nmml");
      settings.setOpenFLPath("project.xml");
      settings.setMainClass("Main");
      settings.setOutputFileName("output.n");
      settings.setHaxeTarget(HaxeTarget.NEKO);
      mySettings = settings;
      myServerAddress = serverAddress;
    }

    @Nullable
    @Override
    public String getCompilationServerAddress() {
      return myServerAddress;
    }

    @Override
    public HaxeSdkAdditionalDataBase getHaxeSdkData() { return null; }

    @NotNull
    @Override
    public HaxeModuleSettingsBase getModuleSettings() { return mySettings; }

    @Override
    public String getModuleName() { return "test"; }

    @Override
    public String getCompilationClass() { return mySettings.getMainClass(); }

    @Override
    public String getOutputFileName() { return mySettings.getOutputFileName(); }

    @Override
    public String getOutputDirectory() { return "/tmp/out"; }

    @Override
    public Boolean getIsTestBuild() { return false; }

    @Override
    public void errorHandler(String message) { fail("Unexpected error: " + message); }

    @Override
    public void warningHandler(String message) { }

    @Override
    public void infoHandler(String message) { }

    @Override
    public void log(String message) { }

    @Override
    public String getSdkHomePath() { return SDK_HOME; }

    @Override
    public String getHaxelibPath() { return SDK_HOME + "/haxelib"; }

    @Override
    public String getNekoBinPath() { return SDK_HOME + "/neko"; }

    @Override
    public boolean isDebug() { return false; }

    @Override
    public String getSdkName() { return "test-sdk"; }

    @Override
    public List<String> getSourceRoots() { return Collections.emptyList(); }

    @Override
    public String getModuleDefaultCompileOutputPath() { return "/tmp/out"; }

    @Override
    public void setErrorRoot(String root) { myErrorRoot = root; }

    @Override
    public String getErrorRoot() { return myErrorRoot; }

    @Override
    public void handleOutput(String[] lines) { }

    @Override
    public HaxeTarget getHaxeTarget() { return mySettings.getHaxeTarget(); }

    @Override
    public String getModuleDirPath() { return "/tmp/module"; }
  }
}
