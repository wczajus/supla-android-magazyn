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
