# @AttributeOverride가 Embeddable의 @Column을 통째로 교체해 컬럼 타입·NOT NULL이 사라진 문제

## 증상

`BraillePattern`(6점 셀 VO)에 `TINYINT NOT NULL`을 선언했는데, 실제 MariaDB에 생성된 컬럼은 `int(11) NULL`이었다.

```
attempt_cells   diff_mask          int(11)   YES
attempt_cells   expected_pattern   int(11)   YES
attempt_cells   input_pattern      int(11)   YES
problem_cells   expected_pattern   int(11)   YES
```

ERD 4.4절은 이 컬럼들을 `TINYINT NOT NULL` + `CHECK 0~63`으로 규정한다. **컴파일도 테스트도 전부 통과했다** — 타입이 넓어지고 NULL이 허용됐을 뿐이라 어떤 검사도 실패하지 않는다.

## 원인

`@Embeddable`의 필드에 `@Column`을 달아 두고, 쓰는 쪽에서 컬럼명만 바꾸려고 `@AttributeOverride`를 썼다.

```java
// Embeddable
@Embeddable
public class BraillePattern {
  @Column(nullable = false, columnDefinition = "TINYINT")
  private int value;
}

// 사용처 — 이름만 바꾸려던 의도
@Embedded
@AttributeOverride(name = "value", column = @Column(name = "expected_pattern"))
private BraillePattern expectedPattern;
```

`@AttributeOverride`의 `column`은 원본 `@Column`과 **병합되지 않고 통째로 대체된다.** 위 코드의 `@Column(name = "expected_pattern")`은 `nullable`과 `columnDefinition`을 지정하지 않았으므로 JPA 기본값(`nullable = true`, 타입은 자동 추론)이 적용되고, Embeddable에 적어 둔 `TINYINT NOT NULL`은 버려진다.

한 엔티티가 같은 VO를 여러 번 담을 때(여기서는 `attempt_cells`가 정답·입력·차이 마스크로 3개) 컬럼명 충돌을 피하려면 `@AttributeOverride`가 필수라서, 이 함정은 피할 수 없다.

## 해결

재정의할 때 **컬럼 속성 전체를 다시 명시한다.**

```java
@Embedded
@AttributeOverride(
    name = "value",
    column = @Column(name = "expected_pattern", nullable = false, columnDefinition = "TINYINT"))
private BraillePattern expectedPattern;
```

`BraillePattern`의 `@Column`에는 이 사실을 주석으로 남겼다 — 거기 값만 고치면 실제 스키마는 바뀌지 않기 때문이다.

## 재발 방지

`ddl-auto`로 스키마를 만드는 동안에는 **생성된 컬럼 타입을 실제 DB에서 눈으로 확인해야** 이 계열의 문제가 잡힌다. 엔티티 매핑 테스트는 값 왕복만 검증하므로 `int(11) NULL`에서도 통과한다.

```bash
docker compose up -d db && cd apps/backend && ./gradlew bootRun    # 스키마 생성
```

```sql
-- ERD와 대조할 컬럼의 물리 타입·NULL 허용 여부를 확인한다
select table_name, column_name, column_type, is_nullable
from information_schema.columns
where table_schema = database()
order by table_name, ordinal_position;
```

Flyway를 도입하면 DDL이 스키마 원본이 되므로 이 함정 자체가 사라진다. 그때까지는 엔티티를 추가·수정한 뒤 위 조회를 한 번 돌린다.
