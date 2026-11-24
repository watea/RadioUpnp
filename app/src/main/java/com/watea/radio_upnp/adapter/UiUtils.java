/*
 * Copyright (c) 2018-2026. Stephane Treuchot
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

package com.watea.radio_upnp.adapter;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Color;

import androidx.annotation.NonNull;

import com.watea.radio_upnp.R;

public final class UiUtils {
  private UiUtils() {
  }

  public static int getStarResource(boolean isPreferred) {
    return isPreferred ? R.drawable.ic_star_white_24dp : R.drawable.ic_star_border_white_24dp;
  }

  public static int getThemeAttributeColor(@NonNull Context context, int attr) {
    final int[] attrs = {attr};
    try (final TypedArray typedArray = context.obtainStyledAttributes(attrs)) {
      return typedArray.getColor(0, 0);
    }
  }

  static boolean hasSufficientContrast(int color1, int color2) {
    final double luminance1 = calculateRelativeLuminance(color1);
    final double luminance2 = calculateRelativeLuminance(color2);
    final double brighter = Math.max(luminance1, luminance2);
    final double darker = Math.min(luminance1, luminance2);
    final double contrastRatio = (brighter + 0.05) / (darker + 0.05);
    return (contrastRatio >= 4.5); // Minimum contrast ratio for normal text
  }

  static boolean isMoreThanHalfTransparent(int color) {
    return (Color.alpha(color) < 128);
  }

  private static double calculateRelativeLuminance(int color) {
    final double red = getSRGBComponent(color >> 16 & 0xFF);
    final double green = getSRGBComponent(color >> 8 & 0xFF);
    final double blue = getSRGBComponent(color & 0xFF);
    return (0.2126 * red + 0.7152 * green + 0.0722 * blue);
  }

  private static double getSRGBComponent(int component) {
    final double value = component / 255.0;
    return ((value <= 0.03928) ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4));
  }
}