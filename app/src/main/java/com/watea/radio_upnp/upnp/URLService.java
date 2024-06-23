package com.watea.radio_upnp.upnp;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.apache.commons.io.IOUtils;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.util.Hashtable;
import java.util.Map;

public class URLService {
  private static final String LOG_TAG = URLService.class.getName();
  @NonNull
  final URLConnection uRLConnection;
  private final Map<String, String> tags = new Hashtable<>();
  @Nullable
  private String content = null;

  public URLService(@NonNull URL uRL) throws IOException {
    uRLConnection = uRL.openConnection();
  }

  public URLService(@NonNull URL uRL, @NonNull URI uRI) throws IOException, URISyntaxException {
    this(uRL.toURI().resolve(uRI).toURL());
  }

  // Ignore case
  @Nullable
  public String getTag(@NonNull String key) {
    return tags.get(key.toLowerCase());
  }

  public void clearTags() {
    tags.clear();
  }

  @Nullable
  public Bitmap getBitmap() throws IOException {
    return BitmapFactory.decodeStream(getInputStream());
  }

  @NonNull
  public URLService fetchContent() throws IOException {
    String encoding = uRLConnection.getContentEncoding();
    encoding = (encoding == null) ? "UTF-8" : encoding;
    content = IOUtils.toString(getInputStream(), encoding);
    Log.d(LOG_TAG, "fetchContent:\n" + content);
    return this;
  }

  // Calls consumer on START_TAG, END_TAG.
  // XML contents may be handeld with getTag, clearTags.
  public void parseXml(@NonNull Consumer consumer) throws XmlPullParserException, IOException {
    final XmlPullParserFactory xmlPullParserFactory = XmlPullParserFactory.newInstance();
    xmlPullParserFactory.setNamespaceAware(true);
    final XmlPullParser xmlPullParser = xmlPullParserFactory.newPullParser();
    xmlPullParser.setInput(new StringReader(content));
    int eventType = xmlPullParser.getEventType();
    String currentTag = null;
    while (eventType != XmlPullParser.END_DOCUMENT) {
      switch (eventType = xmlPullParser.next()) {
        case XmlPullParser.START_TAG:
          consumer.startAccept(this, currentTag = xmlPullParser.getName());
          break;
        case XmlPullParser.TEXT:
          if (currentTag != null) {
            tags.put(currentTag.toLowerCase(), xmlPullParser.getText());
            // Tag processed
            currentTag = null;
          }
          break;
        case XmlPullParser.END_TAG:
          consumer.endAccept(this, xmlPullParser.getName());
          break;
        default:
          // Nothing to do
      }
    }
    consumer.endParseAccept(this);
  }

  @NonNull
  public URL getURL() {
    return uRLConnection.getURL();
  }

  private InputStream getInputStream() throws IOException {
    return uRLConnection.getInputStream();
  }

  public interface Consumer {
    default void startAccept(@NonNull URLService uRLService, @NonNull String currentTag) {
    }

    default void endAccept(@NonNull URLService uRLService, @NonNull String currentTag) {
    }

    void endParseAccept(@NonNull URLService uRLService);
  }
}