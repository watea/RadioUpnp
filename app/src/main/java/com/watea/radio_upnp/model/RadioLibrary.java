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

package com.watea.radio_upnp.model;

import static com.watea.radio_upnp.model.RadioSQLContract.SQL_CREATE_ENTRIES;
import static com.watea.radio_upnp.model.RadioSQLContract.SQL_DELETE_ENTRIES;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.util.List;
import java.util.Vector;

public class RadioLibrary {
  private static final String LOG_TAG = RadioLibrary.class.getName();
  private static final int ICON_SIZE = 300;
  private final List<Listener> listeners = new Vector<>();
  @NonNull
  private final SQLiteDatabase radioDataBase;
  @NonNull
  private final Context context;

  public RadioLibrary(@NonNull Context context) {
    this.context = context;
    radioDataBase = new RadioDbSQLHelper(this.context).getWritableDatabase();
  }

  public void close() {
    radioDataBase.close();
  }

  public int updateFrom(@NonNull Long radioId, @NonNull ContentValues values) {
    return radioDataBase.update(
      // The table to query
      RadioSQLContract.Columns.TABLE_RADIO,
      // Values for columns
      values,
      // The columns for the WHERE clause
      RadioSQLContract.Columns._ID + " = ?",
      // The values for the WHERE clause
      new String[]{radioId.toString()});
  }

  @Nullable
  public Radio getFrom(@NonNull Long radioId) {
    Cursor cursor = radioDataBase.query(
      // The table to query
      RadioSQLContract.Columns.TABLE_RADIO,
      // The columns to return
      null,
      // The columns for the WHERE clause
      RadioSQLContract.Columns._ID + " = ?",
      // The values for the WHERE clause
      new String[]{radioId.toString()},
      // don't group the rows
      null,
      // don't filter by row groups
      null,
      // The sort order
      null);
    Radio radio = cursor.moveToNext() ? new Radio(cursor) : null;
    cursor.close();
    return radio;
  }

  @Nullable
  public Radio getFrom(@Nullable MediaMetadataCompat metadata) {
    if (metadata == null) {
      return null;
    } else {
      String id = metadata.getDescription().getMediaId();
      return (id == null) ? null : getFrom(Long.valueOf(id));
    }
  }

  public int getPositionFrom(@NonNull Long radioId) {
    Cursor cursor = radioDataBase.query(
      // The table to query
      RadioSQLContract.Columns.TABLE_RADIO,
      // The columns to return
      new String[]{RadioSQLContract.Columns.COLUMN_POSITION},
      // The columns for the WHERE clause
      RadioSQLContract.Columns._ID + " = ?",
      // The values for the WHERE clause
      new String[]{radioId.toString()},
      // don't group the rows
      null,
      // don't filter by row groups
      null,
      // The sort order
      null);
    int position = cursor.moveToNext() ?
      cursor.getInt(cursor.getColumnIndex(RadioSQLContract.Columns.COLUMN_POSITION)) : 0;
    cursor.close();
    return position;
  }

  public int deleteFrom(@NonNull Long radioId) {
    return radioDataBase.delete(
      // The table to query
      RadioSQLContract.Columns.TABLE_RADIO,
      // The columns for the WHERE clause
      RadioSQLContract.Columns._ID + " = ?",
      // The values for the WHERE clause
      new String[]{radioId.toString()});
  }

  @NonNull
  public List<Long> getAllRadioIds() {
    return cursorToIdListAndClose(allIdsQuery());
  }

  @NonNull
  public List<Long> getPreferredRadioIds() {
    return cursorToIdListAndClose(preferredIdsQuery());
  }

  @NonNull
  public Bitmap resourceToBitmap(int resource) {
    Bitmap b = BitmapFactory.decodeResource(context.getResources(), resource);
    return Bitmap.createScaledBitmap(b, ICON_SIZE, ICON_SIZE, false);
  }

