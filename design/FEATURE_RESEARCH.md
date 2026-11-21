# 기능 전수 조사 — 커플이 서로의 폰을 찾는 앱 (2026-11-21)

포너디가 풀려는 문제는 하나입니다. **“여보, 내 폰 어디 있어?”에 바로 답하기.**
집 안 어딘가(소파 밑, 가방 속)에 있는 폰을 울려서 찾고, 밖에서 잃어버렸으면 위치를 확인합니다.
그래서 기능을 고를 때는 두 가지를 기준으로 삼았습니다.

1. **찾는 데 도움이 되는가** — 폰을 실제로 찾을 확률이 올라가는가, 찾는 데 드는 단계가 줄어드는가.
2. **감시 도구가 되지 않는가** — 받는 사람이 통제하고(D-4), 상대가 무엇을 했는지 모두 보이는가.
   커플 위치 앱의 가장 큰 불만은 배터리 소모와 감시입니다. 2025년 11월 카카오맵 친구위치를 무제한 공유로 바꿨을 때도 감시 논란이 크게 일었습니다.

조사 방법: Life360, Apple 나의 찾기, Google Find Hub, Samsung Find, Glympse, iSharing, Google 지도 위치 공유,
WhatsApp·카카오맵 실시간 위치, 커플 앱(Together, Couplin, Love8, 비트윈), Zenly(종료)·Bump, Snap Map, 통신사 안심 서비스를 살펴봤습니다.
기준은 2025~2026년 공식 문서·스토어 설명·기사이고, 출처는 맨 아래에 있습니다. 확인하지 못한 내용은 쓰지 않았습니다.

---

## 1. 지금 포너디에 있는 것 (v0.3.6)

| 영역 | 기능 |
|---|---|
| 울리기 | 알람 스트림으로 무음·진동 무시, 방해 금지 해제(권한 있을 때), 60%에서 최대로 커지는 볼륨, 1·2·5분, 진동·플래시, **찾으면 볼륨·방해 금지 원래대로 복원**(앱이 죽거나 재부팅돼도) |
| 받는 사람 통제 | 10초 거절 창, 요청 받지 않기(30분~직접 끌 때까지), 위치 정책(자동·허용 시만·끔), **지울 수 없는 받은 요청 기록** |
| 위치 | 요청할 때마다 한 번만 측위, 앱 안 지도(OSM)와 주소, 지도 앱 길찾기 |
| 가까이 찾기 | 울리는 동안 블루투스 신호 세기로 “바로 옆·이 근처·조금 멀어요”와 가까워지는지 표시 |
| 상태 공유 | 상대 배터리 %·충전 중, 마지막 응답 시각, 받지 않는 중, 상대 앱 버전 |
| 보안 | QR 페어링, 종단 암호화(P-256 + AES-GCM, 중계는 내용 모름), 재전송 방지, 시계 보정 |
| 배포 | GitHub 자체 업데이트(서명·해시 확인), Play 새 버전 알림 |

## 2. 이번 v0.4.0에 넣은 것

다른 앱들이 공통으로 갖고 있고 포너디 원칙에도 맞는 것 가운데, Play 권한 선언을 바꾸지 않고 할 수 있는 세 가지입니다.

| 기능 | 따라 한 앱 | 왜 |
|---|---|---|
| **바로 울리기** — 앱 아이콘 길게 누르기 바로가기(울리기·위치 보기), 홈 화면 아이콘 추가, 빠른 설정 타일 | Wear OS·Apple Watch의 폰 핑, 커플 앱 위젯 | 폰을 찾는 순간에는 단계 하나가 아깝습니다. 앱을 열고 고르는 과정 없이 한 번에 울립니다. 대상 액티비티는 외부에 열려 있지 않아(exported=false) 다른 앱이 이 경로로 울릴 수 없습니다. |
| **내 위치 보내기** — “지금 여기 있어요 / 잘 도착했어요 / 지금 출발해요”와 함께 내 위치를 한 번 보냄 | Apple Check In, Life360 체크인, 카톡 위치 보내기 | 요청을 기다리지 않고 **내가 먼저** 알려 주는 방향이라 감시와 반대입니다. 앱을 열어 둔 채 내가 누를 때만 측위하므로 새 백그라운드 위치 사용이 없습니다. 받은 쪽은 지도 카드·알림·기록으로 봅니다. |
| **배터리가 부족하면 알리기** — 15% 이하로 떨어지면 상대에게 한 번 알림(충전을 시작하거나 25%를 넘기면 다시 켜짐) | Life360·iSharing 배터리 알림, Love8 | 폰이 꺼지면 울릴 수도, 위치를 볼 수도 없습니다. 꺼지기 전에 “지금 찾아 두라”는 마지막 기회를 줍니다. 위치는 보내지 않고 배터리 숫자만 보냅니다. 설정에서 끌 수 있습니다. |

