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

import android.content.ContentValues;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.media.MediaMetadataCompat;
import android.util.Log;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Objects;

@SuppressWarnings({"unused", "WeakerAccess"})
public class Radio {
  public static final String RADIO_ID = "radio_id";
  private static final String LOG_TAG = Radio.class.getName();
  @NonNull
  private Long mId;
  @NonNull
  private String mName;
  @Nullable
  private File mIconFile;
  @NonNull
  private Type mType;
  @NonNull
  private Language mLanguage;
  @NonNull
  private URL mURL;
  @Nullable
  private URL mWebPageURL;
  @NonNull
  private Quality mQuality;
  private Boolean mIsPreferred = false;

  public Radio(
    @NonNull String name,
    @Nullable File iconFile,
    @NonNull URL uRL,
    @Nullable URL webPageURL) {
    this(name, iconFile, Type.MISC, Language.OTHER, uRL, webPageURL, Quality.LOW);
  }

  public Radio(
    @NonNull String name,
    @Nullable File iconFile,
    @NonNull Type type,
    @NonNull Language language,
    @NonNull URL uRL,
    @Nullable URL webPageURL,
    @NonNull Quality quality) {
    mId = -1L;
    mName = name;
    mIconFile = iconFile;
    mType = type;
    mLanguage = language;
    mURL = uRL;
    mWebPageURL = webPageURL;
    mQuality = quality;
  }

  // SQL constructor
  public Radio(@NonNull Cursor cursor) {
    mId = cursor.getLong(cursor.getColumnIndex(RadioSQLContract.Columns._ID));
    mName = cursor.getString(cursor.getColumnIndex(RadioSQLContract.Columns.COLUMN_NAME));
    String iconFileName =
      cursor.getString(cursor.getColumnIndex(RadioSQLContract.Columns.COLUMN_ICON));
    mIconFile = new File(iconFileName);
    mType =
      Type.valueOf(cursor.getString(cursor.getColumnIndex(RadioSQLContract.Columns.COLUMN_TYPE)));
    mLanguage =
      Language.valueOf(
        cursor.getString(cursor.getColumnIndex(RadioSQLContract.Columns.COLUMN_LANGUAGE)));
    try {
      mURL = new URL(cursor.getString(cursor.getColumnIndex(RadioSQLContract.Columns.COLUMN_URL)));
    } catch (MalformedURLException malformedURLException) {
      Log.e(LOG_TAG, "Radio: internal error, bad URL definition");
      throw new RuntimeException();
    }
    try {
      mWebPageURL =
        new URL(cursor.getString(cursor.getColumnIndex(RadioSQLContract.Columns.COLUMN_WEB_PAGE)));
    } catch (MalformedURLException malformedURLException) {
      Log.d(LOG_TAG, "Bad WebPageURL definition");
    }
    mQuality =
      Quality.valueOf(
        cursor.getString(cursor.getColumnIndex(RadioSQLContract.Columns.COLUMN_QUALITY)));
    mIsPreferred =
      Boolean.valueOf(
        cursor.getString(cursor.getColumnIndex(RadioSQLContract.Columns.COLUMN_IS_PREFERRED)));
  }

  // Add radio ID to given URI as query parameter
  @NonNull
  public Uri getHandledUri(@NonNull Uri uri) {
    return uri
      .buildUpon()
      // Add path to target to type the stream, remove first "/"
      .appendEncodedPath(Objects.requireNonNull(getUri().getPath()).substring(1))
      // Add radio ID as query parameter
      .appendQueryParameter(RADIO_ID, mId.toString())
      .build();
  }

  @NonNull
  public Long getId() {
    return mId;
  }

  public void setId(@NonNull Long id) {
    mId = id;
  }

  @NonNull
  public String getName() {
    return mName;
  }

  public void setName(@NonNull String name) {
    mName = name;
  }

  @NonNull
  public Type getType() {
    return mType;
  }

  public void setType(@NonNull Type type) {
    mType = type;
  }

