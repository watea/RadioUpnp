/*
 * Copyright (c) 2026. Stephane Treuchot
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
import android.util.Log;

import androidx.annotation.NonNull;

import java.util.function.Consumer;

public class Watchdog {
  private static final String LOG_TAG = Watchdog.class.getSimpleName();
  private final Handler handler = new Handler(Looper.getMainLooper());
  @NonNull
  private final Runnable runnable;
  private final int timeoutS;

  public Watchdog(@NonNull Consumer<String> consumer, @NonNull String lockKey, int timeoutS) {
    this.runnable = () -> {
      Log.d(LOG_TAG, "Watchdog fired for " + lockKey);
      consumer.accept(lockKey);
    };
    this.timeoutS = timeoutS;
    launch();
  }

  public void relaunch() {
    cancel();
    launch();
  }

  public void cancel() {
    handler.removeCallbacks(runnable);
  }

  public void launch() {
    handler.postDelayed(runnable, timeoutS * 1000L);
  }
}