## 3. 필요한 기능 전수 — 다음 후보

“찾는 데 도움 되는 정도”와 “만드는 비용·위험”으로 순서를 매겼습니다. ★는 다음에 할 만한 것입니다.

| 순위 | 기능 | 하는 앱 | 포너디에 맞게 하면 | 걸리는 점 |
|---|---|---|---|---|
| ★1 | **배터리가 바닥나기 직전 마지막 위치 자동 전송** | Apple(Send Last Location), Samsung(5% 미만), Huawei(8%) | 배터리 부족 알림에 위치를 함께 실어 보냄. 위치 정책이 ‘끔’이면 보내지 않고, 보낼 때마다 알림·기록 | 앱이 닫힌 상태에서 측위하므로 **Play 백그라운드 위치 선언을 고쳐야 함**(지금 선언은 “요청이 있을 때 한 번”). 선언 문구·영상을 바꾸면 Play 검토를 다시 받아야 함 → 사용자 결정 필요 |
| ★2 | **분실 모드 메시지** — 상대 폰 잠금 화면에 “이 폰 주인은 ○○, 이 번호로 연락 주세요”와 전화 버튼 | Apple·Google·Samsung 분실 모드 | 울리기처럼 요청을 보내면 받은 폰이 잠금 화면 위 전체 화면으로 메시지를 띄움(받지 않기·거절 규칙 동일). 새 권한 없음 | 실제 잠금(PIN 강제)·초기화는 기기 관리자 권한이 필요하고 Google Find Hub가 이미 잘함 → 메시지와 전화 버튼까지만 |
| ★3 | **매일 받지 않는 시간(취침 시간 반복)** | Life360 알림 일정, 운영체제 방해 금지 일정 | 설정에 “매일 23:00~07:00”. 상대 화면에도 그 시간대가 보임 | 상대 폰이 옛 상태를 들고 있을 수 있어 상태에 일정 자체를 실어야 함 |
| 4 | 울림 소리 고르기 | Findmykids Loud Signal, 각 OS | 기본음 외 2~3개 | 취향 문제, 찾는 성공률과는 거의 무관 |
| 5 | 잠금 화면·홈 화면 위젯(상대 배터리·마지막 응답 + 울리기 버튼) | Together(잠금 화면 위젯), 비트윈 | Glance 위젯 하나 | 위젯 갱신 주기·배터리 고려. 바로가기가 이미 핵심을 해결 |
| 6 | 정해 둔 시간 동안 실시간 위치 공유(15분·1시간, 내가 시작·언제든 중지) | WhatsApp, Glympse, Google Find Hub People, Apple iOS 27 | “내 위치 보내기”의 연장. 내가 켜고, 끝나는 시각이 양쪽에 보임 | 포그라운드 위치 서비스가 계속 떠 있어야 하고, 중계 메시지 한도(쌍당 10분 60회) 안에서 1분 간격 정도. Play 포그라운드 서비스 선언 확인 필요 |
| 7 | 도착·출발 알림(지오펜스: 집·회사) | Life360, Google 지도, Apple, iSharing | 위치 주인이 직접 켜는 경우에만, 켜져 있다는 사실을 주기적으로 다시 알려 줌(Google 지도 방식) | 계속 위치를 봐야 해서 배터리·감시 위험이 큼. “잘 도착했어요” 보내기로 대부분 대체됨 |
| 8 | 워치에서 울리기 | Wear OS, Galaxy Watch, Apple Watch | Wear OS 타일 | 워치 앱을 따로 만들어야 함. 사용자 폰은 Galaxy라 Samsung Find가 이미 됨 |
| 9 | 음성 비서(“포너디로 아내 폰 울려 줘”) | Google Nest “find my phone” | 앱 바로가기를 Assistant가 실행 | Gemini 전환기라 지원 범위가 불확실 |
| 10 | 영어 UI | — | strings 리소스로 문구 이동 | 공개 범위를 넓힐 때 |

## 4. 하지 않을 것과 이유

