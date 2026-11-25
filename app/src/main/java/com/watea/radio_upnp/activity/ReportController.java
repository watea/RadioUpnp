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

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.content.FileProvider;

import com.watea.radio_upnp.BuildConfig;
import com.watea.radio_upnp.R;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.Calendar;

// Mails logcat and device info to the developer
class ReportController {
  private static final String LOG_TAG = ReportController.class.getSimpleName();
  @NonNull
  private final MainActivity mainActivity;

  ReportController(@NonNull MainActivity mainActivity) {
    this.mainActivity = mainActivity;
  }

  @NonNull
  static Intent getNewSendIntent() {
    return new Intent(Intent.ACTION_SEND)
      .setType("message/rfc822")
      .putExtra(Intent.EXTRA_EMAIL, new String[]{"fr.watea@gmail.com"});
  }

  void send() {
    new Thread(() -> {
      final File logFile = new File(mainActivity.getFilesDir(), "logcat.txt");
      final String[] command = {"logcat", "-d", "-b", "all", "-v", "threadtime", "*:V"};
      try {
        final Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try {
          try (final PrintWriter writer = new PrintWriter(new FileOutputStream(logFile));
               final BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            // --- Device info ---
            writer.println("===== DEVICE INFO =====");
            writer.println("App version : " + BuildConfig.VERSION_NAME);
            writer.println("Android     : " + Build.VERSION.RELEASE);
            writer.println("SDK         : " + Build.VERSION.SDK_INT);
            writer.println("Device      : " + Build.MANUFACTURER + " " + Build.MODEL);
            writer.println("Brand       : " + Build.BRAND);
            writer.println("Board       : " + Build.BOARD);
            writer.println("========================");
            writer.println();
            // --- Logcat ---
            String line;
            while ((line = reader.readLine()) != null) {
              writer.println(line);
            }
          }
          process.waitFor();
        } finally {
          process.destroy();
        }
        if (logFile.length() > 0) {
          mainActivity.runSafelyOnUiThread(() -> {
            try {
              mainActivity.startActivity(getNewSendIntent(logFile));
            } catch (Exception exception) {
              Log.e(LOG_TAG, "send failure", exception);
              tellError();
            }
          });
        } else {
          mainActivity.runSafelyOnUiThread(this::tellError);
        }
      } catch (Exception exception) {
        Log.e(LOG_TAG, "send failure", exception);
        mainActivity.runSafelyOnUiThread(this::tellError);
      }
    }).start();
  }

  private void tellError() {
    mainActivity.tell(R.string.report_error);
    mainActivity.checkNavigationMenu();
  }

  @NonNull
  private Intent getNewSendIntent(@NonNull File logFile) {
    final Uri logFileUri = FileProvider.getUriForFile(mainActivity, mainActivity.getPackageName() + ".fileprovider", logFile);
    return getNewSendIntent()
      .putExtra(Intent.EXTRA_SUBJECT, "RadioUPnP report " + BuildConfig.VERSION_NAME + " / " + Calendar.getInstance().getTime())
      .putExtra(Intent.EXTRA_STREAM, logFileUri)
      .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
  }
}