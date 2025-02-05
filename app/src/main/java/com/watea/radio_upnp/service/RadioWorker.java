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

package com.watea.radio_upnp.service;

import static android.content.Context.BIND_AUTO_CREATE;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

public class RadioWorker extends Worker {
  private static final String LOG_TAG = RadioWorker.class.getSimpleName();
  @NonNull
  final Context context;
  @Nullable
  private AlarmService.AlarmServiceBinder alarmServiceBinder = null;
  private final ServiceConnection alarmConnection = new ServiceConnection() {
    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
      alarmServiceBinder = (AlarmService.AlarmServiceBinder) service;
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
      alarmServiceBinder = null;
    }
  };

  public RadioWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
    super(context, workerParams);
    this.context = context;
    // Bind to UPnP service
    if (!this.context.bindService(
      new Intent(this.context, AlarmService.class), alarmConnection, BIND_AUTO_CREATE)) {
      Log.e(LOG_TAG, "Internal failure; AlarmService not bound");
    }
  }

  @NonNull
  @Override
  public Result doWork() {
    // Should not happen
    if (alarmServiceBinder == null) {
      Log.e(LOG_TAG, "doWork: alarmServiceBinder is null");
      return Result.failure();
    }
    alarmServiceBinder.launchRadio();
    return Result.success();
  }

  @Override
  public void onStopped() {
    context.unbindService(alarmConnection);
    // Force disconnection to release resources
    alarmConnection.onServiceDisconnected(null);
  }
}