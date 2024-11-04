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

package com.watea.radio_upnp.upnp;

import android.util.Log;

import androidx.annotation.NonNull;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public abstract class Watchdog {
  private static final String LOG_TAG = Watchdog.class.getSimpleName();
  private static final int DELAY = 5000; // ms
  private static final int TOLERANCE = 2;
  private static final String ACTION_GET_TRANSPORT_INFO = "GetTransportInfo";
  private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
  @NonNull
  private final ActionController actionController;
  private final Action action;
  private int failureCount = 0;

  public Watchdog(
    @NonNull ActionController actionController,
    @NonNull Service avTransportService) {
    this.actionController = actionController;
    action = avTransportService.getAction(ACTION_GET_TRANSPORT_INFO);
    if (action == null) {
      onEvent(ReaderState.FAILURE);
    }
  }

  public void start(@NonNull String instanceId) {
    if (action == null) {
      Log.e(LOG_TAG, "Watchdog start failed: actionWatchdog is null");
    } else {
      // May fail
      try {
        executor.scheduleWithFixedDelay(
          () -> executeActionWatchdog(instanceId), 0, DELAY, TimeUnit.MILLISECONDS);
      } catch (Exception exception) {
        Log.e(LOG_TAG, "Watchdog could not be started", exception);
      }
    }
  }

  public void kill() {
    executor.shutdown();
  }

  public abstract void onEvent(@NonNull ReaderState readerState);

  private void executeActionWatchdog(@NonNull String instanceId) {
    new UpnpAction(action, actionController, instanceId) {
      @Override
      protected void onSuccess() {
        final String currentTransportState = getResponse("CurrentTransportState");
        if ((currentTransportState != null) &&
          (currentTransportState.equals("TRANSITIONING") ||
            currentTransportState.equals("PLAYING"))) {
          failureCount = 0;
          onEvent(ReaderState.PLAYING);
        } else {
          logfailure("Watchdog; state is not PLAYING: " + currentTransportState);
        }
      }

      @Override
      protected void onFailure() {
        logfailure("Watchdog: no answer");
      }

      private void logfailure(@NonNull String message) {
        Log.d(LOG_TAG, message);
        if (failureCount++ >= TOLERANCE) {
          Log.d(LOG_TAG, "Watchdog: timeout!");
          onEvent(ReaderState.TIMEOUT);
        }
      }
    }.execute(true);
  }

  public enum ReaderState {
    FAILURE, TIMEOUT, PLAYING
  }
}