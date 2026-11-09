# Weryfikacja Folder LAN 1.0

Wykonane kontrole:

- Rzeczywista kompilacja `assembleDebug` i `assembleRelease` przez Android SDK35 / AGP8.11.1 / Gradle8.13 / JDK21; kod aplikacji kierowany do Java17 i Android8+.
- `testDebugUnitTest`: **33 testy, 0 błędów, 0 pominiętych**: ścieżki plików i nazwy Windows, ograniczenie adresów LAN, fabryka gniazd wskazanej sieci, rzeczywisty format discovery TCP/HMAC i parsery, zabezpieczenia lokalnego serwera telefonu.
- `lintDebug`: **0 błędów**. Pozostają ostrzeżenia o nowszych wersjach SDK/zależności i ikonach, bez wyłączania kontroli błędów.
- **9 testów logiki helpera PowerShell** przez ekstrakcję funkcji AST: IPv4/podsieci, bezpieczny odczyt literału hasła ze sztucznego pliku, brak wykonania tego pliku, odmowa wyrażeń/niejednoznacznych przypisań/błędu parsera. Parser PowerShell przeszedł.
- **Rzeczywista integracja SMB3 na JVM z lokalną Sambą**, wymuszone szyfrowanie i podpisywanie: logowanie, lista, mkdir, upload/download z porównaniem bajtów, Unicode, ochrona przed nadpisaniem, rename/delete, odmowa usunięcia niepustego folderu, odmowa błędnego hasła i publicznego IP. To nie jest symulacja odpowiedzi SMB, ale serwer testowy nie jest Windows użytkownika.
- `apksigner verify`: poprawny podpis APK v2 i v3. APK ma nazwę `Folder LAN`, pakiet `pl.folderlan.app`, minSDK26 i targetSDK35. Wersja do pobrania jest release, nie debug.
- **Uruchomienie aktualnego release APK na emulatorze Android9/API28:** instalacja zakończona Success, start MainActivity Status:ok, polski ekran główny i okno parowania zaobserwowane. Nie stwierdzono wpisu awarii aplikacji. Test parowania SMB i zachowania poświadczeń po restarcie procesu nie został dokończony; nie jest zaliczony.
- Sprawdzono, że hasło z dostarczonego oryginalnego PS1 nie występuje w źródłach projektu ani w rozpakowanych wpisach APK; oryginału nie dołączono. Klucza podpisującego APK i jego hasła również nie dołączono.

Log kompilacji: `gradle-build.log`; testy: `testy-junit.txt`, `testy-helpera.txt`, `testy-smb3.txt`; podpis i metadane: `podpis-apk.txt`, `apk-info.txt`. Wynik uruchomienia na emulatorze Android: `android-smoke.txt`.

## Granice potwierdzenia

Nie wykonano prób na fizycznym telefonie użytkownika, jego Windows, NTLite ani jego routerze. Nie potwierdzono rzeczywistego wykrywania i HMAC między Androidem a oryginalnym PS1 na Windows; potwierdzono implementację protokołu i testowy wektor. Brak tych prób nie jest dowodem działania konkretnej zapory, uprawnień NTFS/udziału ani konfiguracji Wi-Fi.

Udostępnianie telefonu przez SAF i HTTP wymaga oddzielnej próby na docelowym urządzeniu. Jest to dostęp przez przeglądarkę PC, nie SMB/automatyczne wyświetlenie telefonu w Eksploratorze Windows. HTTP nie szyfruje transferów, dlatego funkcja jest przeznaczona wyłącznie do zaufanego Wi-Fi i wyłącza się po wyjściu aplikacji w tło.
