# Zasady dla Codex — PowerShell / Windows 11 / NTLite

## Cel
Twórz stabilne, kompletne skrypty PowerShell (.ps1), przede wszystkim dla Windows 11 oraz instalacji z NTLite w fazach "Maszyna" (SYSTEM) i "Po zalogowaniu użytkownika", o ile użytkownik tego wymaga.

## Zasady obowiązkowe
1. Dostarczaj **pełny plik PS1** (nie urywki). Plik musi działać przy określonej wersji PowerShell: Windows PowerShell 5.1 / PowerShell 7, zgodnie z wymaganiami projektu.
2. Wstawiaj edytowalne ustawienia w sekcji `KONFIGURACJA` na początku każdego skryptu. Opisz każdą opcję po polsku.
3. Używaj czytelnych polskich komunikatów; pliki Windows PowerShell 5.1 z polskimi znakami zapisuj w UTF-8 z BOM, kiedy jest to potrzebne.
4. Najpierw sprawdź składnię; dodaj testy, jeżeli da się je uruchomić. Nie deklaruj, że NTLite lub Windows przeszły testy, jeśli testów tam nie wykonano.
5. Używaj obsługi wyjątków (`try/catch/finally`), kontroli praw administratora i czytelnych logów z datą oraz szczegółami błędów; nie wymuszaj podniesienia uprawnień, gdy skrypt działa jako SYSTEM lub nie potrzebuje administratora.
6. Zawsze uwzględniaj kontekst: uruchomienie interaktywne vs SYSTEM / NTLite. W trybie SYSTEM nie pokazuj GUI ani okien wymagających potwierdzenia.
7. Jeśli zlecono cichą instalację, uwzględnij jawnie zdefiniowane przełączniki (`-Silent`, `/s` itp.) i idempotentne działanie. Nie zakładaj, że parametry instalatorów różnych producentów są takie same.
8. Jeśli zlecono GUI, oddziel kod logiki od kodu interfejsu. Zachowaj poprawny układ, kontrolę skalowania DPI i zamykanie okna po zakończeniu.
9. Unikaj destrukcyjnych zmian domyślnie. Przed zmianami usług, rejestru czy usuwaniem plików przeanalizuj wpływ na system i opracuj plan przywrócenia.
10. **Nigdy nie umieszczaj sekretów w repozytorium.** Dotyczy to haseł SMTP, Wi-Fi, tokenów, kluczy API i prywatnych danych. W kodzie używaj konfiguracji poza repozytorium, menedżera poświadczeń lub zmiennych środowiskowych.
11. Przed zakończeniem zadania podaj: ścieżkę gotowego pliku, zakres zmian, wyniki faktycznie wykonanych testów oraz ewentualne ograniczenia.
12. Nie łącz wielu niezależnych aplikacji w jednym skrypcie bez wyraźnego powodu. Każdy większy projekt umieszczaj w osobnym podfolderze.

## Polecana struktura
- `scripts/`: krótkie skrypty i wzorce
- `projects/<nazwa>/`: większe narzędzia i GUI
- `tests/`: testy Pester
- `docs/`: instrukcje i decyzje techniczne

## Testowanie
- Kontrola parsera PowerShell przed zapisem.
- Testy Pester dla czystych funkcji i nieszkodliwych fragmentów.
- Testy NTLite / instalatorów / GUI wymagają osobnej weryfikacji na Windows 11 (najlepiej w maszynie wirtualnej).
