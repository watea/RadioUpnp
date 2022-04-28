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

import java.net.MalformedURLException;
import java.util.List;
import java.util.Vector;

public class RadioLibrary {
  private static final String LOG_TAG = RadioLibrary.class.getName();
  private static final int ICON_SIZE = 300;
  private static final String SPACER = "#";
  private final List<Listener> listeners = new Vector<>();
  @NonNull
  private final SQLiteDatabase radioDataBase;
  @NonNull
  private final Context context;
  // Current managed radio
  @Nullable
  private Long currentRadioId;

  public RadioLibrary(@NonNull Context context) {
    this.context = context;
    radioDataBase = new RadioDbSQLHelper(this.context).getWritableDatabase();
  }

  public boolean isCurrentRadio(@NonNull Radio radio) {
    return radio.getId().equals(currentRadioId);
  }

  public void close() {
    radioDataBase.close();
  }

  public boolean isOpen() {
    return radioDataBase.isOpen();
  }

  public boolean updateFrom(@NonNull Long radioId, @NonNull ContentValues values) {
    return (radioDataBase.update(
      // The table to query
      RadioSQLContract.Columns.TABLE_RADIO,
      // Values for columns
      values,
      // The columns for the WHERE clause
      RadioSQLContract.Columns._ID + " = ?",
      // The values for the WHERE clause
      new String[]{radioId.toString()}) > 0);
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
    int columnIndex = cursor.getColumnIndex(RadioSQLContract.Columns.COLUMN_POSITION);
    assert columnIndex >= 0;
    int position = cursor.moveToNext() ? cursor.getInt(columnIndex) : 0;
    cursor.close();
    return position;
  }

  public boolean deleteFrom(@NonNull Long radioId) {
    return (radioDataBase.delete(
      // The table to query
      RadioSQLContract.Columns.TABLE_RADIO,
      // The columns for the WHERE clause
      RadioSQLContract.Columns._ID + " = ?",
      // The values for the WHERE clause
      new String[]{radioId.toString()}) > 0);
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

  // Add a radio and store according icon
  public boolean add(@NonNull Radio radio) {
    ContentValues contentValues = radio.toContentValues();
    // Position = last
    contentValues.put(RadioSQLContract.Columns.COLUMN_POSITION, getMaxPosition() + 1);
    radio.setId(radioDataBase.insert(RadioSQLContract.Columns.TABLE_RADIO, null, contentValues));
    // Success? => store icon file
    if ((radio.getId() >= 0) && radio.storeIcon(context)) {
      contentValues.clear();
      contentValues.put(RadioSQLContract.Columns.COLUMN_ICON, radio.getIconFile().getPath());
      return updateFrom(radio.getId(), contentValues);
    }
    return false;
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
    if (updateFrom(radioId, values)) {
      Radio radio = getFrom(radioId);
      assert radio != null;
      tellListeners(listener -> listener.onPreferredChange(radio));
      return true;
    }
    return false;
  }

  public boolean move(@NonNull Long fromRadioId, @NonNull Long toRadioId) {
    ContentValues fromPosition = positionContentValuesOf(fromRadioId);
    ContentValues toPosition = positionContentValuesOf(toRadioId);
    return updateFrom(fromRadioId, toPosition) && updateFrom(toRadioId, fromPosition);
  }

  public void addListener(@NonNull Listener listener) {
    listeners.add(listener);
  }

  public void removeListener(@NonNull Listener listener) {
    listeners.remove(listener);
  }

  @Nullable
  public Radio getCurrentRadio() {
    return (currentRadioId == null) ? null : getFrom(currentRadioId);
  }

  // Null if no radio
  public void setCurrentRadio(@Nullable MediaMetadataCompat metadata) {
    String id = (metadata == null) ? null : metadata.getDescription().getMediaId();
    currentRadioId = (id == null) ? null : Long.valueOf(id);
    tellListeners(listener -> listener.onNewCurrentRadio(getCurrentRadio()));
  }

  @NonNull
  public String marshall(boolean textOnly) {
    StringBuilder result = new StringBuilder().append(textOnly ? Radio.MARSHALL_HEAD + "\n" : "");
    for (Long id : getAllRadioIds()) {
      Radio radio = getFrom(id);
      assert radio != null;
      result.append(radio.marshall(textOnly)).append(textOnly ? "\n" : SPACER);
    }
    return result.toString();
  }

  // Symmetrical from export().
  // Returns true if some radios have been imported.
  public boolean importFrom(@NonNull String importString) {
    boolean result = false;
    if (!importString.isEmpty()) {
      for (String radioString : importString.split(SPACER)) {
        try {
          result = add(new Radio(radioString)) || result;
        } catch (MalformedURLException malformedURLException) {
          Log.e(LOG_TAG, "importFrom: a radio failed to be imported", malformedURLException);
        }
      }
      if (result) {
        tellListeners(Listener::onRefresh);
      }
    }
    return result;
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
    int columnIndex = cursor.getColumnIndex(maxPosition);
    assert columnIndex >= 0;
    int position = cursor.moveToNext() ? cursor.getInt(columnIndex) : 0;
    cursor.close();
    return position;
  }

  private void tellListeners(@NonNull Consumer<Listener> consumer) {
    for (Listener listener : listeners) {
      consumer.accept(listener);
    }
  }

  public interface Listener {
    default void onPreferredChange(@NonNull Radio radio) {
    }

    default void onNewCurrentRadio(@Nullable Radio radio) {
    }

    default void onRefresh() {
    }
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