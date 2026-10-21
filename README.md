# Osmo Pilot

Aplikacja na Androida (Kotlin) do sterowania **DJI Osmo Action 6** (oraz Action 4 / 5 Pro / Osmo 360)
wyłącznie przez Bluetooth LE, zgodnie z protokołem DJI R SDK z
[dji-sdk/Osmo-GPS-Controller-Demo](https://github.com/dji-sdk/Osmo-GPS-Controller-Demo).

## Pobranie APK

GitHub → zakładka **Actions** → workflow **Build APK** → ostatni udany run → sekcja *Artifacts*
→ `osmo-pilot-apk-N` (zip z plikiem `osmo-pilot-N.apk`). Na telefonie trzeba zezwolić na instalację
z nieznanych źródeł. Każdy build jest podpisany tym samym kluczem (`app/debug.keystore`),
więc kolejne wersje instalują się „na” poprzednią bez utraty parowania.

## Użycie

1. Przy pierwszym uruchomieniu zezwól na: Urządzenia w pobliżu (Bluetooth) i Powiadomienia.
   Zalecane: „Wyłącz optymalizację baterii” (przycisk na dole ekranu) – Samsung agresywnie usypia aplikacje.
2. Włącz kamerę → **Szukaj kamer** → wybierz kamerę (pogrubione = rozpoznana reklama DJI).
3. Pierwsze połączenie: na kamerze pojawi się prośba o zgodę – zaakceptuj. Kolejne połączenia
   (verify_mode=0) nie wymagają potwierdzenia.
4. Duży przycisk START/STOP steruje nagrywaniem. To samo jest w stałym powiadomieniu
   (widoczne także na ekranie blokady).
5. Ekran **Log** → *Kopiuj* / *Udostępnij*. Przełącznik „Pełny log RX” dodaje każdą ramkę statusu (2 Hz).
   Log jest też zapisywany do `files/logs/pilot.log` w danych aplikacji.

## Architektura

| Plik | Odpowiednik w demo ESP32 |
| --- | --- |
| `protocol/Crc.kt` | `utils/crc/custom_crc16.c`, `custom_crc32.c` (seed 0x3AA3) |
| `protocol/Frame.kt` | `protocol/dji_protocol_parser.c` + składanie ramek z notyfikacji |
| `protocol/Commands.kt` | `dji_protocol_data_descriptors.c`, struktury z `docs/protocol_data_segment.md` |
| `ble/GattClient.kt` | `ble/ble.c` (serwis FFF0, notify FFF4, write FFF5, MTU 512) |
| `ble/Scanner.kt` | filtr reklam DJI (`bsp_link_is_dji_camera_adv`), wybudzanie reklamą `WKP` |
| `ble/CameraController.kt` | `connect_logic.c`, `command_logic.c`, `status_logic.c` |
| `service/CameraService.kt` | foreground service (`connectedDevice`) + powiadomienie z przyciskiem |

Sekwencja połączenia: GATT → MTU → discovery → włączenie notyfikacji FFF4 →
`0019` (verify_mode 1 przy pierwszym parowaniu, 0 później) → ACK kamery → żądanie kamery z
`verify_mode=2` → nasza odpowiedź z SEQ kamery → `0000` (wersja) → `1D05` (subskrypcja statusu 2 Hz).
Po zerwaniu łącza aplikacja łączy się ponownie z `autoConnect=true` i powtarza handshake.

Testy jednostkowe protokołu (`app/src/test`) sprawdzają kodowanie na wektorach z dokumentacji DJI.
