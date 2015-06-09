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
package com.intellij.plugins.haxe.model.type;

import com.intellij.psi.PsiElement;

public abstract class SpecificTypeReference {
  static public SpecificTypeReference ensure(SpecificTypeReference clazz) {
    return (clazz != null) ? clazz : new SpecificHaxeClassReference(null, SpecificHaxeClassReference.EMPTY, null);
  }

  static public SpecificTypeReference createArray(SpecificTypeReference elementType) {
    return SpecificHaxeClassReference.withGenerics(new HaxeClassReference("Array", null), new SpecificTypeReference[]{elementType}, null);
  }

  final public boolean isUnknown() { return this.toStringWithoutConstant().equals("Unknown"); }
  final public boolean isVoid() { return this.toStringWithoutConstant().equals("Void"); }
  final public boolean isInt() { return this.toStringWithoutConstant().equals("Int"); }
  final public boolean isNumeric() { return isInt() || isFloat(); }
  final public boolean isBool() { return this.toStringWithoutConstant().equals("Bool"); }
  final public boolean isFloat() { return this.toStringWithoutConstant().equals("Float"); }
  final public boolean isString() { return this.toStringWithoutConstant().equals("String"); }
  final public boolean isArray() {
    if (this instanceof SpecificHaxeClassReference) {
      final SpecificHaxeClassReference reference = (SpecificHaxeClassReference)this;
      return reference.clazz.getName().equals("Array");
    }
    return false;
  }
  final public SpecificTypeReference getArrayElementType() {
    if (isArray()) {
      final SpecificTypeReference[] specifics = ((SpecificHaxeClassReference)this).specifics;
      if (specifics.length >= 1) return specifics[0];
    }
    return null;
  }

  abstract public SpecificTypeReference withConstantValue(Object constantValue);
  public SpecificTypeReference withoutConstantValue() {
    return withConstantValue(null);
  }
  public boolean isConstant() {
    return this.getConstant() != null;
  }
  abstract public Object getConstant();
  abstract public PsiElement getElementContext();
  abstract public String toString();
  abstract public String toStringWithoutConstant();
  public SpecificTypeReference access(String name, HaxeExpressionEvaluatorContext context) {
    return null;
  }
  final public boolean canAssign(SpecificTypeReference type2) {
    return HaxeTypeCompatible.isAssignable(this, type2);
  }
}
