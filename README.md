# AI Limit Notifier

Native Android-App für ChatGPT, Codex, Claude und Claude Code. Sie speichert bekannte kurzfristige und wöchentliche Rücksetzzeiten lokal und erinnert dich, sobald sie voraussichtlich erreicht sind. Auf dem Dashboard siehst du Quelle, Zuverlässigkeit und Zeitpunkt der letzten Aktualisierung.

## Auf Android installieren

1. Öffne die [GitHub Releases](https://github.com/posista/ai_track/releases) auf deinem Smartphone und melde dich bei GitHub an, falls das Repository privat ist.
2. Nutze in der [aktuellen Vorschau](https://github.com/posista/ai_track/releases/tag/preview-1.0.0) den Link **APK für Android herunterladen**. Bei einem automatischen Release findest du die Datei `AI-Limit-Notifier.apk` oder `AI-Limit-Notifier-debug.apk` unter **Assets**. Öffne die APK in den Downloads. Android kann dich auffordern, deinem Browser oder Dateimanager **Apps aus dieser Quelle installieren** zu erlauben.
3. Öffne **AI Limit Notifier** und erlaube **Reset-Erinnerungen**. Für die automatische Erkennung öffne **Lesezugriff einrichten** und aktiviere **AI Limit Notifier · Limitmeldungen**. Bei seitlich installierten Apps kann Android den Zugriff zunächst als eingeschränkte Einstellung behandeln. Android erläutert die Freigabe in seiner [Hilfe zu eingeschränkten Einstellungen](https://support.google.com/android/answer/12623953?hl=de).
4. Ohne verwertbare Limitmeldung kannst du eine Anbieteranzeige über **Teilen → AI Limit Notifier** oder **Text übernehmen** importieren. Prüfe vor dem Speichern die angezeigte Rücksetzzeit. Bei Bedarf wähle **Zeit eintragen** und übernimm das Datum aus der Anbieteranzeige.

Die App benötigt Android 8.0 oder neuer. Eine Debug-Vorschau verwendet eine eigene App-ID. Bei zwei Debug-Builds mit unterschiedlichen Schlüsseln verlangt Android vor der Neuinstallation die Deinstallation der alten Vorschau. Dadurch werden deren lokal gespeicherte Zeiten gelöscht.

Für einen Zwischenstand wähle den gewünschten erfolgreichen Lauf unter [Actions → Android](https://github.com/posista/ai_track/actions/workflows/android.yml) und lade **android-apk** herunter. Das Artefakt ist eine ZIP-Datei mit einer installierbaren APK.

## Quellen und Erinnerungen

- **Automatisch auf Android:** Die App prüft neu gepostete Mitteilungen aus den offiziellen ChatGPT- und Claude-Apps. Sie übernimmt nur eindeutig interpretierbare Resetangaben. Ob und wann die Anbieter solche Mitteilungen posten, hängt von deren Apps ab.
- **Textimport:** Eine eindeutige Meldung mit Datum und Uhrzeit oder eine strukturierte Zeile kann nach Sichtprüfung gespeichert werden. Das strukturierte Format lautet `Dienst | kurzfristig/wöchentlich/unbekannt | ISO-8601-Zeit mit Offset | optional erreicht/geplant`. Mehrere Zeilen können in einem Schritt importiert werden.
- **Desktop:** Der optionale lokale Export unterstützt den Codex App Server und die Claude-Code-Statuszeile. Anleitung und Quellen stehen in der [Machbarkeitsprüfung](docs/limit-erkennung.md).
- **Erinnerungen:** Die App speichert einen konkreten Zeitpunkt und plant eine lokale Meldung über WorkManager. Android kann die Ausführung etwa im Energiesparmodus verschieben. Ausstehende Arbeit bleibt bei einem normalen Neustart erhalten. Nach einem erzwungenen Stopp muss die App erneut geöffnet werden. Ist ein Reset seit mindestens 24 Stunden fällig, verfällt die ausstehende Meldung.
- **Aktualität:** Pro Dienst und Fenster speichert die App einen Eintrag. Ein neuer Wert ersetzt den bisherigen. Die App bestätigt den tatsächlichen Reset nicht beim Anbieter und startet keine wiederkehrende Erinnerung aus einem vergangenen Datum.

## Datenschutz

Die Android-App hat keine Internet-Berechtigung. Beim Lesezugriff liefert Android der App Ereignisse zu Benachrichtigungen. Die App verwirft Mitteilungen anderer Apps anhand des Paketnamens, bevor sie deren Text liest. In Room speichert sie nur Dienst, Limitfenster, Rücksetz- und Beobachtungszeit, Herkunft, erkannten Status und Zustellstatus. Der Rohtext einer Mitteilung wird nicht gespeichert. App-Backups sind deaktiviert. Gespeicherte Rücksetzzeiten lassen sich auf dem Dashboard einzeln entfernen.

## Entwicklung und Veröffentlichung

Mit JDK 21 und Android SDK 35 inklusive Build-Tools 35.0.0:

```sh
./gradlew lintDebug testDebugUnitTest assembleDebug
python3 -m unittest discover -s tools
```

GitHub Actions prüft Wrapper, Desktop-Export und Android-Quellcode, führt die Tests aus und stellt die signierte APK als Artefakt bereit. Ein Tag wie `v1.0.0` startet zusätzlich die Veröffentlichung als GitHub Release. Ohne Release-Secrets verwendet der Workflow eine Debug-APK und markiert den Release als Vorschau.

### Eigene Release-Signatur einrichten

Erzeuge einen Keystore auf einem vertrauenswürdigen Rechner und bewahre ihn dauerhaft sicher auf. Für Android-Updates brauchst du denselben Signierschlüssel und einen höheren `versionCode`.

```sh
keytool -genkeypair -keystore release.jks -storetype JKS -alias ai-limit-notifier \
  -keyalg RSA -keysize 2048 -validity 10000
```

Hinterlege in GitHub unter **Settings → Secrets and variables → Actions** alle vier Secrets:

| Secret | Wert |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | Keystore-Datei als einzeiliges Base64 |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore-Passwort |
| `ANDROID_KEY_ALIAS` | Alias des Schlüssels |
| `ANDROID_KEY_PASSWORD` | Passwort des Schlüssels |

Base64 lässt sich unter Linux mit `base64 -w0 release.jks` erzeugen. Verwahre den Ausgabewert wie den Keystore. Bei unvollständigen Secrets bricht der getaggte Build ab. Die CI stellt den Keystore temporär wieder her und prüft die APK mit `apksigner`, bevor sie ihn entfernt.
