/*
 * Copyright (c) 2024-2026. Stephane Treuchot
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies
 * of the Software, and to permit persons to whom the Software is furnished to
 * do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
 * OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */

package com.watea.radio_upnp.upnp;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

// <serviceStateTable>
//  <stateVariable sendEvents="no">
//    <name>stateVariableName</name>
//    <dataType>ui2</dataType>
//    <allowedValueRange>
//      <minimum>0</minimum>
//      <maximum>100</maximum>
//      <step>1</step>
//    </allowedValueRange>
//  </stateVariable>
//  Declarations for other state variables (if any) go here
// </serviceStateTable>
public class StateVariable extends Asset {
  public static final String XML_NAME = "stateVariable";
  @Nullable
  private String name = null;
  @Nullable
  private Integer minimum = null;
  @Nullable
  private Integer maximum = null;

  @Override
  public void endAccept(@NonNull URLService uRLService, @NonNull String currentTag) {
    if (currentTag.equals(XML_NAME)) {
      name = uRLService.getTag("name");
      minimum = parseInt(uRLService.getTag("minimum"));
      maximum = parseInt(uRLService.getTag("maximum"));
      // No more tags for StateVariable
      uRLService.clearTags();
    }
  }

  @Nullable
  public String getName() {
    return name;
  }

  // Native allowed range, when declared; false if this state variable has no allowedValueRange
  public boolean hasRange() {
    return (minimum != null) && (maximum != null) && (maximum > minimum);
  }

  public int getMinimum() {
    return (minimum == null) ? 0 : minimum;
  }

  public int getMaximum() {
    return (maximum == null) ? 0 : maximum;
  }

  @Nullable
  private Integer parseInt(@Nullable String value) {
    try {
      return (value == null) ? null : Integer.valueOf(value);
    } catch (NumberFormatException numberFormatException) {
      return null;
    }
  }
}