# CoT Bridge — ATAK ↔ Meshtastic

Android-приложение, превращающее телефон с ATAK-CIV и Meshtastic-нодой в **двусторонний шлюз** между публичным TAK Server и Meshtastic-мешем.

> 📖 **[Инструкция по установке и настройке — GUIDE.md](GUIDE.md)** — как поставить, получить cert от URPC, настроить обратку RX и раздать друзьям. Начни отсюда.

## Кому это нужно

Phase A (ATAK-CIV + Meshtastic ATAK Plugin) даёт тебе клиент, который виден на TAK Server **и** шлёт свой PLI/чат в меш. Но **чужие** CoT, которые приходят с TAK Server, плагин в меш не транслирует — это конструктивное ограничение плагина (он сидит на ATAK PreSendProcessor и видит только то, что ATAK сам отправляет, не то что получает). Этот APK закрывает дыру.

## Архитектура

```
[Публичный TAK Server (URPC, login/pass)]
            ▲
            │ TLS 8089 — это делает ATAK-CIV сам через enrollment cert
            ▼
[ATAK-CIV] ─UDP multicast 239.2.3.1:6969 (все исходящие+входящие CoT)─→ [CoT Bridge]
    ▲                                                                       │
    │ UDP 127.0.0.1:4242 (Network Input)                                    ▼ AIDL bind
    │                                                       com.geeksville.mesh.service.MeshService
    └─────────────────────────────────────────────────────────────│
                                                                  ▼ BLE
                                                            [Heltec нода] → меш
```

Bridge не общается с URPC сам — это делает ATAK. Bridge только перекидывает CoT XML между сокетами: multicast 6969 (от ATAK) → TAKPacket protobuf (в меш через IMeshService), и обратно.

## Что собирается на телефоне

| Что | Где |
|---|---|
| ATAK-CIV | OnePlus (после Phase A — уже работает с URPC) |
| Meshtastic Android | OnePlus, **стабильная 2.7.13** |
| Heltec Wireless Tracker | подключён по BT к Meshtastic Android, FW 2.7.15+, role TAK |
| **CoT Bridge** (этот APK) | OnePlus |

Honor не трогаем — у него работает Phase A (ATAK-CIV + Meshtastic ATAK Plugin), он получает мешевые точки через тот же AIDL-канал.

## Сборка

CI собирает .apk автоматически. Локально не обязательно.

### Через GitHub Actions (без своей машины)

1. Сделай новый GitHub-репозиторий, запушь содержимое этой папки в `main`.
2. CI стартанёт автоматически (`.github/workflows/build.yml`).
3. После завершения: Actions → последний run → Artifacts → `cotbridge-debug` → скачай .apk.
4. На тэг `vN.N.N` создаётся GitHub Release с прикреплённым .apk.

JitPack тянет `meshtastic-android-api`/`-model`/`-proto-android` v2.7.13 — первый билд может занять 3-5 минут пока JitPack компилирует Meshtastic-Android из исходников.

### Локально (если есть Android Studio)

```bash
gradle :app:assembleDebug
# артефакт: app/build/outputs/apk/debug/app-debug.apk
```

Требуется JDK 17 + Android SDK (compileSdk 35).

## Установка на OnePlus

1. Скинь .apk на телефон.
2. Открой файл — разреши установку из неизвестного источника.
3. При первом запуске разреши уведомления.

## Что настроить (один раз)

### ATAK-CIV — включить multicast вывод

ATAK по умолчанию **рассылает свои собственные** CoT в multicast 239.2.3.1:6969. Чужие (полученные с URPC) — **не по умолчанию**. Включить:

1. ATAK → ☰ → Settings → Network Preferences → **Network Connections**.
2. Найди `SA Multicast` (или `Default Multicast`) — отредактируй, `TX: ON`, `RX: ON`.
3. Settings → Tool Preferences → Specific Tool Preferences → нажми `Show All` (правый верх) → найди опцию `Forward incoming traffic from streams to multicast` (или похожее по смыслу — название в разных билдах меняется). Включи.
4. Дополнительно: Settings → Network Preferences → проверь, что **Network Input** включён и слушает UDP на порту 4242. Если нужно — добавь Network Connection типа `UDP`, port `4242`, direction `Receive`.

