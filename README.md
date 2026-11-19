# Familienstammbaum

**Webversion für alle (iPhone, Android, Computer):** https://cokakula.github.io/familienstammbaum-app/

Ende-zu-Ende-verschlüsselt: Alle Angaben und Fotos werden im Browser verschlüsselt, bevor sie zu Google gehen. Google speichert nur unlesbare Daten. Die Webversion liegt im Zweig `gh-pages` dieses Projekts.

**Android-App (optional):** zeigt genau diese Webversion an und ist damit immer auf dem gleichen Stand. Dazu kommen Geburtstags-Erinnerungen im Hintergrund, die App-Sperre und das Speichern von Sicherungen. Download: https://github.com/Cokakula/familienstammbaum-app/releases/latest/download/Familienstammbaum.apk

---

# Familienstammbaum – Android-App

Die App zeigt deinen Familienstammbaum und erinnert dich an Geburtstage, auch wenn sie geschlossen ist.

**Ende-zu-Ende-verschlüsselt:** Alle Angaben und Fotos werden auf dem Handy verschlüsselt, bevor sie zu Google gehen. Google speichert nur unlesbare Daten. Die Oberfläche und die Entschlüsselung stecken in der App selbst, nicht bei Google. Jede Einladung funktioniert nur einmal und gilt danach nur auf dem Gerät, auf dem sie geöffnet wurde.

## Installieren

1. Öffne auf dem Handy die Seite **Releases** dieses Projekts (rechts auf der GitHub-Seite) und lade `Familienstammbaum.apk` herunter.
   Direktlink zur neuesten Version: `https://github.com/Cokakula/familienstammbaum-app/releases/latest/download/Familienstammbaum.apk`
2. Tippe auf die heruntergeladene Datei. Android fragt beim ersten Mal, ob Chrome Apps installieren darf: **Einstellungen → Zulassen**, dann zurück und **Installieren**.
3. Falls Google Play Protect warnt («Unbekannte App»): **Weitere Details → Trotzdem installieren**. Die Warnung kommt, weil die App nicht aus dem Play Store stammt.
4. App **Stammbaum** öffnen und den Einladungslink einfügen:
   - Am einfachsten: In WhatsApp oder Gmail den Einladungslink lange drücken → **Teilen** → **Stammbaum**.
   - Oder den Link kopieren und in der App **Aus Zwischenablage einfügen** tippen.
   - Zum ersten Einrichten (Admin): die Adresse deiner Google-Web-App einfügen. Hattest du schon die alte Version, nimm deinen bisherigen Admin-Link; die App übernimmt die Daten verschlüsselt.
5. Mitteilungen erlauben, wenn Android danach fragt. Sonst kommen keine Geburtstags-Erinnerungen.

## Geburtstags-Erinnerungen

- Jeden Morgen um 08:00 Uhr (einstellbar) holt die App die Geburtstage aus dem Stammbaum.
- Mitteilung am Geburtstag, auf Wunsch auch am Vortag.
- Direkt aus der Mitteilung: **Anrufen** oder **WhatsApp** mit einem fertigen Glückwunsch (Deutsch und Albanisch). WhatsApp erscheint nur, wenn die Nummer im Stammbaum mit +41, +383 usw. gespeichert ist.
- Tippen auf die Mitteilung öffnet die Karte der Person.
- Einstellungen: im Stammbaum-Menü **Geburtstags-Erinnerungen**. Dort gibt es auch **Jetzt prüfen**.
- Erinnert wird an lebende Personen mit vollständigem Geburtsdatum. Wer am 29. Februar geboren ist, wird in anderen Jahren am 28. Februar erinnert.

Tipp für Pixel-Handys: Unter **Einstellungen → Apps → Stammbaum → Akku** «Uneingeschränkt» wählen, damit die Erinnerungen pünktlich kommen.

## App-Sperre und Privatsphäre

- Beim Öffnen und nach einer Minute im Hintergrund fragt die App nach Fingerabdruck, Gesicht oder PIN (in den App-Einstellungen abschaltbar).
- Der Zugang dieses Geräts liegt verschlüsselt im Android-Schlüsselspeicher und wird nicht in die Google-Sicherung des Handys übernommen.
- Der GPS-Standort von Fotos wird mitverschlüsselt. Android fragt dafür einmal nach «Zugriff auf Standort in Medien».

## Neue Version

Jede Änderung im Ordner `main` baut automatisch eine neue APK (Reiter **Actions**). Die neue APK einfach über die alte installieren; Link und Einstellungen bleiben erhalten.

## Technik

- Kotlin, WebView, WorkManager. Android 8 oder neuer.
- Gebaut von GitHub Actions (`.github/workflows/build.yml`).
- `app/app-signing.keystore` ist der feste Signaturschlüssel. Ohne ihn lassen sich Updates nicht über die installierte App installieren. Er enthält keine Familiendaten.
- Die Oberfläche liegt in `app/src/main/assets/www/index.html` (gleiche Datei wie die Browser-Version) und läuft über `https://appassets.androidplatform.net`.
- Die Google-Web-App braucht die verschlüsselte Version von `Code.gs` (mit `doPost`).
