# SUPLA Magazyn

Moduł dla Androida i licznika ZAMEL MEW-01. Obsługuje poprzednią pulę z faktury i drugą pulę zgromadzoną w bieżącym okresie, z potrąceniem 20% albo 30%. Obie pule mogą korzystać z bilansu godzinowego. Szczegółowa [analiza danych i obliczeń](MAGAZYN_BILANS_GODZINOWY.md) opisuje metodę, jakość historii i granice modelu.

W nowej konfiguracji lub przy edycji wybierz **Dwa magazyny i bilansowanie godzinowe**, ustaw potrącenie i zapisz. Obliczenia rozpoczynają się od pierwszego dnia okresu, bez ręcznego wyboru stanu licznika. Pierwsze pobranie historii może potrwać dłużej. Dotychczasowy zapis zachowuje starą metodę do zapisania nowej konfiguracji. Poniższy opis wyboru próbki dotyczy trybu jednej puli, dostępnego po wyłączeniu opcji godzinowej.

## Konfiguracja

1. Otwórz samodzielny kanał licznika → **Magazyn → Skonfiguruj magazyn**.
2. Wpisz pulę z faktury w kWh, pierwszy dzień okresu oraz ostatni dzień ważności (włącznie). Daty liczymy w Europe/Warsaw, z uwzględnieniem zmiany czasu.
3. Dla automatycznej konfiguracji naciśnij **Wybierz odczyt z historii**. Domyślny zakres obejmuje dzień przed wskazaną datą, wybrany dzień i dzień po nim. Możesz zmienić obie granice, np. przeszukać wcześniejszy rok.
4. Aplikacja pyta o najbliższe próbki przed północą i po niej, bez pobierania całego roku danych. Pokazuje najwyżej dwa odczyty: najbliższy przed granicą (lub dokładnie o północy) i najbliższy po niej, w kolejności chronologicznej. Domyślnie zaznacza odczyt najbliższy północy; przy remisie wcześniejszy. Widać rzeczywistą datę, godzinę, stan w kWh i oznaczenie najbliższej próbki.
5. Zatwierdź odczyt, następnie zapisz konfigurację. Zapis wymaga dodatniej puli w kWh; puste lub niepoprawne pole blokuje przycisk. Aktywne akcje są zielone, niedostępne szare; pola zachowują czytelny kontrast także podczas edycji. Zmiana dat okresu kasuje wcześniejszy wybór, aby nie użyć niewłaściwego pomiaru.
6. Jeśli historia jest niedostępna, wybierz ręczny stan początkowy: narastający pobór z sieci, łącznie z trzech faz, na początek okresu. To osobna wartość od salda na fakturze.

Nie wymagamy próbki w ciągu dwóch minut przed północą. Przy wybraniu wcześniejszego odczytu wynik obejmie też pobór od tej próbki do początku okresu. Przy późniejszym odczycie początkowy fragment poboru zostanie pominięty. Przy wyborze widać stronę granicy i faktyczny czas bazowego pomiaru. Pomarańczowy komunikat o przesunięciu względem północy nie jest wyświetlany ani podczas konfiguracji, ani w podsumowaniu. Nie interpoluje ani nie wymyśla brakującego odczytu dokładnie na północ.

Szukane daty są włączne. Nie proponujemy próbek z przyszłości ani z chwili po końcu ważności. Podgląd historii nie zapisuje konfiguracji. Wybór musi zostać potwierdzony, a konfiguracja osobno zapisana.

## Obliczenia i przechowywanie

W poprzednim trybie jednej puli: saldo = pula początkowa − przyrost sumy trzech liczników FAE. Używamy surowych wartości API w całkowitych jednostkach 0,00001 kWh; bez Float i syntetycznych próbek wykresu. W trybie godzinowym wykorzystujemy także liczniki RAE: po bilansowaniu potrącamy wybrany procent od oddania, a pobór pomniejsza najpierw pulę z faktury, potem nową pulę.

Ekran pokazuje baterię, pozostałą pulę, wykorzystanie, cały pobór, pobór ponad pulę, termin i czas ostatniego pomiaru. Odświeżanie działa po otwarciu, ręcznie oraz po komunikacie licznika, najwyżej raz na minutę i przy widocznym ekranie. Aplikacja nie musi być uruchamiana codziennie.

Po końcu ważności nie odejmujemy późniejszego poboru. Odczyt jest ograniczony do końca okresu. Jeśli nie ma próbki dokładnie na granicy, niewykorzystana reszta jest oznaczona jako szacunek z ostatniego wcześniejszego odczytu.

Konfiguracja i ostatni pomiar są lokalne, osobne dla profilu i kanału. Przy braku sieci pozostaje ostatnie zapisane saldo z czasem pomiaru i komunikatem błędu. Usunięcie profilu usuwa jego magazyny. Edycja zastępuje jedną pulę; nie prowadzimy archiwum faktur ani synchronizacji między telefonami.

Spadek sumy lub jednej fazy między dostępnymi odczytami blokuje saldo do korekty. Sama zmiana wielkości puli nie usuwa ostrzeżenia. Reset i ponowny wzrost w przerwie bez dostępnych pomiarów mogą pozostać niewykryte. Saldo jest pomocniczym oszacowaniem, a nie odtworzeniem bilansowania i faktury dostawcy.

## APK release i aktualizacje

Workflow `.github/workflows/android-magazyn.yml` buduje niedebugowalny wariant `internalTestRelease`, uruchamia pełne testy `testInternalTestReleaseUnitTest` oraz `spotlessCheck`. Sprawdza podpis APK, identyfikator, nazwę i brak flagi debuggable. Gałęzie `feature/**` publikują podgląd jako prerelease. Po scaleniu `master` publikuje zwykły Release z plikiem `supla-magazyn.apk` i sumą SHA-256.

Aplikacja **SUPLA Magazyn** ma identyfikator `org.supla.android.t`, więc działa obok oficjalnej SUPLI. Zachowuje podpis poprzednich APK i rosnący versionCode, aby umożliwić aktualizację z zachowaniem danych. Pierwsza instalacja wymaga osobnego dodania konta SUPLA. Konfiguracja Firebase pozostaje wariantem testowym upstreamu; powiadomienia push nie zostały sprawdzone z prywatną instalacją użytkownika.

Budowanie wymaga JDK 21, platformy SDK `platforms;android-37.0` i Build Tools 36.0.0. Natywne biblioteki są w repozytorium. Workflow kopiuje konfiguracje `drone.google-services.json` do odpowiadających plików `google-services.json` i dekoduje `ci/test-signing.p12.b64` do `.gradle/magazyn-test-signing.p12`.

Klucz jest celowo publicznym kluczem deweloperskim, wspólnym z dotychczasowymi wersjami testowymi. Alias i hasło: `androiddebugkey` / `android`. Nie jest kluczem produkcyjnym do Google Play. Certyfikat SHA-256: `5F:16:6D:6C:18:94:2B:A5:93:ED:09:F1:25:EA:24:98:2A:A6:E2:EA:2F:84:1D:27:88:C2:6C:D3:9C:BD:32:C0`.

## Weryfikacja na telefonie

Testy automatyczne korzystają z fikcyjnych odczytów. Na rzeczywistym MEW-01 należy sprawdzić pobieranie historii, wybór daty i próbki, ręczne porównanie salda, działanie offline oraz wygląd w obu motywach. Zmiany przechodzą przez PR i po zielonych kontrolach są scalane do `master`.
