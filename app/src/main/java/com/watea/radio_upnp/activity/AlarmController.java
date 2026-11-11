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

import static android.content.Context.BIND_AUTO_CREATE;

import android.app.AlarmManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.icu.text.DateFormatSymbols;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
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

import java.util.Calendar;

class AlarmController {
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
  // Index is Calendar.DAY_OF_WEEK - 1
  @NonNull
  private final ToggleButton[] dayButtons = new ToggleButton[7];
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
      alarmService.setListener(() -> {
        if (alertDialog.isShowing()) {
          toggleButton.setChecked(false);
        }
      });
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
      if (alarmService != null) {
        alarmService.setListener(null);
      }
      alarmService = null;
    }
  };

  AlarmController(@NonNull MainActivity mainActivity) {
    this.mainActivity = mainActivity;
    // Build alert dialogs
    batteryOptimisationUserHint = mainActivity
      .new UserHint(R.string.key_battery_optimization_press_got_it, R.string.battery_optimization);
    final AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(this.mainActivity);
    final View view = LayoutInflater.from(alertDialogBuilder.getContext()).inflate(R.layout.view_alarm, null);
    timePicker = view.findViewById(R.id.timePicker);
    timePicker.setIs24HourView(true);
    toggleButton = view.findViewById(R.id.toggleButton);
    imageView = view.findViewById(R.id.imageView);
    textView = view.findViewById(R.id.text_view);
    // Day buttons are labelled in locale order
    final ViewGroup daysLayout = view.findViewById(R.id.days);
    final DateFormatSymbols dateFormatSymbols = DateFormatSymbols.getInstance();
    final String[] narrowDays = dateFormatSymbols.getWeekdays(DateFormatSymbols.STANDALONE, DateFormatSymbols.NARROW);
    final String[] fullDays = dateFormatSymbols.getWeekdays();
    final int firstDay = Calendar.getInstance().getFirstDayOfWeek();
    for (int i = 0; i < daysLayout.getChildCount(); i++) {
      final int day = (firstDay - 1 + i) % 7 + 1;
      final ToggleButton dayButton = (ToggleButton) daysLayout.getChildAt(i);
      dayButton.setTextOn(narrowDays[day]);
      dayButton.setTextOff(narrowDays[day]);
      dayButton.setContentDescription(fullDays[day]);
      dayButtons[day - 1] = dayButton;
    }
    alertDialog = alertDialogBuilder
      .setTitle(R.string.title_alarm)
      .setIcon(R.drawable.ic_alarm_white_24dp)
      .setView(view)
      // Restore checked item
      .setOnDismissListener(dialogInterface -> this.mainActivity.checkNavigationMenu())
      .create();
  }

  void launch() {
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
    radio = alarmService.isStarted() ? alarmService.getRadio() : mainActivity.getLastPlayedRadio();
    final boolean isPossible = (radio != null);
    imageView.setImageBitmap(isPossible ? radio.getIcon() : mainActivity.getDefaultIcon());
    textView.setText(isPossible ? radio.getName() : mainActivity.getString(R.string.no_radio_available));
    toggleButton.setOnCheckedChangeListener(null);
    toggleButton.setChecked(alarmService.isStarted());
    final int days = alarmService.getDays();
    for (int i = 0; i < dayButtons.length; i++) {
      dayButtons[i].setOnCheckedChangeListener(null);
      dayButtons[i].setChecked((days & (1 << i)) != 0);
      dayButtons[i].setEnabled(isPossible);
    }
    toggleButton.setEnabled(isPossible && (days != 0));
    timePicker.setOnTimeChangedListener(null);
    // Drop any uncommitted keyboard entry left from previous display
    timePicker.clearFocus();
    timePicker.setEnabled(isPossible);
    final int hour = alarmService.getHour();
    final int minute = alarmService.getMinute();
    if ((hour >= 0) && (minute >= 0)) {
      timePicker.setHour(hour);
      timePicker.setMinute(minute);
    }
    if (isPossible) {
      final TimePicker.OnTimeChangedListener onTimeChangedListener = (v, h, m) -> toggleButton.setChecked(false);
      timePicker.setOnTimeChangedListener(onTimeChangedListener);
      for (final ToggleButton dayButton : dayButtons) {
        dayButton.setOnCheckedChangeListener((buttonView, isChecked) -> {
          toggleButton.setChecked(false);
          toggleButton.setEnabled(getDays() != 0);
        });
      }
      toggleButton.setOnCheckedChangeListener((buttonView, isChecked) -> {
        if (alarmService == null) {
          mainActivity.tell(R.string.alarm_not_available);
        } else if (isChecked) {
          assert radio != null;
          // Commit keyboard entry; listener detached so that it does not uncheck
          timePicker.setOnTimeChangedListener(null);
          timePicker.clearFocus();
          timePicker.setOnTimeChangedListener(onTimeChangedListener);
          if (alarmService.setAlarm(timePicker.getHour(), timePicker.getMinute(), getDays(), radio.getId())) {
            batteryOptimisationUserHint.show();
          } else {
            mainActivity.showWarningOverlay(mainActivity.getString(R.string.alarm_can_not_be_set));
          }
        } else {
          alarmService.cancelAlarm();
          mainActivity.showWarningOverlay(mainActivity.getString(R.string.alarm_cancelled));
        }
      });
    } else {
      mainActivity.showWarningOverlay(mainActivity.getString(R.string.no_radio_available));
    }
    alertDialog.show();
  }

  void onActivityStart() {
    if (!mainActivity.bindService(new Intent(mainActivity, AlarmService.class), alarmConnection, BIND_AUTO_CREATE)) {
      Log.e(LOG_TAG, "Internal failure; AlarmService not bound");
    }
  }

  void onActivityStop() {
    mainActivity.unbindService(alarmConnection);
    // Force suspended connection
    alarmConnection.onServiceDisconnected(null);
  }

  // Bit index is Calendar.DAY_OF_WEEK - 1
  private int getDays() {
    int days = 0;
    for (int i = 0; i < dayButtons.length; i++) {
      if (dayButtons[i].isChecked()) {
        days |= 1 << i;
      }
    }
    return days;
  }
}