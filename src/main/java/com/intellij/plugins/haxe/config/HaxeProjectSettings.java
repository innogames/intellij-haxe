/*
 * Copyright 2000-2013 JetBrains s.r.o.
 * Copyright 2014-2014 AS3Boyan
 * Copyright 2014-2014 Elias Ku
 * Copyright 2017-2019 Eric Bishton
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
package com.intellij.plugins.haxe.config;

import com.intellij.openapi.components.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Condition;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.plugins.haxe.util.HaxeModificationTracker;
import com.intellij.plugins.haxe.util.HaxeTrackedModifiable;
import com.intellij.util.containers.ContainerUtil;
import org.jdom.Element;
import org.jetbrains.annotations.NotNull;

import java.util.*;

/**
 * @author: Fedor.Korotkov
 */
@State(
  name = "HaxeProjectSettings",
  storages = {
    @Storage("haxe.xml")
  }
)
public class HaxeProjectSettings implements PersistentStateComponent<Element>, HaxeTrackedModifiable {
  public static final String HAXE_SETTINGS = "HaxeProjectSettings";
  public static final String DEFINES = "defines";
  public static final String AUTO_DETECT_DEFINES = "auto_detect_defines";
  public static final String AUTO_DETECT_REFERENCES = "auto_detect_references";
  public static final String USE_COMPILATION_SERVER = "use_compilation_server";
  public static final String COMPILATION_SERVER_PORT = "compilation_server_port";

  /** A port of 0 means "pick a free one", which is what keeps side-by-side worktrees independent. */
  public static final int COMPILATION_SERVER_PORT_AUTO = 0;

  private String userCompilerDefinitions = "";
  private boolean autoDetectDefinitions = true;
  private boolean detectCodeReferencesInConsole = true;
  private boolean useCompilationServer = false;
  private int compilationServerPort = COMPILATION_SERVER_PORT_AUTO;
  private HaxeModificationTracker tracker = new HaxeModificationTracker(getClass().getName());

  public Set<String> getUserCompilerDefinitionsAsSet() {
    return new HashSet<String>(Arrays.asList(getUserCompilerDefinitions()));
  }

  public static HaxeProjectSettings getInstance(Project project) {
    return project.getService(HaxeProjectSettings.class);
  }

  public String[] getUserCompilerDefinitions() {
    // TODO: Bug here, if there are definitions that contain commas (e.g. mylib_version="2,4,3")
    return userCompilerDefinitions.split(",");
  }

  @NotNull
  public Map<String, String> getUserCompilerDefinitionMap() {
    Map<String, String> defintionMap = new HashMap<>();
    String[] definitions = getUserCompilerDefinitions();
    for (String def : definitions) {
      if (def.trim().isEmpty()) continue;

      String[] split = def.split("=", 2);

      // Dashes are subtraction operators, so definitions (on the command line) that
      // contain dashes are mapped to an equivalent using underscores (when looking up definitions).
      String key = split[0];
      String value = split.length > 1 ? split[1] : null;
      defintionMap.put(key, value == null ? "" : value);


    }
    return defintionMap;
  }

  public void setUserCompilerDefinitions(String[] userCompilerDefinitions) {
    this.userCompilerDefinitions = StringUtil.join(ContainerUtil.filter(userCompilerDefinitions, new Condition<String>() {
      @Override
      public boolean value(String s) {
        return s != null && !s.isEmpty();
      }
    }), ",");
    tracker.notifyUpdated();
  }

  @Override
  public void loadState(Element state) {
    userCompilerDefinitions = state.getAttributeValue(DEFINES, "");
    String defines = state.getAttributeValue(AUTO_DETECT_DEFINES);
    String references = state.getAttributeValue(AUTO_DETECT_REFERENCES);

    autoDetectDefinitions = Optional.ofNullable(defines).map(Boolean::parseBoolean).orElse(true);
    detectCodeReferencesInConsole= Optional.ofNullable(references).map(Boolean::parseBoolean).orElse(true);

    useCompilationServer = Optional.ofNullable(state.getAttributeValue(USE_COMPILATION_SERVER))
      .map(Boolean::parseBoolean).orElse(false);
    compilationServerPort = parsePort(state.getAttributeValue(COMPILATION_SERVER_PORT));

    tracker.notifyUpdated();
  }

  private static int parsePort(String value) {
    if (value == null || value.isEmpty()) {
      return COMPILATION_SERVER_PORT_AUTO;
    }
    try {
      int port = Integer.parseInt(value.trim());
      return port > 0 && port <= 65535 ? port : COMPILATION_SERVER_PORT_AUTO;
    }
    catch (NumberFormatException e) {
      return COMPILATION_SERVER_PORT_AUTO;
    }
  }

  @Override
  public Element getState() {
    final Element element = new Element(HAXE_SETTINGS);
    element.setAttribute(DEFINES, userCompilerDefinitions);
    element.setAttribute(AUTO_DETECT_DEFINES, String.valueOf(autoDetectDefinitions));
    element.setAttribute(AUTO_DETECT_REFERENCES, String.valueOf(detectCodeReferencesInConsole));
    element.setAttribute(USE_COMPILATION_SERVER, String.valueOf(useCompilationServer));
    element.setAttribute(COMPILATION_SERVER_PORT, String.valueOf(compilationServerPort));
    return element;
  }

  @Override
  public Stamp getStamp() {
    return tracker.getStamp();
  }

  @Override
  public boolean isModifiedSince(Stamp s) {
    return tracker.isModifiedSince(s);
  }

  public boolean getAutoDetectDefinitions() {
    return autoDetectDefinitions;
  }

  public void setAutoDetectDefinitions(boolean selected) {
    autoDetectDefinitions = selected;
  }
  public boolean getDetectCodeReferencesInConsole() {
    return detectCodeReferencesInConsole;
  }

  public void setDetectCodeReferencesInConsole(boolean selected) {
    detectCodeReferencesInConsole = selected;
  }

  /**
   * Whether builds and compiler completion go through a warm Haxe compilation server instead of
   * spawning a cold compiler each time.
   */
  public boolean isUseCompilationServer() {
    return useCompilationServer;
  }

  public void setUseCompilationServer(boolean useCompilationServer) {
    this.useCompilationServer = useCompilationServer;
    tracker.notifyUpdated();
  }

  /**
   * Port the compilation server listens on, or {@link #COMPILATION_SERVER_PORT_AUTO} to have a free
   * one picked at start.  Auto is the default so that several worktrees open at once cannot collide
   * on a port, or worse, silently share one server.
   */
  public int getCompilationServerPort() {
    return compilationServerPort;
  }

  public void setCompilationServerPort(int compilationServerPort) {
    this.compilationServerPort = compilationServerPort > 0 ? compilationServerPort : COMPILATION_SERVER_PORT_AUTO;
    tracker.notifyUpdated();
  }
}
