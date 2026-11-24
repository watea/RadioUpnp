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

package com.watea.radio_upnp.activity;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;

import com.watea.radio_upnp.R;
import com.watea.radio_upnp.model.Radios;

class UserHint {
  @NonNull
  private final AlertDialog alertDialog;
  private final int delay;
  private boolean gotIt;
  private int count = 0;

  // Context must be an activity (dialog)
  UserHint(@NonNull Context context, int key, int message, int delay) {
    this.delay = delay;
    final SharedPreferences sharedPreferences = Radios.getAppPreferences(context);
    final String keyString = context.getString(key);
    gotIt = sharedPreferences.getBoolean(keyString, false);
    alertDialog = new AlertDialog.Builder(context)
      .setMessage(message)
      .setPositiveButton(
        R.string.action_got_it,
        (dialogInterface, i) -> sharedPreferences.edit().putBoolean(keyString, (gotIt = true)).apply())
      .create();
  }

  UserHint(@NonNull Context context, int key, int message) {
    this(context, key, message, 0);
  }

  void show() {
    count = (count < delay) ? count + 1 : count;
    if (!alertDialog.isShowing() && !gotIt && (count >= delay)) {
      alertDialog.show();
    }
  }
}