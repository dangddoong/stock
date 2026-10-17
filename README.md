
## 1. 테이블 설계

`product` 테이블은 상품 ID, 상품 코드, 상품명, 재고 수량으로 구성됩니다.

| 컬럼 | 타입 | 제약조건 | 설명 |
| --- | --- | --- | --- |
| `id` | `BIGINT` | PK, 자동 생성 | 상품의 내부 식별자 |
| `product_code` | `VARCHAR(50)` | NOT NULL, UNIQUE | 입고 시 상품을 식별하는 고유 코드 |
| `name` | `VARCHAR(200)` | NOT NULL | 상품명 |
| `quantity` | `BIGINT` | NOT NULL, DEFAULT 0, CHECK ≥ 0 | 현재 재고 수량 |

```sql
CREATE TABLE product (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    product_code VARCHAR(50)  NOT NULL,
    name         VARCHAR(200) NOT NULL,
    quantity     BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT uk_product_code UNIQUE (product_code),
    CONSTRAINT ck_product_quantity CHECK (quantity >= 0)
);
```

기존 `id`, `name`, `quantity` 외에 상품 코드인 `product_code`를 추가하였습니다.

등록되지 않은 상품도 입고될 수 있으므로, 입고 요청에서 기존 상품과 신규 상품을 구분할 수 있는 식별 기준이 필요합니다.`id`는 DB에서 생성되기 때문에 신규 상품의 입고 요청에서는 사용할 수 없습니다.

상품명은 중복되거나 변경될 수 있는 속성이므로 식별 기준으로 적합하지 않다고 판단했습니다. 따라서 상품마다 고유한 코드를 사용하고, UNIQUE 제약조건으로 동일 코드의 중복 등록을 방지했습니다.

상품명은 신규 등록 시 저장하며, 기존 상품에 대한 입고에서는 변경하지 않습니다.

재고 수량에는 `CHECK (quantity >= 0)` 제약조건을 설정했습니다. 출고 SQL에서 재고 부족을 검사하는 것에 더해, DB에서도 음수 재고가 저장되지 않도록 방어합니다.

## 2. API별 Flow 및 상세설명

| 기능 | API | 처리 방식 |
| --- | --- | --- |
| 입고 | `POST /api/products/stock-in` | UPSERT |
| 출고 | `POST /api/products/{id}/stock-out` | 조건부 UPDATE |
| 재고 조회 | `GET /api/products/{id}/stock` | SELECT |

### 1) 입고

기존 상품이면 재고를 증가시키고, 등록되지 않은 상품이면 상품 정보와 최초 재고를 저장합니다. 동일 상품에 대한 동시 입고에서도 성공한 입고 수량이 모두 반영되어야 합니다.

#### 처리 Flow

```text
입고 요청 (productCode, name, quantity)
                  │
                  ▼
           요청 데이터 검증
                  │
                  ▼
                UPSERT
                  │
         ┌────────┴────────┐
         ▼                 ▼
      신규 상품          기존 상품
         │                 │
       INSERT            UPDATE
   상품과 최초 재고 등록    현재 재고 증가
         │                 │
         └────────┬────────┘
                  ▼
       RETURNING 결과 수신
              │
              ▼
            COMMIT
              │
              ▼
           성공 응답
```

#### 핵심 SQL

```sql
INSERT INTO product AS p (
    product_code,
    name,
    quantity
)
VALUES (
    :productCode,
    :initialName,
    :incomingQuantity
)
ON CONFLICT (product_code)
DO UPDATE
SET quantity = p.quantity + :incomingQuantity
RETURNING id, product_code, name, quantity;
```

`product_code`의 UNIQUE 제약조건을 기준으로 신규 등록과 기존 상품의 재고 증가를 구분합니다.

신규 상품이면 INSERT를 수행하고, 기존 상품이면 DB의 현재 재고에 입고 수량을 더합니다. 애플리케이션에서 조회한 수량으로 덮어쓰지 않으므로, 동시 입고 시 다른 요청의 증가분이 사라지는 Lost Update를 방지합니다.

`RETURNING`으로 변경 결과를 받아 별도 SELECT 없이 응답 데이터를 구성하며, 트랜잭션이 커밋된 뒤 성공 응답을 반환합니다.

#### UPSERT를 선택한 이유

SELECT로 상품 존재 여부를 먼저 확인하면, 조회와 등록 사이에 다른 요청이 개입할 수 있습니다.

```text
요청 A                         요청 B
  │                              │
SELECT → 상품 없음             SELECT → 상품 없음
  │                              │
INSERT 성공                    INSERT 시도
  │                              │
COMMIT                         UNIQUE 충돌
```

