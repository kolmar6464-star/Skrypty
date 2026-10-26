# Zdalny Pulpit 11 PLUS (Android) — test build

Android 10+ (API 29+). Kompanion Windows EXE, pełny pulpit RustDesk oraz Tailscale są instalowane oddzielnie.
Funkcje: zdalny screenshot PNG zapisywany do Galerii telefonu, wyłączenie/restart Windows, uruchomienie aplikacji RustDesk i wskazówki do sterowania Androidem z PC.

Po pierwszym uruchomieniu użytkownik wpisuje adres urządzenia Tailscale (100.64.0.0/10) i kod parowania pokazywany w instalatorze Windows. Dane są zapisywane tylko lokalnie na telefonie. Połączenia z panelem Windows są ograniczone do adresów 100.64.0.0/10 i zabezpieczone 64-znakowym tokenem; tunel Tailscale szyfruje transfer.

Włączenie całkowicie wyłączonego komputera (Wake-on-LAN) NIE zostało jeszcze zaimplementowane. Wymaga routera/przekaźnika w domu oraz obsługi UEFI/Ethernet. Funkcja 'Włącz komputer' pokazuje instrukcję.
APK jest podpisany standardowym kluczem debug GitHub Actions, nie kluczem produkcyjnym; nie używaj do dystrybucji publicznej bez własnego klucza podpisu.

W repozytorium używana jest izolowana gałąź build-zdalny-pulpit-11-plus-apk-20261026.