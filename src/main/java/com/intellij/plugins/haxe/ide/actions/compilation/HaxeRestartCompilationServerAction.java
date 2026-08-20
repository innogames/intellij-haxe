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
import com.intellij.openapi.project.Project;
import com.intellij.plugins.haxe.HaxeBundle;
import com.intellij.plugins.haxe.compilation.server.HaxeCompilationServerService;
import com.intellij.plugins.haxe.config.HaxeProjectSettings;
import org.jetbrains.annotations.NotNull;

/**
 * Escape hatch for a stale compilation server.  Module reuse can keep the side effects of build
 * macros around longer than they should be, and this is how a developer throws that state away
 * without restarting the IDE.
 */
public class HaxeRestartCompilationServerAction extends AnAction {

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
    HaxeCompilationServerService.getInstance(project).restart();
    HaxeCompilationServerNotifier.info(project, HaxeBundle.message("haxe.compilation.server.action.restart.done"));
  }
}
