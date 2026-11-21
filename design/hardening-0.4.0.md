v0.4.0(바로 울리기 · 내 위치 보내기 · 배터리 부족 알림)에서 드러난 견고성 개선 후보입니다. 형식: 무엇을 · 왜 · 영향 범위.

- [ ] 배터리 부족 알림을 상대별로 보낸 기록 · 연결이 둘 이상이고 한 곳만 실패하면 다음 확인 때 성공한 상대에게도 또 보냄 · `BatteryWatch`, `Hub.sendBatteryLow`
- [ ] 배터리 확인 주기 관측 · Doze 중에는 WorkManager 15분 주기가 몇 시간 밀릴 수 있어 15%에서 알리지 못하고 꺼질 수 있음(실기기에서 실제 지연 측정 필요) · `BatteryWorker`
- [ ] ‘내 위치 보내기’ 실패 시 다시 보내기 버튼 · 지금은 카드 닫고 타일을 다시 눌러야 함 · `HomeScreen.ShareOutCard`
- [ ] 받은 위치 카드의 오래됨 표시 · 며칠 지난 ‘잘 도착했어요’ 카드가 닫을 때까지 홈에 남음(시각은 보임) · `HomeScreen`, `Store.shared`
- [ ] 바로가기 동기화 실패 관측 · `Shortcuts.sync` 실패는 로그만 남음(런처가 거부하면 사용자가 모름) · `quick/Shortcuts.kt`
- [ ] 빠른 설정 타일 상태 갱신 · 연결·해제 직후 타일 이름은 다음에 패널을 열 때 바뀜(`requestListeningState` 미사용) · `RingTileService`
- [ ] 기록의 ‘시간 다 돼서 멈춤’ 문구가 실제 울림 시간(1·5분)과 무관하게 “2분” · `HistoryScreen.outcomeText`
