# ADR 0003 — 백엔드 Git Flow

- 상태 **채택**
- 날짜 **2026-11-25**

## 배경

Spring Boot 변경은 `spring-boot-v2`에 쌓였고, 기본 브랜치 `main`은 초기 FastAPI 버전에서 멈춰 있었습니다. 기본 체크아웃이 배포용 코드와 일치하도록 정리할 필요가 있었습니다.

## 결정

`main`은 검증을 마친 릴리스 브랜치, `develop`은 작업 통합 브랜치입니다. 새 기능은 `develop`에서 `feature/<이름>`을 만들고 리뷰와 검증 후 `develop`에 병합합니다. 릴리스는 `develop`에서 준비하고 `main`에 병합합니다. 동시에 통합 작업이 진행되어 릴리스 마무리를 분리해야 할 때 `release/<버전>`을 사용하고 마무리 커밋은 `develop`에도 반영합니다. 운영 긴급 수정은 `main`에서 `hotfix/<이름>`을 만들어 검증 후 양쪽에 반영합니다.

기존 Spring Boot 이력을 기준으로 `develop`을 구성하고, 매물 리팩터링 feature를 통합한 뒤 `main`에 릴리스합니다. `spring-boot-v2` 브랜치와 초기 FastAPI 커밋 이력을 보존합니다.

## 결과

GitHub 기본 브랜치에서 Spring Boot 소스와 빌드 방법을 확인할 수 있습니다. 운영 VM의 실행 버전은 별도 배포로 바뀝니다.