  @NonNull
  public Language getLanguage() {
    return mLanguage;
  }

  public void setLanguage(@NonNull Language language) {
    mLanguage = language;
  }

  @NonNull
  public URL getURL() {
    return mURL;
  }

  public void setURL(@NonNull URL uRL) {
    mURL = uRL;
  }

  @NonNull
  public Uri getUri() {
    return Uri.parse(mURL.toString());
  }

  @Nullable
  public URL getWebPageURL() {
    return mWebPageURL;
  }

  public void setWebPageURL(URL webSiteURL) {
    mWebPageURL = webSiteURL;
  }

  @Nullable
  public Uri getWebPageUri() {
    return (mWebPageURL == null) ? null : Uri.parse(mWebPageURL.toString());
  }

  @Nullable
  public File getIconFile() {
    return mIconFile;
  }

  public void setIconFile(@NonNull File file) {
    mIconFile = file;
  }

  // Note: no defined size for icon
  @Nullable
  public Bitmap getIcon() {
    return (mIconFile == null) ? null : BitmapFactory.decodeFile(mIconFile.getPath());
  }

  @NonNull
  public Quality getQuality() {
    return mQuality;
  }

  public void setQuality(@NonNull Quality quality) {
    mQuality = quality;
  }

  public Boolean isPreferred() {
    return mIsPreferred;
  }

  public void togglePreferred() {
    mIsPreferred = !mIsPreferred;
  }

  // SQL access
  @NonNull
  public ContentValues toContentValues() {
    ContentValues contentValues = new ContentValues();
    contentValues.put(RadioSQLContract.Columns.COLUMN_NAME, mName);
    // Null allowed on transition
    contentValues.put(RadioSQLContract.Columns.COLUMN_ICON,
      (mIconFile == null) ? null : mIconFile.getPath());
    contentValues.put(RadioSQLContract.Columns.COLUMN_TYPE, mType.toString());
    contentValues.put(RadioSQLContract.Columns.COLUMN_LANGUAGE, mLanguage.toString());
    contentValues.put(RadioSQLContract.Columns.COLUMN_URL, mURL.toString());
    contentValues.put(RadioSQLContract.Columns.COLUMN_WEB_PAGE,
      (mWebPageURL == null) ? null : mWebPageURL.toString());
    contentValues.put(RadioSQLContract.Columns.COLUMN_QUALITY, mQuality.toString());
    contentValues.put(RadioSQLContract.Columns.COLUMN_IS_PREFERRED, mIsPreferred.toString());
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
      .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, mName)
      //.putLong(MediaMetadataCompat.METADATA_KEY_DOWNLOAD_STATUS, MediaDescriptionCompat.STATUS_DOWNLOADED)
      //.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, -1)
      //.putString(MediaMetadataCompat.METADATA_KEY_GENRE, "Genre")
      .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, mId.toString())
      //.putString(MediaMetadataCompat.METADATA_KEY_MEDIA_URI, "MediaURI")
      //.putLong(MediaMetadataCompat.METADATA_KEY_NUM_TRACKS, 1)
      //.putRating(MediaMetadataCompat.METADATA_KEY_RATING, RatingCompat.newPercentageRating(100))
      .putString(MediaMetadataCompat.METADATA_KEY_TITLE, mName);
    //.putLong(MediaMetadataCompat.METADATA_KEY_TRACK_NUMBER, 0)
    //.putRating(MediaMetadataCompat.METADATA_KEY_USER_RATING,  RatingCompat.newPercentageRating(100))
    //.putString(MediaMetadataCompat.METADATA_KEY_WRITER, "Writer")
    //.putLong(MediaMetadataCompat.METADATA_KEY_YEAR, Long.valueOf(simpleDateFormat.format(Calendar.getInstance().getTime())))
  }

  @Override
  public boolean equals(Object object) {
    return (object instanceof Radio) && (mId.longValue() == ((Radio) object).getId().longValue());
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