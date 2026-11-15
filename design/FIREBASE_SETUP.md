# Firebase 설정 (한 번만, 약 3분)

앱이 잠든 상대 폰을 깨우려면 FCM이 필요하고, FCM을 쓰려면 Firebase 프로젝트가 있어야 합니다.
아래 세 단계를 마치면 **다운로드 폴더에 JSON 파일 두 개**가 생기고, 나머지는 스크립트가 처리합니다.

## 1. 프로젝트 만들기

1. <https://console.firebase.google.com> 에서 **프로젝트 만들기**(또는 *Firebase 프로젝트 시작하기*).
2. 이름: `find-my-wife-phone` → 계속.
3. Gemini·Google 애널리틱스는 **사용 안 함**으로 두고 → **프로젝트 만들기**.

## 2. Android 앱 등록 → `google-services.json`

1. 프로젝트 개요 화면에서 **Android 아이콘**(앱 추가).
2. Android 패키지 이름: **`io.github.jeiel85.findmywifephone`** (정확히 이 값). 앱 닉네임: `폰 찾기`. SHA-1은 비워 둡니다.
3. **앱 등록** → **google-services.json 다운로드** → 나머지 단계(SDK 추가 등)는 *다음*/*콘솔로 이동*으로 건너뜁니다.

## 3. 서비스 계정 키 → `…-firebase-adminsdk-….json`

1. 왼쪽 위 톱니바퀴 → **프로젝트 설정** → **서비스 계정** 탭.
2. **새 비공개 키 생성** → **키 생성**. JSON 파일이 다운로드됩니다.
   이 파일은 이 프로젝트로 푸시를 보낼 수 있는 열쇠입니다. 누구에게도 보내지 말고, 저장소에 넣지 마세요.
3. 같은 화면의 **클라우드 메시징** 탭에서 *Firebase Cloud Messaging API(V1)* 가 **사용 설정됨**인지 확인합니다(새 프로젝트는 기본으로 켜져 있음).

## 4. 마무리

Git Bash에서 저장소 루트로 가서:

```bash
scripts/finish-firebase.sh
```

다운로드 폴더의 최신 파일 두 개를 찾아 아래를 자동으로 합니다.

- 두 파일이 같은 프로젝트이고 패키지 이름이 맞는지 확인
- `google-services.json` → `android/app/` (git 제외)
- 서비스 계정 키 → `%LOCALAPPDATA%\find-my-wife-phone\` (현재 사용자만 읽기) + 중계 Worker 비밀값 `FCM_SERVICE_ACCOUNT`
- 테스트 → 서명된 릴리스 APK `dist/find-my-wife-phone.apk`

키를 다운로드 폴더에 계속 둘 필요는 없습니다. 스크립트가 끝나면 지워도 됩니다.
