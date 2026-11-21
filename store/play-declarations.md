# Play Console 권한 선언 문구

앱 콘텐츠 → 위치 정보 액세스 권한(백그라운드 위치)에 넣은 문구입니다. 각 칸은 500자 이하입니다.
영상(YouTube 일부 공개, 2026-11-17): 백그라운드 위치 https://www.youtube.com/watch?v=Qr9QryseNsA

## 앱의 목적 (v0.3.3부터 같음)

Ponerdi lets two family members who paired their phones with a one-time QR code find each other's phone: ring it loudly even on silent, and see where it is when it is lost. It has no ads, no accounts and no tracking.

## 백그라운드 위치를 쓰는 기능 (v0.3.3부터 같음)

"See location": when a paired family member asks where this phone is, the phone (often in a pocket, with the app closed) shows a notification with a 10-second decline countdown, then takes one location fix and sends it, end-to-end encrypted, only to that family member. No continuous tracking; each shared location stays only in the two phones' on-device history. The owner can switch it to "only when I allow" or "never" in Settings. A disclosure dialog explains this before the permission prompt.

## v0.5.0 마지막 위치(배터리 부족)와 선언

Play 양식은 "백그라운드 위치를 가장 많이 쓰는 기능 **1개만** 설명하고, 승인은 앱 전체에 대해 부여된다"고 안내합니다(2026-11-21 확인).
그래서 선언은 위 '위치 보기' 그대로 두고, 두 번째 용도(배터리 15%·5% 마지막 위치)는 정책이 요구하는 **앱 안의 명확한 공개**에 넣었습니다 —
위치 권한 요청 전 안내 대화상자(`ui/Permissions.kt` LocationDisclosure)와 개인정보처리방침. Play 검토에서 문의가 오면 이 문서와 D-13을 근거로 답합니다.

## 포그라운드 서비스

바뀐 것 없음. 마지막 위치는 WorkManager 작업 안에서 한 번 측위하며 포그라운드 서비스를 쓰지 않습니다.