| 기능 | 하는 앱 | 하지 않는 이유 |
|---|---|---|
| 상시 실시간 위치·이동 기록(타임라인) | Life360, iSharing, Together, 카카오맵 | 앱의 목적(폰 찾기)을 넘어선 감시 도구가 됨. 배터리 소모가 가장 큰 불만. 받는 사람이 통제한다는 원칙(D-4)과 충돌 |
| 상대 모르게 위치 숨기기·가짜 위치 | Apple iOS 27 Hide Location, Snap Ghost, Zenly | 포너디는 반대로 “무엇을 했는지 모두 보이게”가 원칙. 대신 ‘요청 받지 않기’가 상대에게 **보이게** 있음 |
| 침입자 사진, 주변 소리 듣기 | 서드파티 도난 방지 앱, Findmykids Sound Around | 부부 사이에선 감시로 쓰일 위험이 큼. 도난 대응은 OS(Find Hub·Samsung Find)가 더 잘함 |
| 원격 잠금·초기화, SIM 교체 알림, 전원 끄기 방지 | Find Hub, Samsung | 기기 관리자·전화 상태 권한이 필요하고 Play 정책 부담이 큼. 도난은 OS 기능을 함께 쓰도록 안내하는 편이 맞음 |
| 오프라인 찾기 네트워크 | Apple, Google, Samsung | 수억 대 기기 네트워크가 있어야 가능. 개인 앱으로는 불가 |
| 운전·충돌 감지, SOS 긴급 출동 | Life360, iSharing | 생명·안전 기능은 오작동 책임이 큼. 폰 찾기와 거리가 멂 |
| 박수·휘파람으로 찾기 | (신뢰할 만한 근거 없음) | 마이크를 계속 켜 둬야 해서 배터리·사생활 부담. 상대 폰에서 울리기로 이미 해결 |

## 5. 리뷰에서 배운 것

- **칭찬**: 상대 배터리 %가 보이니 “폰이 꺼진 건지” 알 수 있어 덜 불안하다(Google 지도, Zenly, 커플 앱) — 포너디는 이미 보여 줌.
  받는 쪽이 가입 없이 볼 수 있다(Glympse). 공유자에게 “알림이 걸려 있다”는 사실을 반복해 알려 주는 투명한 설계(Google 지도)는 신뢰를 쌓는 장치로 평가됨.
- **불만**: 배터리 소모(Life360), 위치 데이터 판매(Life360, 2021), 감시 논란(카카오맵 2025), 유료 등급 장벽, 무음에서 안 울림(Apple 일부 사례)·조용한 곳에서 못 찾음(Find Hub 기본값).
- 포너디가 지킬 것: 측위는 요청이 있거나 내가 누를 때만, 광고·계정·데이터 판매 없음, 무음 뚫고 울리기는 계속 확실하게.

## 출처

- Life360: [요금제](https://www.life360.com/en-gb/plans-pricing), [배터리 사용](https://support.life360.com/hc/en-us/articles/23053716563223-Life360-and-Battery-Usage), [allaboutcookies 리뷰](https://allaboutcookies.org/life360-review)
- Apple: [분실 기기 찾기](https://support.apple.com/en-au/HT210515), [Check In](https://support.apple.com/guide/personal-safety/ips56b5bc469/web), [iOS 27 나의 찾기](https://9to5mac.com/2026/06/12/ios-27-find-my-adds-new-ways-to-hide-your-location-more/), [한국 도입](https://www.apple.com/kr/newsroom/2025/03/apple-brings-additional-find-my-capabilities-to-south-korea/)
- Google: [Find Hub](https://www.android.com/learn-find-hub/), [분실 기기 찾기·잠금·초기화](https://support.google.com/android/answer/6160491?hl=en), [지도 위치 공유](https://support.google.com/maps/answer/7326816?hl=en), [위치 공유 알림](https://9to5google.com/2022/07/27/google-maps-location-sharing-notifications/), [Wear OS 폰 찾기](https://support.google.com/wearos/answer/6166506?hl=en), [Nest 음성 찾기](https://support.google.com/googlenest/answer/7535860)
- Samsung: [Find My Mobile](https://www.samsung.com/ae/support/apps-services/how-does-find-my-mobile-work/), [SmartThings Find](https://www.samsung.com/au/support/apps-services/use-smartthings-find-with-the-smartthings-app/), [Huawei 마지막 위치](https://consumer.huawei.com/en/support/content/en-us00887458)
- 기타: [Glympse](https://support.glympse.com/faq/how-does-glympse-work/), [iSharing 요금제](https://isharingsoft.com/isharing-plans-recommendation-friends-partners-families), [WhatsApp 실시간 위치](https://blog.whatsapp.com/share-your-live-location), [Snap Family Center](https://newsroom.snap.com/new-location-sharing-features-family-center), [Zenly 종료](https://techcrunch.com/2022/12/05/zenly-was-the-best-social-app-and-it-will-sadly-shut-down-on-february-3rd/), [Together Couple Tracker](https://apps.apple.com/app/id1387179977), [Love8](https://apps.apple.com/us/app/6448163027), [Findmykids Loud Signal](https://findmykids.org/features/loud-signal)
- 한국: [뉴시스 — 카카오맵 친구위치 개편](https://www.newsis.com/view/NISX20251117_0003406094), [노컷뉴스](https://www.nocutnews.co.kr/news/6430129), [SKT ZEM](https://news.sktelecom.com/137749)