  // Store bitmap as filename.png
  @NonNull
  public File bitmapToFile(@NonNull Bitmap bitmap, @NonNull String fileName)
    throws FileNotFoundException, RuntimeException {
    fileName = fileName + ".png";
    FileOutputStream fileOutputStream = context.openFileOutput(fileName, Context.MODE_PRIVATE);
    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 0, fileOutputStream)) {
      Log.e(LOG_TAG, "bitmapToFile: internal failure");
      throw new RuntimeException();
    }
    return new File(context.getFilesDir().getPath() + "/" + fileName);
  }

  // Add a radio with id as icon file name
  @NonNull
  public Long insertAndSaveIcon(@NonNull Radio radio, @NonNull Bitmap icon)
    throws FileNotFoundException, RuntimeException {
    // Store radio in database
    Long radioId = insert(radio);
    if (radioId >= 0) {
      ContentValues contentValues = new ContentValues();
      // RadioId is used as file id for bitmap file
      contentValues.put(
        RadioSQLContract.Columns.COLUMN_ICON,
        bitmapToFile(icon, radioId.toString()).getPath());
      // Store file name in database
      if (updateFrom(radioId, contentValues) <= 0) {
        Log.e(LOG_TAG, "insertAndSaveIcon: internal failure");
        throw new RuntimeException();
      }
    }
    return radioId;
  }

  public void setRadioIconFile(@NonNull Radio radio, @NonNull Bitmap icon)
    throws FileNotFoundException, RuntimeException {
    bitmapToFile(icon, radio.getId().toString());
  }

  @NonNull
  public String getRoot() {
    return "root";
  }

  @NonNull
  public List<MediaBrowserCompat.MediaItem> getMediaItems() throws RuntimeException {
    List<MediaBrowserCompat.MediaItem> result = new Vector<>();
    Cursor cursor = allIdsQuery();
    int idColumnIndex = cursor.getColumnIndexOrThrow(RadioSQLContract.Columns._ID);
    while (cursor.moveToNext()) {
      Radio radio = getFrom(cursor.getLong(idColumnIndex));
      if (radio == null) {
        Log.e(LOG_TAG, "getMediaItems: internal failure");
        throw new RuntimeException();
      }
      result.add(new MediaBrowserCompat.MediaItem(
        radio.getMediaMetadataBuilder().build().getDescription(),
        MediaBrowserCompat.MediaItem.FLAG_PLAYABLE));
    }
    cursor.close();
    return result;
  }

  @Nullable
  public Long get(@NonNull Long radioId, boolean isPreferred, int direction) {
    List<Long> ids = isPreferred ? getPreferredRadioIds() : getAllRadioIds();
    return ids.contains(radioId) ?
      ids.get((ids.size() + ids.indexOf(radioId) + direction) % ids.size()) :
      null;
  }

  public boolean setPreferred(@NonNull Long radioId, @NonNull Boolean isPreferred) {
    ContentValues values = new ContentValues();
    values.put(RadioSQLContract.Columns.COLUMN_IS_PREFERRED, isPreferred.toString());
    boolean result = (updateFrom(radioId, values) > 0);
    if (result) {
      for (Listener listener : listeners) {
        listener.onPreferredChange(radioId, isPreferred);
      }
    }
    return result;
  }

  public boolean move(@NonNull Long fromRadioId, @NonNull Long toRadioId) {
    ContentValues fromPosition = positionContentValuesOf(fromRadioId);
    ContentValues toPosition = positionContentValuesOf(toRadioId);
    return (updateFrom(fromRadioId, toPosition) > 0) && (updateFrom(toRadioId, fromPosition) > 0);
  }

  public void addListener(@NonNull Listener listener) {
    listeners.add(listener);
  }

  // Utility for database update of radio position
  @NonNull
  private ContentValues positionContentValuesOf(@NonNull Long radioId) {
    ContentValues contentValues = new ContentValues();
    contentValues.put(RadioSQLContract.Columns.COLUMN_POSITION, getPositionFrom(radioId));
    return contentValues;
  }

  @NonNull
  private List<Long> cursorToIdListAndClose(@NonNull Cursor cursor) {
    int idColumnIndex = cursor.getColumnIndexOrThrow(RadioSQLContract.Columns._ID);
    List<Long> radioIds = new Vector<>();
    while (cursor.moveToNext()) {
      radioIds.add(cursor.getLong(idColumnIndex));
    }
    cursor.close();
    return radioIds;
  }

  @NonNull
  private Cursor allIdsQuery() {
    return radioDataBase.query(
      // The table to query
      RadioSQLContract.Columns.TABLE_RADIO,
      // The columns to return
      new String[]{RadioSQLContract.Columns._ID},
      // The columns for the WHERE clause
      null,
      // The values for the WHERE clause
      null,
      // don't group the rows
      null,
      // don't filter by row groups
      null,
      // The sort order
      RadioSQLContract.Columns.COLUMN_POSITION + " ASC");
  }

  @NonNull
  private Cursor preferredIdsQuery() {
    return radioDataBase.query(
      // The table to query
      RadioSQLContract.Columns.TABLE_RADIO,
      // The columns to return
      new String[]{RadioSQLContract.Columns._ID},
      // The columns for the WHERE clause
      RadioSQLContract.Columns.COLUMN_IS_PREFERRED + " = ?",
      // The values for the WHERE clause
      new String[]{Boolean.toString(true)},
      // don't group the rows
      null,
      // don't filter by row groups
      null,
      // The sort order
      RadioSQLContract.Columns.COLUMN_POSITION + " ASC");
  }

  private int getMaxPosition() {
    String maxPosition = "MAX(" + RadioSQLContract.Columns.COLUMN_POSITION + ")";
    Cursor cursor = radioDataBase.query(
      // The table to query
      RadioSQLContract.Columns.TABLE_RADIO,
      // The columns to return
      new String[]{maxPosition},
      // The columns for the WHERE clause
      null,
      // The values for the WHERE clause
      null,
      // don't group the rows
      null,
      // don't filter by row groups
      null,
      // The sort order
      null);
    int position = cursor.moveToNext() ? cursor.getInt(cursor.getColumnIndex(maxPosition)) : 0;
    cursor.close();
    return position;
  }

  private long insert(@NonNull Radio radio) {
    ContentValues contentValues = radio.toContentValues();
    // Position = last
    contentValues.put(RadioSQLContract.Columns.COLUMN_POSITION, getMaxPosition() + 1);
    return radioDataBase.insert(RadioSQLContract.Columns.TABLE_RADIO, null, contentValues);
  }

  public interface Listener {
    void onPreferredChange(@NonNull Long radioId, @NonNull Boolean isPreferred);
  }

  private static class RadioDbSQLHelper extends SQLiteOpenHelper {
    private static final int DATABASE_VERSION = 1;
    private static final String DATABASE_NAME = "Radio.db";

    private RadioDbSQLHelper(@NonNull Context context) {
      super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    public void onCreate(@NonNull SQLiteDatabase db) {
      db.execSQL(SQL_CREATE_ENTRIES);
    }

    public void onUpgrade(@NonNull SQLiteDatabase db, int oldVersion, int newVersion) {
      // No version management
      db.execSQL(SQL_DELETE_ENTRIES);
      onCreate(db);
    }

    public void onDowngrade(@NonNull SQLiteDatabase db, int oldVersion, int newVersion) {
      onUpgrade(db, oldVersion, newVersion);
    }
  }
}