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
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.RatingCompat;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.watea.radio_upnp.service.RadioURL;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;

@SuppressWarnings("unused")
public class Radio {
  public static final Radio DUMMY_RADIO;
  private static final String LOG_TAG = Radio.class.getName();

  static {
    Radio radio = null;
    try {
      radio = new Radio("", new URL("http:"), null);
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
  private URL webPageUrl;
  @NonNull
  private Quality quality;
  private Boolean isPreferred = false;

  // Create Radio with no icon file
  public Radio(
    @NonNull String name,
    @NonNull URL uRL,
    @Nullable URL webPageURL) {
    this(name, new File(""), Type.MISC, Language.OTHER, uRL, webPageURL, Quality.MEDIUM);
  }

  // Create Radio with no id
  public Radio(
    @NonNull String name,
    @NonNull File iconFile,
    @NonNull Type type,
    @NonNull Language language,
    @NonNull URL uRL,
    @Nullable URL webPageURL,
    @NonNull Quality quality) {
    id = -1L;
    this.name = name;
    this.iconFile = iconFile;
    this.type = type;
    this.language = language;
    url = uRL;
    webPageUrl = webPageURL;
    this.quality = quality;
  }

  // SQL constructor
  @SuppressLint("Range")
  public Radio(@NonNull Cursor cursor) {
    id = cursor.getLong(cursor.getColumnIndex(RadioSQLContract.Columns._ID));
    name = cursor.getString(cursor.getColumnIndex(RadioSQLContract.Columns.COLUMN_NAME));
    iconFile = new File(
      cursor.getString(cursor.getColumnIndex(RadioSQLContract.Columns.COLUMN_ICON)));
    type =
      Type.valueOf(cursor.getString(cursor.getColumnIndex(RadioSQLContract.Columns.COLUMN_TYPE)));
    language = Language.valueOf(
      cursor.getString(cursor.getColumnIndex(RadioSQLContract.Columns.COLUMN_LANGUAGE)));
    try {
      url = new URL(cursor.getString(cursor.getColumnIndex(RadioSQLContract.Columns.COLUMN_URL)));
    } catch (MalformedURLException malformedURLException) {
      Log.e(LOG_TAG, "Internal error, bad URL definition");
      throw new RuntimeException();
    }
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

  @NonNull
  public Language getLanguage() {
    return language;
  }

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

  @NonNull
  public File setIconFile(@NonNull File file) {
    return iconFile = file;
  }

  // Note: no defined size for icon
  @NonNull
  public Bitmap getIcon() {
    return BitmapFactory.decodeFile(iconFile.getPath());
  }

  @NonNull
  public Quality getQuality() {
    return quality;
  }

  public void setQuality(@NonNull Quality quality) {
    this.quality = quality;
  }

  @NonNull
  public Boolean isPreferred() {
    return isPreferred;
  }

  public void togglePreferred() {
    isPreferred = !isPreferred;
  }

  // SQL access
  @NonNull
  public ContentValues toContentValues() {
    ContentValues contentValues = new ContentValues();
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
    //SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy");
    Bitmap icon = getIcon();
    return new MediaMetadataCompat.Builder()
      //.putLong(MediaMetadataCompat.METADATA_KEY_ADVERTISEMENT, 0)
      //.putString(MediaMetadataCompat.METADATA_KEY_ALBUM, "Album")
      .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, icon)
      //.putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, "AlbumArtURI")
      //.putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ARTIST, "AlbumArtist")
      .putBitmap(MediaMetadataCompat.METADATA_KEY_ART, icon)
      //.putString(MediaMetadataCompat.METADATA_KEY_ART_URI, "ArtURI")
      //.putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "Artist")
      //.putString(MediaMetadataCompat.METADATA_KEY_AUTHOR, "Author")
      //.putLong(MediaMetadataCompat.METADATA_KEY_BT_FOLDER_TYPE, MediaDescriptionCompat.BT_FOLDER_TYPE_TITLES)
      //.putString(MediaMetadataCompat.METADATA_KEY_COMPILATION, "Compilation")
      //.putString(MediaMetadataCompat.METADATA_KEY_COMPOSER, "Composer")
      //.putString(MediaMetadataCompat.METADATA_KEY_DATE, "Date")
      //.putLong(MediaMetadataCompat.METADATA_KEY_DISC_NUMBER, 0)
      //.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_DESCRIPTION, "DisplayDescription")
      .putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, icon)
      //.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI, "DisplayIconURI")
      //.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, "DisplaySubtitle")
      .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, name)
      //.putLong(MediaMetadataCompat.METADATA_KEY_DOWNLOAD_STATUS, MediaDescriptionCompat.STATUS_DOWNLOADED)
      //.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, -1)
      //.putString(MediaMetadataCompat.METADATA_KEY_GENRE, "Genre")
      .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, id.toString())
      //.putString(MediaMetadataCompat.METADATA_KEY_MEDIA_URI, "MediaURI")
      //.putLong(MediaMetadataCompat.METADATA_KEY_NUM_TRACKS, 1)
      .putRating(
        MediaMetadataCompat.METADATA_KEY_RATING,
        RatingCompat.newPercentageRating(isPreferred ? 100 : 0))
      .putString(MediaMetadataCompat.METADATA_KEY_TITLE, name);
    //.putLong(MediaMetadataCompat.METADATA_KEY_TRACK_NUMBER, 0)
    //.putRating(MediaMetadataCompat.METADATA_KEY_USER_RATING,  RatingCompat.newPercentageRating(100))
    //.putString(MediaMetadataCompat.METADATA_KEY_WRITER, "Writer")
    //.putLong(MediaMetadataCompat.METADATA_KEY_YEAR, Long.valueOf(simpleDateFormat.format(Calendar.getInstance().getTime())))
  }

  @Override
  public boolean equals(@Nullable Object object) {
    return ((object instanceof Radio) && (id.longValue() == ((Radio) object).id.longValue()));
  }

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

  public enum Language {
    ENGLISH,
    SPANISH,
    FRENCH,
    PORTUGUESE,
    OTHER
  }

  public enum Quality {
    LOW,
    MEDIUM,
    HIGH
  }
}