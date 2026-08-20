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
package com.intellij.plugins.haxe.ide.actions.compilation;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.Project;
import com.intellij.plugins.haxe.HaxeBundle;
import com.intellij.plugins.haxe.compilation.server.HaxeCompilationServerService;
import com.intellij.plugins.haxe.config.HaxeProjectSettings;
import org.jetbrains.annotations.NotNull;

import java.awt.datatransfer.StringSelection;

/**
 * Hands the running server's port to the clipboard, for pointing a terminal build or a script at
 * the same warm server.  The port is also in {@code .idea/haxe-compilation-server.port} and in the
 * command line echoed to the Build window; this just saves looking it up.
 */
public class HaxeCopyCompilationServerPortAction extends AnAction {

  @Override
  public @NotNull ActionUpdateThread getActionUpdateThread() {
    return ActionUpdateThread.BGT;
  }

  @Override
  public void update(@NotNull AnActionEvent e) {
    Project project = e.getProject();
    e.getPresentation().setEnabledAndVisible(
      project != null && HaxeProjectSettings.getInstance(project).isUseCompilationServer());
  }

  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    Project project = e.getProject();
    if (project == null) {
      return;
    }
    Integer port = HaxeCompilationServerService.getInstance(project).getMostRecentlyUsedPort();
    if (port == null) {
      HaxeCompilationServerNotifier.info(project, HaxeBundle.message("haxe.compilation.server.action.copy.port.none"));
      return;
    }
    CopyPasteManager.getInstance().setContents(new StringSelection(String.valueOf(port)));
    HaxeCompilationServerNotifier.info(
      project, HaxeBundle.message("haxe.compilation.server.action.copy.port.done", String.valueOf(port)));
  }
}