Без этого мост не увидит чужих точек на multicast.

### Meshtastic Android — оставь как есть

В Phase A ты уже подключил ноду по BT. Не отключай этот плагин в ATAK — он не помешает, и для **самой OnePlus** даёт корректное отображение своих + мешевых точек.

(Альтернатива — отключить плагин, чтобы он не дёргал тот же AIDL канал параллельно с нашим мостом. Если увидишь дубликаты в логе моста — отключим плагин, проверим. Пока оставь.)

## Запуск моста

1. Открой приложение CoT Bridge.
2. Поля `Multicast` / `UDP-порт` оставь по умолчанию (239.2.3.1:6969, 4242), если не менял в ATAK.
3. **Старт** → должно появиться уведомление "Мост активен — connected к Meshtastic".
4. Статус:
   - **Сервис** — зелёный
   - **Multicast от ATAK** — зелёный
   - **Meshtastic AIDL** — зелёный (если красный — Meshtastic Android не отвечает; перезапусти его)

## Проверка

| Что | Где смотреть | Ожидание |
|---|---|---|
| Свой PLI идёт в меш | счётчик `ATAK → bridge` и `bridge → меш` растут | через минуту после старта |
| Чужой PLI с URPC идёт в меш | те же счётчики растут когда чужие точки на карте обновляются | через минуту |
| Мешевые точки идут в ATAK | счётчики `меш → bridge` и `bridge → ATAK` растут | если в меше есть ещё участники |
| На Honor видна твоя точка с OnePlus | карта Honor | работает и в Phase A |
| **На Honor видны точки с URPC** | карта Honor | новое — этого Phase A не умел |

## Что поддерживается

- **PLI (Position Location Information)** — типы CoT `a-f-*` (friendly), `a-h-*` (hostile), `a-n-*` (neutral), `a-u-*` (unknown). Координаты, скорость, курс, callsign, team color, role.
- **GeoChat** — тип CoT `b-t-f`. Текст сообщения, callsign отправителя, имя чата.
- **Прочее (маркеры, drawing, casevac, sensor)** — пропускается. LoRa-bandwidth не вытянет, а в TAK_PLUGIN protobuf нет полей для произвольных маркеров. Если очень надо — добавим конвертацию `bytes detail` для CoT detail XML.

## Troubleshooting

- **Multicast от ATAK: ошибка** — Wi-Fi выключен или нет multicast lock. Проверь Wi-Fi/мобильный включён. Multicast работает только когда есть активный network interface.
- **Meshtastic AIDL: ошибка** — Meshtastic Android не установлен или не запущен. Запусти Meshtastic Android вручную, дождись пока в нём подключится нода (зелёный индикатор), потом перезапусти мост.
- **Счётчики `ATAK → bridge` нули** — ATAK не рассылает в multicast. Проверь Network Preferences (см. выше). Также проверь, что в multicast 6969 RX включён.
- **Чужие точки с URPC не идут в меш** — `Forward incoming traffic from streams to multicast` выключен в ATAK (см. выше). Или сервер шлёт CoT-типы, которые мы пропускаем (например, чьи-то маркеры/drawing — для них в TAKPacket нет полей).
- **Эхо-фильтр растёт быстро** — норма. ATAK дублирует наш инжект обратно в multicast, мост это видит и дропает по uid+time. Если `dropped` ≈ `txToAtak` — всё работает правильно.

## Файлы Phase A (оставлены для справки)

- [INSTALL_GUIDE.md](INSTALL_GUIDE.md) — установка Phase A на OnePlus и Honor.
- [ADMIN_REQUEST.md](ADMIN_REQUEST.md) — шаблоны письма админу TAK Server.
- [TROUBLESHOOTING.md](TROUBLESHOOTING.md) — общая отладка.

## Лицензия

Личный проект.
