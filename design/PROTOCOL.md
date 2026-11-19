# 프로토콜 v1

두 폰과 중계 서버(Cloudflare Worker) 사이의 약속입니다. 결정 배경은 [`DECISIONS.md`](../DECISIONS.md)에 있습니다.
구현: 중계 [`relay/src/index.js`](../relay/src/index.js), 앱 [`core/`](../android/app/src/main/java/io/github/jeiel85/findmywifephone/core) · [`crypto/Crypto.kt`](../android/app/src/main/java/io/github/jeiel85/findmywifephone/crypto/Crypto.kt).

## 전체 흐름

```
 남편 폰                    중계 Worker (D1)                 FCM                 아내 폰
   │  POST /v1/messages {to, env}  │                            │                     │
   │ ─────────────────────────────▶│ 링크(남편→아내) 확인          │                     │
   │                               │ 속도 제한                   │                     │
   │                               │ ─ data {k:msg, src, env}  ─▶│ ── high priority ──▶│
   │                               │                            │                     │ 복호화 → 10초 카운트다운
   │                               │◀──────────── POST /v1/messages (ring-state…) ─────│ → 울림 / 거절 / 받지 않기
   │◀─────────────── FCM ──────────│                            │                     │
```

폰은 잠들어 있어 직접 접속을 받을 수 없습니다. 그래서 상대 폰을 깨우는 데 FCM 고우선 데이터 메시지를 쓰고,
중계 서버는 "누가 누구를 깨워도 되는지"와 FCM 토큰만 압니다. 메시지 내용은 두 폰만 아는 키로 암호화돼 있습니다.

## 중계 API

기본 주소 `https://find-my-wife-phone-relay.jeiel85.workers.dev`. 요청·응답은 JSON, 오류는 항상 `{schema: 1, error: "<code>"}`.
인증은 `Authorization: Bearer <token>`(등록 때 받은 43자 base64url). 서버는 토큰의 SHA-256만 저장합니다.

| 메서드 | 경로 | 인증 | 본문 | 성공 | 주요 오류 |
|---|---|---|---|---|---|
| GET | `/v1/health` | – | – | 200 `{ok}` | |
| POST | `/v1/devices` | – | `{fcmToken}` | 201 `{deviceId, token}` | 400 `invalid_fcm_token` · 429 (IP당 시간 20회) |
| PUT | `/v1/devices/me` | ✓ | `{fcmToken}` | 200 | 401 |
| DELETE | `/v1/devices/me` | ✓ | – | 200 (링크·초대 함께 삭제) | 401 |
| POST | `/v1/invites` | ✓ | `{inviteId, ttlSec≤600}` | 201 `{inviteId, expiresAt}` | 400 `invalid_invite` · 429 |
| POST | `/v1/invites/{inviteId}/join` | ✓ | `{pub, env}` | 200 `{peerId}` | 404 `invite_not_found` · 410 `invite_expired` · 400 `self_pairing` · 410/502 (초대자에게 푸시 실패, 초대는 유지) |
| POST | `/v1/messages` | ✓ | `{to, env, ttlSec 10..86400}` | 202 | 403 `not_paired` · 410 `peer_unreachable` · 429 (쌍당 10분 60회, 보내는 기기당 10분 120회) · 502 `push_failed` |
| DELETE | `/v1/links/{peerId}` | ✓ | – | 200 (양방향 삭제) | 401 |

- `deviceId`: 22자 base64url(16바이트 난수). `inviteId`: 43자 base64url = SHA-256(pairSecret).
- `pub`: P-256 비압축 점 65바이트의 base64url(87자). `env`: 아래 봉투, 3000자 이하.
- FCM 데이터: 페어링 `{k: "pair", src, pub, env}`, 메시지 `{k: "msg", src, env}` (`src` = 보낸 기기 ID. `from`·`notification`·`message_type`·`google.*`·`gcm.*`는 FCM 예약어라 쓸 수 없음). Android `priority: HIGH`, `ttl`은 요청값.
  FCM이 `UNREGISTERED`/404를 주면 그 기기의 FCM 토큰을 지우고 410 `peer_unreachable`을 돌려줍니다(앱이 다음 실행 때 다시 등록).
