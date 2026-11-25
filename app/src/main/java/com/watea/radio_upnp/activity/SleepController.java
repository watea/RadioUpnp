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

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.NumberPicker;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionCommand;

import com.watea.radio_upnp.R;
import com.watea.radio_upnp.model.Radios;
import com.watea.radio_upnp.service.RadioService;

// Sleep timer UI: duration dialog and set/cancel toggle; the timer runs in RadioService
class SleepController {
  private static final int SLEEP_MIN = 5;
  private static final int SLEEP_MAX = 90;
  private static final int REQUEST_CODE_POST_NOTIFICATIONS = 101;
  @NonNull
  private final MainActivity mainActivity;
  @NonNull
  private final SharedPreferences sharedPreferences;
  @NonNull
  private final AlertDialog alertDialog;

  @SuppressLint("InflateParams")
  SleepController(@NonNull MainActivity mainActivity) {
    this.mainActivity = mainActivity;
    sharedPreferences = Radios.getAppPreferences(mainActivity);
    final AlertDialog.Builder builder = new AlertDialog.Builder(mainActivity);
    final View view = LayoutInflater.from(builder.getContext()).inflate(R.layout.view_sleep, null);
    final NumberPicker minutePicker = view.findViewById(R.id.numberPicker);
    minutePicker.setMinValue(SLEEP_MIN);
    minutePicker.setMaxValue(SLEEP_MAX);
    minutePicker.setValue(getSleep());
    minutePicker.setOnValueChangedListener((picker, oldVal, newVal) ->
      sharedPreferences.edit().putInt(mainActivity.getString(R.string.key_sleep), newVal).apply());
    alertDialog = builder
      .setTitle(R.string.title_sleep)
      .setIcon(R.drawable.ic_hourglass_bottom_white_24dp)
      .setView(view)
      // Restore checked item
      .setOnDismissListener(dialogInterface -> mainActivity.checkNavigationMenu())
      .create();
  }

  void show() {
    if ((Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) &&
      (mainActivity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)) {
      mainActivity.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_CODE_POST_NOTIFICATIONS);
    }
    alertDialog.show();
  }

  // Cancels the timer if set, else sets it if playing
  void toggle(@NonNull MediaController mediaController, boolean isPlaying) {
    final boolean isSleepSet = mediaController.getSessionExtras().getBoolean(mainActivity.getString(R.string.key_sleep_set), false);
    if (!isSleepSet && !isPlaying) {
      return;
    }
    final int sleep = getSleep();
    final Bundle bundle = new Bundle();
    bundle.putInt(mainActivity.getString(R.string.key_sleep), sleep);
    final String action = isSleepSet ? RadioService.ACTION_SLEEP_CANCEL : RadioService.ACTION_SLEEP_SET;
    mediaController.sendCustomCommand(new SessionCommand(action, Bundle.EMPTY), bundle); // Asynchronous
    if (isSleepSet) {
      mainActivity.tell(R.string.sleep_cancelled);
    } else {
      mainActivity.tell(mainActivity.getString(R.string.sleep_set, sleep));
    }
  }

  private int getSleep() {
    return sharedPreferences.getInt(mainActivity.getString(R.string.key_sleep), SLEEP_MIN);
  }
}