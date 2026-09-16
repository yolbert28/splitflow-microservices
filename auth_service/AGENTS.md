# AGENTS.md

This file provides guidance to AI coding agents working in this repository.
All paths in this document are relative to the repository root.

## Required reading

Before planning, reviewing, or modifying this project, read
[docs/GENERIC_RULES.md](docs/GENERIC_RULES.md) in full and apply it alongside
this file. The link is an explicit reading requirement; do not assume your
tool automatically imports linked Markdown files.

GENERIC_RULES.md contains reusable working rules. This file adds the project's
backend conventions and spec-driven workflow. For repository conventions,
project-specific rules refine the generic defaults. Neither file overrides
the user's explicit instructions or the agent's higher-priority instructions.
If documents conflict in a way that affects behavior or scope, clarify the
conflict before implementing the affected part.

## Project

REST API built with Java 25 and Spring Boot 4.1.1 that handles user
authentication and registration for the SplitFlow microservices platform.

- Module/service structure: single-module microservice (`auth_service`)
- Package/namespace root: `dev.yolbert.auth_service`
- Baseline functionality: registers users, issues email-verification OTPs, and
  publishes domain events to RabbitMQ via an outbox pattern
- The existing `RegisterUseCase` + `VerifyEmailUseCase` pair is the reference
  for new features. Inspect their implementation before extending the service.

## Commands

Run commands from the repository root.

```bash
# Build (skip tests)
./mvnw clean package -DskipTests

# Run unit tests only
./mvnw test

# Run the service locally (requires Docker Compose for Postgres + RabbitMQ)
./mvnw spring-boot:run

# Run a single test class
./mvnw test -Dtest=RegisterUseCaseTest

# Run a single test method
./mvnw test -Dtest=RegisterUseCaseTest#shouldRegisterUser
```

Unit tests live in `src/test/java`. Integration tests (when added) should live
in `src/test/java` as well and use Testcontainers (already on the classpath)
to spin up Postgres and RabbitMQ containers.

There is no separate Checkstyle or SpotBugs configuration beyond what's in
the repository. Use the existing configuration; do not introduce a new quality
tool as an unrelated change.

For code changes, run `./mvnw clean package` before reporting completion. Run
relevant integration tests and manual checks (e.g., via `curl` or any REST
client against the running service) when acceptance criteria require
end-to-end behavior. For documentation-only changes, verify content and
references without requiring a full build. Report any checks that could not
run and the reason.

## Spec-driven workflow

### Reference documents

Each template carries its own instructions for the agent, its section
structure, and its identifier scheme. Read the template in full and follow it;
this file does not restate its content and must not contradict it.

- `docs/SPEC_TEMPLATE.md`: copy to the feature's `SPEC.md` when creating or
  updating a specification. It owns the spec's sections, its `RF-` requirement
  and `CA-` acceptance-criteria identifiers, and its approval states.
- `docs/PLAN_TEMPLATE.md`: copy to the feature's `PLAN.md` once the
  specification is approved. It owns the plan's sections and approval states.
  Reference requirements and criteria by their spec identifiers.
- `docs/API_GUIDELINES.md`: consult when writing or reviewing requirements,
  acceptance criteria, the technical plan, and backend validation. Consider
  API contract and versioning, authentication and authorization, input
  validation, idempotency, transactionality, concurrency, error handling and
  status codes, persistence and migrations, performance and scalability
  (pagination, caching, query cost), observability (logging, metrics,
  tracing), and data privacy. Apply only relevant items; clarify undefined
  product behavior instead of inventing it.

Preserve each template's structure and its embedded comments in the copy. Do not
rewrite the shared templates for an individual feature.

### Feature documents

Keep each feature's documents together:

- `docs/features/<feature-name>/SPEC.md`
- `docs/features/<feature-name>/PLAN.md`
- `docs/features/<feature-name>/TASKS.md`

Use an existing feature directory when continuing its work.

### Sequence

Each stage is gated by the previous document's state. A document is only
"Approved" when the user says so: a complete document is not an approved
one, and the agent never changes that state on its own.

1. **Specification:** complete `SPEC.md` from `docs/SPEC_TEMPLATE.md`,
   collaboratively and section by section, following the template's own
   instructions. Do not start the plan until the user approves the spec.
2. **Plan:** write `PLAN.md` from `docs/PLAN_TEMPLATE.md` for the approved
   specification, following the template's own instructions. Additionally,
   record whether subagents are needed and their bounded responsibilities; do
   not assume delegation is required or available. Do not start tasks until
   the user approves the plan.
3. **Tasks:** derive `TASKS.md` from the approved plan. There is no shared
   template for it, so this file defines it: small, ordered, verifiable
   checkboxes, each with an identifier, objective, scope, dependencies, the
   spec criteria it resolves, and its validation method. Keep tasks concise
   enough to execute and detailed enough to determine when they are done.
4. **Implementation:** execute the tasks within the agreed scope, preserving
   the architecture below. Authorization to implement must be explicit; it is
   not implied by the documents' state. Update task status as work progresses.
5. **Validation:** verify acceptance criteria with appropriate evidence and
   record the result in `TASKS.md`, including any outstanding checks.

Do not treat a filled template as resolution of unanswered questions. Ask
about missing product decisions that affect behavior; resolve routine technical
details from the code and established conventions. Do not ask for renewed
permission for steps the user has already authorized.

If implementation reveals a requirement gap or contradiction, clarify the
affected behavior and update the relevant documents before continuing that
part. Keep the specification, plan, tasks, and resulting behavior consistent.

## Architecture