- 매일 04:17 UTC cron이 만료된 초대와 하루 지난 속도 제한 카운터를 지웁니다. 같은 cron이 다시는 깨울 수 없는 기기도 지웁니다:
  FCM이 토큰을 무효라고 한 때(`dead_at`)부터 30일 동안 새 토큰을 보내지 않은 기기, 어떤 기기와도 연결되지 않은 채 90일 동안 호출이 없던 기기. 지운 기기를 가리키는 연결·초대도 함께 지웁니다.
  연결된 기기는 조용하다는 이유만으로 지우지 않습니다(받기만 하는 폰은 몇 달씩 중계를 부르지 않을 수 있음).
- 중계는 FCM의 5xx·429·연결 끊김을 0.3초, 1초 뒤 두 번 다시 시도한 뒤에야 502 `push_failed`를 돌려줍니다.
- 앱은 `401`을 받으면 등록을 지우고 새로 등록한 뒤, 초대 만들기·QR 참여는 한 번 다시 시도합니다.

## 페어링 (QR)

1. 보여주는 폰 A: 16바이트 `pairSecret`을 만들고 `POST /v1/invites {inviteId: b64url(SHA-256(secret))}`(10분) 후 QR을 띄웁니다.
   QR 텍스트: `https://jeiel85.github.io/find-my-wife-phone/pair/#<b64url(JSON {v:1, id, pub, s, n, exp})>`
   — `id` A의 deviceId, `pub` A의 공개키, `s` pairSecret, `n` A의 표시 이름. 값은 fragment에만 있어 웹 서버로 가지 않습니다.
2. 스캔하는 폰 B: `pairKey = HKDF(ECDH(B개인키, A공개키), salt = pairSecret, info = "fmwp pair v1")`를 만들고
   `Hello{name, model, st}`를 봉투로 싸서 `POST /v1/invites/{inviteId}/join {pub: B공개키, env}`.
3. 중계: 초대를 확인하고 A↔B 링크를 만든 뒤 A를 깨웁니다(`k: pair`). 초대는 한 번 쓰면 사라집니다.
4. A: 열려 있는 초대의 secret마다 키를 만들어 봉투가 열리는 것을 찾고, 피어로 저장한 뒤 `Hello{ack: true}`를 보냅니다.

중계는 두 공개키를 모두 보지만 `pairSecret`(QR에만 있음)을 모르므로 키를 만들 수도, 중간에서 공개키를 바꿔치기할 수도 없습니다.
바꿔치기한 공개키로 만든 봉투는 A에서 GCM 인증에 실패합니다.

## 암호화

- 키: 설치마다 P-256 키쌍 하나. 개인키·중계 토큰·pairKey는 Android Keystore AES-GCM 키로 감싸 저장, 백업 제외.
- 공개키를 받을 때 곡선 위의 점인지 직접 검사합니다(invalid-curve 공격 방지).
- 봉투: `base64url(0x01 ‖ nonce(12) ‖ AES-256-GCM(pairKey, nonce, plaintext, aad))`, `aad = "fmwp1|<from>|<to>"`.
  방향이 AAD에 묶여 A→B 메시지를 B→A로 되돌려 보낼 수 없습니다.
- 재전송 방지: 모든 메시지에 `id`(UUID)·`ts`. 받는 쪽은 최근 300개 `id`를 기억하고, 2분 넘게 미래인 `ts`는 버립니다.
  울리기·위치 요청은 `ts`가 2분보다 오래되면 실행하지 않고 기록에 "늦게 도착"으로 남깁니다(FCM TTL 60초와 이중 안전장치).
- 시계 보정(v0.3.6~): 폰 시계가 틀려도 위 판정이 맞도록, 앱은 중계 응답의 HTTP `Date` 헤더로 자기 시계의 어긋남을
  잽니다(왕복 5초 이하일 때, 왕복 중간 시각 기준). `ts`를 찍을 때와 나이를 잴 때 모두 보정한 시각을 씁니다.
  화면에 보이는 시각(기록 등)은 폰 시계 그대로입니다. 어긋남이 1분을 넘으면 설정에 안내가 뜹니다.

