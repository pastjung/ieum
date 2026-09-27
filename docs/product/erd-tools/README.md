# ERD 도식 생성 도구

`../erd.md`의 컬럼 표를 읽어 `../images/erd-*.{svg,png}`를 생성한다.

## 원본은 erd.md다

도식은 산출물이고 **원본은 [erd.md](../erd.md)의 컬럼 표**다. 표를 고치면 이 도구로 도식을 다시 만들어 같은 MR에 포함한다. 이미지를 직접 편집하지 않는다.

관계(FK)는 표에서 파싱하지 않고 `render-erd.cjs`의 `edges` 배열이 선언한다. 표에 FK 컬럼을 추가하면 `edges`도 함께 고쳐야 하며, 어긋나면 렌더가 실패한다. 테이블을 추가할 때는 `colors`와 `roles`도 함께 채워야 한다.

## 실행

```bash
cd docs/product/erd-tools
npm ci
npm run render
```

`npm run render`가 도메인 5개 뷰와 전체 상세도를 모두 만든다. 나눠 실행하려면 `npm run render:views` / `npm run render:full`을 쓴다.

생성되는 파일은 `../images/`의 12개다.

| 파일 | 범위 |
| --- | --- |
| `erd-overview.{svg,png}` | 16개 테이블 전체 관계도 |
| `erd-accounts.{svg,png}` | 계정·인증 |
| `erd-lectures.{svg,png}` | 실시간 수업 |
| `erd-content.{svg,png}` | 커리큘럼·문제·학습 요소 |
| `erd-learning.{svg,png}` | 개인 세션·풀이·평가·숙련도 |
| `erd-full.{svg,png}` | 모든 테이블의 모든 컬럼 |

중간 산출물인 `.dot`와 validation JSON은 파일로 만들지 않는다. 검증 요약은 stdout으로 출력된다.

## 재현 조건

의존성은 범위 지정자 없이 정확한 버전으로 고정한다. `@viz-js/viz`가 한 단계만 올라가도 내장 Graphviz가 바뀌어 SVG가 달라진다.

| 항목 | 값 |
| --- | --- |
| `@viz-js/viz` | `3.25.0` (내장 Graphviz 14.1.3) |
| `sharp` | `0.35.4` (libvips 8.18.6) |

**SVG는 고정된 Graphviz 버전에 의존한다.** 레이아웃 계산과 출력 형식이 Graphviz 버전마다 달라지므로 `@viz-js/viz` 버전을 임의로 올리지 않는다.

**PNG의 byte-identical 결과는 플랫폼과 `sharp`/libvips 버전, 시스템 폰트에 함께 의존한다.** SVG는 폰트를 이름으로만 참조하고(`Malgun Gothic`, `Arial`), 실제 글리프는 PNG로 래스터화할 때 시스템 폰트에서 그려지기 때문이다.

기존 12개 이미지가 byte-identical하게 재현되는 것을 확인한 기준 환경은 다음과 같다.

| 항목 | 값 |
| --- | --- |
| OS | Windows, `win32 x64` |
| Node.js | 24 |
| npm | 11 |
| 폰트 | Malgun Gothic (`malgun.ttf`, `malgunbd.ttf`) |

## 다른 환경에서 결과가 달라지면

Malgun Gothic이 없는 환경이나 다른 플랫폼에서는 PNG가 달라질 수 있다. **이때 생성된 이미지를 그대로 커밋하지 않는다.** `erd.md`를 고치지 않았는데 이미지가 바뀌었다면 스키마 변경이 아니라 환경 차이가 원인이므로, 변경 사유를 확인받은 뒤에 커밋한다.

`erd.md`를 고쳐서 이미지가 바뀐 경우는 정상이며, 표 변경과 이미지 변경을 같은 MR에 함께 담는다.

## 검증

`erd.md`를 고치지 않은 상태에서 재생성했다면 이미지가 하나도 바뀌지 않아야 한다.

```bash
cd docs/product/erd-tools && npm ci && npm run render
cd ../../.. && git diff --exit-code -- docs/product/images
```
