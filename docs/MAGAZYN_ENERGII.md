# SUPLA Magazyn

Moduł dla Androida i licznika ZAMEL MEW-01. Obsługuje poprzednią pulę z faktury i drugą pulę zgromadzoną w bieżącym okresie, z potrąceniem 20% albo 30%. Obie pule mogą korzystać z bilansu godzinowego. Szczegółowa [analiza danych i obliczeń](MAGAZYN_BILANS_GODZINOWY.md) opisuje metodę, jakość historii i granice modelu.

W konfiguracji niezależnie ustaw **Magazyn bieżącego okresu (drugi magazyn)** i **Bilansowanie godzinowe**. Dla jednej puli z bilansem godzinowym wyłącz drugi magazyn i włącz bilansowanie. Potrącenie wybierasz wyłącznie dla drugiego magazynu. Obliczenia rozpoczynają się od pierwszego dnia okresu, bez ręcznego wyboru stanu licznika. Pierwsze pobranie historii może potrwać dłużej. Luki są uzupełniane z zachowaniem przyrostów liczników; ekran oznacza saldo przybliżone/częściowe i udostępnia szczegóły oraz pełne ponowne przeliczenie. Dotychczasowy zapis zachowuje starą metodę do zapisania nowej konfiguracji. Poniższy opis wyboru próbki dotyczy jednej puli bez bilansowania godzinowego, gdy oba przełączniki są wyłączone.

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

Po udanej budowie i publikacji job `cleanup` zachowuje pliki tylko z dwóch najnowszych udanych buildów tego workflow (łącznie ze wszystkich gałęzi). Usuwa APK i sumę kontrolną ze starszych wydań/prerelease oraz artefakty Actions, w tym raporty, ze starszych zakończonych przebiegów. Historia wydań, tagi i logi workflow pozostają. Trwające przebiegi są chronione do kolejnego czyszczenia. Build ręczny bez publikacji też podlega tej regule; nieudana budowa/publikacja nie uruchamia czyszczenia. Przed usuwaniem sprawdzamy obecność nowego artefaktu APK, a błędy API inne niż brak już usuniętego pliku zatrzymują job. Regułę i jej zabezpieczenia weryfikuje `node --test ci/retain-build-artifacts.test.cjs`.

Aplikacja **SUPLA Magazyn** ma identyfikator `org.supla.android.t`, więc działa obok oficjalnej SUPLI. Zachowuje podpis poprzednich APK i rosnący versionCode, aby umożliwić aktualizację z zachowaniem danych. Pierwsza instalacja wymaga osobnego dodania konta SUPLA. Konfiguracja Firebase pozostaje wariantem testowym upstreamu; powiadomienia push nie zostały sprawdzone z prywatną instalacją użytkownika.

Budowanie wymaga JDK 21, platformy SDK `platforms;android-37.0` i Build Tools 36.0.0. Natywne biblioteki są w repozytorium. Workflow kopiuje konfiguracje `drone.google-services.json` do odpowiadających plików `google-services.json` i dekoduje `ci/test-signing.p12.b64` do `.gradle/magazyn-test-signing.p12`.

Klucz jest celowo publicznym kluczem deweloperskim, wspólnym z dotychczasowymi wersjami testowymi. Alias i hasło: `androiddebugkey` / `android`. Nie jest kluczem produkcyjnym do Google Play. Certyfikat SHA-256: `5F:16:6D:6C:18:94:2B:A5:93:ED:09:F1:25:EA:24:98:2A:A6:E2:EA:2F:84:1D:27:88:C2:6C:D3:9C:BD:32:C0`.

## Weryfikacja na telefonie

Testy automatyczne korzystają z fikcyjnych odczytów. Na rzeczywistym MEW-01 należy sprawdzić pobieranie historii, wybór daty i próbki, ręczne porównanie salda, działanie offline oraz wygląd w obu motywach. Zmiany przechodzą przez PR i po zielonych kontrolach są scalane do `master`.

## Układ ekranu

Kolejność: pasek licznika i tytuł → poprzedni magazyn z jego szczegółami w jednej karcie → opcjonalny bieżący magazyn → suma obu magazynów → karta jakości danych → Odśwież/Edytuj → stopka. Karta „Łącznie do wykorzystania” pojawia się także przy jednym magazynie: dodajemy pozostałe salda włączonych magazynów bez ponownego potrącania współczynnika, z oznaczeniem przybliżenia i bez baterii/procentu. Przy niepoprawnym pomiarze nie pokazujemy fałszywego zera. Procent poprzedniej puli to pozostała energia podzielona przez początkową, a bieżącej — pozostała energia podzielona przez zgromadzoną po potrąceniu. Przy zerowej zgromadzonej energii pokazujemy 0,0%. Odczyty energii, sumy, procent dostępnej energii i czasy przerw mają dokładnie jedno miejsce po przecinku, z zaokrągleniem HALF_UP. Dotyczy to również szczegółów historii i wybranego odczytu. Zaokrąglenie następuje dopiero podczas wyświetlania; wartości przechowywane, obliczenia i pola edycji zachowują pełną dokładność. Liczby przerw, współczynniki 70%/80% i daty zachowują swój naturalny format.

