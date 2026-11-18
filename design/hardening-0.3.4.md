v0.3.4(Play 버전 새 버전 알림)를 만들며 드러난 개선 후보입니다. 항목당 무엇을 · 왜 · 영향 범위.

- [ ] Play 확인 실패 이유 나누기 · 지금은 모든 실패를 "Play 스토어에서 받은 앱에서만 확인할 수 있어요"로 보여 줘서, Play에서 받은 앱의 네트워크·Play 서비스 오류도 같은 문구가 됨(`InstallException.errorCode`로 ERROR_APP_NOT_OWNED와 나머지를 구분) · `src/play/.../update/SelfUpdate.kt` check()
- [ ] '새 버전 있음' 경로 자동 시험 · 업데이트 있음 상태·알림·스토어 이동은 Play 설치본에서만 실제로 확인되고 단위 테스트가 없음(`FakeAppUpdateManager`로 상태 매핑 시험) · `SelfUpdate`가 AppUpdateManager를 주입받게, `testPlay`
- [ ] 설정 이름 정리 · Play 빌드의 "새 버전 알림" 스위치가 GitHub 빌드용 이름 `autoUpdate`를 그대로 씀 — 채널을 오가면 의미가 바뀌는 값 · `data/Store.kt`, 두 flavor의 UpdateSection
- [ ] play-bundle.sh 서명 확인 정합 · 스크립트는 `play-upload-signing.properties`(미등록 예비 키)가 있어야 진행하지만 실제 서명은 릴리스 키(D-10)로 함 — 없는 파일 때문에 막히거나 잘못된 키로 오해할 수 있음 · `scripts/play-bundle.sh`
