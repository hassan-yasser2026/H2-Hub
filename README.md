# H2 Smart Hub

This repository contains the native Android app and the API server it uses. The web interface has been removed.

## Android app

Open the repository in Android Studio and build the debug app, or run this from the repository root on Windows:

```powershell
.\gradlew.bat :app:assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

The Android app reads `SERVER_URL` from `app/android-defaults.properties`; local overrides belong in the ignored `app/android.properties` file. The production default is `https://h2-smart-hub-production.up.railway.app`.

Do not put Gemini or ElevenLabs credentials in Android properties or the app bundle. Configure `GEMINI_API_KEY` and, when needed, `ELEVEN_LABS_API_KEY` only in the server environment (Railway Variables for production).

## API server

The Node.js API server is retained because the Android app uses it as a proxy for Gemini requests. To run it locally, install Node.js, install dependencies with `npm install`, set `GEMINI_API_KEY` in your environment, then run:

```powershell
npm run dev
```

For production, `npm run build` bundles the API server and `npm start` runs it. The server listens on `PORT` (default `3000`).

`GET /health` reports whether the AI provider keys are configured and whether persistence is available. The server has no database integration; cloud-sync routes return `503` until persistent storage and authenticated accounts are implemented. The AI routes have per-IP rate limits, and the Gemini proxy accepts only the model actions used by the app.
