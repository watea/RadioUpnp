# RadioUpnp

An Android radio streaming app with UPnP/DLNA and Google Cast support.

[<img src="https://play.google.com/intl/en_us/badges/images/generic/en-play-badge.png"
alt="Get it on Google Play"
height="80">](https://play.google.com/store/apps/details?id=com.watea.radio_upnp)
[<img src="https://gitlab.com/IzzyOnDroid/repo/-/raw/master/assets/IzzyOnDroid.png"
alt="Get it on IzzyOnDroid"
height="80">](https://apt.izzysoft.de/fdroid/index/apk/com.watea.radio_upnp)

Or get the latest APK from the [Releases section](https://github.com/watea/RadioUpnp/releases/latest).

[<img src="https://shields.rbtlog.dev/simple/com.watea.radio_upnp?style=for-the-badge" alt="RB Status">](https://shields.rbtlog.dev/com.watea.radio_upnp)

---

## Features

- Stream internet radio stations to your Android device
- Cast audio to **UPnP/DLNA renderers** on your local network
- Cast audio to **Google Cast** devices (Chromecast, etc.)
- Sleep timer, alarm, playlist history
- Import/export radio lists (JSON, CSV)
- Google Assistant voice commands support
- Android Auto support

---

## How remote streaming works

Most UPnP/DLNA radio apps simply forward the radio stream URL directly to the renderer.
RadioUpnp takes a different approach: **the audio is fully decoded on the Android device and re-streamed as PCM/WAV to the renderer**.

This brings two key advantages:
- Any radio format supported by ExoPlayer (HLS, AAC, MP3, etc.) works on any renderer, regardless of its codec support
- ICY metadata (track titles) is captured locally and displayed in the app in real time

### Architecture

```
Internet radio stream
        │
        ▼
   ExoPlayer (Media3)
        │
        ▼
  CapturingAudioSink        ← custom AudioSink intercepting raw PCM
        │
        ▼
     Pacer thread           ← paces PCM output to match real-time bitrate
        │
        ▼
  UpnpStreamServer          ← local HTTP server (NanoHTTPD) serving WAV stream
        │
        ▼
  UPnP/DLNA renderer  ──or──  Google Cast device
```

`CapturingAudioSink` wraps ExoPlayer's `DefaultAudioSink` and intercepts the decoded PCM data before it reaches the audio hardware. A `Pacer` thread paces the output to match the real-time audio bitrate, preventing buffer overruns on the renderer side. The WAV header is built dynamically from the format reported by ExoPlayer (`sampleRate`, `channelCount`, `bitsPerSample`).

For Google Cast, the same local WAV stream is served to the Cast device, keeping a unified streaming pipeline for both protocols.

---

## A fully custom UPnP stack

Most Android UPnP apps historically relied on **Cling**, a library that has been unmaintained for years and carries significant technical debt. RadioUpnp takes a different path: the entire UPnP stack was rewritten from scratch over 6 months, resulting in a lightweight and modern implementation with no legacy dependency.

The custom stack covers:
- **SSDP discovery** — via [AndroidSsdpClient](https://github.com/watea/androidssdpclient), a purpose-built library
- **Device description parsing** — XML UPnP device and service descriptors
- **SOAP action control** — UPnP action invocation over HTTP via OkHttp
- **AVTransport / RenderingControl / ConnectionManager** — the three core UPnP services for audio rendering

This makes RadioUpnp one of the very few modern Android open source apps with a fully self-contained UPnP implementation.

---

## Tech stack

- **Media3 / ExoPlayer** — radio decoding and PCM capture via custom `AudioSink`
- **NanoHTTPD** — embedded HTTP server for WAV streaming
- **AndroidSsdpClient** — lightweight SSDP discovery (custom library)
- **OkHttp** — UPnP/DLNA SOAP action control
- **Google Cast SDK** — Chromecast support
- **MediaBrowserServiceCompat** — background playback and Android Auto

---

## Contributing

Contributions are welcome, especially around:
- UPnP/DLNA compatibility with specific renderers
- Audio format handling edge cases
- Google Assistant / App Actions integration

Please open an issue before submitting a pull request.