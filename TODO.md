# TODO.md — ARCShop

우선순위 순. `[DEFERRED]` 항목은 이번 루프에서 건너뛴다. 완료 시 `[x]`, 실패 3회 초과 시
`[BLOCKED: 사유]`, 자동화 불가 검증은 `[MANUAL: 사유]`.

## 1. 필수 트랜잭션 코어
- [x] 순수 도메인 모델 (ItemSignature, PriceComponent, Product, Category, TxResult)
- [x] 포트 인터페이스 (EconomyPort, CashPort, ItemStockPort, PurchaseLimitPort)
- [x] TransactionService: 복합 가격 계산(화폐+아이템 동시), 구매/판매 가능여부 분기,
      아이템 메타 완전 일치 기반 검증, 개인 재고 제한 원자적 예약, 부분 실패 롤백 — 단위 테스트 동반
- [x] mock 화폐/재고 저장소(FakePorts) — 동시 구매 경합(race) 시나리오 테스트 포함

## 2. Bukkit/ARCCore 연동 계층
- [ ] ARCShopModule (`@ModuleSpec`, arceconomy 의존 선언)
- [ ] ItemSignature ↔ ItemStack 변환 (Paper `serializeAsBytes`, 수량 정규화)
- [ ] EconomyPort 구현체 (arceconomy `EconomyService` 위임)
- [ ] 공유 DB(Exposed/JDBC) 연결 팩토리 — [DECISIONS.md](DECISIONS.md) #1
- [ ] CashPort 구현체 (공유 DB)
- [ ] PurchaseLimitPort 구현체 (공유 DB, 원자적 tryReserve)
- [ ] ItemStockPort 구현체 (플레이어 인벤토리, ItemSignature 완전 일치 매칭)
- [ ] 상점 정의 로더 (YAML: 카테고리/상품)

## 3. 상점 구조 / GUI
- [ ] Category → Product 계층 (자유 설계 채택 — DECISIONS.md #2)
- [ ] 상점 GUI (카테고리 메뉴 → 상품 메뉴)
- [ ] 클릭 인터랙션: 좌클릭=1개 구매, Shift+좌클릭=64개 구매, 우클릭=1개 판매, Shift+우클릭=전체 판매

## 4. 재고 — 개인 제한
- [ ] 플레이어별 상품 최대 구매 수량 제한 (프록시 공유 DB, PurchaseLimitPort)
- [DEFERRED] 재고 전체 제한 (기획서 명시적 보류 — 인터페이스만 확장 가능하게 열어둠, 미구현)
- [DEFERRED] 일일/주간/월간 구매 제한
- [DEFERRED] 기간 할인 (상품 가격 임시 조정)

## 5. 명령어 / 운영
- [ ] `/arcshop reload` (설정 리로드), `/arcshop open <category>` (관리자용 강제 오픈)

## 6. 빌드/검증
- [x] `./gradlew test` 통과 (순수 코어, Bukkit 부팅 없이)
- [MANUAL: 실제 인게임에서 상점 GUI 렌더 및 좌/우클릭·Shift 조합 클릭 인터랙션이 기대대로 동작하는지 확인]
- [MANUAL: 실제 프록시(다중 서버) 환경에서 캐시 잔액/개인 구매 한도가 서버 간 정상 동기화되는지 확인]
- [MANUAL: 운영자가 공유 DB(MySQL/MariaDB) 접속 정보를 `arcshop.yml`에 설정했는지 확인 — 미설정 시 단일 서버 SQLite로 폴백하며 경고 로그 출력]
