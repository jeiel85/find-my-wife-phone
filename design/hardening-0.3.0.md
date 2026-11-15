v0.3.0(앱 ID를 com.jeiel85.ponerdi로 변경)을 만들며 드러난 개선 후보입니다. 항목당 무엇을 · 왜 · 영향 범위.

- [ ] 옛 앱(v0.2.0 이하) 업데이트 실패 안내 · 새 릴리스가 다른 패키지라 옛 앱 설정 화면에 “받은 파일을 검증하지 못해 설치하지 않았어요”가 계속 뜸 · `update/Updater.kt` (옛 앱 코드는 못 고치므로 README·릴리스 노트로 안내)
- [ ] Play용 빌드에서 자체 업데이트 제외 · Play 정책상 REQUEST_INSTALL_PACKAGES 자체 업데이트 금지 · `build.gradle.kts` 제품 플레이버, `update/`
- [ ] 옛 패키지 기기 행 정리 · 옛 앱을 지워도 중계 D1의 devices·links 행이 남음 · `relay` cron (hardening-0.1.0의 오래된 기기 정리와 함께)
- [ ] Firebase의 옛 Android 앱(io.github…) 등록 정리 · 옛 설치본이 사라진 뒤 불필요 · Firebase 콘솔
