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

package com.watea.radio_upnp.service;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

// Sleep timer; must be called on main thread
class SleepController {
  private static final Handler HANDLER = new Handler(Looper.getMainLooper());
  private final NotificationChannels notificationChannels;
  private final Listener listener;
  @Nullable
  private ScheduledExecutorService scheduler = null;

  SleepController(@NonNull NotificationChannels notificationChannels, @NonNull Listener listener) {
    this.notificationChannels = notificationChannels;
    this.listener = listener;
  }

  void set(int minutes) {
    scheduler = Executors.newScheduledThreadPool(1);
    scheduler.schedule(
      () -> HANDLER.post(() -> {
        listener.onSleepEnd();
        release();
      }),
      minutes,
      TimeUnit.MINUTES);
    scheduler.shutdown();
    listener.onSleepOn(true);
    notificationChannels.notifySleep(minutes);
  }

  void release() {
    if (scheduler != null) {
      scheduler.shutdownNow();
      scheduler = null;
      listener.onSleepOn(false);
      notificationChannels.cancelSleep();
    }
  }

  interface Listener {
    void onSleepOn(boolean isSleepOn);

    void onSleepEnd();
  }
}