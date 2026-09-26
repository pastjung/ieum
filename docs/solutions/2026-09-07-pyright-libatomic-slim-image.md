# python:slim 이미지에서 pyright가 exit 127로 죽는 문제

## 증상

MR verify의 ai test 스테이지에서 `pyright` 실행 시:
`.../node: error while loading shared libraries: libatomic.so.1: cannot open shared object file` → exit 127로 빌드 실패. 로컬(Windows)에서는 정상 통과.

## 원인

pip의 pyright는 실행 시 node 바이너리를 내려받아 구동하는데, `python:3.12-slim`(Debian slim)에는 node가 요구하는 `libatomic1`이 없다. "로컬은 되는데 CI만 죽는" 전형적 환경 차이.

## 해결

Dockerfile test 스테이지에 라이브러리 설치 + node 재다운로드 방지 캐시:

```dockerfile
RUN apt-get update && apt-get install -y --no-install-recommends libatomic1 \
    && rm -rf /var/lib/apt/lists/*
...
RUN --mount=type=cache,target=/root/.cache/pyright-python \
    ruff check . && ruff format --check . && pyright && python -m pytest
```

## 재발 방지

- slim 이미지에 네이티브 실행 파일을 들이는 도구를 추가할 때는 요구 시스템 라이브러리를 먼저 확인한다.
- 로컬과 CI 결과가 다르면 CI가 기준 (docs/conventions/quality/testing.md).
