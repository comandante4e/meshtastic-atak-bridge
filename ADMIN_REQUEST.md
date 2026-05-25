# Шаблоны письма админу TAK Server

Используй один из шаблонов ниже — в зависимости от того, что именно нужно. Большинство админов TAK Server / FreeTAKServer / OpenTAKServer выпускают data package одной командой за минуту.

---

## Если у тебя пока только логин/пароль и enrollment не сработал

> Привет!
>
> Я подключаюсь к серверу `<HOSTNAME>` под логином `<MY_USERNAME>`. У меня логин/пароль, но в ATAK-CIV (Android, v5.6) подключение через enrollment отваливается — пишет SSL handshake error / Server does not support enrollment.
>
> Можешь, пожалуйста, выпустить **data package (.zip)** для моего пользователя? В нём должны быть:
> - мой клиентский сертификат (.p12 с паролем);
> - CA сертификат сервера (truststore-root.p12 или .pem);
> - `config.pref` с адресом сервера, портом и параметрами подключения.
>
> Если у тебя обычный TAK Server — это команда `makeCert.sh client <username>` + сборка `.zip` через UI Marti (Certificate Authority → Generate Client Certificate). На FreeTAKServer/OpenTAKServer есть кнопка "Data Package" в админке.
>
> Когда соберёшь — закинь мне .zip в личку. Спасибо!

---

## Если хочешь, чтобы вместо data package сервер просто включил enrollment endpoint

> Привет!
>
> На сервере `<HOSTNAME>` сейчас не работает client cert enrollment по логину/паролю — ATAK-CIV не может подключиться, имея только мои username/password.
>
> Можешь включить enrollment endpoint? У стандартного TAK Server это HTTPS на порту 8446 — в `CoreConfig.xml` секция `<auth><LdapAuth>` / `<FileAuth>` с `enableEnroll="true"`. После перезапуска tomcat ATAK сможет получить cert по моим кредам сам.
>
> Если так удобнее — окей, выпиши мне data package (см. второе сообщение).

---

## Что нужно сделать на TAK Server (краткая шпаргалка для админа)

На случай если админ не сталкивался с этим — можешь скинуть ему ниже. Команды для **официального TAK Server** (для FTS/OpenTAK есть UI-кнопка):

```bash
# 1. Сгенерировать клиентский cert
cd /opt/tak/certs
./makeCert.sh client <username>

# 2. Собрать data package
# Marti UI → Certificate Authority → Manage TAK Certificates →
# выбрать сертификат → Generate Data Package → задать пароль на .p12

# 3. Скачать .zip и отдать пользователю
```

Альтернативно, если уже есть `.p12` и CA:

```bash
# Структура data package (.zip):
# ├── client.p12              ← клиентский сертификат
# ├── truststore-root.p12     ← CA сервера
# ├── config.pref             ← XML с настройками
# └── manifest.xml            ← манифест пакета
```

Пример `config.pref`:
```xml
<?xml version='1.0' standalone='yes'?>
<preferences>
  <preference version="1" name="cot_streams">
    <entry key="count" class="class java.lang.Integer">1</entry>
    <entry key="description0" class="class java.lang.String">MyServer</entry>
    <entry key="enabled0" class="class java.lang.Boolean">true</entry>
    <entry key="connectString0" class="class java.lang.String">HOSTNAME:8089:ssl</entry>
  </preference>
  <preference version="1" name="com.atakmap.app_preferences">
    <entry key="clientPassword" class="class java.lang.String">PASSWORD</entry>
    <entry key="caPassword" class="class java.lang.String">PASSWORD</entry>
    <entry key="caLocation" class="class java.lang.String">cert/truststore-root.p12</entry>
    <entry key="certificateLocation" class="class java.lang.String">cert/client.p12</entry>
  </preference>
</preferences>
```

И `manifest.xml`:
```xml
<MissionPackageManifest version="2">
  <Configuration>
    <Parameter name="uid" value="auto-generated-uid"/>
    <Parameter name="name" value="MyServer"/>
    <Parameter name="onReceiveImport" value="true"/>
    <Parameter name="onReceiveDelete" value="false"/>
  </Configuration>
  <Contents>
    <Content ignore="false" zipEntry="cert/client.p12"/>
    <Content ignore="false" zipEntry="cert/truststore-root.p12"/>
    <Content ignore="false" zipEntry="config.pref"/>
  </Contents>
</MissionPackageManifest>
```
