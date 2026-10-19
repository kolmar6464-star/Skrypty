# Skrypty PowerShell — projekty Windows 11 i NTLite

Repozytorium do tworzenia i ulepszania skryptów PS1 z pomocą Codex.

## Jak zacząć
1. Wejdź na https://chatgpt.com/codex i zaloguj się na swoje konto ChatGPT.
2. W przeglądarce albo aplikacji komputerowej utwórz i opublikuj środowisko Codex, wskazując repozytorium `kolmar6464-star/Skrypty`.
3. W nowym zadaniu wybierz to środowisko, wklej opis żądanego skryptu i użyj trybu **Code**.
4. Sprawdź plik PS1, raport z testów i podsumowanie zmian. Jeśli potrzebne, poproś Codex o poprawki.
5. Zaakceptuj i zapisz zmiany w repozytorium. Pobrane PS1 przetestuj we własnym Windows, najlepiej najpierw na maszynie wirtualnej.

## Co znajdziesz w repozytorium?
- `AGENTS.md`: standardy i wymagania dla Codex.
- `scripts/Nowy-Skrypt.ps1`: prosty, nieszkodliwy przykład skryptu z raportowaniem.
- `tests/Nowy-Skrypt.Tests.ps1`: bazowe testy Pester.
- `.github/workflows/powershell-ci.yml`: automatyczna kontrola składni i testów.
- `docs/CODEX-START.md`: instrukcja z gotowymi poleceniami.

## Uwaga — repozytorium PUBLICZNE
**Nie umieszczaj tutaj haseł SMTP, Wi-Fi, tokenów ani danych prywatnych.**
Do projektów z poufnymi ustawieniami zmień widoczność na `Private` w GitHub, a sekrety i tak trzymaj poza kodem.

Automatyczne testy nie zastępują weryfikacji działania skryptów NTLite na Windows 11.
