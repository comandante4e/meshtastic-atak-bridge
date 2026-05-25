# Troubleshooting

Что делать когда что-то не работает. Идём сверху вниз — сначала нода, потом ATAK, потом плагин, потом сервер.

## Нода не видна по BT

**Симптом:** Meshtastic Android не находит ноду в Bluetooth pairing.

- Проверь питание ноды — LED должен мигать. Если нет — заряди / подключи к USB.
- Нода в BT pairing mode? Чтобы войти — short press кнопки User Button (`SW2` на T-Beam, левая кнопка) и держи 5 секунд, увидишь "Pairing mode" на экране.
- На телефоне: Settings → Bluetooth → **Forget** старую запись о ноде (если была), потом снова Pair new device.
- PIN по умолчанию `123456`. Если ты или прошлый владелец менял — нужен правильный.
- Если Android вообще не сканирует — проверь, что включён Location (BT scan на Android требует geo-разрешения).

## Нода спарилась, но Meshtastic Android её не видит

**Симптом:** в Settings → Bluetooth она есть, но в самом приложении вкладка Nodes пустая или показывает "Not connected".

- В Meshtastic Android: Settings → Choose Radio Device → выбери ноду явно.
- Перезапусти приложение полностью (swipe из recents).
- Проверь прошивку ноды (Settings → Radio Configuration) — должна быть ≥ 2.7.15. Старее — обнови через **Settings → Firmware Update** в самом приложении.
- Если прошивка не обновляется по BT — используй web-flasher [flasher.meshtastic.org](https://flasher.meshtastic.org/) с USB-кабеля.

## ATAK не видит плагин Meshtastic

**Симптом:** ATAK → ☰ → Plugins — пусто или нет Meshtastic.

- Проверь имя файла APK плагина: должна быть точная пара версий с ATAK-CIV. Если у тебя ATAK 5.6.x — плагин должен быть `...-5.6.0-civ-release.apk`. С плагином от 5.4 на ATAK 5.6 — не загрузится.
- Удали и переустанови плагин: Settings → Apps → ATAK-Plugin-Meshtastic → Uninstall → потом снова открой .apk.
- Перезапусти ATAK полностью.
- ATAK → Settings → Tool Preferences → Specific Tool Preferences → Plugin Management → найди Meshtastic → tap → Load.

## Плагин загружен, но "Disconnected"

**Симптом:** Plugins → Meshtastic → Status показывает "Disconnected" или "No nodes".

- Открой Meshtastic Android и убедись, что **там** связь с нодой есть и зелёная. Плагин использует service от Meshtastic Android — если основное приложение не подключено, плагин не получит данные.
- В Meshtastic Android: Settings → Module Settings → **ATAK Plugin → Enable** (это разрешение для node-side ATAK forwarding). Без этой галки плагин не получит ATAKPacket-сообщения, только TEXT_MESSAGE.
- В плагине ATAK: Settings → Bind to device — выбери ноду явно.
- Перезапусти оба приложения в порядке: убил ATAK → убил Meshtastic Android → запустил Meshtastic Android, дождался connection → запустил ATAK.

## Сервер: "Connection failed: SSL handshake error"

**Симптом:** Manage Server Connections — иконка красная, в логах SSL handshake / certificate / enrollment.

- Это самый ожидаемый провал в нашем сценарии: сервер не поддерживает username/password enrollment. См. [ADMIN_REQUEST.md](ADMIN_REQUEST.md) — попроси у админа data package.
- Если знаешь, что enrollment включён — проверь, что порт правильный: enrollment обычно на **8446** (не на 8089, который для готового SSL-CoT).
- Проверь, что hostname резолвится: открой в браузере телефона `https://hostname:8446/Marti/api/` — должна выдать что-то (даже 401 — ok, важно что TLS работает).

## Сервер: "Connection refused" / TCP error

- Hostname правильный? Может быть VPN нужен.
- Открой https://hostname:8089 в браузере — должна быть TLS-страница или 401. Если ничего — порт закрыт или сервер выключен.

## Сервер подключился, но мою точку никто не видит

- Проверь callsign — он не должен совпадать с чужим (иначе на чужой ATAK твоя точка перезатрёт его).
- В ATAK → ☰ → Settings → My Preferences → **Send Position** → ON, interval 30 сек.
- В ATAK → Network Status → tap на сервер → Show Server Details — должно быть "Sending CoT: yes".

## Чат идёт на сервер, но не в меш (или наоборот)

Сервер ↔ ATAK работает, меш ↔ ATAK работает, но мост между ними молчит.

- Плагин → Settings → **Forward Chat: ON**, **Forward PLI: ON**, **Bidirectional: ON**.
- Возможно, плагин фильтрует по callsign — проверь, что нет whitelist'а ограничивающего трафик.
- Проверь LoRa-конфиг ноды: если modem preset слишком быстрый (e.g. `ShortFast`) при слабом сигнале пакеты теряются. На дистанции 1+ км используй `LongFast` / `LongModerate`.

## Меш молчит совсем

- На вкладке Channels в Meshtastic Android посмотри последние RX-пакеты от соседних узлов. Если их нет — либо ты вне зоны, либо разные каналы/PSK.
- Сверь PSK с соседями (Channel → Share → QR-code, чтобы соседи отсканили). Если PSK не совпадают — никто никого не слышит.
- Region у ноды правильный? RU/EU — должно быть `EU_868` (или то, что у соседей).

## Энергопотребление / нода разряжается за час

- Не относится напрямую к функциональности, но: в Settings → Power → выбери Power Saving preset, отключи лишние модули (Telemetry, Range Test).
- Понизь `tx_power` если соседи близко — экономит батарею.

## Plan B: пора переходить к Phase B

Если после всех попыток связь с сервером невозможна (нет enrollment + нет data package), Phase A заглушена. Скажи мне — заводим Android-проект под кастомный Bridge APK с REST-клиентом.
