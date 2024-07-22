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

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.RatingCompat;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.net.MalformedURLException;
import java.net.URL;

public class Radio {
  @NonNull
  public static final Radio DUMMY_RADIO;
  private static final String LOG_TAG = Radio.class.getSimpleName();
  private static final String SPACER = ";";
  public static final String EXPORT_HEAD =
    export("name") + export("url") + export("webPageUrl") + export("isPreferred");
  private static final String NAME = "name";
  private static final String ICON = "icon";
  private static final String URL = "url";
  private static final String WEB_PAGE_URL = "web_page_url";
  private static final String MIME = "mime";
  private static final String QUALITY = "quality";
  private static final String IS_PREFERRED = "is_preferred";
  private static final int DEFAULT = -1;

  static {
    Radio radio = null;
    try {
      radio = new Radio(
        "DUMMY",
        BitmapFactory.decodeResource(Resources.getSystem(), android.R.drawable.ic_secure),
        new URL("http:"),
        null,
        "",
        0,
        false);
    } catch (MalformedURLException malformedURLException) {
      Log.e(LOG_TAG, "Internal failure; bad static init");
    }
    assert radio != null;
    DUMMY_RADIO = radio;
  }

  @NonNull
  private final String mime;
  private final int quality;
  @NonNull
  private String name;
  @SuppressWarnings("NotNullFieldNotInitialized")
  @NonNull
  private Bitmap icon;
  @NonNull
  private URL url;
  @Nullable
  private URL webPageUrl;
  private boolean isPreferred;

  public Radio(
    @NonNull String name,
    @NonNull Bitmap icon,
    @NonNull URL url,
    @Nullable URL webPageUrl,
    @NonNull String mime,
    int quality,
    boolean isPreferred) {
    this.name = name;
    setIcon(icon);
    this.url = url;
    this.webPageUrl = webPageUrl;
    this.mime = mime;
    this.quality = quality;
    this.isPreferred = isPreferred;
  }

  public Radio(
    @NonNull String name,
    @NonNull Bitmap icon,
    @NonNull URL url,
    @Nullable URL webPageUrl,
    @NonNull String mime,
    int quality) {
    this(name, icon, url, webPageUrl, mime, quality, false);
  }

  public Radio(
    @NonNull String name,
    @NonNull Bitmap icon,
    @NonNull URL url,
    @Nullable URL webPageUrl) {
    this(name, icon, url, webPageUrl, "", DEFAULT);
  }

  public Radio(@NonNull JSONObject jSONObject) throws JSONException, MalformedURLException {
    this(
      jSONObject.getString(NAME),
      getBitmapFrom(jSONObject.getString(ICON)),
      new URL(jSONObject.getString(URL)),
      getURLFrom(jSONObject.getString(WEB_PAGE_URL)),
      jSONObject.getString(MIME),
      jSONObject.getInt(QUALITY),
      jSONObject.getBoolean(IS_PREFERRED));
  }

  public Radio(@NonNull String json) throws JSONException, MalformedURLException {
    this((JSONObject) new JSONTokener(json).nextValue());
  }

  // Store bitmap as filename.png
  @NonNull
  public static File storeToFile(
    @NonNull Context context, @NonNull Bitmap bitmap, @NonNull String fileName)
    throws FileNotFoundException {
    fileName = fileName + ".png";
    final FileOutputStream fileOutputStream =
      context.openFileOutput(fileName, Context.MODE_PRIVATE);
    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 0, fileOutputStream)) {
      Log.e(LOG_TAG, "bitmapToFile: internal failure");
      throw new FileNotFoundException();
    }
    return new File(context.getFilesDir().getPath() + "/" + fileName);
  }

  // Crop bitmap as a square
  @NonNull
  public static Bitmap crop(@NonNull Bitmap icon) {
    final int height = icon.getHeight();
    final int width = icon.getWidth();
    final int min = Math.min(height, width);
    return Bitmap.createBitmap(icon, (width - min) / 2, (height - min) / 2, min, min, null, false);
  }

  @NonNull
  private static String export(@NonNull String string) {
    return string + SPACER;
  }

  @Nullable
  private static URL getURLFrom(@NonNull String string) throws MalformedURLException {
    return string.isEmpty() ? null : new URL(string);
  }

  @NonNull
  private static Bitmap getBitmapFrom(@NonNull String base64String) {
    final byte[] byteArray = Base64.decode(base64String, Base64.NO_WRAP);
    return BitmapFactory.decodeByteArray(byteArray, 0, byteArray.length);
  }

  @NonNull
  public static Bitmap createScaledBitmap(@NonNull Bitmap bitmap, int size) {
    return Bitmap.createScaledBitmap(bitmap, size, size, true);
  }

  @NonNull
  public String getName() {
    return name;
  }

  public void setName(@NonNull String name) {
    this.name = name;
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

  // Note: no defined size for icon
  @NonNull
  public Bitmap getIcon() {
    return icon;
  }

  public void setIcon(@NonNull Bitmap icon) {
    this.icon = crop(icon);
  }

  public boolean isPreferred() {
    return isPreferred;
  }

  public void setIsPreferred(boolean isPreferred) {
    this.isPreferred = isPreferred;
  }

  @NonNull
  public String getId() {
    return Integer.toString(hashCode());
  }

  @NonNull
  public MediaMetadataCompat.Builder getMediaMetadataBuilder() {
    final Bitmap icon = getIcon();
    return new MediaMetadataCompat.Builder()
      .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, getId())
      .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, icon)
      .putBitmap(MediaMetadataCompat.METADATA_KEY_ART, icon)
      .putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, icon)
      .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, name)
      .putRating(
        MediaMetadataCompat.METADATA_KEY_RATING,
        RatingCompat.newPercentageRating(isPreferred ? 100 : 0))
      .putString(MediaMetadataCompat.METADATA_KEY_TITLE, name);
  }

  @NonNull
  public String export() {
    return export(name) +
      export(url.toString()) +
      export((webPageUrl == null) ? "" : webPageUrl.toString()) +
      export(Boolean.toString(isPreferred));
  }

  @NonNull
  public JSONObject getJSONObject() throws JSONException {
    return new JSONObject()
      .put(NAME, name)
      .put(ICON, iconToBase64String())
      .put(URL, url.toString())
      .put(WEB_PAGE_URL, (webPageUrl == null) ? "" : webPageUrl.toString())
      .put(MIME, mime)
      .put(QUALITY, quality)
      .put(IS_PREFERRED, isPreferred);
  }

  @NonNull
  private String iconToBase64String() {
    final ByteArrayOutputStream baos = new ByteArrayOutputStream();
    icon.compress(Bitmap.CompressFormat.PNG, 100, baos);
    return Base64.encodeToString(baos.toByteArray(), Base64.DEFAULT);
  }
}