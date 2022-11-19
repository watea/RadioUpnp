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

import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.RatingCompat;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.watea.radio_upnp.service.RadioURL;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;

public class Radio {
  public static final Radio DUMMY_RADIO;
  private static final String LOG_TAG = Radio.class.getName();
  private static final String SPACER = ";";
  public static final String MARSHALL_HEAD =
    marshall("name") + marshall("url") + marshall("webPageUrl") + marshall("isPreferred");

  static {
    Radio radio = null;
    try {
      radio = new Radio("", new URL("http:"), null, false, null);
    } catch (MalformedURLException malformedURLException) {
      Log.e(LOG_TAG, "Bad static init");
    }
    DUMMY_RADIO = radio;
  }

  @NonNull
  private Long id;
  @NonNull
  private String name;
  @NonNull
  private File iconFile;
  @NonNull
  private Type type;
  @NonNull
  private Language language;
  @NonNull
  private URL url;
  @Nullable
  private URL webPageUrl = null;
  @NonNull
  private Quality quality;
  @SuppressWarnings("FieldMayBeFinal")
  @NonNull
  private Boolean isPreferred;
  // Convenient member to temporary store icon,
  // as icon is stored as file (which may be changed, actually)
  @Nullable
  private Bitmap icon;

  // Create Radio with no icon file
  public Radio(
    @NonNull String name,
    @NonNull URL uRL,
    @Nullable URL webPageURL,
    @NonNull Boolean isPreferred,
    @Nullable Bitmap icon) throws MalformedURLException {
    this(
      name,
      new File(""),
      Type.MISC,
      Language.OTHER,
      uRL,
      webPageURL,
      Quality.MEDIUM,
      isPreferred,
      icon);
  }

  // Create Radio with no id
  public Radio(
    @NonNull String name,
    @NonNull File iconFile,
    @NonNull Type type,
    @NonNull Language language,
    @NonNull URL uRL,
    @Nullable URL webPageURL,
    @NonNull Quality quality,
    @NonNull Boolean isPreferred,
    @Nullable Bitmap icon) throws MalformedURLException {
    id = -1L;
    this.name = name;
    this.iconFile = iconFile;
    this.type = type;
    this.language = language;
    url = uRL;
    webPageUrl = webPageURL;
    this.quality = quality;
    this.isPreferred = isPreferred;
    this.icon = icon;
  }

  // SQL constructor
  @SuppressLint("Range")
  public Radio(@NonNull Cursor cursor) throws MalformedURLException {
    id = cursor.getLong(cursor.getColumnIndex(RadioSQLContract.Columns._ID));
    name = cursor.getString(cursor.getColumnIndex(RadioSQLContract.Columns.COLUMN_NAME));
    iconFile = new File(
      cursor.getString(cursor.getColumnIndex(RadioSQLContract.Columns.COLUMN_ICON)));
    type =
      Type.valueOf(cursor.getString(cursor.getColumnIndex(RadioSQLContract.Columns.COLUMN_TYPE)));
    language = Language.valueOf(
      cursor.getString(cursor.getColumnIndex(RadioSQLContract.Columns.COLUMN_LANGUAGE)));
    url = new URL(cursor.getString(cursor.getColumnIndex(RadioSQLContract.Columns.COLUMN_URL)));
    try {
      webPageUrl =
        new URL(cursor.getString(cursor.getColumnIndex(RadioSQLContract.Columns.COLUMN_WEB_PAGE)));
    } catch (MalformedURLException malformedURLException) {
      Log.i(LOG_TAG, "Bad WebPageURL definition");
    }
    quality = Quality.valueOf(
      cursor.getString(cursor.getColumnIndex(RadioSQLContract.Columns.COLUMN_QUALITY)));
    isPreferred = Boolean.valueOf(
      cursor.getString(cursor.getColumnIndex(RadioSQLContract.Columns.COLUMN_IS_PREFERRED)));
  }

  public Radio(@NonNull String string) throws MalformedURLException {
    this(string.split(SPACER));
  }

  // Symmetrical to marshall(), no icon file created
  private Radio(@NonNull String[] strings) throws MalformedURLException {
    this(
      strings[0],
      new URL(strings[1]),
      (strings[2].length() == 0) ? null : new URL(strings[2]),
      Boolean.valueOf(strings[3]),
      toBitmap(strings[4]));
  }