## 메시지 (봉투 안의 JSON)

공통: `{v: 1, type, id, ts, st?}`. `st`(상태)는 어떤 메시지에도 붙을 수 있고, 받으면 상대 카드의 상태를 갱신합니다.

`st` = `{snoozeUntil: ms(0=받는 중, 2^63-1=직접 끌 때까지), loc: "auto"|"ask"|"off", battery: -1..100, charging}`

| type | 방향 | 필드 | 의미 |
|---|---|---|---|
| `hello` / `hello-ack` | 양쪽 | `name, model` | 페어링 인사 |
| `status` | 양쪽 | – | 설정이 바뀌었을 때 알림(받지 않기, 위치 정책) |
| `ring` | 요청자 → 대상 | `reqId` | 울려 줘 |
| `ring-cancel` | 요청자 → 대상 | `reqId` | 그만 울려 |
| `ring-state` | 대상 → 요청자 | `reqId, state` | `countdown` `ringing` `declined` `snoozed` `found` `stopped` `timeout` `busy` `error` |
| `locate` | 요청자 → 대상 | `reqId` | 위치 알려 줘 |
| `locate-state` | 대상 → 요청자 | `reqId, state` | `countdown` `asking` `locating` `declined` `snoozed` `off` `unavailable` `error` |
| `locate-result` | 대상 → 요청자 | `reqId, lat, lng, acc, fixAt` | 한 번의 위치 |
| `unpair` | 양쪽 | – | 연결 해제(받으면 피어 삭제) |

모르는 `type`·`state`, 다른 `v`는 무시하거나 `error`로 읽어, 새 버전 폰이 옛 버전 폰을 망가뜨리지 않게 합니다.

## 수신 쪽 동작

- **울리기**: 받지 않기 중이면 즉시 `snoozed`. 아니면 포그라운드 서비스(mediaPlayback) + 전체 화면 알림으로 **10초 카운트다운**(거절 가능)
  → 알람 스트림(`USAGE_ALARM`, 무음·진동 모드와 무관)으로 최대 2분 울림. 볼륨은 60%에서 시작해 4초에 걸쳐 최대로.
  방해 금지 접근 권한이 있으면 방해 금지를 잠시 풀고, 끝나면 **원래 볼륨과 방해 금지 상태로 되돌립니다**.
  되돌릴 값은 바꾸기 전에 디스크에 먼저 저장해, 울리는 중 앱이 죽거나 재부팅돼도 다음 실행 때 복원합니다.
- **위치**: 정책 `auto`(기본)는 알림 + 10초 카운트다운 후 한 번 전송, `ask`는 60초 안에 "허용"해야 전송, `off`는 거절.
  어떤 경우든 위치를 보내면 "누구에게 보냈는지" 알림이 뜨고 기록에 남습니다. 받은 요청 기록은 지울 수 없습니다.
  `auto`를 기본으로 둔 이유: 폰을 잃어버렸을 때는 그 폰 앞에 허용을 누를 사람이 없기 때문입니다.
- **그만 울리기**(`ring-cancel`): FCM은 도착 순서를 지키지 않으므로 `ring-cancel`이 `ring`보다 먼저 올 수 있습니다.
  수신 쪽은 받은 `reqId`를 기억해 두고(최근 50개, 디스크) 나중에 온 같은 `reqId`의 `ring`은 울리지 않고 `stopped`로 기록합니다.
  멈춘 뒤에는 `ring-state`를 따로 보내지 않습니다.
- **요청자 쪽 순서**: `ring-cancel`은 `ring`을 중계 서버에 넘긴 뒤에 보내고, 중계 서버가 받아야 화면을 '멈췄어요'로 바꿉니다
  (실패하면 '멈추지 못했어요'와 멈춤 버튼을 그대로 둡니다). 한 번 끝난 요청(`stopped` 포함)에는 늦게 온
  `countdown`·`ringing`을 반영하지 않고, 끝 상태(`found` 등)만 받아들입니다.
