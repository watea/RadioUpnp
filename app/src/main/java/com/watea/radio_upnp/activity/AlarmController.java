/*
 * Copyright (c) 2018. Stephane Treuchot
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

import static android.content.Context.BIND_AUTO_CREATE;

import android.app.AlarmManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.TimePicker;
import android.widget.ToggleButton;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.watea.radio_upnp.R;
import com.watea.radio_upnp.model.Radio;
import com.watea.radio_upnp.service.AlarmService;

public class AlarmController {
  private static final String LOG_TAG = AlarmController.class.getSimpleName();
  @NonNull
  private final MainActivity mainActivity;
  @NonNull
  private final TimePicker timePicker;
  @NonNull
  private final ImageView imageView;
  @NonNull
  private final TextView textView;
  @NonNull
  private final ToggleButton toggleButton;
  @NonNull
  private final AlertDialog alertDialog;
  @NonNull
  private final MainActivity.UserHint batteryOptimisationUserHint;
  @Nullable
  private Radio radio = null;
  @Nullable
  private AlarmService.AlarmServiceBinder alarmService = null;
  private final ServiceConnection alarmConnection = new ServiceConnection() {
    @Override
    public void onServiceConnected(ComponentName componentName, IBinder service) {
      alarmService = (AlarmService.AlarmServiceBinder) service;
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
      alarmService = null;
    }
  };

  public AlarmController(@NonNull MainActivity mainActivity) {
    this.mainActivity = mainActivity;
    // Create view
    final View view = View.inflate(this.mainActivity, R.layout.view_alarm, null);
    timePicker = view.findViewById(R.id.timePicker);
    timePicker.setIs24HourView(true);
    toggleButton = view.findViewById(R.id.toggleButton);
    imageView = view.findViewById(R.id.imageView);
    textView = view.findViewById(R.id.text_view);
    // Build alert dialogs
    batteryOptimisationUserHint = mainActivity
      .new UserHint(R.string.key_battery_optimization_press_got_it, R.string.battery_optimization);
    alertDialog = new AlertDialog.Builder(this.mainActivity)
      .setTitle(R.string.title_alarm)
      .setIcon(R.drawable.ic_alarm_white_24dp)
      .setView(view)
      // Restore checked item
      .setOnDismissListener(dialogInterface -> this.mainActivity.checkNavigationMenu())
      .create();
  }

  public void onActivityResume() {
    // Bind to AlarmService
    if (!mainActivity.bindService(
      new Intent(mainActivity, AlarmService.class), alarmConnection, BIND_AUTO_CREATE)) {
      Log.e(LOG_TAG, "Internal failure; AlarmService not bound");
    }
  }

  public void onActivityPause() {
    mainActivity.unbindService(alarmConnection);
    // Force suspended connection
    alarmConnection.onServiceDisconnected(null);
  }

  public void launch() {
    if (alarmService == null) {
      mainActivity.tell(R.string.alarm_not_available);
      return;
    }
    final AlarmManager alarmManager = (AlarmManager) mainActivity.getSystemService(Context.ALARM_SERVICE);
    if ((Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) && (!alarmManager.canScheduleExactAlarms())) {
      mainActivity.startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM));
      mainActivity.showWarningOverlay(mainActivity.getString(R.string.alarm_not_allowed));
      return;
    }
    // Selected radio
    radio = alarmService.isStarted() ? alarmService.getRadio() : mainActivity.getLastPlayedRadio();
    final boolean isPossible = (radio != null);
    imageView.setImageBitmap(isPossible ? radio.getIcon() : mainActivity.getDefaultIcon());
    textView.setText(isPossible ? radio.getName() : mainActivity.getString(R.string.no_radio_available));
    // Init toggleButton
    toggleButton.setOnCheckedChangeListener(null);
    toggleButton.setChecked(alarmService.isStarted());
    toggleButton.setEnabled(isPossible);
    // Init timePicker
    timePicker.setOnTimeChangedListener(null);
    timePicker.setEnabled(isPossible);
    final int hour = alarmService.getHour();
    final int minute = alarmService.getMinute();
    if ((hour >= 0) && (minute >= 0)) {
      timePicker.setHour(hour);
      timePicker.setMinute(minute);
    }
    // Set listeners and launch
    if (isPossible) {
      timePicker.setOnTimeChangedListener((v, h, m) -> toggleButton.setChecked(false));
      toggleButton.setOnCheckedChangeListener((buttonView, isChecked) -> {
        if (alarmService == null) {
          mainActivity.tell(R.string.alarm_not_available);
        } else {
          if (isChecked) {
            // Notification will be shown
            if (alarmService.setAlarm(timePicker.getHour(), timePicker.getMinute(), radio.getURL().toString())) {
              batteryOptimisationUserHint.show();
            } else {
              mainActivity.showWarningOverlay(mainActivity.getString(R.string.alarm_can_not_be_set));
            }
          } else {
            alarmService.cancelAlarm();
            mainActivity.showWarningOverlay(mainActivity.getString(R.string.alarm_cancelled));
          }
        }
      });
    } else {
      mainActivity.showWarningOverlay(mainActivity.getString(R.string.no_radio_available));
    }
    alertDialog.show();
  }
}