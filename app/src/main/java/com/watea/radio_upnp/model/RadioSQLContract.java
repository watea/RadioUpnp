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

import android.provider.BaseColumns;

@SuppressWarnings("WeakerAccess")
public final class RadioSQLContract {
  static final String SQL_CREATE_ENTRIES =
    "CREATE TABLE " + Columns.TABLE_RADIO + " (" +
      Columns._ID + " INTEGER PRIMARY KEY," +
      Columns.COLUMN_NAME + " TEXT," +
      Columns.COLUMN_ICON + " TEXT," +
      Columns.COLUMN_TYPE + " TEXT," +
      Columns.COLUMN_LANGUAGE + " TEXT," +
      Columns.COLUMN_URL + " TEXT," +
      Columns.COLUMN_WEB_PAGE + " TEXT," +
      Columns.COLUMN_QUALITY + " TEXT," +
      Columns.COLUMN_IS_PREFERRED + " TEXT," +
      Columns.COLUMN_POSITION + " INTEGER)";
  static final String SQL_DELETE_ENTRIES = "DROP TABLE IF EXISTS " + Columns.TABLE_RADIO;

  // To prevent someone from accidentally instantiating the contract class
  private RadioSQLContract() {
  }

  public static class Columns implements BaseColumns {
    public static final String TABLE_RADIO = "radio";
    public static final String COLUMN_NAME = "name";
    public static final String COLUMN_ICON = "icon";
    public static final String COLUMN_TYPE = "type";
    public static final String COLUMN_LANGUAGE = "language";
    public static final String COLUMN_URL = "url";
    public static final String COLUMN_WEB_PAGE = "webSite";
    public static final String COLUMN_QUALITY = "quality";
    public static final String COLUMN_IS_PREFERRED = "isPreferred";
    public static final String COLUMN_POSITION = "position";
  }
}