UNIQUE 제약조건으로 중복 등록은 방지할 수 있지만, 실패한 요청의 입고를 반영하려면 충돌 처리와 재시도 등의 로직이 추가로 필요합니다.

UPSERT는 이러한 충돌 처리를 DB에 맡겨 신규 등록과 기존 수량 증가를 하나의 SQL에서 처리합니다. PostgreSQL은 `ON CONFLICT DO UPDATE`를 통해 동시 요청에서도 원자적인 INSERT 또는 UPDATE를 지원합니다. [PostgreSQL Transaction Isolation](https://www.postgresql.org/docs/current/transaction-iso.html)

| 방식 | 장점 | 고려사항 |
| --- | --- | --- |
| SELECT 후 INSERT/UPDATE | 업무 흐름이 직관적 | 추가 DB 접근 및 동시 신규 등록 충돌 처리 필요 |
| INSERT DO NOTHING 후 UPDATE | 중복 INSERT 예외 회피 | 여러 SQL과 분기 처리 필요 |
| JPA 조회 후 save | ORM 중심 구현 가능 | 수량 증가와 신규 등록 경쟁에 별도 동시성 제어 필요 |
| UPSERT | 단일 SQL로 신규 등록 및 재고 증가 처리 | PostgreSQL 문법에 의존 |

UPSERT가 기존 상품에 대한 단순 UPDATE보다 항상 빠른 것은 아닙니다. 다만 현재 요구사항에는 신규 등록도 포함되므로, DB 접근 횟수와 충돌 처리의 복잡도를 함께 고려해 선택했습니다.

#### 서버 확장과 Queue 도입 검토

동시성 제어를 DB에서 수행하므로, 여러 애플리케이션 인스턴스가 동일한 Primary DB에 접근하는 구조에서도 같은 재고 변경 규칙을 적용할 수 있습니다. 서버 내부의 메모리 락에 의존하지 않아 애플리케이션을 수평 확장할 수 있습니다.

다만 서버 수를 늘린다고 동일 상품의 처리량이 비례해서 증가하지는 않습니다. 같은 상품에 대한 변경은 동일한 행에서 경합하므로, 요청이 집중되면 DB의 잠금 대기와 처리 용량이 병목이 될 수 있습니다.

실제 병목이 확인되면 SQL 실행 시간, 잠금 대기, 커넥션 사용량 등을 점검하고, 순간적인 요청을 완충할 필요가 있을 때 Queue 도입을 검토합니다.

Queue를 도입하면 요청 접수와 실제 재고 반영이 분리될 수 있으므로, 즉시 처리 결과를 반환하는 현재 API와 응답 방식이 달라집니다. 또한 Queue만으로 DB의 지속적인 처리 한계가 해결되지는 않으며, 중복 메시지에 대한 멱등성과 실패 재처리 설계도 필요합니다.

### 2) 출고

현재 재고가 요청 수량 이상인 경우에만 수량을 차감합니다. 동시 출고 요청에서도 재고가 음수가 되지 않아야 하며, 존재하지 않는 상품과 재고 부족을 구분해 응답합니다.

#### 처리 Flow

```text
출고 요청 (productId, quantity)
              │
              ▼
       요청 데이터 검증
              │
              ▼
         조건부 UPDATE
              │
      ┌───────┴────────┐
      ▼                ▼
   변경 성공        변경 결과 없음
      │                │
 RETURNING        상품 존재 여부 조회
      │                │
      │          ┌─────┴─────┐
      │          ▼           ▼
      │        상품 없음    상품 있음
      │          │           │
      │         404         409
      ▼
    COMMIT
      │
      ▼
   성공 응답
```

#### 핵심 SQL

```sql
UPDATE product
SET quantity = quantity - :outgoingQuantity
WHERE id = :id
  AND quantity >= :outgoingQuantity
RETURNING id, product_code, name, quantity;
```

`WHERE` 절에서 재고가 충분한지 확인하고, 조건을 만족한 경우에만 차감합니다. **재고 부족 검사와 수량 차감이 하나의 SQL에서 원자적으로 수행됩니다.**

#### 조건부 UPDATE를 선택한 이유

재고를 SELECT로 조회한 뒤 애플리케이션에서 출고 가능 여부를 판단하면, 실제 UPDATE 전에 다른 요청이 재고를 변경할 수 있습니다.

예를 들어 재고가 10개일 때 두 요청이 각각 7개를 출고하려 하면, 두 요청 모두 조회 시점에는 재고가 충분하다고 판단할 수 있습니다.

현재 업무 규칙은 “재고가 요청 수량 이상이면 차감한다”이므로, 이를 UPDATE의 조건으로 직접 표현했습니다.

PostgreSQL의 `READ COMMITTED`에서는 동일 행을 변경하는 UPDATE가 경합하면 앞선 트랜잭션의 종료를 기다립니다. 앞선 변경이 커밋되면 변경된 행을 기준으로 `WHERE` 조건을 다시 평가합니다. [PostgreSQL Transaction Isolation](https://www.postgresql.org/docs/current/transaction-iso.html)

초기 재고가 10개이고 두 요청이 각각 7개를 출고하는 경우의 흐름은 다음과 같습니다.

| 순서 | 요청 A | 요청 B |
| --- | --- | --- |
| 1 | UPDATE 실행 및 행 잠금 획득 | UPDATE 실행 |
| 2 | 재고 10 → 3 | 잠금 대기 |
| 3 | COMMIT | 대기 종료 |
| 4 | 성공 응답 | 변경된 재고 3으로 조건 재평가 |
| 5 | — | 조건 불충족, 차감하지 않음 |

최종 재고는 3개이며, 한 요청만 성공합니다.

| 방식 | 장점 | 고려사항 |
| --- | --- | --- |
| SELECT 후 UPDATE | 구현이 직관적 | 별도 동시성 제어 없이는 조회와 변경 사이 경쟁 발생 |
| SELECT FOR UPDATE | 잠금 상태에서 여러 업무 조건 검증 가능 | 별도 잠금 조회 필요 |
| 낙관적 락 (`@Version`) | 버전으로 동시 변경 감지 | 충돌 시 실패 처리 또는 재시도 필요 |
| 조건부 UPDATE | 단일 SQL로 조건 검사 및 차감 | 복잡한 다중 행 조건에는 추가 설계 필요 |

조건부 UPDATE도 DB 내부의 행 잠금을 사용합니다. 애플리케이션에서 별도의 잠금 조회나 버전 관리 로직을 구현하지 않고, UPDATE 자체의 동시성 제어를 활용한 방식입니다.

#### 실패 시에만 추가 조회하는 이유

UPDATE 결과가 없으면 상품이 존재하지 않거나 재고가 부족한 경우입니다. 이때만 상품 존재 여부를 추가 조회합니다.

```java
@Transactional
public ProductStockSnapshot stockOut(long id, long outgoingQuantity) {
    return repository.decreaseStockIfSufficient(id, outgoingQuantity)
            .orElseThrow(() -> {
                ErrorCode errorCode = repository.existsById(id)
                        ? ErrorCode.STOCK_UNAVAILABLE
                        : ErrorCode.PRODUCT_NOT_FOUND;

                return new BusinessException(errorCode);
            });
}
```

상품이 없으면 `404 PRODUCT_NOT_FOUND`, 상품이 있으면 `409 STOCK_UNAVAILABLE`로 응답합니다.

정상 출고는 UPDATE 한 번으로 처리합니다. 추가 SELECT는 오류 응답을 구분하는 용도이며, 재고 차감의 정합성은 조건부 UPDATE가 보장합니다. 두 SQL 사이에 입고가 발생할 수도 있으므로, 409는 해당 출고 시도가 실패했다는 의미입니다.

### 3) 재고 조회

상품 ID로 현재 재고를 조회합니다. 존재하지 않는 상품은 404로 응답하며, 다른 트랜잭션의 미커밋 변경은 조회하지 않습니다.

#### 처리 Flow

```text
재고 조회 요청 (productId)
           │
           ▼
      상품 ID 검증
           │
           ▼
       SELECT 조회
           │
     ┌─────┴──────┐
     ▼            ▼
  상품 있음     상품 없음
     │            │
  재고 반환       404
```

#### 핵심 SQL

```sql
SELECT
    id,
    product_code,
    name,
    quantity
FROM product
WHERE id = :id;
```

상품 ID는 Primary Key이므로 별도의 조회용 인덱스는 추가하지 않았습니다.

#### 단순 SELECT를 선택한 이유

조회는 재고를 변경하지 않으므로 `SELECT FOR UPDATE`로 행 잠금을 획득할 필요가 없습니다.

PostgreSQL의 MVCC와 `READ COMMITTED` 격리 수준에 따라, 일반 SELECT는 조회문 시작 시점에 커밋된 데이터를 읽습니다. 다른 트랜잭션이 재고를 변경 중이더라도 그 트랜잭션의 미커밋 변경은 읽지 않습니다. [PostgreSQL Transaction Isolation](https://www.postgresql.org/docs/current/transaction-iso.html)

조회 결과는 해당 시점의 재고이며, 이후의 출고 가능 여부를 보장하지 않습니다. 실제 출고 시에는 조건부 UPDATE에서 재고가 충분한지 다시 판단합니다.

| 방식 | 장점 | 고려사항 |
| --- | --- | --- |
| Primary DB SELECT | 커밋된 재고를 직접 조회, 단순한 구조 | DB에 조회 부하 발생 |
| SELECT FOR UPDATE | 조회 후 변경을 위한 잠금 확보 | 단순 조회에서는 불필요한 잠금 경합 발생 |
| Redis 캐시 | 조회 부하 감소 | 캐시 무효화 및 데이터 정합성 관리 필요 |
| Read Replica | 조회 부하 분산 | 복제 지연으로 이전 재고 조회 가능 |

현재는 PK 기반 단건 조회이므로 Primary DB를 직접 조회합니다. 캐시나 Read Replica는 실제 조회 병목이 확인된 뒤, 허용 가능한 데이터 지연을 함께 고려해 검토합니다.

### 현재 범위와 멱등성

현재 입출고 API는 각 요청을 독립적으로 처리하며, 동일 요청의 재시도에 대한 멱등성은 보장하지 않습니다. 재고 변경이 커밋된 뒤 응답 전달에 실패하면, 클라이언트의 재시도로 같은 입출고가 다시 반영될 수 있습니다. 이는 동시 요청 간 재고 정합성과 별도로 다뤄야 하는 문제입니다.

향후 입출고 이력을 도입할 때 `requestId` 또는 `Idempotency-Key`를 함께 받아 중복 반영을 방지할 수 있습니다. 요청 식별자에 UNIQUE 제약조건을 적용하고, 해당 식별자의 기록과 재고 변경을 동일 트랜잭션으로 처리합니다. 동일 식별자로 재요청하면 기존 처리 결과를 반환하고, 상품이나 수량이 다른 요청은 거절하는 정책이 필요합니다.

## 3. 테스트코드

### 1) 동시 입고 및 출고 요청에 대한 동시성 제어 테스트

`ProductStockConcurrencyTest`에서 동시 입출고 시 재고 정합성을 검증합니다.

Testcontainers로 PostgreSQL 17.6을 실행해 실제 PostgreSQL의 UPSERT, 조건부 UPDATE, 트랜잭션 동작을 대상으로 테스트합니다.

최대 16개의 작업 스레드를 사용하고, `CountDownLatch`로 초기 작업들의 시작 시점을 맞춰 경합을 유도합니다. 각 서비스 호출은 별도의 트랜잭션에서 실행되며, 모든 작업이 종료된 뒤 최종 재고와 성공·실패 결과를 검증합니다.

| 테스트 시나리오 | 검증 내용 |
| --- | --- |
| 미등록 상품에 1개씩 100건 입고 | 모든 요청 성공, 상품 1행, 최종 재고 100개 |
| 재고 10개인 상품에 1개씩 100건 입고 | 최종 재고 110개, 기존 상품명 유지 |
| 같은 신규 상품 코드에 서로 다른 이름으로 40건 입고 | 상품 1행, 각 2개씩 총 80개 반영, 등록된 이름 하나 유지 |
| 재고 10개에 7개씩 2건 출고 | 1건 성공, 1건 재고 부족, 최종 재고 3개 |
| 재고 100개에 1개씩 200건 출고 | 100건 성공, 100건 재고 부족, 최종 재고 0개 |
| 입고와 출고를 혼합해 실행 | 성공한 입출고 수량과 최종 재고 일치, 음수 재고 없음 |
| 여러 상품 코드에 동시 입고 | 상품별 입고 합계가 서로 영향을 주지 않고 반영 |

입출고 혼합 테스트는 실행 순서에 따라 출고 성공 건수가 달라질 수 있으므로, 성공 건수를 고정하지 않고 다음 관계를 검증합니다.

```text
최종 재고
= 초기 재고
+ 성공한 입고 수량의 합
- 성공한 출고 수량의 합
```

이 테스트는 서비스와 DB 사이의 동시성 정합성을 검증합니다. 여러 서버 인스턴스에서 HTTP 요청을 보내는 부하 테스트와는 범위가 다릅니다.

### 2) 잘못된 요청에 대한 에러 응답 테스트

`ProductStockControllerTest`에서는 `MockMvc`와 모킹한 서비스를 사용해 요청 검증 및 HTTP 오류 응답을 확인합니다.

`ProductStockIntegrationTest`에서는 실제 PostgreSQL과 연결해 상품 미존재, 재고 부족 등의 동작과 데이터 변경 여부를 검증합니다.

| 상황 | 기대 응답 |
| --- | --- |
| 필수값 누락, 빈 상품명, 잘못된 상품 코드 | `400 INVALID_REQUEST` |
| 상품명에 null 문자(`\u0000`) 포함 | `400 INVALID_REQUEST`, `name` 필드 오류 |
| 수량이 0 또는 음수 | `400 INVALID_REQUEST` |
| 소수·문자열 수량 또는 `long` 범위 초과 | `400 INVALID_REQUEST` |
| 잘못된 JSON 또는 요청 본문 형식 | `400 INVALID_REQUEST` |
| 상품 ID가 0·음수이거나 잘못된 형식 | `400 INVALID_REQUEST` |
| 존재하지 않는 상품 조회 또는 출고 | `404 PRODUCT_NOT_FOUND` |
| 숫자 상품 코드를 상품 ID로 잘못 사용하고 해당 ID의 상품이 없는 경우 | `404 PRODUCT_NOT_FOUND` |
| 존재하지 않는 API 경로 | `404 RESOURCE_NOT_FOUND` |
| 현재 재고보다 많은 수량 출고 | `409 STOCK_UNAVAILABLE` |
| 지원하지 않는 HTTP 메서드 | `405 METHOD_NOT_ALLOWED` |
| 지원하지 않는 응답 형식을 `Accept`로 요청 | `406 HTTP_ERROR` |
| 지원하지 않는 Content-Type | `415 UNSUPPORTED_MEDIA_TYPE` |

HTTP 상태 코드뿐 아니라 오류 코드와 필드 오류 정보도 검증합니다. 요청 검증에 실패한 경우 서비스가 호출되지 않는지 확인합니다. 일부 잘못된 입고 요청에서는 응답에 `Exception` 문자열이 포함되지 않는지도 확인합니다.

통합 테스트에서는 재고 부족 요청에 409로 응답하고 기존 재고가 유지되는지, 존재하지 않는 상품의 조회·출고 요청에 404로 응답하는지 검증합니다.

null 문자가 포함된 상품명은 신규·기존 상품 모두 거절하며, 신규 상품이 생성되거나 기존 재고가 변경되지 않는지 검증합니다. 숫자 상품 코드와 내부 상품 ID를 구분해 처리하고, 상품 코드를 ID로 잘못 사용한 요청이 실패해도 실제 상품의 재고가 유지되는지 확인합니다.

## 참고 자료

- [PostgreSQL — INSERT / ON CONFLICT](https://www.postgresql.org/docs/current/sql-insert.html)
- [PostgreSQL — UPDATE](https://www.postgresql.org/docs/current/sql-update.html)
- [PostgreSQL — Transaction Isolation](https://www.postgresql.org/docs/current/transaction-iso.html)
- [PostgreSQL — MVCC](https://www.postgresql.org/docs/current/mvcc-intro.html)

## 실행 및 테스트

### 코드 컨벤션

Java 소스와 테스트는 [Google Java Style](https://google.github.io/styleguide/javaguide.html)을 기준으로 작성합니다.
들여쓰기는 공백 2칸, 줄 길이는 기본 100자이며, 와일드카드 import 대신 명시적인 import를 사용하고 조건문에는 중괄호를 붙입니다.
Spotless와 google-java-format 1.28.0으로 포맷과 import 순서를 통일합니다.

```bash
./gradlew spotlessApply  # 전체 Java 소스와 테스트 자동 포맷
./gradlew spotlessCheck  # 포맷 검사 (파일 변경 없음)
```

`./gradlew check`에는 `spotlessCheck`도 포함됩니다. `.editorconfig`는 IDE의 기본 들여쓰기를 맞추며,
최종 포맷은 `spotlessApply`를 기준으로 합니다. 이름의 의미, 메서드 책임, 중괄호 사용 등 자동 포맷으로 보장되지 않는 항목은 리뷰에서 확인합니다.

### 실행 명령

Java 21과 실행 중인 Docker가 필요합니다. 프로젝트 루트에서 다음 명령을 실행합니다.

```bash
# PostgreSQL 실행
docker compose up -d --wait

# 애플리케이션 실행 (기본 포트: 8080)
./gradlew bootRun
```

전체 테스트는 다음 명령으로 실행합니다. Testcontainers가 별도의 PostgreSQL을 실행하므로 애플리케이션이나 Compose DB를 미리 실행할 필요는 없습니다.

```bash
./gradlew check
```

`./gradlew test`는 컨트롤러 테스트만 실행합니다. 동시성·DB 통합 테스트까지 검증하려면 `./gradlew check`를 사용해야 합니다.
