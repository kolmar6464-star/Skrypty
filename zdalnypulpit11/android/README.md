# Zdalny Pulpit 11 ID — Android 2.0

Aplikacja Android stanowi prosty launcher oficjalnego RustDesk 1.5.0. Wpisujesz wyłącznie ID PC; przycisk Połącz uruchamia `rustdesk://connection/new/{ID}` w RustDesk na telefonie. ID jest zapamiętywane lokalnie.

Nie wymaga Tailscale ani 64-znakowego tokenu. Wymaga **pierwszego bezpiecznego uwierzytelnienia w RustDesk**. Aby nie wpisywać hasła przy każdym połączeniu, skonfiguruj hasło stałe na swoim PC i zezwól RustDesk Android zapamiętać je.

Działające przyciski: połączenie zdalne RustDesk poprzez ID, instrukcje screenshota Android do galerii, wyłączania/restartowania komputera w zdalnej sesji, sterowania Androidem z Windows i Wake-on-LAN.

**Ograniczenia**: screenshot nie jest autonomicznie pobierany z Windows w oryginalnej rozdzielczości; Android zapisuje zdjęcie ekranu telefonu przy użyciu przycisków systemowych. Wyłączanie/restart wykonuje się w sesji RustDesk. Wake-on-LAN spoza domu wymaga infrastruktury w sieci domowej. Nie istnieje niezależna usługa przyjmująca komendy po samym ID.

APK podpisany kluczem **debug** budowniczego. Aby przejść z poprzedniego Zdalny Pulpit 11 PLUS v1.1 na v2.0 może być konieczne odinstalowanie poprzedniej aplikacji (inny klucz podpisu); RustDesk pozostaje osobną aplikacją, nie trzeba go usuwać.
