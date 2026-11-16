v0.3.2('그만 울리기'가 제대로 멈추게 고침)를 만들며 드러난 개선 후보입니다. 항목당 무엇을 · 왜 · 영향 범위.

- [ ] 울리기 전송의 "애매한 실패" 표시 · 응답만 끊긴 경우(status 0)에도 "보내지 못했어요"로 보이지만 상대 폰은 울릴 수 있음 · `core/Hub.kt` request, `ui/HomeScreen.kt` describe
- [ ] 옛 버전 수신 폰 대응 · v0.3.1 이하 폰은 멈춤이 울리기보다 먼저 오면 여전히 끝까지 울림(버전 협상 없음) · `core/Messages.kt` PeerStatus에 앱 버전 노출 검토
- [ ] 취소된 울리기의 알림 깜빡임 · RingService가 startForeground 의무 때문에 카운트다운 알림을 잠깐 띄웠다 지움 · `ring/RingService.kt` begin
- [ ] 요청자 쪽 멈춤 E2E 시험 수단 · 가짜 상대 폰은 FCM을 받을 수 없어 요청자 화면 흐름을 자동 시험할 수 없음 · `relay` 로컬 모드(가짜 FCM) 또는 에뮬레이터 2대용 스크립트
- [ ] 시간 초과 문구 · "2분 동안 울리고 멈췄어요"가 설정한 울림 길이와 다를 수 있음 · `ui/HomeScreen.kt` describe
- [ ] 멈춤으로 건너뛴 울리기 로그 · 취소 때문에 울리지 않은 경우 로그가 없어 현장 진단이 어려움 · `core/Hub.kt` onRingRequest, `ring/RingService.kt` begin
