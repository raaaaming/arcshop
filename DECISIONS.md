# DECISIONS.md — ARCShop 설계 결정 기록

되돌리기 어려운 결정만 기록한다. 사소한 결정(패키지 배치 등)은 커밋 메시지에만 남긴다.

## 1. 프록시 간 공유 상태(개인 재고 제한 / 캐시 잔액) 저장소

**선택지**
- (a) ARCCore `SqliteStorageService` (arc-database) 사용
- (b) Redis Pub/Sub 등 새 메시징 계층 직접 구축
- (c) 운영자가 지정하는 공유 DB(MySQL/MariaDB)에 Exposed로 직접 연결

**근거**
- (a)는 `dataFolder/databases` 밑에 **서버-로컬** SQLite 파일을 쓰는 구조라 프록시 환경에서 서버 간
  공유가 되지 않는다 (ARCCore 소스 확인 완료 — `ModuleDatabases`는 항상 로컬 디렉터리 기준).
- ARCCore에는 프록시 간 메시징이 없다. `arc-eventbus`의 `DistributedEventBusPort`는 문서에도
  "consumers that need cross-node events implement this port"라고 명시된 **미구현 확장점**뿐이다.
  Redis 등 실제 분산 이벤트 구현체가 ARCCore/생태계에 존재하지 않는다.
- (b)는 ARCShop 하나를 위해 새 인프라(Redis)를 요구해 과설계다. (c)는 이미 arceconomy가 실제로 쓰는
  Exposed(exposed-core/jdbc)를 그대로 재사용하면서, "진실의 원천은 항상 중앙 저장소"(CLAUDE.md 원칙 1)
  요구를 가장 단순하게 만족한다.

**결정**: (c). ARCShop은 `SqliteStorageService`를 쓰지 않고, `arcshop.yml` 설정으로 지정된 JDBC URL
(MySQL/MariaDB 권장; 단일 서버 테스트용으로만 SQLite 허용하되 경고 로그 출력)에 Exposed로 직접
연결한다. MySQL/MariaDB JDBC 드라이버는 ARCCore/arceconomy 어디에도 없는 새 외부 라이브러리이므로,
`@ModuleSpec(libraries = ["org.mariadb.jdbc:mariadb-java-client:3.4.1"])` 로 선언해 ARCCore가
런타임에 자동 다운로드하게 한다 (라이브러리를 셰이딩하거나 직접 번들링하지 않음).

개인 재고 제한(`purchaseLimit`)과 캐시 잔액(`cash`) 모두 이 방식으로 저장한다 — 캐시도 "재화"이므로
플레이어가 서버를 이동해도 값이 유지되어야 한다는 CLAUDE.md 원칙 1을 동일하게 적용했다.

## 2. 상점 구조

기획서가 "더 나은 구조가 있으면 채택 가능"이라 명시. Category(id, name, productIds) → Product(id,
priceComponents, resultItem, buyable/sellable, personalPurchaseLimit) 의 단순 2단 구조를 채택했다.
카테고리 트리/서브카테고리 등은 요구사항에 없어 추가하지 않았다 (YAGNI).

## 3. 아이템 동일성(ItemSignature) 구현

Bukkit(Paper) `ItemStack.serializeAsBytes()` 로 만든 정규화 바이트(수량은 1로 고정 후 직렬화)를
Base64 문자열로 만들어 키로 쓴다. 이름/설명/인챈트 등 ItemMeta 전체가 바이트 비교에 포함되므로
"완전 일치" 요구를 직접 NBT 비교 코드를 새로 짜지 않고 만족한다 (Paper 표준 API 재사용, YAGNI).

## 4. 판매 가격 = 구매 가격 (대칭)

Product의 `priceComponents`를 구매 비용이자 판매 환불액으로 동일하게 사용한다. 기획서에 구매/판매
가격을 분리하라는 명시적 요구가 없어 가장 단순한 해석을 택했다. 추후 비대칭 가격이 필요하면
`Product`에 별도 `sellPriceComponents`를 추가하는 확장을 고려한다 (지금은 미구현 — YAGNI).

## 5. 개인 구매 한도는 판매로 되돌아가지 않음

`personalPurchaseLimit`은 "누적 평생 구매 수량" 캡으로 취급하고, 판매해도 카운터를 감소시키지
않는다. 그렇지 않으면 구매→판매→재구매를 반복해 한도를 무력화하는 악용이 가능하기 때문이다.

## 6. GUI 프레임워크

ARCCore에는 인벤토리/GUI 프레임워크가 없음을 소스 확인(전체 grep, 0건)했다. 따라서 순수 Bukkit
`Inventory`/`InventoryHolder`/`InventoryClickEvent` 로 직접 구현한다.

## 7. 포트(core.port)는 동기 인터페이스

arceconomy의 `EconomyService` 자체가 동기 API이고(내부적으로만 스레드 안전), 새로 만드는
`CashPort`/`ItemStockPort`/`PurchaseLimitPort` 도 동일한 관례를 따라 전부 동기로 통일했다. "메인
스레드를 블로킹하지 않는다"는 원칙은 이 포트를 호출하는 Bukkit 모듈 계층에서 ARCCore
`scheduler.runAsync { ... }` 로 트랜잭션 전체를 비동기 실행하고, 결과 반영(인벤토리/GUI 갱신)만
`runSync`로 되돌리는 방식으로 지킨다.

## 8. shop.yml 아이템 표기 — `material` 지름길

캡처된 Base64 완전 일치 키(`item`/`result-item`)만 지원하면 이름/인챈트 등 커스텀 메타가 없는 흔한
바닐라 재화(다이아몬드, 철 주괴 등)조차 반드시 인게임에서 `/arcshop capture`를 거쳐야 해서 첫 설정이
번거롭다. 그래서 메타 없는 순정 아이템은 `result-material`/`price[].material`에 `Material` 이름만
적으면 되도록 허용했다 — 내부적으로 `ItemStack(material)`을 만들어 동일한 `serializeAsBytes` 정규화를
거치므로 완전 일치 규칙(#3)은 그대로 유지된다. 이름/설명/인챈트가 붙은 커스텀 아이템은 여전히
`item`/`result-item`(캡처된 키)만 써야 한다.
