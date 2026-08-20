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

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.project.Project;
import com.intellij.plugins.haxe.HaxeBundle;
import org.jetbrains.annotations.NotNull;

/** Balloons for the compilation server actions. */
public class HaxeCompilationServerNotifier {

  static final String GROUP_ID = "haxe.compilation.server";

  private HaxeCompilationServerNotifier() {
  }

  static void info(@NotNull Project project, @NotNull String message) {
    NotificationGroupManager.getInstance()
      .getNotificationGroup(GROUP_ID)
      .createNotification(message, NotificationType.INFORMATION)
      .setTitle(HaxeBundle.message("haxe.compilation.server.notification.group"))
      .notify(project);
  }
}
