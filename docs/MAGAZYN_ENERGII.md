# SUPLA Magazyn — plan i budowanie

Wersja na Androida dla licznika ZAMEL MEW-01. Jedna ręcznie wprowadzana pula energii z faktury, pomniejszana wyłącznie o pobór energii czynnej z sieci od ustalonej daty. Oddanie energii i współczynnik 0,8 nie uczestniczą w obliczeniach. To pomocniczy licznik wykorzystania puli, nie odwzorowanie faktury dostawcy.

## Etapy

1. Bazowa APK i GitHub Actions; osobna nazwa aplikacji, stały podpis testowy.
2. Model magazynu, lokalny zapis przypisany do profilu i kanału, obliczenia i testy.
3. Zakładka magazynu przy liczniku: konfiguracja dat, salda i odczytu początkowego, ikona baterii i saldo.
4. Odczyty historyczne z API, obsługa braków danych, resetu i końca okresu.
5. Testy na rzeczywistym MEW-01, korekty i PR do master.

## Budowanie

Projekt upstream wymaga JDK 21, platformy Android 37 (`platforms;android-37.0` w katalogu SDK) i Build Tools 36.0.0. Biblioteki natywne są już w repozytorium. Workflow `.github/workflows/android-magazyn.yml` przygotowuje środowisko, kopiuje istniejące konfiguracje testowe Firebase, uruchamia testy i buduje uniwersalną APK. Workflow uruchamia się po pushu na gałęzie master i feature/**. Udany build publikuje APK w osobnym prerelease oraz zachowuje artefakt i raporty testów. Kontrole commita są widoczne także w PR; nie uruchamiamy drugi raz tego samego buildu dla zdarzenia pull_request.

Wariant `internaltest` ma identyfikator `org.supla.android.t`, więc instaluje się obok oficjalnego `org.supla.android`. Nazwa na telefonie: **SUPLA Magazyn**. Po pierwszej instalacji wymaga osobnego dodania konta SUPLA; dane oficjalnej aplikacji nie są automatycznie przenoszone. Konfiguracja Firebase pochodzi z upstreamowego wariantu testowego; powiadomienia push wymagają osobnej weryfikacji i nie są kryterium ukończenia modułu magazynu.

`ci/test-signing.p12.b64` jest celowo publicznym kluczem **wyłącznie do testów** (alias `androiddebugkey`, hasło `android`). Zapewnia identyczny podpis kolejnych APK, niezależnie od runnera. Nie służy do produkcji ani publikacji w Google Play. Nie zastępować go bez zaplanowania ponownej instalacji aplikacji. Numer wersji CI rośnie od 10001.

Gałąź robocza: `feature/magazyn-energii`. Zmiany trafiają do własnego repozytorium, przez PR do `master`.

Certyfikat testowy SHA-256: `5F:16:6D:6C:18:94:2B:A5:93:ED:09:F1:25:EA:24:98:2A:A6:E2:EA:2F:84:1D:27:88:C2:6C:D3:9C:BD:32:C0`. Gradle jawnie wskazuje plik `.gradle/magazyn-test-signing.p12`, a workflow sprawdza certyfikat gotowej APK przed publikacją. Lokalnie przed budowaniem należy wykonać `mkdir -p .gradle` i `base64 --decode ci/test-signing.p12.b64 > .gradle/magazyn-test-signing.p12`, a konfiguracje Firebase skopiować tak jak w workflow.

## Dostępny moduł — pierwsza wersja

Otwórz szczegóły kanału licznika energii, następnie zakładkę **Magazyn**. Dotyczy samodzielnego kanału licznika, nie grup urządzeń. Przewidziany dla ZAMEL MEW-01 z trzema licznikami energii czynnej pobranej z sieci.

1. Wpisz dostępną pulę z faktury w kWh, pierwszy dzień okresu oraz ostatni dzień ważności (włącznie).
2. Domyślnie aplikacja odczyta stan początkowy z historii SUPLI. Dopuszcza pomiar najwyżej dwie minuty przed początkiem okresu, pokazując jego faktyczny czas i oznaczając przybliżenie. Nie korzysta z interpolowanych danych wykresu.
3. Gdy historii brakuje, wybierz ręczny stan początkowy: narastający pobór z sieci, łącznie z trzech faz, na początek wskazanego okresu. To osobna wartość od salda z faktury.
4. Po zapisaniu widzisz pozostałą pulę, wykorzystanie, cały pobór i ewentualny pobór ponad pulę. Energia oddana nie jest doliczana. Obliczenia używają całkowitych jednostek 0,00001 kWh, bez zaokrągleń Float.
5. Aktualizacja następuje po otwarciu ekranu, ręcznie oraz po komunikacie licznika (najwyżej raz na minutę). Aplikacja nie musi pracować stale; używa różnicy narastających liczników.

Daty interpretujemy w strefie Europe/Warsaw, z uwzględnieniem zmiany czasu. Po końcu ważności nie odejmujemy późniejszego poboru — odczyt jest ograniczony do końca okresu. Gdy nie ma próbki dokładnie na granicy, niewykorzystana reszta jest oznaczona jako szacunek z ostatniego wcześniejszego pomiaru.

Konfiguracja i ostatni pomiar są lokalne, osobne dla profilu i kanału. Przy braku sieci pozostaje ostatnio zapisane saldo z datą pomiaru i komunikatem o nieudanym odświeżeniu. Usunięcie profilu usuwa też jego magazyny. Edycja zastępuje konfigurację jednej puli; nie prowadzimy archiwum faktur ani synchronizacji między telefonami.

Spadek sumy lub jednej z faz między dostępnymi odczytami blokuje saldo do korekty. Samo zwiększenie początkowej puli nie kasuje tego ostrzeżenia. Reset i ponowny wzrost w przerwie bez dostępnych pomiarów mogą pozostać niewykryte; nie odtwarzamy pełnej historii resetów. Saldo jest pomocniczym oszacowaniem według sumy poboru z faz, a nie wierną symulacją rozliczenia dostawcy i bilansowania.

## Sprawdzenie na telefonie

- Zainstaluj APK z najnowszego prerelease i dodaj swoje konto SUPLA.
- Sprawdź, czy na MEW-01 działa zakładka Magazyn w jasnym i ciemnym motywie.
- Dla znanego okresu porównaj przyrost poboru z ręcznym wyliczeniem. Sprawdź też komunikat przy wyłączonym internecie.
- Dane własnego konta i rzeczywisty MEW-01 wymagają sprawdzenia na telefonie użytkownika. Testy automatyczne korzystają z fikcyjnych odczytów i nie łączą się z prywatną instalacją.

Workflow sprawdza formatowanie przez `:app:spotlessCheck`, buduje `:app:assembleInternaltest` i uruchamia cały zestaw testów jako `:app:testInternaltestUnitTest`, czyli na wariancie odpowiadającym instalowanej APK. Pozwala to współdzielić kompilację aplikacji między budowaniem a testami, bez drugiego kompilowania wariantu debug.
