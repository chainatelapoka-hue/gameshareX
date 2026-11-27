# gameshareX

Low-end client app for **GameShare**. It joins the GameShare host's Wi-Fi Direct network by scanning the host's QR code, then sends all of the phone's traffic (TCP and UDP) through the host's SOCKS5 proxy.

- Minimum Android: **7.0** (API 24)
- Native tunnel: [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) (MIT, vendored in `app/src/main/jni/hev-socks5-tunnel`)

## Download

**Latest APK:** https://github.com/chainatelapoka-hue/gamesharex/releases/latest/download/gameshareX.apk

## How it works

1. The host phone runs **GameShare** and shows a QR code (`GSX1;S=...;P=...;H=...;O=...;A=...`).
2. On the client, tap **QR স্ক্যান করে কানেক্ট করুন** and scan it.
3. gameshareX joins the Wi-Fi Direct network, binds itself to it, and starts a VPN that forwards everything to the host's SOCKS5 proxy.
4. Press **বন্ধ করুন** to stop.

Authentication: if the host has authentication on, enter the username and password in the fields before scanning.

## Build

CI builds the APK on every push to `main` (see `.github/workflows/build-apk.yml`). It installs the NDK, compiles the native tunnel, runs Lint's NewApi check, and publishes the APK to a GitHub Release.

## License

Apache-2.0 for gameshareX. hev-socks5-tunnel is MIT (see `app/src/main/jni/hev-socks5-tunnel/LICENSE`).
