# Jak korzystać z Codex — instrukcja

## Jednorazowo
1. Zaloguj się w przeglądarce: https://chatgpt.com/codex
2. Przejdź do konfiguracji środowiska Codex Cloud.
3. Wybierz konto GitHub `kolmar6464-star` i repozytorium `Skrypty`.
4. Utwórz i **opublikuj środowisko** o nazwie np. `Skrypty PowerShell`.
5. Jeśli ekran prosi o autoryzację GitHub, zatwierdź ją na własnym koncie.

**Uwaga:** połączenie GitHub w ChatGPT nie potwierdza jeszcze utworzenia środowiska Codex Cloud. Jeśli repozytorium jest niewidoczne, sprawdź uprawnienia połączenia w ustawieniach GitHub/Codex.

## Za każdym razem
1. W Codex wybierz środowisko `Skrypty PowerShell`.
2. Wybierz `Code`, gdy Codex ma tworzyć lub zmieniać pliki, albo `Ask`, gdy ma jedynie analizować projekt.
3. Opisz wymagania (np. Windows 11, PowerShell 5.1, GUI, tryb NTLite SYSTEM lub użytkownik).
4. Przejrzyj wynik, testy i zmienione pliki. Poleć poprawić zgłoszone błędy.
5. Zapisz zatwierdzone zmiany w GitHub i pobierz właściwy plik PS1, aby przetestować go w Windows.

## Gotowe polecenie do nowego skryptu
```text
W repozytorium Skrypty utwórz kompletny nowy skrypt PowerShell 5.1 dla Windows 11.
Funkcjonalność: [OPIS FUNKCJI].
Wymagam polskich opisów, sekcji KONFIGURACJA na górze, obsługi błędów,
szczegółowego raportu LOG i gotowego pliku PS1.
Jeśli skrypt ma działać w NTLite, rozróżnij tryb SYSTEM i tryb użytkownika,
zapewnij możliwość cichej instalacji i brak interaktywnych okien w SYSTEM.
Wykonaj kontrolę składni i dostępne testy. Nie wpisuj żadnych haseł w kodzie.
Podaj link do utworzonego pliku i uczciwie wskaż, czego nie dało się przetestować.
```

## Gotowe polecenie do naprawy
```text
Przeanalizuj skrypt PS1 oraz dołączony raport LOG.
Zidentyfikuj pierwotną przyczynę awarii i napraw cały skrypt.
Nie usuwaj istniejących funkcji bez uzasadnienia.
Przygotuj kompletny poprawiony PS1, odnotuj zmiany i uruchom dostępne testy.
```

## Testowanie
Repozytorium ma workflow GitHub Actions do kontroli składni i testów Pester na Windows.
Automatyczny test nie oznacza, że GUI, instalatory czy etap NTLite SYSTEM zostały przetestowane w rzeczywistym systemie.

## Bezpieczeństwo
Repozytorium jest publiczne. Nie publikuj w nim danych logowania SMTP, haseł Wi-Fi, tokenów ani innych sekretów.