  // First URL if m3u, else do nothing
  @Nullable
  public static URL getUrlFromM3u(@NonNull URL uRL) {
    if (!uRL.toString().endsWith(".m3u")) {
      return uRL;
    }
    HttpURLConnection httpURLConnection = null;
    try (BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(
      (httpURLConnection = new RadioURL(uRL).getActualHttpURLConnection()).getInputStream()))) {
      String result;
      while ((result = bufferedReader.readLine()) != null) {
        if (result.startsWith("http://") || result.startsWith("https://")) {
          return new URL(result);
        }
      }
    } catch (IOException iOException) {
      Log.e(LOG_TAG, "Error getting M3U", iOException);
    } finally {
      if (httpURLConnection != null) {
        httpURLConnection.disconnect();
      }
    }
    return null;
  }

  @NonNull
  private static String marshall(@NonNull String string) {
    return string + SPACER;
  }

  // Store bitmap as filename.png
  @NonNull
  public static File storeToFile(
    @NonNull Context context, @NonNull Bitmap bitmap, @NonNull String fileName)
    throws FileNotFoundException {
    fileName = fileName + ".png";
    FileOutputStream fileOutputStream = context.openFileOutput(fileName, Context.MODE_PRIVATE);
    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 0, fileOutputStream)) {
      Log.e(LOG_TAG, "bitmapToFile: internal failure");
      throw new FileNotFoundException();
    }
    return new File(context.getFilesDir().getPath() + "/" + fileName);
  }

  @NonNull
  private static Bitmap toBitmap(@NonNull String base64String) {
    byte[] byteArray = Base64.decode(base64String, Base64.NO_WRAP);
    return BitmapFactory.decodeByteArray(byteArray, 0, byteArray.length);
  }

  @Nullable
  public URL getUrlFromM3u() {
    return getUrlFromM3u(url);
  }

  @NonNull
  public Long getId() {
    return id;
  }

  public void setId(@NonNull Long id) {
    this.id = id;
  }

  @NonNull
  public String getName() {
    return name;
  }

  public void setName(@NonNull String name) {
    this.name = name;
  }

  @NonNull
  public Type getType() {
    return type;
  }

  public void setType(@NonNull Type type) {
    this.type = type;
  }

  @SuppressWarnings("unused")
  @NonNull
  public Language getLanguage() {
    return language;
  }

  @SuppressWarnings("unused")
  public void setLanguage(@NonNull Language language) {
    this.language = language;
  }

  @NonNull
  public URL getURL() {
    return url;
  }

  public void setURL(@NonNull URL uRL) {
    url = uRL;
  }

  @NonNull
  public Uri getUri() {
    return Uri.parse(url.toString());
  }

  @Nullable
  public URL getWebPageURL() {
    return webPageUrl;
  }

  public void setWebPageURL(URL webSiteURL) {
    webPageUrl = webSiteURL;
  }

  @Nullable
  public Uri getWebPageUri() {
    return (webPageUrl == null) ? null : Uri.parse(webPageUrl.toString());
  }

  @NonNull
  public File getIconFile() {
    return iconFile;
  }

  // Note: no defined size for icon
  @NonNull
  public Bitmap getIcon() {
    return BitmapFactory.decodeFile(iconFile.getPath());
  }

  public void setIcon(@NonNull Bitmap icon) {
    this.icon = icon;
  }

  @SuppressWarnings("unused")
  @NonNull
  public Quality getQuality() {
    return quality;
  }

  @SuppressWarnings("unused")
  public void setQuality(@NonNull Quality quality) {
    this.quality = quality;
  }

  @NonNull
  public Boolean isPreferred() {
    return isPreferred;
  }

  // SQL access
  @NonNull
  public ContentValues toContentValues() {
    final ContentValues contentValues = new ContentValues();
    contentValues.put(RadioSQLContract.Columns.COLUMN_NAME, name);
    // Null allowed on transition
    contentValues.put(RadioSQLContract.Columns.COLUMN_ICON, iconFile.getPath());
    contentValues.put(RadioSQLContract.Columns.COLUMN_TYPE, type.toString());
    contentValues.put(RadioSQLContract.Columns.COLUMN_LANGUAGE, language.toString());
    contentValues.put(RadioSQLContract.Columns.COLUMN_URL, url.toString());
    contentValues.put(RadioSQLContract.Columns.COLUMN_WEB_PAGE,
      (webPageUrl == null) ? null : webPageUrl.toString());
    contentValues.put(RadioSQLContract.Columns.COLUMN_QUALITY, quality.toString());
    contentValues.put(RadioSQLContract.Columns.COLUMN_IS_PREFERRED, isPreferred.toString());
    return contentValues;
  }

  @NonNull
  public MediaMetadataCompat.Builder getMediaMetadataBuilder() {
    final Bitmap icon = getIcon();
    return new MediaMetadataCompat.Builder()
      .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, icon)
      .putBitmap(MediaMetadataCompat.METADATA_KEY_ART, icon)
      .putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, icon)
      .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, name)
      .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, id.toString())
      .putRating(
        MediaMetadataCompat.METADATA_KEY_RATING,
        RatingCompat.newPercentageRating(isPreferred ? 100 : 0))
      .putString(MediaMetadataCompat.METADATA_KEY_TITLE, name);
  }

  public boolean storeIcon(@NonNull Context context) {
    assert icon != null;
    try {
      iconFile = storeToFile(context, icon, id.toString());
    } catch (FileNotFoundException fileNotFoundException) {
      Log.e(LOG_TAG, "storeIcon: internal failure", fileNotFoundException);
      return false;
    }
    return true;
  }

  @Override
  public boolean equals(@Nullable Object object) {
    return ((object instanceof Radio) && (id.longValue() == ((Radio) object).id.longValue()));
  }

  // Only actually used field are exported
  @NonNull
  public String marshall(boolean textOnly) {
    return marshall(name) +
      marshall(url.toString()) +
      marshall((webPageUrl == null) ? "" : webPageUrl.toString()) +
      marshall(isPreferred.toString()) +
      (textOnly ? "" : marshall(iconToBase64String()));
  }

  @NonNull
  private String iconToBase64String() {
    final ByteArrayOutputStream baos = new ByteArrayOutputStream();
    getIcon().compress(Bitmap.CompressFormat.PNG, 100, baos);
    return Base64.encodeToString(baos.toByteArray(), Base64.DEFAULT);
  }

  @SuppressWarnings("unused")
  public enum Type {
    POPROCK,
    WORLD,
    HITS,
    URBAN,
    ELECTRO,
    CLASSIC,
    NEWS,
    MISC,
    OTHER
  }

  @SuppressWarnings("unused")
  public enum Language {
    ENGLISH,
    SPANISH,
    FRENCH,
    PORTUGUESE,
    OTHER
  }

  @SuppressWarnings("unused")
  public enum Quality {
    LOW,
    MEDIUM,
    HIGH
  }
}