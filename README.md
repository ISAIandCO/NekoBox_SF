# NekoBox for Android

[![API](https://img.shields.io/badge/API-21%2B-brightgreen.svg?style=flat)](https://android-arsenal.com/api?level=21)
[![License: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-orange.svg)](https://www.gnu.org/licenses/gpl-3.0)

## Отказ от ответственности

> Этот проект предназначен исключительно для технического исследования и изучения кода и не предоставляет никаких услуг сетевого прокси. Не используйте проект для деятельности, нарушающей местные законы и правила. Не используйте его в производственной среде. Пользователь несёт полную ответственность за любые риски, связанные с использованием проекта. Если вы скачали или использовали материалы проекта, удалите их в течение 24 часов и не храните, не распространяйте и не публикуйте их на постоянной основе. **Автор оставляет за собой право в любой момент изменять, обновлять или удалять проект либо его содержимое без предварительного уведомления.**

## Изменения в этом репозитории

В этом репозитории, помимо исходной функциональности проекта, внесены дополнительные изменения:

- Обновлён процесс release-сборки: итоговые APK собираются в заранее определённую директорию с артефактами, чтобы их было проще находить после сборки.
- Скорректирована логика действия **Clear Cache & Restart** для более корректной очистки кэша и повторного запуска приложения через ярлык.
- Устранена уязвимость связанная с раскрытием выходного адреса VPN через SOCKS5 прокси без авторизации.

## Загрузка

[![GitHub All Releases](https://img.shields.io/github/downloads/Matsuridayo/NekoBoxForAndroid/total?label=downloads-total&logo=github&style=flat-square)](https://github.com/starifly/NekoBoxForAndroid/releases)

[Скачать релизы с GitHub](https://github.com/starifly/NekoBoxForAndroid/releases)

**Версия из Google Play с мая 2024 года контролируется третьей стороной и не является open-source-версией. Не рекомендуется её скачивать.**

## Журнал изменений и Telegram-канал

https://t.me/Matsuridayo

## Домашняя страница и документация

https://matsuridayo.github.io

## Поддерживаемые прокси-протоколы

- SOCKS (4/4a/5)
- HTTP(S)
- SSH
- Shadowsocks
- ShadowsocksR
- VMess
- Trojan
- VLESS
- AnyTLS/AnyReality
- ShadowTLS
- TUIC
- Juicity
- Hysteria 1/2
- WireGuard
- Trojan-Go (trojan-go-plugin)
- NaïveProxy (naive-plugin)
- Mieru (mieru-plugin)

<details>
<summary>Пример конфигурации XHTTP Extra TLS</summary>

<pre><code class="language-json">
{
	"x_padding_bytes": "0-0",
	"sc_max_each_post_bytes": "0-0",
	"sc_min_posts_interval_ms": "0-0",
	"sc_stream_up_server_secs": "0-0",
	"xmux": {
		"max_concurrency": "16-32",
		"max_connections": "0-0",
		"c_max_reuse_times": "0-0",
		"h_max_request_times": "600-900",
		"h_max_reusable_secs": "1800-3000",
		"h_keep_alive_period": 0
	},
	"download": {
		"mode": "auto",
		"host": "b.yourdomain.com",
		"path": "/xhttp",
		"x_padding_bytes": "0-0",
		"sc_max_each_post_bytes": "0-0",
		"sc_min_posts_interval_ms": "0-0",
		"sc_stream_up_server_secs": "0-0",
		"xmux": {
			"max_concurrency": "16-32",
			"max_connections": "0-0",
			"c_max_reuse_times": "0-0",
			"h_max_request_times": "600-900",
			"h_max_reusable_secs": "1800-3000",
			"h_keep_alive_period": 0
		},
		"server": "$(ip_or_domain_of_your_cdn)",
		"server_port": 443,
		"tls": {
			"enabled": true,
			"server_name": "b.yourdomain.com",
			"alpn": "h2",
			"utls": {
				"enabled": true,
				"fingerprint": "chrome"
			}
		}
	}
}
</code></pre>
</details>

<details>
<summary>Пример конфигурации XHTTP Extra Reality</summary>

<pre><code class="language-json">
{
	"x_padding_bytes": "0-0",
	"sc_max_each_post_bytes": "0-0",
	"sc_min_posts_interval_ms": "0-0",
	"sc_stream_up_server_secs": "0-0",
	"xmux": {
		"max_concurrency": "16-32",
		"max_connections": "0-0",
		"c_max_reuse_times": "0-0",
		"h_max_request_times": "600-900",
		"h_max_reusable_secs": "1800-3000",
		"h_keep_alive_period": 0
	},
	"download": {
		"mode": "auto",
		"host": "example.com",
		"path": "/xhttp",
		"x_padding_bytes": "0-0",
		"sc_max_each_post_bytes": "0-0",
		"sc_min_posts_interval_ms": "0-0",
		"sc_stream_up_server_secs": "0-0",
		"xmux": {
			"max_concurrency": "16-32",
			"max_connections": "0-0",
			"c_max_reuse_times": "0-0",
			"h_max_request_times": "600-900",
			"h_max_reusable_secs": "1800-3000",
			"h_keep_alive_period": 0
		},
		"server": "$(ip_or_domain_of_your_cdn)",
		"server_port": 443,
		"tls": {
			"enabled": true,
			"server_name": "example.com",
			"reality": {
				"enabled": true,
				"public_key": "$(your_publicKey)",
				"short_id": "$(your_shortId)"
			},
			"utls": {
				"enabled": true,
				"fingerprint": "chrome"
			}
		}
	}
}
</code></pre>
</details>

Скачать плагины для полной поддержки прокси можно [здесь](https://matsuridayo.github.io/nb4a-plugin/).

## Поддерживаемые форматы подписок

- Некоторые широко используемые форматы, включая Shadowsocks, ClashMeta и v2rayN
- sing-box outbound

Поддерживается только разбор outbound-конфигураций, то есть узлов. Информация о правилах маршрутизации и прочих политиках игнорируется.

## Благодарности

### Core

- [SagerNet/sing-box](https://github.com/SagerNet/sing-box)

### Android GUI

- [shadowsocks/shadowsocks-android](https://github.com/shadowsocks/shadowsocks-android)
- [SagerNet/SagerNet](https://github.com/SagerNet/SagerNet)

### Web Dashboard

- [Yacd-meta](https://github.com/MetaCubeX/Yacd-meta)
