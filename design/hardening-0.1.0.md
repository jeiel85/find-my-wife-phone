v0.1.0을 만들며 드러난 견고성·보안·운영 개선 후보입니다. 항목당 무엇을 · 왜 · 영향 범위.

- [ ] 중계 D1의 오래된 기기 정리 · 앱을 다시 설치하면 이전 deviceId·링크 행이 영구히 남음 · `relay` cron, `devices.seen_at` 기준
- [ ] 메시지 속도 제한을 기기 단위로도 · 지금은 쌍(from,to)별 10분 60회라 한 기기가 여러 쌍을 만들면 우회 가능 · `relay/src/index.js` LIMITS
- [ ] FCM 전송 실패(5xx) 재시도 · 지금은 502를 바로 돌려 사용자가 다시 눌러야 함 · `relay/src/fcm.js`
- [ ] 받은 요청의 `ts` 검사 시 기기 시계가 크게 틀린 경우 안내 · 시계가 2분 넘게 어긋나면 요청이 "늦게 도착"으로 버려짐 · `Hub.onRingRequest`
- [ ] 방해 금지 복원 실패를 사용자에게 알림 · 지금은 로그만 남김(Android 15+ 암시적 규칙) · `SoundState.restore`
- [ ] OSM 타일 사용량 · 공개 배포 시 OSM 타일 정책 한도를 넘을 수 있음 → 자체 타일/상용 지도 키 검토 · `ui/Visuals.kt` MiniMap
- [ ] 관측성: Worker 오류율·FCM 실패율 대시보드/알림 · 장애를 사용자가 먼저 알게 됨 · Cloudflare Workers Observability
- [ ] 서비스 계정 키 교체 절차 문서화 · 키 유출 시 대응 시간 단축 · `design/FIREBASE_SETUP.md`
