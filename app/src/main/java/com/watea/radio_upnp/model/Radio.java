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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.net.MalformedURLException;
import java.net.URL;

public class Radio {
  @NonNull
  public static final Radio DUMMY_RADIO;
  public static final int RADIO_ICON_SIZE = 300;
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
        BitmapFactory.decodeResource(Resources.getSystem(), android.R.drawable.ic_menu_help),
        new URL("http://dummy"),
        null);
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
  @NonNull
  private Bitmap icon;
  @NonNull
  private String base64Icon; // cache
  @NonNull
  private URL url;
  @Nullable
  private URL webPageUrl;
  private boolean isPreferred;

  // icon and base64Icon are mutually exclusive, one at least not null
  public Radio(
    @NonNull String name,
    @Nullable Bitmap icon,
    @Nullable String base64Icon,
    @NonNull URL url,
    @Nullable URL webPageUrl,
    @NonNull String mime,
    int quality,
    boolean isPreferred) {
    this.name = name;
    assert (icon == null) != (base64Icon == null);
    this.icon = crop((icon == null) ? getBitmapFrom(base64Icon) : icon);
    this.base64Icon = (base64Icon == null) ? iconToBase64String() : base64Icon;
    this.url = url;
    this.webPageUrl = webPageUrl;
    this.mime = mime;
    this.quality = quality;
    this.isPreferred = isPreferred;
  }

  public Radio(
    @NonNull String name,
    @Nullable Bitmap icon,
    @Nullable String base64Icon,
    @NonNull URL url,
    @Nullable URL webPageUrl) {
    this(name, icon, base64Icon, url, webPageUrl, "", DEFAULT, false);
  }

  public Radio(
    @NonNull String name,
    @NonNull String base64Icon,
    @NonNull URL url,
    @Nullable URL webPageUrl) {
    this(name, null, base64Icon, url, webPageUrl);
  }

  public Radio(
    @NonNull String name,
    @NonNull Bitmap icon,
    @NonNull URL url,
    @Nullable URL webPageUrl) {
    this(name, icon, null, url, webPageUrl);
  }

  public Radio(@NonNull JSONObject jSONObject) throws JSONException, MalformedURLException {
    this(
      jSONObject.getString(NAME),
      null,
      jSONObject.getString(ICON),
      new URL(jSONObject.getString(URL)),
      getURLFrom(jSONObject.getString(WEB_PAGE_URL)),
      jSONObject.getString(MIME),
      jSONObject.getInt(QUALITY),
      jSONObject.getBoolean(IS_PREFERRED));
  }

  @NonNull
  public static Bitmap iconResize(@NonNull Bitmap bitmap) {
    return Radio.createScaledBitmap(bitmap, RADIO_ICON_SIZE);
  }

  // Crop bitmap as a square
  @NonNull
  public static Bitmap crop(@NonNull Bitmap icon) {
    final int height = icon.getHeight();
    final int width = icon.getWidth();
    final int min = Math.min(height, width);
    return (height == width) ? icon : Bitmap.createBitmap(icon, (width - min) / 2, (height - min) / 2, min, min, null, false);
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

  @Nullable
  public static Radio getRadioFromCsv(@NonNull String csvLine) {
    final String[] fields = csvLine.split(SPACER);
    if (fields.length == 4) {
      try {
        final String name = fields[0];
        final URL url = new URL(fields[1]);
        final URL webPageUrl = fields[2].isEmpty() ? null : new URL(fields[2]);
        final boolean isPreferred = Boolean.parseBoolean(fields[3]);
        final Radio radio = new Radio(name, Radio.DUMMY_RADIO.base64Icon, url, webPageUrl);
        radio.setIsPreferred(isPreferred);
        return radio;
      } catch (MalformedURLException malformedURLException) {
        Log.e(LOG_TAG, "getRadioFromCsv: URL error", malformedURLException);
      }
    } else {
      Log.e(LOG_TAG, "getRadioFromCsv: bad .csv line");
    }
    return null;
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
    base64Icon = iconToBase64String();
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
  public MediaMetadataCompat.Builder getMediaMetadataBuilder(@NonNull String postfix) {
    final Bitmap icon = getIcon();
    return new MediaMetadataCompat.Builder()
      .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, getId())
      .putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, icon)
      .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, name + postfix)
      .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, "")
      .putString(MediaMetadataCompat.METADATA_KEY_TITLE, name + postfix)
      .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "")
      .putRating(
        MediaMetadataCompat.METADATA_KEY_RATING,
        RatingCompat.newPercentageRating(isPreferred ? 100 : 0));
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
      .put(ICON, base64Icon)
      .put(URL, url.toString())
      .put(WEB_PAGE_URL, (webPageUrl == null) ? "" : webPageUrl.toString())
      .put(MIME, mime)
      .put(QUALITY, quality)
      .put(IS_PREFERRED, isPreferred);
  }

  @NonNull
  public Bitmap resizeToWidth(int targetWidth) {
    final float ratio = (float) icon.getHeight() / icon.getWidth();
    final int targetHeight = (int) (targetWidth * ratio);
    return Bitmap.createScaledBitmap(icon, targetWidth, targetHeight, true);
  }

  @NonNull
  private String iconToBase64String() {
    final ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
    icon.compress(Bitmap.CompressFormat.PNG, 100, byteArrayOutputStream);
    return Base64.encodeToString(byteArrayOutputStream.toByteArray(), Base64.DEFAULT);
  }
}