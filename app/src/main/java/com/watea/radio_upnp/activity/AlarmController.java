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

import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.util.Log;
import android.view.View;
import android.widget.TimePicker;
import android.widget.ToggleButton;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import com.watea.radio_upnp.R;
import com.watea.radio_upnp.service.AlarmService;

import java.util.List;
import java.util.concurrent.ExecutionException;

public class AlarmController {
  private static final String LOG_TAG = AlarmController.class.getSimpleName();
  @NonNull
  private final MainActivity mainActivity;
  @NonNull
  private final TimePicker timePicker;
  @NonNull
  private final AlertDialog alertDialog;
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
    final View view = View.inflate(this.mainActivity, R.layout.view_alarm, null);
    alertDialog = new AlertDialog.Builder(mainActivity)
      .setTitle(R.string.title_alarm)
      .setIcon(R.drawable.ic_baseline_alarm_white_24dp)
      .setView(view)
      // Restore checked item
      .setOnDismissListener(dialogInterface -> this.mainActivity.checkNavigationMenu())
      .create();
    // Get views
    timePicker = view.findViewById(R.id.timePicker);
    timePicker.setIs24HourView(true);
    final ToggleButton toggleButton = view.findViewById(R.id.toggleButton);
    assert toggleButton != null;
    timePicker.setOnTimeChangedListener((v, h, m) -> toggleButton.setChecked(false));
    toggleButton.setChecked(isAlarmSet());
    toggleButton.setOnCheckedChangeListener((buttonView, isChecked) -> {
      if (alarmService == null) {
        mainActivity.tell(R.string.alarm_not_available);
        return;
      }
      if (isChecked) {
        alarmService.setAlarm(timePicker.getHour(), timePicker.getMinute());
      } else {
        alarmService.cancelAlarm();
      }
    });
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
    } else {
      final int hour = alarmService.getHour();
      final int minute = alarmService.getMinute();
      if ((hour >= 0) && (minute >= 0)) {
        timePicker.setHour(hour);
        timePicker.setMinute(minute);
      }
      alertDialog.show();
    }
  }

  private boolean isAlarmSet() {
    try {
      final List<WorkInfo> workInfos = WorkManager.getInstance(mainActivity).getWorkInfosByTag(LOG_TAG).get();
      return workInfos.stream().anyMatch(workInfo -> (workInfo.getState() == WorkInfo.State.ENQUEUED) || (workInfo.getState() == WorkInfo.State.RUNNING));
    } catch (ExecutionException | InterruptedException exception) {
      Log.e(LOG_TAG, "isAlarmSet failed", exception);
    }
    return false;
  }
}