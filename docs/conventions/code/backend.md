# 코드 스타일 — backend (Spring Boot)

## 원칙

- 일관성을 개인 선호보다 우선한다. 포맷은 리뷰 대상이 아니라 자동화 대상이다.
- 이름은 구현 방식보다 의도를 드러낸다. 주석은 "무엇"이 아니라 "왜"를 설명한다.
- 도메인 용어는 프로젝트 전체에서 통일한다 (learner/user, lesson/class 혼용 금지).

## 자동화 도구

- 포맷: Spotless + google-java-format (Gradle 연결, CI에서 강제 — 도입 예정)
- 스타일 기준: Google Java Style

## 코드 작성

- 한 메서드는 한 가지 책임. 길어지면 검증·변환·저장 단위로 분리한다.
- 매직 넘버·문자열은 상수 또는 enum으로 분리한다.
- null 반환보다 Optional, 빈 컬렉션, 명시적 예외를 우선한다.
- `var`는 지양하고 명시적 타입을 쓴다.

## 네이밍

| 대상 | 규칙 | 예시 |
| --- | --- | --- |
| 변수·메서드 | camelCase | `quizResult`, `gradeAnswer` |
| boolean | 상태가 드러나게 | `isCorrect`, `hasBrailleDevice` |
| 상수·enum 값 | UPPER_SNAKE_CASE | `MAX_DOT_COUNT`, `LESSON_COMPLETED` |
| 클래스·파일 | PascalCase | `LessonService`, `QuizController` |
| 패키지 | lowercase, 점 계층 | `com.ieum.lesson` |
| 컬렉션 | 복수형 | `lessons`, `wrongAnswerIds` |
| 테스트 클래스 | 대상클래스명 + Test | `LessonServiceTest` |

- DTO: 요청은 `XxxRequest`, 응답은 `XxxResponse` (`QuizSubmitRequest`, `LessonSummaryResponse`).
- Service 메서드는 업무 용어로: `assignQuiz()`, `gradeAnswer()`, `buildReviewPlan()`.
- Entity 클래스는 단수 명사, DB 테이블·컬럼은 snake_case로 매핑하고 테이블명은 복수형으로 통일한다.

```java
@Entity
@Table(name = "lessons")
public class Lesson {
  @Column(name = "created_at")
  private LocalDateTime createdAt;
}
```

## 구조와 예외 처리

- 패키지는 도메인 중심: `lesson/`, `quiz/`, `review/`, `member/` + 공통은 `global/`(config, error, security).
- Controller에는 요청/응답 변환만, 비즈니스 로직은 Service에, 도메인 규칙은 Entity에 둘 수 있다.
- 예외는 `@RestControllerAdvice`에서 공통 처리. 비즈니스 예외는 커스텀 예외 + 에러 코드.
- 응답에 스택 트레이스를 노출하지 않는다. 에러 응답 포맷은 api.md를 따른다.
- URL 설계는 api.md, 테스트는 ../quality/testing.md를 따른다.
