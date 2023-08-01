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

package com.watea.radio_upnp.model.legacy;

import static com.watea.radio_upnp.model.legacy.RadioSQLContract.SQL_CREATE_ENTRIES;
import static com.watea.radio_upnp.model.legacy.RadioSQLContract.SQL_DELETE_ENTRIES;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.support.v4.media.MediaMetadataCompat;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.net.MalformedURLException;
import java.util.List;
import java.util.Vector;
import java.util.function.Consumer;
import java.util.function.Function;

public class RadioLibrary {
  private static final String LOG_TAG = RadioLibrary.class.getName();
  private static final String SPACER = "#";
  private final List<Listener> listeners = new Vector<>();
  @NonNull
  private final SQLiteDatabase radioDataBase;
  @NonNull
  private final Context context;
  // Current managed radio
  @Nullable
  private Long currentRadioId = null;

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
    final Cursor cursor = radioDataBase.query(
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
    Radio radio = null;
    try {
      if (cursor.moveToNext()) {
        radio = new Radio(cursor);
      }
    } catch (MalformedURLException malformedURLException) {
      Log.e(LOG_TAG, "getFrom: internal failure", malformedURLException);
    }
    cursor.close();
    return radio;
  }

  public int getPositionFrom(@NonNull Long radioId) {
    final Cursor cursor = radioDataBase.query(
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
    final int columnIndex = cursor.getColumnIndex(RadioSQLContract.Columns.COLUMN_POSITION);
    assert columnIndex >= 0;
    final int position = cursor.moveToNext() ? cursor.getInt(columnIndex) : 0;
    cursor.close();
    return position;
  }

  public boolean deleteFrom(@NonNull Long radioId) {
    final boolean result = (radioDataBase.delete(
      // The table to query
      RadioSQLContract.Columns.TABLE_RADIO,
      // The columns for the WHERE clause
      RadioSQLContract.Columns._ID + " = ?",
      // The values for the WHERE clause
      new String[]{radioId.toString()}) > 0);
    if (result) {
      tellListeners(listener -> listener.onRemove(radioId));
    }
    return result;
  }

  @NonNull
  public List<Long> getAllRadioIds() {
    final Cursor cursor = allIdsQuery();
    return cursorToListAndClose(cursor, RadioSQLContract.Columns._ID, cursor::getLong);
  }

  @NonNull
  public List<String> getAllRadioUrls() {
    final Cursor cursor = allUrlsQuery();
    return cursorToListAndClose(cursor, RadioSQLContract.Columns.COLUMN_URL, cursor::getString);
  }

  @NonNull
  public List<Long> getPreferredRadioIds() {
    final Cursor cursor = preferredIdsQuery();
    return cursorToListAndClose(cursor, RadioSQLContract.Columns._ID, cursor::getLong);
  }

  // Add a radio and store according icon
  public boolean add(@NonNull Radio radio) {
    final ContentValues contentValues = radio.toContentValues();
    // Position = last
    contentValues.put(RadioSQLContract.Columns.COLUMN_POSITION, getMaxPosition() + 1);
    radio.setId(radioDataBase.insert(RadioSQLContract.Columns.TABLE_RADIO, null, contentValues));
    // Success? => store icon file
    final Long radioId = radio.getId();
    if (radioId >= 0) {
      tellListeners(listener -> listener.onAdd(radioId));
      if (radio.storeIcon(context)) {
        contentValues.clear();
        contentValues.put(RadioSQLContract.Columns.COLUMN_ICON, radio.getIconFile().getPath());
        if (!updateFrom(radioId, contentValues)) {
          Log.e(LOG_TAG, "add: internal failure storing icon path");
        }
      }
      return true;
    }
    return false;
  }

  @Nullable
  public Long get(@NonNull Long radioId, int direction) {
    final List<Long> ids = getAllRadioIds();
    return ids.contains(radioId) ?
      ids.get((ids.size() + ids.indexOf(radioId) + direction) % ids.size()) :
      null;
  }

  public boolean setPreferred(@NonNull Long radioId, @NonNull Boolean isPreferred) {
    final ContentValues values = new ContentValues();
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
    final ContentValues fromPosition = positionContentValuesOf(fromRadioId);
    final ContentValues toPosition = positionContentValuesOf(toRadioId);
    final boolean result =
      updateFrom(fromRadioId, toPosition) && updateFrom(toRadioId, fromPosition);
    if (result) {
      tellListeners(listener -> listener.onMove(fromRadioId, toRadioId));
    }
    return result;
  }

  // Add if not already there
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
    final String id = (metadata == null) ? null : metadata.getDescription().getMediaId();
    currentRadioId = (id == null) ? null : Long.valueOf(id);
    tellListeners(listener -> listener.onNewCurrentRadio(getCurrentRadio()));
  }

  @NonNull
  public String marshall(boolean textOnly) {
    final StringBuilder result =
      new StringBuilder().append(textOnly ? Radio.EXPORT_HEAD + "\n" : "");
    for (Long id : getAllRadioIds()) {
      final Radio radio = getFrom(id);
      assert radio != null;
      result.append(radio.marshall(textOnly)).append(textOnly ? "\n" : SPACER);
    }
    return result.toString();
  }

  // Symmetrical to marshall
  @NonNull
  public String[] getRadioStrings(@NonNull String importString) {
    return importString.split(SPACER);
  }

  // Utility for database update of radio position
  @NonNull
  private ContentValues positionContentValuesOf(@NonNull Long radioId) {
    final ContentValues contentValues = new ContentValues();
    contentValues.put(RadioSQLContract.Columns.COLUMN_POSITION, getPositionFrom(radioId));
    return contentValues;
  }

  // columnName must be in cursor
  @NonNull
  private <T> List<T> cursorToListAndClose(
    @NonNull Cursor cursor,
    @NonNull String columnName,
    @NonNull Function<Integer, T> supplier) {
    final List<T> results = new Vector<>();
    final int idColumnIndex = cursor.getColumnIndexOrThrow(columnName);
    while (cursor.moveToNext()) {
      results.add(supplier.apply(idColumnIndex));
    }
    cursor.close();
    return results;
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
  private Cursor allUrlsQuery() {
    return radioDataBase.query(
      // The table to query
      RadioSQLContract.Columns.TABLE_RADIO,
      // The columns to return
      new String[]{RadioSQLContract.Columns.COLUMN_URL},
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
    final String maxPosition = "MAX(" + RadioSQLContract.Columns.COLUMN_POSITION + ")";
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
    final int columnIndex = cursor.getColumnIndex(maxPosition);
    assert columnIndex >= 0;
    final int position = cursor.moveToNext() ? cursor.getInt(columnIndex) : 0;
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

    default void onAdd(@NonNull Long radioId) {
    }

    default void onRemove(@NonNull Long radioId) {
    }

    default void onMove(@NonNull Long fromId, @NonNull Long toId) {
    }
  }

  public interface Provider {
    @Nullable
    Radio getFrom(@NonNull Long radioId);
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