Karta poprzedniego magazynu zawiera początkowy stan i wykorzystanie. Pobór z sieci oraz jeden czas rozliczenia danych znajdują się pod sumą w karcie „Łącznie do wykorzystania”, zarówno dla jednego, jak i dwóch magazynów. W trybie godzinowym pokazujemy granicę pełnych godzin, a w trybie bez bilansowania — czas danych. Metoda bilansowania, objaśnienie szacunku i powtórzony ostatni pomiar nie są wyświetlane w tej karcie; szczegóły jakości pozostają w osobnej karcie poniżej magazynów. W bieżącym magazynie etykieta naliczonej energii brzmi „W magazynie”, a wybrany współczynnik pozostaje w konfiguracji.

„Usuń magazyn” jest dostępne w edycji, z dotychczasowym potwierdzeniem. Przy błędzie odczytu konfiguracji przycisk Edytuj prowadzi do tego samego miejsca, aby nadal można było usunąć uszkodzony zapis.

Energia i daty w formularzu mają wspólny styl `OutlinedTextField`; datę wybiera się przez dotknięcie pola lub ikony kalendarza. Ikona informacji obok „Bilansowanie godzinowe” otwiera przewijane wyjaśnienie obu trybów, przykładu godzinowego, kolejności wykorzystania magazynów i współczynnika.

Typografia podsumowania odpowiada ekranowi „Ogólne” licznika: oba salda i suma używają `labelLarge` (17 sp) jak energia czynna pobrana/zwrócona, etykiety `bodyMedium` (14 sp), wartości szczegółowe `labelMedium` (14 sp), a objaśnienia `bodySmall` (12 sp). Rozmiary pochodzą z motywu SUPLI i respektują systemową skalę czcionki. Karty korzystają ze wspólnego `suplaCard()` (obramowanie i promień z aplikacji) oraz odstępów `Distance`: 24 dp na zewnątrz, 16 dp wewnątrz i 8 dp między elementami. Akumulatory mają 96×48 dp z proporcjonalnie zmniejszonym obrysem. Wartość w szczegółach zajmuje szerokość potrzebną na liczbę i jednostkę, a etykieta pozostałe miejsce. Krótkie etykiety „Stan początkowy”, „Wykorzystano”, „Oddano do sieci” i „W magazynie” ograniczają przypadkowe zawijanie bez zmniejszania czcionki; przy większej skali tekst nadal może się zawijać. Informacja „Po bilansowaniu godzinowym” lub „Bez bilansowania godzinowego” jest osobnym opisem nad oddaniem do sieci. Podglądy obejmują oba motywy, szerokość 320 dp oraz systemowe skale czcionki.

Formularz ma karty „Stan i okres rozliczeniowy”, „Sposób rozliczania” oraz warunkowo „Odczyt początkowy” (dla ręcznego stanu lub wyboru próbki historii). Używa wspólnego `suplaCard()` i odstępów `Distance`, pól z promieniem `radius_default`, wartości i przełączników 14 sp oraz etykiet i pomocy 12 sp. Przełączniki są po prawej stronie jak w ustawieniach licznika, a ikona pomocy bilansowania pozostaje przy etykiecie. Wiersze przełączników mają minimum 64 dp, a wyboru współczynnika 48 dp. Walidacja kwoty, stany niedostępności, wybór daty/historii i potwierdzenie usunięcia pozostają obsługiwane przez dotychczasowe akcje.

Opis G3 („Monitoruj pulę energii…”) został usunięty. Nagłówki obu magazynów mają jawne złamanie wiersza przed słowem „rozliczeniowego”, z zachowaniem zawijania przy większej systemowej skali czcionki.

Oba paski dostępnej energii mają wyłączony znacznik końca skali Material 3 (`drawStopIndicator = {}`), dzięki czemu przy 100% nie pozostaje osobna kropka.

## Widoczność magazynu

W ustawieniach samodzielnego licznika energii opcja „Pokaż magazyn” jest domyślnie włączona, również po odczytaniu konfiguracji starszej wersji. Jest zapisywana lokalnie w ustawieniach danego profilu i kanału. Jej wyłączenie usuwa zakładkę Magazyn z dolnego paska oraz bocznej nawigacji i uniemożliwia wyświetlenie ukrytej strony. Zmiana działa od razu; pozostajemy na wybranej dostępnej zakładce (np. Ustawieniach), a zapamiętana ukryta strona przechodzi na Ogólne. Indeksy zapamiętanych zakładek odnoszą się do pełnej listy, dzięki czemu Historia i Ustawienia nie zamieniają się po zmianie widoczności.

Przełącznik nie zapisuje, nie usuwa ani nie przelicza danych `EnergyReserveStore`. Ponowne włączenie udostępnia dotychczasową konfigurację i odczyty magazynu. Stopka informuje o lokalnym przechowywaniu i aktualizacji po nowej fakturze. W „Łącznie do wykorzystania” czas rozliczenia jest wyrównany do początku wiersza, pod etykietą poboru.
