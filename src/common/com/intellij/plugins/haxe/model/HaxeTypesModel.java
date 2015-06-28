/*
 * Copyright 2000-2013 JetBrains s.r.o.
 * Copyright 2014-2015 AS3Boyan
 * Copyright 2014-2014 Elias Ku
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
package com.intellij.plugins.haxe.model;

import com.intellij.plugins.haxe.lang.psi.HaxeTypeOrAnonymous;
import com.intellij.plugins.haxe.model.type.SpecificHaxeClassReference;
import com.intellij.plugins.haxe.model.type.SpecificTypeReference;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;

public class HaxeTypesModel {
  public final HaxeProjectModel project;
  public final SpecificHaxeClassReference DYNAMIC;
  public final SpecificHaxeClassReference FLOAT;
  public final SpecificHaxeClassReference INT;
  public final SpecificHaxeClassReference BOOL;
  public final SpecificHaxeClassReference STRING;
  public final SpecificHaxeClassReference VOID;

  public HaxeTypesModel(@NotNull HaxeProjectModel project) {
    this.project = project;
    this.DYNAMIC = getPrimitive(project, "Dynamic");
    this.FLOAT = getPrimitive(project, "Float");
    this.INT = getPrimitive(project, "Int");
    this.BOOL = getPrimitive(project, "Bool");
    this.STRING = getPrimitive(project, "String");
    this.VOID = getPrimitive(project, "Void");
  }

  static private SpecificHaxeClassReference getPrimitive(@NotNull HaxeProjectModel project, String name) {
    final HaxeClassModel clazz = project.rootPackage.getHaxeClassFromFileName("StdTypes.hx", name);
    if (clazz != null) {
      return clazz.getInstanceType().getClassType();
    }
    else {
      return null;
    }
  }

  public static HaxeTypesModel fromElement(PsiElement element) {
    return HaxeProjectModel.fromElement(element).getTypes();
  }
}
