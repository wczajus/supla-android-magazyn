# Dwa magazyny i bilansowanie godzinowe

## Dane dostępne w SUPLI

| Dane | Znaczenie | Zastosowanie |
| --- | --- | --- |
| `date_timestamp` | czas rzeczywistego pomiaru | granice okresu i godzin |
| `phase1_fae`, `phase2_fae`, `phase3_fae` | narastająca energia czynna pobrana na fazach | przyrost poboru w godzinie |
| `phase1_rae`, `phase2_rae`, `phase3_rae` | narastająca energia czynna oddana na fazach | przyrost oddania w godzinie |
| `fae_balanced`, `rae_balanced` | liczniki bilansowane wektorowo | nie zastępują bilansu godzinowego |
| lokalne rekordy wykresów | przyrosty Float, czasem sztucznie uzupełnione | nie są źródłem obliczeń magazynu |

Źródło danych: REST `GET /api/{version}/channels/{id}/measurement-logs`. `ElectricityMeasurement.kt` zawiera wszystkie wymienione pola. `LoadElectricityMeterMeasurementsUseCase.hourlyBalance()` i `ElectricityMeterLogEntityExtensions.balanceHourly()` potwierdzają, że SUPLA oblicza bilans godzinowy z przyrostów sześciu liczników fazowych. Wariant magazynu pracuje na surowych liczbach całkowitych, w jednostkach 0,00001 kWh.

Opis operatora: [TAURON — mikrobilansowanie](https://www.tauron.pl/dla-domu/obsluga-i-pomoc/strefa-prosumenta/mikrobilansowanie-zasady-rozliczania). Najpierw sumuje się pobór i oddanie na trzech fazach w tej samej godzinie, potem wyznacza różnicę. Nadwyżki z innych godzin nie są kompensowane przed zastosowaniem potrącenia.

## Obliczenia

Dla każdej pełnej godziny h:

- P(h) = suma przyrostów trzech liczników FAE.
- O(h) = suma przyrostów trzech liczników RAE.
- Pobór rozliczany D(h) = max(P(h) − O(h), 0).
- Oddanie rozliczane E(h) = max(O(h) − P(h), 0).

D = suma D(h), E = suma E(h). Przy potrąceniu 20% współczynnik k wynosi 0,8, przy 30% wynosi 0,7. Nowo zgromadzona energia C = E × k. Potrącenie stosujemy po bilansowaniu, raz do sumy oddania, z zaokrągleniem w dół dopiero do surowej jednostki API.

Dla puli S z faktury:

- Wykorzystano poprzednią = min(S, D).
- Pozostało w poprzedniej = max(S − D, 0), o ile nie minął jej termin.
- Wykorzystano bieżącą = min(C, max(D − S, 0)).
- Pozostało w bieżącej = C − wykorzystano bieżącą.
- Pobór poza obiema pulami = max(D − S − C, 0).

To rozliczenie sum w jednym zadanym okresie, z pierwszeństwem puli z faktury. Nowy eksport może zmienić prowizoryczny wynik bieżącego okresu, także pokryć jego wcześniejszy pobór ponad starą pulę. Nie prowadzimy transakcyjnego rozliczenia każdej kWh według momentu jej udostępnienia.

Przykład godziny: pobór 2 kWh, oddanie 5 kWh → oddanie po bilansie 3 kWh → do nowej puli trafia 2,4 kWh albo 2,1 kWh. Przykład okresu: S = 200 kWh, D = 250 kWh, C = 120 kWh → poprzednia pula 0, bieżąca 70 kWh; z bieżącej wykorzystano 50 kWh.

## Zakres czasu i jakość danych

Obie pule są liczone od północy pierwszego dnia okresu w Europe/Warsaw. Bilans obejmuje wyłącznie pełne godziny, dla których dostępny jest pomiar na końcu albo po końcu godziny. Na ekranie pokazujemy czas końcowej rozliczonej granicy. Bieżąca, niezamknięta godzina nie zmienia salda.

Jeżeli próbki nie wypadają na granicy godziny, graniczne liczniki są interpolowane liniowo między sąsiednimi rzeczywistymi pomiarami oddalonymi najwyżej o godzinę. Ekran jawnie oznacza taki wynik jako szacunek. Użycie epokowych godzin odróżnia dwa wystąpienia 02:00 przy jesiennej zmianie czasu i obsługuje 23-godzinną dobę wiosenną.

Brak próbki przed początkiem okresu, niepełne liczniki fazowe lub przerwa dłuższa niż godzina blokują nowe obliczenie. Nie podstawiamy zer ani nie rozkładamy wielodniowej energii równomiernie. Zachowujemy wcześniejszy kompletny wynik, jego datę i komunikat błędu. Spadek dowolnego licznika FAE/RAE blokuje oba salda jako reset. Zmiana profilu podczas pobierania blokuje zapis danych do niewłaściwego konta.

Pierwsze przeliczenie pobiera historię stronicami od początku okresu. API może ograniczać rozmiar stron, dlatego koniec pobierania wyznacza osiągnięcie granicy lub pusta strona. Następne odświeżenia korzystają z zapisanego punktu kontrolnego: sześciu liczników na ostatniej zamkniętej godzinie i dwóch sum. Nie przechowujemy całego roku surowych pomiarów w preferencjach.

## Konfiguracja i przejście między okresami

Nowa konfiguracja i edycja domyślnie proponują dwa magazyny z bilansowaniem godzinowym. Wybierz potrącenie 20% albo 30% i zapisz. Do tej metody nie jest potrzebne ręczne wskazanie narastającego licznika — granicą jest pierwszy dzień okresu, a dane graniczne odtwarzamy z historii.

Dotychczasowy zapis zachowuje dotychczasowy sposób obliczeń aż do świadomego zapisania nowej konfiguracji. Na ekranie pojawia się druga bateria i przycisk włączenia. Wyłączenie opcji godzinowej w formularzu udostępnia poprzedni tryb ręczny/historyczny dla jednej puli. Stare dane JSON są zgodne z nową wersją.

Po ostatnim dniu ważności obliczenia tego okresu są zamykane. Niewykorzystana stara pula wygasa; nowa pula pozostaje wynikiem tego okresu i nie jest zerowana tylko dlatego, że wygasła stara. Po otrzymaniu kolejnej faktury użytkownik uzgadnia kwotę z dokumentem i edytuje początek/koniec okresu. Nowe obliczenie bieżącej puli zaczyna się od zera dla nowego okresu.

To pomocniczy model dwóch pul dla jednego okresu. Nie odtwarza odrębnych miesięcznych terminów ważności wszystkich partii energii, rozliczania stref taryfowych ani kolejnych faktur automatycznie. Dokładne uzgodnienie z operatorem wymaga jego godzinowego raportu pomiarowego; własny MEW-01 i interpolowane granice mogą dać inny wynik.
