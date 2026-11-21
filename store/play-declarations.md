# Play Console 권한 선언 문구

앱 콘텐츠 → 위치 정보 액세스 권한(백그라운드 위치)에 넣는 문구입니다. 각 칸은 500자 이하입니다.
영상(YouTube 일부 공개)은 2026-11-17에 올린 것을 그대로 씁니다 — 백그라운드 위치 https://www.youtube.com/watch?v=Qr9QryseNsA

## 앱의 목적 (v0.3.3부터 같음)

Ponerdi lets two family members who paired their phones with a one-time QR code find each other's phone: ring it loudly even on silent, and see where it is when it is lost. It has no ads, no accounts and no tracking.

## 백그라운드 위치를 쓰는 기능 (v0.5.0에서 바꿈)

1) "See location": when a paired family member asks, the phone (app closed) shows a notification with a 10-second decline countdown, then sends one fix, end-to-end encrypted, only to them. 2) Last location: when the battery drops below 15% and 5%, one fix goes with the low-battery notice so a dying phone can still be found. No continuous tracking; the owner is notified each time and can turn either off in Settings.

(위 문구는 418자. 바꾸기 전 문구는 "See location" 하나만 설명했습니다.)

## 포그라운드 서비스

바뀐 것 없음. 마지막 위치는 WorkManager 작업 안에서 한 번 측위하며 포그라운드 서비스를 쓰지 않습니다.
