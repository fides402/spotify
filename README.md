# Spotify App (audio in convivenza)

App Android che riproduce audio **senza fermare** le altre app: questa app e qualunque altra (Spotify, YouTube, ecc.) possono suonare **insieme** senza stopparsi a vicenda.

## Comportamento audio focus

- **Richiesta focus**: l’app chiede l’audio focus con `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`. Le altre app **non** vengono messe in pausa quando questa app inizia a suonare.
- **Perdita focus**: quando un’altra app prende il focus, questa app **non** va in pausa: abbassa solo il volume (duck) e continua a suonare. Così entrambi gli audio convivono.

Implementazione in `android/app/src/main/java/com/spotify/app/player/CoexistingAudioFocusHelper.kt`.

## Build

**Da terminale (con rete e JDK 17):**
```bash
cd android
.\gradlew.bat assembleDebug
```
L’APK sarà in `android/app/build/outputs/apk/debug/app-debug.apk`.

**Da Android Studio:** apri la cartella `android`, attendi la sincronizzazione Gradle, poi **Build → Build Bundle(s) / APK(s) → Build APK(s)**.

Serve **JDK 17** (Gradle 8.2 non supporta JDK 25). Se hai solo JDK 25, usa Android Studio che include un JDK compatibile.

## Callback OAuth

Il file `callback.html` alla root del progetto è la pagina di callback per l’auth Spotify (OAuth): invia il `code` all’app tramite `postMessage`. Puoi usarla come endpoint di redirect nella tua app web/Google Build.
