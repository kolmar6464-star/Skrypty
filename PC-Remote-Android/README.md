# PC Remote — Android + Windows 11

Projekt testowy przygotowany do współpracy z istniejącym Menedżerem Powiadomień PC. **Nie modyfikuje** dotychczasowych skryptów WP/Gmail/Android PUSH.

## Budowanie APK
1. Przejdź do zakładki **Actions** i wybierz **Build PC Remote APK**.
2. Otwórz najnowszy run dla gałęzi `pc-remote-android-20261020`.
3. Jeśli build zakończy się sukcesem, pobierz artefakt **PC-Remote-APK-i-Windows** (ZIP).
4. ZIP z artefaktem zawiera `app-debug.apk` i `PC_REMOTE_BRIDGE.ps1`. APK jest **wersją debug** podpisaną testowym kluczem; nie jest publikacją w Google Play.

Źródła projektu i skrypt Windows są w archiwum `PC-Remote-Android/source.tar.xz.b64`. Workflow automatycznie dekoduje archiwum i kompiluje projekt, bez konieczności instalowania Android Studio na komputerze użytkownika.

## Parowanie i test
- Instalator agenta: uruchom `PC_REMOTE_BRIDGE.ps1 -Mode Install` w Windows PowerShell jako administrator.
- Dane parowania: `powershell.exe -NoProfile -File "C:\ProgramData\PCRemoteBridge\PC_REMOTE_BRIDGE.ps1" -Mode Pair`
- W aplikacji przepisz kanał statusu, kanał poleceń i klucz parowania. **Nie umieszczaj ich w repozytorium ani w wiadomościach publicznych.**
- Test: `powershell.exe -NoProfile -File "C:\ProgramData\PCRemoteBridge\PC_REMOTE_BRIDGE.ps1" -Mode Test`
- Raport: `C:\Raport_PC_Remote.log`.

## Ograniczenia
- Serwer `ntfy.sh` jest publiczną usługą; bez własnego serwera nie ma gwarancji dostępności ani prywatności metadanych. Polecenia wyłączenia wymagają ważnego podpisu HMAC.
- Powiadomienia z komputera przy nagłym zaniku prądu nie są możliwe.
- Wake-on-LAN z telefonu działa tylko przy prawidłowej konfiguracji komputera i sieci lokalnej; spoza sieci wymaga dodatkowej infrastruktury.
- Aplikacja nie rozpoznaje automatycznie „nieautoryzowanego użytkownika”; powiadamia o starcie komputera.
- Agent nie wymaga haseł pocztowych, a repozytorium ich nie zawiera.
- Kompilacja i działanie na Twoich urządzeniach nie są potwierdzone do czasu przejścia CI i lokalnego testu.
