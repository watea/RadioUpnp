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

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.fourthline.cling.model.action.ActionInvocation;
import org.fourthline.cling.model.meta.Action;
import org.fourthline.cling.model.meta.Service;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class UpnpWatchdog {
  private static final String LOG_TAG = UpnpWatchdog.class.getName();
  private static final int DELAY = 3000; // ms
  private static final int TOLERANCE = 1;
  private static final String ACTION_GET_TRANSPORT_INFO = "GetTransportInfo";
  private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
  @Nullable
  private UpnpActionController.UpnpAction actionWatchdog = null;
  private int failureCount = 0;

  public UpnpWatchdog(
    @NonNull UpnpActionController upnpActionController,
    @Nullable Service<?, ?> avTransportService,
    @NonNull InstanceIdSupplier instanceIdSupplier,
    @NonNull Runnable callback) {
    Action<?> action;
    if ((avTransportService == null) ||
      ((action = avTransportService.getAction(ACTION_GET_TRANSPORT_INFO)) == null)) {
      callback.run();
    } else {
      actionWatchdog = new UpnpActionController.UpnpAction(upnpActionController, action) {
        @NonNull
        @Override
        public ActionInvocation<?> getActionInvocation() {
          return getActionInvocation(instanceIdSupplier.get());
        }

        @Override
        protected void success(@NonNull ActionInvocation<?> actionInvocation) {
          final String currentTransportState =
            actionInvocation.getOutput("CurrentTransportState").getValue().toString();
          if (currentTransportState.equals("TRANSITIONING") ||
            currentTransportState.equals("PLAYING")) {
            failureCount = 0;
          } else {
            logfailure("Watchdog; state not allowed: " + currentTransportState);
          }
        }

        @Override
        protected void failure() {
          logfailure("Watchdog: no answer");
        }

        private void logfailure(@NonNull String message) {
          Log.d(LOG_TAG, message);
          if (failureCount++ >= TOLERANCE) {
            callback.run();
          }
        }
      };
    }
  }

  public void start() {
    if (actionWatchdog == null) {
      Log.d(LOG_TAG, "Watchdog start failed: actionWatchdog is null");
    } else {
      executor.scheduleAtFixedRate(actionWatchdog::execute, 0, DELAY, TimeUnit.MILLISECONDS);
    }
  }

  public void kill() {
    executor.shutdown();
  }

  public interface InstanceIdSupplier {
    @NonNull
    String get();
  }
}