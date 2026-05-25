# Гайд установки: ATAK ↔ Meshtastic мост (Phase A)

Цель — на одном смартфоне с подключённой по BT Meshtastic-нодой получить рабочий мост: PLI/чат/CoT уходят и на публичный TAK Server, и в меш-сеть одновременно.

Кода писать не надо: ставим три готовых APK, импортируем серверный data package (или подключаемся через enrollment), активируем плагин.

## Матрица совместимости (актуально на май 2026)

| Компонент | Версия | Где взять |
|---|---|---|
| **ATAK-CIV** | 5.6.0.12 | Google Play: [com.atakmap.app.civ](https://play.google.com/store/apps/details?id=com.atakmap.app.civ) · или [tak.gov](https://tak.gov/products/atak-civ) (нужна регистрация) · зеркало комьюнити: [civtak.org](https://www.civtak.org/download-atak/) |
| **Meshtastic Android** | 2.7.13+ | Google Play: [com.geeksville.mesh](https://play.google.com/store/apps/details?id=com.geeksville.mesh) · GitHub: [Meshtastic-Android/releases](https://github.com/meshtastic/Meshtastic-Android/releases) |
| **ATAK-Plugin (Meshtastic)** | 1.1.42 | [github.com/meshtastic/ATAK-Plugin/releases](https://github.com/meshtastic/ATAK-Plugin/releases) — качай APK с именем вида `ATAK-Plugin-Meshtastic.Plugin-1.1.42-...-5.6.0-civ-release.apk` |
| **Прошивка ноды** | 2.7.15+ | через web flasher [flasher.meshtastic.org](https://flasher.meshtastic.org/) или через сам Meshtastic Android |

**Важно:** версия плагина должна совпадать с major-версией ATAK-CIV (`5.6.0-civ` в имени файла). Ставить плагин от другой ветки ATAK — не будет грузиться.

---

## Шаг 1. Прошивка и спаривание ноды

1. Открой **Meshtastic Android**. Если прошивка ноды старше 2.7.15 — обнови через Settings → Firmware Update (нода будет переключена в DFU и перепрошита по BT).
2. Settings → Bluetooth → Pair new device → выбери ноду (имя вида `Meshtastic_xxxx`), введи PIN (по умолчанию `123456`).
3. На вкладке **Nodes** — нода должна появиться зелёной.
4. На вкладке **Channels** — убедись, что первичный канал тот, через который ATAK будет ходить. PSK у тебя уже стоит — не трогай.
5. **Включи ATAK forwarding в Meshtastic Android:** Settings → Module Settings → ATAK Plugin → enable. Это критично — без этой галки плагин не получит данные от ноды.

## Шаг 2. Установка ATAK-CIV

Поставь из Play Store (проще) или загрузи .apk с tak.gov / civtak.org и установи вручную.

Первый запуск:
- Callsign — твой позывной (виден всем на сервере и в меше).
- Team color — твой цвет.
- Role — Team Member.
- Дай разрешения: Location (precise), Storage, Bluetooth.

Закрой и переоткрой ATAK один раз — убедись что карта рисуется и GPS lock есть (синий ромб с твоим позывным).

## Шаг 3. Установка плагина Meshtastic в ATAK

1. Скачай APK плагина с [GitHub Releases](https://github.com/meshtastic/ATAK-Plugin/releases) — файл вида `ATAK-Plugin-Meshtastic.Plugin-1.1.42-...-5.6.0-civ-release.apk`.
2. Открой файл — Android спросит разрешение на установку из неизвестного источника, разреши.
3. После установки запусти ATAK → в правом верхнем углу появится баннер "New plugin detected: Meshtastic" → **Load Plugin**.
4. Если баннер не появился: ATAK → ☰ → Settings → Tool Preferences → Specific Tool Preferences → Plugin Management → найди Meshtastic → Load.

## Шаг 4. Подключение к TAK Server

У тебя только логин/пароль — это значит идём через **enrollment endpoint**. Если он на сервере выключен — fallback на data package от админа.

### 4а. Enrollment (попробовать первым)

1. ATAK → ☰ → Settings → Network Preferences → Manage Server Connections → **Add**.
2. Заполни:
   - **Description:** любая метка (например, "MyServer")
   - **Address:** hostname сервера (без `https://`, например `tak.example.com`)
   - **Port:** обычно `8089` для CoT SSL. Если знаешь enrollment-порт явно (типично `8446`) — указывай его.
   - **Protocol:** SSL
   - **Use Authentication:** **ON**
   - **Username / Password:** твои креды
   - **Use Default Server Certs:** ON (если не предоставлен CA от админа)
3. **Save**. ATAK выполнит HTTPS handshake к enrollment endpoint, получит client cert, сохранит соединение.

Если в Network Status иконка сервера зелёная — ты внутри. Переходи к шагу 5.

### 4б. Если enrollment не сработал

Симптомы провала:
- "Connection failed: SSL handshake error"
- "Server does not support enrollment"
- ATAK молча держит красную иконку

Это значит у сервера выключен endpoint для cert enrollment. Тогда:

1. **Напиши админу сервера** — попроси выпустить **data package** под твой username. Готовый текст — см. [ADMIN_REQUEST.md](ADMIN_REQUEST.md).
2. Получишь .zip-файл — кинь его на телефон любым способом (Telegram себе, USB, облако).
3. В ATAK: ☰ → Import Manager → Local SD → выбери .zip → **Import as TAK Connection**.
4. Connection появится в Manage Server Connections автоматически.

### 4в. Если админ не даёт data package

Phase A не пройдёт — переходим к Phase B (custom Bridge APK). Сообщи мне — заведём проект.

## Шаг 5. Активация плагина Meshtastic

1. ATAK → ☰ → Plugins → **Meshtastic** → Settings (шестерёнка).
2. **Bind to device:** выбери ноду, спаренную на шаге 1.
3. **Channel index:** 0 (первичный канал ноды). Если у вас в мешt несколько каналов и CoT идёт по вторичному — поставь его номер.
4. **Transmit PLI:** ON · **Interval:** 30 или 60 сек.
5. **Forward chat:** ON.
6. **Stale data threshold:** 5 минут (после этого PLI считается устаревшим и убирается с карты).

## Шаг 6. Проверка работы

| Что проверить | Где смотреть | Ожидание |
|---|---|---|
| Сервер подключён | ATAK → Network Status (значок в нижней панели) | Зелёная иконка, "Connected" |
| Меш подключён | Plugins → Meshtastic → Status | "Connected · N nodes visible" |
| Своя точка на карте | Карта ATAK | Синий ромб с твоим callsign |
| Чужие точки с сервера | Карта ATAK | Появляются через ~1 минуту |
| Чужие точки из меш | Карта ATAK | Появляются после первого их PLI |
| Чат на сервер | Chat → All Chat Rooms → отправь "test-server" | Видит другой ATAK на сервере |
| Чат в меш | Тот же чат | Видит другой узел с плагином в радиусе меш |

Если все строки зелёные — Phase A пройдена, ничего больше делать не надо.

Если что-то красное — открой [TROUBLESHOOTING.md](TROUBLESHOOTING.md).

---

## Что дальше

После успешной Phase A можно:
- Подключить второй телефон по этой же инструкции — проверить двустороннюю связь.
- Покрутить настройки плагина: интервал PLI, фильтры по callsign, ретрансляцию.
- Если нужны фичи, которых плагин не даёт (своя фильтрация, кастомные CoT type, гейтинг по правилам) — заводим Phase B (свой APK).
