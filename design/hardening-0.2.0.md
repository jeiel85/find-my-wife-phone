v0.2.0(가까이 찾기, 앱 열린 상태 알림, 플래시 고정)을 만들며 드러난 개선 후보입니다. 항목당 무엇을 · 왜 · 영향 범위.

- [ ] RSSI 구간 경계 보정 · -55/-67/-80 dBm은 일반값이라 S24·S10의 송수신 세기 차이를 반영하지 못함 · `nearby/Proximity.kt` `ProximityMeter`
- [ ] 받는 폰 광고 실패(onStartFailure)를 상태에 반영 · 지금은 start() 호출 성공만 보고 `beacon: true`를 보냄 · `nearby/Bluetooth.kt` `BeaconAdvertiser`
- [ ] 스캔 시작 실패 사유 표시 · 실패하면 “신호를 찾는 중…”에 머묾 · `NearbyScanner.start`
- [ ] Android 10~11 찾는 쪽은 위치 서비스가 꺼져 있으면 스캔 결과가 없음 · 안내 없이 신호 없음으로 보임 · `NearbyCard`
- [ ] 앱 열린 상태에서 알림 화면을 뒤로가기로 닫은 뒤 다시 열 방법 · 같은 요청 동안 자동으로 다시 열지 않음 · `MainActivity` LaunchedEffect
