# Folder LAN — Android APK

Foldery między komputerami Windows i telefonem w tej samej sieci Wi-Fi. Android 8.0 lub nowszy. Aplikacja bez konta w chmurze, reklam i zewnętrznego menedżera plików.

## Pobierz

- [Gotowy APK](https://github.com/kolmar6464-star/Skrypty/raw/refs/heads/main/downloads/Folder_LAN.apk)
- [Źródła i instrukcja ZIP](https://github.com/kolmar6464-star/Skrypty/raw/refs/heads/main/downloads/Folder_LAN_Zrodla.zip)

## Instalacja

1. Pobierz `Folder_LAN.apk` na telefon i otwórz go. Android może poprosić o jednorazowe zezwolenie na instalację z użytej przeglądarki lub aplikacji Pliki.
2. Połącz telefon i komputer z tą samą zaufaną siecią Wi-Fi. Sieć gościnna/izolacja klientów routera może blokować dostęp między urządzeniami.
3. Na komputerze pozostaw dotychczasowy skrypt Folder LAN i utworzony nim udział. Nie trzeba zmieniać NTLite, menu „Odepnij”, zadań ani parowania dwóch komputerów.

APK dystrybucyjny jest podpisany kluczem projektu, a prywatny klucz nie jest dołączony do źródeł. Nie jest to publikacja Google Play. Zapisane parowania pozostają przy aktualizacji podpisanej tym samym kluczem; odinstalowanie aplikacji usuwa je.

## Telefon otwiera folder komputera

1. Wybierz **Szukaj komputerów** albo **Dodaj komputer ręcznie**.
2. Podaj IP/nazwę komputera, udział `FolderUdostepnionyLAN`, konto `FolderLAN_SVC` oraz hasło z lokalnej konfiguracji swojego skryptu Windows. Domena może pozostać pusta, chyba że Twoja konfiguracja wymaga nazwy PC/domeny.
3. **Połącz i zapamiętaj** sprawdza logowanie. Po poprawnym połączeniu poświadczenia są szyfrowane AES-GCM kluczem Android Keystore. Następne otwarcie nie wymaga ponownego wpisywania hasła.
4. Można przeglądać podfoldery, pobierać pliki w miejsce wybrane przez Android, wysyłać pliki, tworzyć foldery, zmieniać nazwy i usuwać z potwierdzeniem. Upload nie nadpisuje istniejących plików; usuwane są tylko puste foldery.

**Bezpieczne parowanie:** hasło wpisujesz jednorazowo bezpośrednio na telefonie. Aplikacja nie wysyła go w discovery/HTTP ani do chmury, nie zawiera go w kodzie i nie zapisuje w logach. To nie jest dostęp anonimowy. Gdy hasło zmieniono na PC, usuń zapisane parowanie i dodaj je ponownie.

Oryginalny plik Windows nie jest dołączony do publicznego projektu, ponieważ zawiera poświadczenie. W razie potrzeby pomocnik `windows/Parowanie-Folder-LAN.ps1` może wyświetlić je **lokalnie**, po wyborze Twojego oryginalnego pliku i wyraźnym potwierdzeniu; nie wykonuje wybranego skryptu i nie przesyła hasła. Nie publikuj oryginalnego pliku ani ujawnionego hasła.

## Komputer otwiera folder telefonu

1. W aplikacji wybierz **Wybierz i udostępnij folder telefonu**. W systemowym oknie Android wybierz konkretny folder i przyznaj dostęp.
2. Potwierdź udostępnianie. Aplikacja pokaże adres `http://LOKALNE-IP:PORT/LOSOWY-LINK/`.
3. Wpisz ten adres w przeglądarce na komputerze. Otworzysz listę folderów i plików, pobieranie, wysyłanie do telefonu, nowy folder, zmianę nazwy i usuwanie z potwierdzeniem.
4. Pozostaw aplikację na ekranie. **Zatrzymaj udostępnianie telefonu** kończy dostęp. Wyjście aplikacji w tło, zamknięcie albo zmiana sieci także kończy serwer. Nowe uruchomienie tworzy inny losowy link.

Ten kierunek działa przez przeglądarkę PC, **nie jako serwer SMB telefonu ani automatyczny dysk w Eksploratorze Windows**. Komputer-komputer nadal działa jak wcześniej przez oryginalny skrypt. Nie ma automatycznej synchronizacji ani lustrzanych kopii wszystkich folderów — użytkownik wybiera, co udostępnić i przesłać.

Udostępnianie telefonu używa HTTP bez szyfrowania transportu. Działa tylko na prywatnym adresie Wi-Fi, z klientami tej samej podsieci i losowym linkiem dostępu; korzystaj z niego wyłącznie w zaufanej sieci. Osoba mająca link otrzymuje dostęp do wybranego folderu. Nie przesyłaj linku publicznie. Limit pojedynczego uploadu z przeglądarki to 2 GiB.

## Rzeczywisty protokół Windows

Przeanalizowano dostarczony `Folder_Udostepniony_LAN_NAPRAWA_REINSTALACJI_BEZ_JSON_v2.ps1`. W odróżnieniu od pierwotnego opisu używa on **TCP 47291**, nie UDP47291:

- zapytanie: `FOLDERLAN1|<losowy nonce 32hex>` + nowa linia;
- odpowiedź: `FOLDERLAN1|<nazwa PC>|<HMAC 64hex>`;
- Android odtwarza oryginalne obliczenie HMAC i potwierdza komputer hasłem podczas parowania wpisu znalezionego tym protokołem.

Skan jest ograniczony do jednej lokalnej /24 na adres Wi-Fi, z krótkimi timeoutami. Dostępne są dodatkowo NetBIOS, mDNS `_smb._tcp` i ręczny adres. Nazwa otrzymana przed parowaniem nie jest dowodem zaufania; właściwy dostęp w każdym przypadku wymaga uwierzytelnionego SMB3.

Opcjonalny pomocnik Windows odpowiada na własny protokół UDP47292 przez kilka minut; nie jest to zmiana oryginalnego protokołu. Nie dodaje reguł zapory, udziałów, kont, usług ani zaplanowanych zadań. Uruchamiaj go po zalogowaniu użytkownika:

```powershell
powershell.exe -NoProfile -STA -ExecutionPolicy Bypass -File .\windows\Parowanie-Folder-LAN.ps1 -OriginalScriptPath "C:\TwojFolder\oryginalny-skrypt.ps1"
```

SYSTEM/sesja0 nie otwiera GUI i kończy pomocnika kodem2. Pomocnik jest osobnym narzędziem, nie dodatkiem do fazy NTLite Maszyna.

## Bezpieczeństwo i błędy

- SMB3 wymaga podpisywania i szyfrowania. Nie ma SMB1, dostępu gościa ani automatycznego osłabiania zabezpieczeń.
- Adresy publiczne/Internet, multicast i loopback są blokowane. Nie przekierowuj portu445,47291 ani portu aplikacji w routerze do internetu. Internet mobilny bez bezpiecznego kanału nie jest obsługiwany.
- Brak Wi-Fi: połącz oba urządzenia z siecią lokalną. Komputer nie odpowiada: sprawdź jego zasilanie, IP, udział SMB i zaporę dla sieci prywatnej. Błędne poświadczenia: wpisz poprawne konto/hasło z PC. Brak prawa do zapisu: sprawdź uprawnienia udziału/NTFS lub wybranego folderu Android.
- Poświadczenia w Android Keystore nie są eksportowane do źródeł, backupu ani logów. Ekrany aplikacji chroni FLAG_SECURE. SMB hasło podane w interfejsie istnieje chwilowo w pamięci procesu; aplikacja nie obiecuje jego całkowitego wymazania ze wszystkich kopii pamięci JVM.

## Budowanie ze źródeł

Wymagane JDK17 lub21, Android SDK platform35/build-tools35.0.0 i Gradle8.13. Ustaw `ANDROID_HOME` albo `sdk.dir` w lokalnym, niepublikowanym `local.properties`.

```text
./gradlew assembleDebug testDebugUnitTest lintDebug
```

APK testowy: `app/build/outputs/apk/debug/app-debug.apk`. APK release przed podpisaniem: zadanie `assembleRelease`, potem `zipalign` i `apksigner` z własnym lokalnym kluczem. Kluczy i haseł podpisu nie umieszczaj w repozytorium. Wersje bibliotek i kopie licencji znajdują się w `THIRD_PARTY_NOTICES.txt`, również wewnątrz APK.

Potwierdzone wyniki i ograniczenia testów: `verification/WYNIKI.md`. Przed wdrożeniem docelowym sprawdź swój telefon, komputer Windows oraz sieć — test Linux/Samba lub emulatora nie potwierdza całej konfiguracji NTLite i uprawnień Twojego Windows.
