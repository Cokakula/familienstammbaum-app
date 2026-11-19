# Familienstammbaum – Android-App

Die App zeigt deinen Google-Familienstammbaum im Vollbild und erinnert dich an Geburtstage, auch wenn sie geschlossen ist. Die Daten bleiben im Google-Konto des Stammbaums. In der App selbst liegen nur dein Link und die Einstellungen.

## Installieren

1. Öffne auf dem Handy die Seite **Releases** dieses Projekts (rechts auf der GitHub-Seite) und lade `Familienstammbaum.apk` herunter.
   Direktlink zur neuesten Version: `https://github.com/Markpalu84/familienstammbaum-app/releases/latest/download/Familienstammbaum.apk`
2. Tippe auf die heruntergeladene Datei. Android fragt beim ersten Mal, ob Chrome Apps installieren darf: **Einstellungen → Zulassen**, dann zurück und **Installieren**.
3. Falls Google Play Protect warnt («Unbekannte App»): **Weitere Details → Trotzdem installieren**. Die Warnung kommt, weil die App nicht aus dem Play Store stammt.
4. App **Stammbaum** öffnen und deinen persönlichen Link einfügen:
   - Am einfachsten: In WhatsApp oder Gmail den Einladungslink lange drücken → **Teilen** → **Stammbaum**.
   - Oder den Link kopieren und in der App **Aus Zwischenablage einfügen** tippen.
   - Als Admin nimmst du deinen Admin-Link aus «Mitglieder & Zugriff».
5. Mitteilungen erlauben, wenn Android danach fragt. Sonst kommen keine Geburtstags-Erinnerungen.

## Geburtstags-Erinnerungen

- Jeden Morgen um 08:00 Uhr (einstellbar) holt die App die Geburtstage aus dem Stammbaum.
- Mitteilung am Geburtstag, auf Wunsch auch am Vortag.
- Direkt aus der Mitteilung: **Anrufen** oder **WhatsApp** mit einem fertigen Glückwunsch (Deutsch und Albanisch). WhatsApp erscheint nur, wenn die Nummer im Stammbaum mit +41, +383 usw. gespeichert ist.
- Tippen auf die Mitteilung öffnet die Karte der Person.
- Einstellungen: im Stammbaum-Menü **Geburtstags-Erinnerungen**. Dort gibt es auch **Jetzt prüfen**.
- Erinnert wird an lebende Personen mit vollständigem Geburtsdatum. Wer am 29. Februar geboren ist, wird in anderen Jahren am 28. Februar erinnert.

Tipp für Pixel-Handys: Unter **Einstellungen → Apps → Stammbaum → Akku** «Uneingeschränkt» wählen, damit die Erinnerungen pünktlich kommen.

## Neue Version

Jede Änderung im Ordner `main` baut automatisch eine neue APK (Reiter **Actions**). Die neue APK einfach über die alte installieren; Link und Einstellungen bleiben erhalten.

## Technik

- Kotlin, WebView, WorkManager. Android 8 oder neuer.
- Gebaut von GitHub Actions (`.github/workflows/build.yml`).
- `app/app-signing.keystore` ist der feste Signaturschlüssel. Ohne ihn lassen sich Updates nicht über die installierte App installieren. Er enthält keine Familiendaten.
- Die Google-Web-App braucht den Endpunkt `?api=geburtstage` aus der aktuellen `Code.gs`.
