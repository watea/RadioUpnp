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

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Log;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.watea.radio_upnp.R;
import com.watea.radio_upnp.model.Radio;
import com.watea.radio_upnp.model.Radios;

import org.json.JSONException;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

class ImportExportController {
  private static final String LOG_TAG = ImportExportController.class.getSimpleName();
  @NonNull
  private final MainActivity mainActivity;
  // One launcher per action, indexed by ordinal: the result callback knows its action, even after process death
  @NonNull
  private final List<ActivityResultLauncher<Intent>> launchers = new ArrayList<>();

  // Must be called before activity is started
  ImportExportController(@NonNull MainActivity mainActivity) {
    this.mainActivity = mainActivity;
    for (final Action action : Action.values()) {
      launchers.add(mainActivity.registerForActivityResult(
        new ActivityResultContracts.StartActivityForResult(),
        result -> onResult(action, result)));
    }
  }

  void launchExport() {
    showDialog(R.string.title_export, R.drawable.ic_output_white_24dp, R.string.export_message, Action.CSV_EXPORT, Action.JSON_EXPORT);
  }

  void launchImport() {
    showDialog(R.string.title_import, R.drawable.ic_exit_to_app_white_24dp, R.string.import_message, Action.CSV_IMPORT, Action.JSON_IMPORT);
  }

  // Neutral button is CSV, positive is JSON
  private void showDialog(int title, int icon, int message, @NonNull Action csvAction, @NonNull Action jsonAction) {
    new AlertDialog.Builder(mainActivity)
      .setTitle(title)
      .setIcon(icon)
      .setMessage(message)
      .setNeutralButton(R.string.action_csv_export, (dialog, which) -> launch(csvAction))
      .setPositiveButton(R.string.action_json_export, (dialog, which) -> launch(jsonAction))
      // Restore checked item
      .setOnDismissListener(dialogInterface -> mainActivity.checkNavigationMenu())
      .create()
      .show();
  }

  private void launch(@NonNull Action action) {
    final Intent intent = action.isImport ?
      new Intent(Intent.ACTION_OPEN_DOCUMENT)
        .addCategory(Intent.CATEGORY_OPENABLE)
        // Radios.MIME_CSV MIME type is not supported
        .setType((action == Action.CSV_IMPORT) ? "*/*" : action.mime) :
      new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
    try {
      launchers.get(action.ordinal()).launch(intent);
    } catch (ActivityNotFoundException activityNotFoundException) {
      mainActivity.tell(R.string.no_file_picker_found);
    }
  }

  private void onResult(@NonNull Action action, @NonNull ActivityResult result) {
    final Intent data = result.getData();
    final Uri uri = (data == null) ? null : data.getData();
    if ((result.getResultCode() == Activity.RESULT_OK) && (uri != null)) {
      if (action.isImport) {
        importFrom(uri, action.mime);
      } else {
        exportTo(uri, action.mime);
      }
    }
  }

  private void exportTo(@NonNull Uri treeUri, @NonNull String type) {
    final List<Radio> snapshot = new ArrayList<>(Radios.getInstance());
    new Thread(() -> {
      try {
        final Uri fileUri = createFileInTree(treeUri, mainActivity.getString(R.string.app_name), type);
        if (fileUri == null) {
          Log.e(LOG_TAG, "exportTo: internal failure, file not created");
          mainActivity.runSafelyOnUiThread(() -> mainActivity.tell(R.string.export_failed_not_created));
          return;
        }
        try (final OutputStream outputStream = mainActivity.getContentResolver().openOutputStream(fileUri)) {
          if (outputStream == null) {
            Log.e(LOG_TAG, "exportTo: internal failure, file not created");
            mainActivity.runSafelyOnUiThread(() -> mainActivity.tell(R.string.export_failed));
          } else {
            Radios.write(snapshot, outputStream, type);
            mainActivity.runSafelyOnUiThread(() ->
              mainActivity.tell(mainActivity.getString(R.string.export_done) + mainActivity.getString(R.string.app_name)));
          }
        }
      } catch (JSONException | IOException exception) {
        Log.e(LOG_TAG, "exportTo: internal failure", exception);
        mainActivity.runSafelyOnUiThread(() -> mainActivity.tell(R.string.export_failed));
      }
    }).start();
  }

  @Nullable
  private Uri createFileInTree(
    @NonNull Uri treeUri,
    @NonNull String fileName,
    @NonNull String type) throws FileNotFoundException {
    // Get the document ID of the tree URI
    final String docId = DocumentsContract.getTreeDocumentId(treeUri);
    // Build the document URI for the file
    final Uri docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);
    // Important: use the document URI as the parent for the new file
    return DocumentsContract.createDocument(mainActivity.getContentResolver(), docUri, type, fileName);
  }

  private void importFrom(@NonNull Uri uri, @NonNull String type) {
    new Thread(() -> {
      try (final InputStream inputStream = mainActivity.getContentResolver().openInputStream(uri)) {
        if (inputStream == null) {
          Log.e(LOG_TAG, "importFrom: internal failure");
        } else {
          Radios.getInstance().importFrom(
            Radios.MIME_JSON.equals(type),
            false,
            inputStream,
            result -> mainActivity.runSafelyOnUiThread(() ->
              mainActivity.tell(result ? R.string.import_successful : R.string.import_failed)));
        }
      } catch (Exception exception) {
        Log.e(LOG_TAG, "importFrom: I/O failure", exception);
        mainActivity.runSafelyOnUiThread(() -> mainActivity.tell(R.string.import_failed));
      }
    }).start();
  }

  private enum Action {
    JSON_IMPORT(Radios.MIME_JSON, true),
    CSV_IMPORT(Radios.MIME_CSV, true),
    JSON_EXPORT(Radios.MIME_JSON, false),
    CSV_EXPORT(Radios.MIME_CSV, false);

    @NonNull
    final String mime;
    final boolean isImport;

    Action(@NonNull String mime, boolean isImport) {
      this.mime = mime;
      this.isImport = isImport;
    }
  }
}