---
name: api-endpoint
description: backend(Spring Boot) 또는 ai(FastAPI)에 API 엔드포인트를 추가·수정할 때 반드시 사용. 스키마-테스트-구현 순서를 강제하는 절차.
---

# API 엔드포인트 추가 절차

0. 규칙 확인: docs/conventions/code/api.md (URL·camelCase·에러 포맷·페이지네이션) + 작업하는 앱의 code/<앱>.md. 노션 API 명세서에 이미 정의된 엔드포인트라면 명세를 사용자에게 확인받는다.
1. **스키마부터**: 요청/응답 DTO(backend) 또는 Pydantic 모델(ai)을 먼저 작성한다 — XxxRequest / XxxResponse. Entity/DB 모델을 응답으로 직접 노출하지 않는다.
2. **실패하는 테스트 먼저**: 정상 케이스 1개 + 실패 케이스 1개 이상을 작성하고, 실행해서 빨간불을 확인한다 (docs/conventions/quality/testing.md).
3. **구현**: Controller/Router에는 요청·응답 변환만, 로직은 Service에. 예외는 공통 핸들러로.
4. **검증**: 테스트 초록불 + 해당 앱 린트·타입체크 통과 (AGENTS.md 검증 명령).
5. 완료 보고에 테스트 실행 결과를 첨부한다. backend와 ai가 같은 리소스를 다루면 두 쪽 규칙이 어긋나지 않는지 확인한다.
