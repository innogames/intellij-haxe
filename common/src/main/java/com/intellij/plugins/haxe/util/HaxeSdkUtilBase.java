/*
 * Copyright 2000-2013 JetBrains s.r.o.
 * Copyright 2014-2014 AS3Boyan
 * Copyright 2014-2014 Elias Ku
 * Copyright 2017 Eric Bishton
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

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.projectRoots.SdkAdditionalData;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.plugins.haxe.config.sdk.HaxeSdkAdditionalDataBase;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * @author: Fedor.Korotkov
 */
public class HaxeSdkUtilBase {
  private static final Logger LOG = Logger.getInstance("#com.intellij.plugins.haxe.util.HaxeSdkUtilBase");
  protected static final String COMPILER_EXECUTABLE_NAME = "haxe";
  protected static final String HAXELIB_EXECUTABLE_NAME = "haxelib";

  @Nullable
  public static String getCompilerPathByFolderPath(String folderPath) {
    return getExecutablePathByFolderPath(folderPath, COMPILER_EXECUTABLE_NAME);
  }

  @Nullable
  public static String getHaxelibPathByFolderPath(String folderPath) {
    return getExecutablePathByFolderPath(folderPath, HAXELIB_EXECUTABLE_NAME);
  }

  @Nullable
  private static String getExecutablePathByFolderPath(String folderPath, String name) {
    if (!SystemInfo.isLinux) {
      final String candidatePath = folderPath + "/bin/" + getExecutableName(name);
      LOG.debug("candidatePath: " + candidatePath);
      if (fileExists(candidatePath)) {
        return FileUtil.toSystemIndependentName(candidatePath);
      }
    }

    final String resultPath = folderPath + "/" + getExecutableName(name);
    LOG.debug("resultPath: " + resultPath);
    if (fileExists(resultPath)) {
      return FileUtil.toSystemIndependentName(resultPath);
    }

    LOG.debug(name + " path: null");
    return null;
  }

  public static String getExecutableName(String name) {
    if (SystemInfo.isWindows && !name.endsWith(".exe")) {
      return name + ".exe";
    }
    return name;
  }

  private static boolean fileExists(@Nullable String filePath) {
    return filePath != null && new File(FileUtil.toSystemDependentName(filePath)).exists();
  }

  @Nullable
  public static HaxeSdkAdditionalDataBase getSdkData(@Nullable Module module) {
    if(module != null) {
      ModuleRootManager manager = ModuleRootManager.getInstance(module);
      if (manager != null) {
        return getSdkData(manager.getSdk());
      }
    }
    return null;
  }

  @Nullable
  public static HaxeSdkAdditionalDataBase getSdkData(@Nullable Sdk sdk) {
    if(sdk != null) {
      SdkAdditionalData sdkData = sdk.getSdkAdditionalData();
      if(sdkData != null && sdkData instanceof HaxeSdkAdditionalDataBase) {
        return (HaxeSdkAdditionalDataBase)sdkData;
      }
    }
    return null;
  }

  /**
   * Directories to put in front of PATH for a Haxe SDK, highest priority first.
   *
   * The configured haxelib deliberately comes before the SDK home.  The compiler resolves
   * {@code -lib} by running whichever {@code haxelib} it finds on PATH, and a stock {@code haxelib}
   * always sits in the SDK home, so with the SDK home first the SDK's "haxelib path" setting could
   * never take effect for compiler invocations.  Projects that resolve their libraries through a
   * haxelib shim rather than the stock one - lix in scoped mode, for instance - need the configured
   * haxelib to win.  With the default that SDK detection writes ({@code <sdkHome>/haxelib}) both
   * entries name the same directory and the result is the same PATH as always.
   *
   * @param haxeSdkData SDK whose directories are wanted.
   * @return directories in system independent form, without duplicates.
   */
  @NotNull
  private static List<String> getPathEntries(@NotNull HaxeSdkAdditionalDataBase haxeSdkData) {
    final List<String> entries = new ArrayList<>();
    addPathEntry(entries, getContainingDirectory(haxeSdkData.getHaxelibPath()));
    addPathEntry(entries, haxeSdkData.getHomePath());
    addPathEntry(entries, getContainingDirectory(haxeSdkData.getNekoBinPath()));
    return entries;
  }

  private static void addPathEntry(@NotNull List<String> entries, @Nullable String directory) {
    if (directory == null || directory.isEmpty()) {
      return;
    }
    for (String entry : entries) {
      if (FileUtil.pathsEqual(entry, directory)) {
        return;
      }
    }
    entries.add(directory);
  }

  /**
   * @return the directory holding the given executable, or null when there is nothing to add to
   * PATH: the setting is not filled in, or it is a bare command name with no directory part.
   */
  @Nullable
  private static String getContainingDirectory(@Nullable String executablePath) {
    if (executablePath == null || executablePath.isEmpty()) {
      return null;
    }
    final String parent = new File(FileUtil.toSystemDependentName(executablePath)).getParent();
    return parent == null ? null : FileUtil.toSystemIndependentName(parent);
  }

  @NotNull
  private static String getEnvironmentPathPatch(@Nullable HaxeSdkAdditionalDataBase haxeSdkData) {
    if (haxeSdkData == null) {
      return "";
    }
    final List<String> entries = getPathEntries(haxeSdkData);
    if (entries.isEmpty()) {
      return "";
    }
    final String pathsep = SystemInfo.isWindows ? ";" : ":";
    return String.join(pathsep, entries) + pathsep;
  }

  @NotNull
  public static ProcessBuilder createProcessBuilder(List<String> commandLine, @Nullable File workingDirectory, @Nullable HaxeSdkAdditionalDataBase haxeSdkData) {
    final ProcessBuilder processBuilder = new ProcessBuilder(commandLine);

    if(workingDirectory != null) {
      processBuilder.directory(workingDirectory);
    }

    patchEnvironment(processBuilder.environment(), haxeSdkData);

    return processBuilder;
  }

  /**
   * Patch the PATH environment variable to include the Haxe SDK.  The original environment
   * is modified in place.
   *
   * @param env list of environment variables.
   * @param haxeSdkData SDK for which the environment is being modified.
   * @return the environment that was passed in.  It is modified in place.
   */
  @NotNull
  public static Map<String,String> patchEnvironment(@NotNull Map<String,String> env, @Nullable HaxeSdkAdditionalDataBase haxeSdkData) {
    String pathvar = SystemInfo.isWindows ? "Path" : "PATH";
    if (haxeSdkData != null) {
      final String path = getEnvironmentPathPatch(haxeSdkData) + env.get(pathvar);
      env.put(pathvar, path);
    }
    return env;
  }
}