Layered architecture with explicit request/response boundaries. The existing
`RegisterUseCase` is wired end to end with this structure, under
`src/main/java/dev/yolbert/auth_service/`:

| Path | Responsibility |
| --- | --- |
| `controller/*Controller.java` | Entry point: parses requests, calls use cases, maps responses and status codes |
| `dto/*.java` | Wire DTOs (request/response commands and response data), separate from domain models |
| `mapper/*.java` | Domain-to-DTO mapping functions (e.g., `UserMapper`) |
| `domain/entity/*.java` | JPA entities used as domain models (e.g., `User`, `Otp`, `Outbox`) |
| `repository/*Repository.java` | Spring Data JPA repository interfaces owned by this layer |
| `service/*UseCase.java` | Use cases containing business logic (e.g., `RegisterUseCase`, `VerifyEmailUseCase`) |
| `config/*.java` | Spring configuration: security, RabbitMQ, exception handling |
| `worker/*.java` | Background workers (e.g., `OutboxPublisher` — scheduled outbox relay) |
| `utils/*.java` | Stateless utility classes (code generators, custom validators) |

### Layer dependencies

- `controller → service` and `controller → dto`.
- Use cases access repositories and domain entities directly; they must not
  depend on the controller layer or framework-specific HTTP types.
- The controller layer calls use cases and maps their return values to
  `ApiSuccessResponse<T>`; it never accesses repositories directly.
- Dependency injection wires implementations via Spring constructor injection.

### Dependency injection

- Spring (constructor injection). All project-owned classes use constructor
  injection; field injection is not used.
- Follow the existing configuration in `config/SecurityConfig.java` and
  `config/RabbitMQConfig.java` for wiring beans.
- Match dependency scopes (singleton by default) to their intended lifetime
  and existing conventions.

### API design and contracts

- REST over HTTP. Routes follow the existing convention:
  - `POST /user/**` — user management (e.g., `POST /user/register`)
  - `POST /auth/**` — authentication flows (e.g., `POST /auth/verify-email`)
- All public endpoints are currently open (`permitAll`); authorization rules
  are defined in `SecurityConfig`.
- Request bodies are validated with Bean Validation (`jakarta.validation`)
  at the controller boundary before reaching use-case logic.
- Responses use `ApiSuccessResponse<T>` for success and `ApiErrorResponse`
  for errors; both are defined in `dto/`. `GlobalExceptionHandler` maps
  domain exceptions to the appropriate HTTP status codes.
- There is no OpenAPI/Swagger schema yet; add one when a feature requires it,
  keeping it in sync with the implementation.

### Persistence and data access

- Spring Data JPA with Hibernate and PostgreSQL (`org.postgresql`).
  Do not introduce an alternative data access tool without justification.
- Migrations live in `src/main/resources/db/migration/` and follow Flyway's
  versioned naming (`V<n>__<description>.sql`). Migrations are not reversible
  by default; add undo scripts only if the plan requires it.
- JPA entities live in `domain/entity/`; only domain models are exposed to
  callers outside the data layer.
- `spring.jpa.hibernate.ddl-auto=validate` — Hibernate validates the schema
  against migrations; it never auto-creates or drops tables.

### Concurrency, transactions, and errors

- Standard synchronous Spring MVC (thread-per-request). Do not use reactive
  (`WebFlux`) or async constructs unless explicitly approved.
- Wrap multi-step writes in `@Transactional` where partial failure would leave
  inconsistent state (see `RegisterUseCase.execute`).
- `OutboxPublisher` uses `@Scheduled(fixedDelay = 5000)` + `@Transactional`
  to relay outbox events; keep blocking work out of reactive threads.
- Handle expected failures explicitly with domain exceptions (e.g.,
  `EmailAlreadyExistsException`, `InvalidOtpException`,
  `TooManyOtpAttemptsException`) and let `GlobalExceptionHandler` map them.
  Do not swallow exceptions or return fabricated success results.

### Security

- Spring Security with stateless session management (`STATELESS`). CSRF is
  disabled for the REST API.
- Password hashing: BCrypt via `BCryptPasswordEncoder`.
- No JWT or OAuth2 is implemented yet; add it only when a feature requires it
  and document the mechanism in the relevant SPEC.
- Validate and sanitize all external input via Bean Validation; never trust
  client-provided identifiers for authorization decisions.
- Avoid logging sensitive data (passwords, OTP codes, tokens, PII).

### Observability

- SLF4J with Logback (Spring Boot default). Use `LoggerFactory.getLogger` with
  the class as the logger name.
- Spring Boot Actuator is on the classpath; endpoints are available at
  `/actuator/**` (configuration in `application.properties`).
- Log enough context to diagnose failures (event IDs, aggregate IDs, error
  messages) without leaking sensitive data.

### Messaging (outbox pattern)

- RabbitMQ via Spring AMQP (`spring-boot-starter-amqp`).
- Exchange: `user.events` (direct). Current queues and routing keys:
  - `user.registered` → queue `user.registered`
- Events are not published directly from use cases. Instead, use cases write
  to the `outbox` table, and `OutboxPublisher` relays them every 5 seconds.
- Keep integration-specific DTOs (event payloads) out of the domain layer;
  serialize at the boundary (see `RegisterUseCase.buildPayload`).
- Follow this same pattern for any new domain event.

## Completion report

Summarize the implemented behavior, the affected feature documents, and the
validation results. Link acceptance criteria to evidence in `TASKS.md`.
Clearly identify anything incomplete or unverified; do not present a test name,
an unexecuted command, or "should work" as proof of success.
