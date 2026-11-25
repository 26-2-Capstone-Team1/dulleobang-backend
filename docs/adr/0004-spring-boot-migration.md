# ADR 0004 — FastAPI에서 Spring Boot로 전환

- 상태 **채택**
- 날짜 **2026-11-25**

## 배경

초기 FastAPI 구현에서는 공통 요청 처리와 계약 검증을 `app/main.py`에서 담당했고 인증, 매물, 업로드, AI 결과 처리 로직이 큰 서비스 모듈에 모여 있었습니다. 앱 계약을 유지하면서 방 복원과 가구 모델 기능을 늘리려면 기능과 계층별로 변경 범위를 좁히고 검증과 트랜잭션의 책임을 명확히 할 필요가 있었습니다.

## 검토한 방안

- **FastAPI 유지 후 구조 개선**은 기존 Python 런타임과 배포 방식을 활용할 수 있습니다. 서비스·저장소·검증과 트랜잭션 경계를 기존 코드에 새로 적용해야 합니다.
- **Spring Boot 전환**은 Spring MVC, Bean Validation, Spring Security와 트랜잭션 관리를 한 애플리케이션에서 연결할 수 있습니다. Java 타입과 테스트로 계층 및 계약을 확인할 수 있으며 JDK·Gradle 환경과 배포 전환이 필요합니다.

## 결정

공개 HTTP API는 Java 21과 Spring Boot로 운영합니다. 컨트롤러는 HTTP 요청과 응답을 다루고, 서비스는 권한과 업무 규칙 및 트랜잭션을 관리하며, 저장소는 PostgreSQL 접근을 담당합니다. API DTO와 도메인 데이터는 각 계층의 책임에 맞춰 정의합니다. JPA와 JdbcTemplate 사용 기준은 [ADR 0002](0002-postgresql-access.md)에 따릅니다.

앱의 `/api` 경로와 JSON 계약을 이전 기준으로 삼습니다. GPU 처리는 Python 워커 프로세스가 맡고 내부 작업 API로 연결합니다. 배포 단위는 Spring Boot JAR이며 데이터베이스 스키마 변경은 Flyway로 관리합니다.

## 결과

공개 API와 GPU 처리 코드를 각각 배포할 수 있습니다. 이전 및 후속 변경에서는 앱 계약, 데이터베이스 마이그레이션, 워커와의 왕복을 검증해야 합니다. 전환 결정의 기준은 코드 구조와 운영 방식입니다.

- [초기 FastAPI 구현](https://github.com/26-2-Capstone-Team1/dulleobang-backend/tree/2fcef116622b44597c90a63d558569da0d9cdc2a)
- [Spring Boot 전환 커밋](https://github.com/26-2-Capstone-Team1/dulleobang-backend/commit/c31050f013d3d0241fc504d17b708b4fcc525805)
