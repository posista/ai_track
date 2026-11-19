# AI Limit Notifier

Native Android-App für ChatGPT, Codex, Claude und Claude Code. Sie speichert bekannte kurzfristige und wöchentliche Rücksetzzeiten lokal und erinnert dich, sobald sie voraussichtlich erreicht sind. Auf dem Dashboard siehst du Quelle, Zuverlässigkeit und Zeitpunkt der letzten Aktualisierung.

## Auf Android installieren

1. Öffne die [GitHub Releases](https://github.com/posista/ai_track/releases) auf deinem Smartphone und melde dich bei GitHub an, falls das Repository privat ist.
2. Nutze in der [aktuellen Vorschau](https://github.com/posista/ai_track/releases/tag/v1.2.0-preview.2) den Link **APK für Android herunterladen**. Bei einem automatischen Release findest du die Datei `AI-Limit-Notifier.apk` oder `AI-Limit-Notifier-debug.apk` unter **Assets**. Öffne die APK in den Downloads. Android kann dich auffordern, deinem Browser oder Dateimanager **Apps aus dieser Quelle installieren** zu erlauben.
3. Öffne **AI Limit Notifier** und erlaube **Reset-Erinnerungen**. Für die automatische Erkennung öffne **Lesezugriff einrichten** und aktiviere **AI Limit Notifier · Limitmeldungen**. Bei seitlich installierten Apps kann Android den Zugriff zunächst als eingeschränkte Einstellung behandeln. Android erläutert die Freigabe in seiner [Hilfe zu eingeschränkten Einstellungen](https://support.google.com/android/answer/12623953?hl=de).
4. Sende über **Test-Erinnerung senden** eine lokale Testmeldung. Über **Teilen → AI Limit Notifier**, **Text übernehmen** oder **Datei öffnen** kannst du Zeiten aus Anbieteranzeigen und Desktop-Exporten importieren. Bei einer einzelnen Meldung mit Uhrzeit ohne Datum kannst du das Datum ergänzen.

Die App benötigt Android 8.0 oder neuer. Eine Debug-Vorschau verwendet eine eigene App-ID. Bei zwei Debug-Builds mit unterschiedlichen Schlüsseln verlangt Android vor der Neuinstallation die Deinstallation der alten Vorschau. Dadurch werden deren lokal gespeicherte Zeiten gelöscht. Sichere vor der Deinstallation oder dem Wechsel zur stabilen App deine zukünftigen Resetzeiten mit **Zeiten exportieren**. Öffne diese Datei anschließend in der neuen App. Quelle und Zustellhistorie werden beim Datei-Wechsel nicht übertragen. Beim Update derselben App mit gleicher Signatur bleiben die lokalen Einträge einschließlich Zustellhistorie erhalten.

Für einen Zwischenstand wähle den gewünschten erfolgreichen Lauf unter [Actions → Android](https://github.com/posista/ai_track/actions/workflows/android.yml) und lade **android-apk** herunter. Das Artefakt ist eine ZIP-Datei mit einer installierbaren APK.

## ChatGPT- und Claude-Nutzung scannen

1. Öffne über **Nutzung scannen** ChatGPT oder Claude und gehe zu **Einstellungen → Nutzung**, falls der Anbieter diese Ansicht für dein Konto anbietet. Du kannst auch eine Limitmeldung öffnen.
2. Erstelle ein Bildschirmfoto mit Kontingent und Rücksetzangabe. Teile es mit **AI Limit Notifier** oder wähle es unter **Bildschirmfoto lesen**.
3. Wähle den Anbieter. Bestätige **Bildschirmfoto gerade aufgenommen**, wenn der Reset als Restzeit oder Wochentag angegeben ist. Prüfe die Vorschau der Zeiten und tippe auf **Übernehmen**.

Die Texterkennung läuft mit einem gebündelten Modell auf dem Gerät. Die App speichert keine Bilder und keinen OCR-Rohtext. Automatische Übernahme über Android-Mitteilungen funktioniert weiterhin, wenn der Anbieter eine passende Meldung veröffentlicht und der Lesezugriff eingerichtet ist. Eine vollständige Hintergrundabfrage der beiden Abokonten ist mangels dokumentierter Kontoschnittstelle nicht verfügbar.

## Quellen und Erinnerungen

- **Automatisch auf Android:** Die App prüft neu gepostete Mitteilungen aus den offiziellen ChatGPT- und Claude-Apps. Sie übernimmt nur eindeutig interpretierbare Resetangaben. Ob und wann die Anbieter solche Mitteilungen posten, hängt von deren Apps ab.
- **Text- und Dateiimport:** Das strukturierte Format lautet `Dienst | kurzfristig/wöchentlich/unbekannt | ISO-8601-Zeit mit Offset | erreicht/geplant | optionaler Kontingentname`. Status und Kontingent sind optional. Für ein benanntes Kontingent gib den Status mit an. Mehrere Zeilen können in einem Schritt importiert werden.
- **Desktop:** Der optionale lokale Export unterstützt den Codex App Server und die Claude-Code-Statuszeile. Anleitung und Quellen stehen in der [Machbarkeitsprüfung](docs/limit-erkennung.md).
- **Erinnerungen:** Die App speichert einen konkreten Zeitpunkt und plant eine lokale Meldung über WorkManager. Android kann die Ausführung etwa im Energiesparmodus verschieben. Ausstehende Arbeit bleibt bei einem normalen Neustart erhalten. Nach einem erzwungenen Stopp muss die App erneut geöffnet werden. Ist ein Reset seit mindestens 24 Stunden fällig, verfällt die ausstehende Meldung.
- **Aktualität:** Pro Dienst, Fenster und Kontingent speichert die App einen Eintrag. Ein neuer Wert ersetzt den bisherigen Eintrag desselben Kontingents. Die App bestätigt den tatsächlichen Reset nicht beim Anbieter und startet keine wiederkehrende Erinnerung aus einem vergangenen Datum.

## Datenschutz

Die Android-App hat keine Internet-Berechtigung. Beim Lesezugriff liefert Android der App Ereignisse zu Benachrichtigungen. Die App verwirft Mitteilungen anderer Apps anhand des Paketnamens, bevor sie deren Text liest. In Room speichert sie nur Dienst, Limitfenster, Rücksetz- und Beobachtungszeit, Herkunft, Kontingent, erkannten Status, Zustellstatus und gegebenenfalls einen Hash zur Erkennung identischer relativer Meldungen. Der Rohtext einer Mitteilung wird nicht gespeichert. App-Backups sind deaktiviert. Gespeicherte Rücksetzzeiten lassen sich auf dem Dashboard einzeln entfernen.

## Entwicklung und Veröffentlichung

Mit JDK 21 und Android SDK 35 inklusive Build-Tools 35.0.0:

```sh
./gradlew lintDebug testDebugUnitTest assembleDebug
python3 -m unittest discover -s tools
```

GitHub Actions prüft Wrapper, Desktop-Export und Android-Quellcode, führt die Tests aus und stellt die signierte APK als Artefakt bereit. Ein Tag wie `v1.2.0` veröffentlicht die dauerhaft signierte APK. Ein Tag wie `v1.2.0-preview.2` veröffentlicht eine Debug-Vorschau. Der Tag muss zur APK-Version passen. Der Workflow prüft Signatur und SHA-256 vor der Veröffentlichung und lädt das Asset zur Kontrolle erneut herunter. Wenn GitHub den Runner-Start blockiert, erscheint kein Actions-Artefakt. Die veröffentlichte Vorschau kann in diesem Fall einen APK-Link im Release-Text verwenden.

Vor einem stabilen Release prüfe auf einem Smartphone:

1. Installiere die vorige Version, speichere zwei zukünftige Rücksetzzeiten und installiere die neue APK als Update. Beide APKs benötigen dieselbe App-ID und dasselbe Signaturzertifikat. Der neue `versionCode` muss höher sein. Prüfe beide gespeicherten Zeiten nach dem Update.
2. Sende eine Test-Erinnerung. Lege danach einen Reset in wenigen Minuten an und sperre das Gerät. Prüfe Zeitpunkt und Anzahl der Meldungen.
3. Lege weitere Resets an und wiederhole die Prüfung nach einem normalen Neustart und bei aktivem Energiesparmodus. WorkManager darf später zustellen. Öffne danach die App und prüfe, dass die Erinnerung kein zweites Mal erscheint.

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

Base64 lässt sich unter Linux mit `base64 -w0 release.jks` erzeugen. Verwahre den Ausgabewert wie den Keystore. Bei fehlenden oder unvollständigen Secrets bricht der stabile Release-Build ab. Die CI stellt den Keystore temporär wieder her und prüft die APK mit `apksigner`, bevor sie ihn entfernt.
