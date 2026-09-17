# PLAN: Actualización de perfil de usuario

**SPEC de referencia:** `docs/features/user-profile-update/SPEC.md`
**Versión de la spec revisada:** Aprobada el 2026-09-16
**Contrato de API afectado:** Nuevos endpoints (ver §Contrato de API)
**Estado:** Aprobado <!-- Borrador | En revisión | Aprobado -->

<!-- PARA LA PERSONA
Copia esta plantilla como PLAN.md junto a la SPEC.md aprobada.
Este documento define la solución técnica. Una vez revisado, el agente puede
derivar TASKS.md con tareas, dependencias y comprobaciones.
-->

<!-- PARA EL AGENTE
- Lee la SPEC.md aprobada y las instrucciones del proyecto.
  Si falta un documento necesario o la spec no está aprobada, indícalo antes de avanzar.
- Inspecciona el repositorio. Referencia rutas verificadas y distingue las nuevas propuestas.
- Propón una solución proporcional al alcance y coherente con el proyecto.
  Reutiliza lo existente y justifica nuevas dependencias o cambios de arquitectura.
- Distingue hechos, decisiones confirmadas y propuestas. Consulta las decisiones
  no resueltas; haz pocas preguntas por vez y actualiza el plan con las respuestas.
- Referencia los requisitos y criterios por su ID, sin copiar toda la spec.
- Si una decisión cambia el comportamiento o alcance, vuelve a la spec y solicita
  confirmación. No resuelvas una duda de producto mediante una suposición técnica.
- Conserva estos comentarios. No implementes durante la planificación.
- Solicita aprobación antes de marcar el plan como Aprobado. La autorización
  para implementar debe ser explícita; no se deduce del estado de los documentos.
-->

## Contexto técnico verificado

| Componente o archivo existente | Ruta verificada | Responsabilidad y uso previsto |
| --- | --- | --- |
| `JwtTokenProvider` | `config/JwtTokenProvider.java` | Genera y valida tokens RS256. `generateAccessToken(UUID userId)` emite JWT con claims `sub` y `exp`. **Se modifica** para añadir claim `sid` (session ID). |
| `JwtAuthenticationFilter` | `config/JwtAuthenticationFilter.java` | Extrae y valida el Bearer token; puebla el `SecurityContext` con `userId`. **Se modifica** para extraer `sid` y verificar que la sesión no esté revocada. |
| `SecurityConfig` | `config/SecurityConfig.java` | Declara `permitAll` para endpoints públicos. **Se modifica** para añadir los nuevos endpoints públicos. |
| `GlobalExceptionHandler` | `config/GlobalExceptionHandler.java` | Mapea excepciones de dominio a códigos HTTP. **Se modifica** para añadir los manejadores de las nuevas excepciones. |
| `LoginRateLimitFilter` | `config/LoginRateLimitFilter.java` | Rate limiting en memoria para `POST /auth/login`. No se modifica. |
| `Session` | `domain/entity/Session.java` | JPA entity para la tabla `session`. No se modifica. |
| `User` | `domain/entity/User.java` | JPA entity para la tabla `users`. **Se modifica** para añadir `photoUrl`. |
| `OtpPurpose` | `domain/entity/OtpPurpose.java` | Enum con `EMAIL_VERIFICATION`, `PASSWORD_RESET`, `LOGIN_2FA`. Ya contiene `PASSWORD_RESET`; no necesita cambios. |
| `SessionRepository` | `repository/SessionRepository.java` | `findByRefreshTokenHash`. **Se añaden** métodos: `findAllByUserIdAndRevokedFalse` y `findById` (ya en `JpaRepository`). |
| `UserRepository` | `repository/UserRepository.java` | `existsByEmail`, `findByEmail`. No necesita cambios. |
| `OtpRepository` | `repository/OtpRepository.java` | `findTopByUserIdAndPurposeOrderByCreatedAtDesc`. No necesita cambios. |
| `UserMapper` | `mapper/UserMapper.java` | Convierte `User` → `UserResponseData`. **Se modifica** para incluir `photoUrl`. |
| `UserResponseData` | `dto/UserResponseData.java` | DTO de respuesta de usuario. **Se modifica** para añadir `photo_url` y `updated_at`. |
| `PasswordValidator` / `FullNameValidator` | `utils/validation/` | Reutilizados tal cual en los nuevos commands. |
| `FriendCodeGenerator` | `utils/FriendCodeGenerator.java` | Reutilizado en `RegenerateFriendCodeUseCase`. |
| `TokenHasher` | `utils/TokenHasher.java` | SHA-256 de tokens opacos. No se modifica. |
| `OtpCodeGenerator` | `utils/OtpCodeGenerator.java` | Generación de OTPs. Reutilizado. |
| `VerifyLoginOtpUseCase` | `service/VerifyLoginOtpUseCase.java` | **Se modifica** para pasar `sessionId` a `generateAccessToken`. |
| `RefreshTokenUseCase` | `service/RefreshTokenUseCase.java` | **Se modifica** para pasar `sessionId` a `generateAccessToken`. |
| `AuthController` | `controller/AuthController.java` | **Se modifica**: añade endpoints de password reset y resend-verification. |
| `UserController` | `controller/UserController.java` | No se modifica (`POST /user/` queda igual). |
| Migraciones Flyway | `src/main/resources/db/migration/` | V1–V4 existentes. Se añade `V5__add_photo_url_to_users.sql`. |

**Convenciones y patrón de referencia:** `RegisterUseCase` y `VerifyEmailUseCase` — flujo completo con transacción, excepción de dominio, mapeo en controller y outbox para eventos de notificación. Se sigue el mismo esquema en todos los use cases nuevos.

## Solución propuesta

### Invalidación de access tokens al revocar sesión

El problema central es: el JWT es stateless (RS256, TTL 1 h), por lo que revocar la sesión en BD no invalida el token ya emitido.

**Mecanismo elegido: claim `sid` (session ID) en el JWT + validación en el filtro.**

- `generateAccessToken` recibe también el `sessionId` (UUID de la fila en `session`).
- El JWT incluye el claim `sid: <sessionId>`.
- `JwtAuthenticationFilter` extrae `sid`, consulta `SessionRepository.findById(sessionId)` y rechaza con `401` si la sesión está revocada (`revoked = true`) o no existe.
- Impacto: una consulta a BD por cada request autenticado. Es aceptable dado el stack sincrónico actual y el TTL corto del token (1 h). Si en el futuro el volumen lo requiere, se puede interponer una caché por `sessionId`.
- `VerifyLoginOtpUseCase.issueTokens` y `RefreshTokenUseCase.execute` se actualizan para pasar `session.getId()` a `generateAccessToken`.

### Arquitectura de los nuevos use cases

Se crean seis use cases nuevos y un nuevo controller. Cada use case es `@Service` con constructor injection, `@Transactional` donde hay escrituras múltiples, y delega la publicación de notificaciones al outbox.

| Use case (nuevo) | Endpoints que lo invocan | RF relacionados |
| --- | --- | --- |
| `ChangePasswordUseCase` | `PATCH /auth/user/me/password` | RF-01 |
| `ResetPasswordRequestUseCase` | `POST /auth/password-reset/request` | RF-01b (paso 1) |
| `ResetPasswordConfirmUseCase` | `POST /auth/password-reset/confirm` | RF-01b (paso 4) |
| `UpdateProfileUseCase` | `PATCH /auth/user/me` | RF-02, RF-03, RF-06 |
| `RegenerateFriendCodeUseCase` | `POST /auth/user/me/friend-code` | RF-04 |
| `ResendVerificationUseCase` | `POST /auth/resend-verification` | RF-05 |

### Identificación de la sesión actual en RF-01

Para revocar todas las sesiones **excepto la actual**, `ChangePasswordUseCase` necesita el `sessionId` de la petición entrante. Este dato se extrae del claim `sid` del JWT en el controller y se pasa al use case como parte del command.

### Flujo de UpdateProfileUseCase (RF-02 / RF-03 / RF-06)

El body de `PATCH /auth/user/me` acepta campos opcionales (`full_name`, `email`, `photo_url`). Al menos uno debe estar presente. El use case aplica en una sola transacción los campos que lleguen:

- Si llega `email` diferente al actual: verifica unicidad → actualiza email → pone `verified_at = null` → invalida OTPs `EMAIL_VERIFICATION` pendientes → genera OTP nuevo → publica al outbox.
- Si llega `full_name` o `photo_url`: actualiza directamente.
- `updated_at` se actualiza siempre.

## Contrato de API

| Método | Ruta | Autenticación | Códigos esperados | RF |
| --- | --- | --- | --- | --- |
| `PATCH` | `/auth/user/me` | JWT Bearer | 200, 400, 409, 401, 500 | RF-02, RF-03, RF-06 |
| `PATCH` | `/auth/user/me/password` | JWT Bearer | 200, 400, 401, 500 | RF-01 |
| `POST` | `/auth/user/me/friend-code` | JWT Bearer | 200, 401, 500 | RF-04 |
| `POST` | `/auth/password-reset/request` | Público | 200, 500 | RF-01b |
| `POST` | `/auth/password-reset/confirm` | Público | 200, 400, 429, 500 | RF-01b |
| `POST` | `/auth/resend-verification` | Público | 200, 500 | RF-05 |

**Esquemas de request y response relevantes:**

`PATCH /auth/user/me` — request (al menos un campo):
```json
{ "full_name": "...", "email": "...", "photo_url": "https://..." }
```

`PATCH /auth/user/me` — response `200`:
```json
{ "message": "...", "data": { "id", "full_name", "email", "friend_code",
  "photo_url", "verified", "created_at", "updated_at" } }
```

`PATCH /auth/user/me/password` — request:
```json
{ "current_password": "...", "new_password": "...", "confirm_new_password": "..." }
```

`POST /auth/user/me/friend-code` — response `200`:
```json
{ "message": "...", "data": { "friend_code": "XXXXXXXXXX" } }
```

`POST /auth/password-reset/request` — request:
```json
{ "email": "..." }
```

`POST /auth/password-reset/confirm` — request:
```json
{ "email": "...", "otp_code": "...", "new_password": "...", "confirm_new_password": "..." }
```

`POST /auth/resend-verification` — request:
```json
{ "email": "..." }
```

**Compatibilidad hacia atrás:** todos los endpoints existentes son `PATCH/POST` en rutas nuevas. No hay cambio de contrato en endpoints ya publicados. La modificación de `JwtTokenProvider` añade el claim `sid` al JWT: los consumidores actuales del token (solo el propio `JwtAuthenticationFilter` dentro del servicio) se actualizan en la misma entrega; no hay consumidores externos conocidos del JWT.

## Módulos y componentes afectados

| Componente o ruta | Capa | Acción | Cambio y responsabilidad | RF |
| --- | --- | --- | --- | --- |
| `config/JwtTokenProvider.java` | Infraestructura | Modificar | Añadir parámetro `sessionId` y claim `sid` en `generateAccessToken`; añadir `getSessionIdFromToken` | RF-01, RF-01b |
| `config/JwtAuthenticationFilter.java` | Infraestructura | Modificar | Extraer `sid`, consultar `SessionRepository.findById`, rechazar si revocada | CA-09c, CA-17 |
| `config/SecurityConfig.java` | Infraestructura | Modificar | Añadir `permitAll` para `/auth/password-reset/**` y `/auth/resend-verification` | RF-01b, RF-05 |
| `config/GlobalExceptionHandler.java` | Infraestructura | Modificar | Añadir handlers para nuevas excepciones de dominio | Todos |
| `domain/entity/User.java` | Dominio | Modificar | Añadir campo `photoUrl` (`String`, nullable) | RF-06 |
| `repository/SessionRepository.java` | Repositorio | Modificar | Añadir `findAllByUserIdAndRevokedFalse(UUID userId)` | RF-01, RF-01b |
| `dto/UserResponseData.java` | DTO | Modificar | Añadir `photo_url` y `updated_at` | RF-06, RF-02 |
| `mapper/UserMapper.java` | Mapper | Modificar | Incluir `photoUrl` y `updatedAt` en el mapping | RF-02, RF-06 |
| `service/VerifyLoginOtpUseCase.java` | Servicio | Modificar | Pasar `session.getId()` a `generateAccessToken` | Infraestructura |
| `service/RefreshTokenUseCase.java` | Servicio | Modificar | Pasar `newSession.getId()` a `generateAccessToken` | Infraestructura |
| `controller/AuthController.java` | Controller | Modificar | Añadir `POST /auth/password-reset/request`, `/confirm`, `/auth/resend-verification` | RF-01b, RF-05 |
| `controller/UserProfileController.java` [NEW] | Controller | Crear | `PATCH /auth/user/me`, `PATCH /auth/user/me/password`, `POST /auth/user/me/friend-code` | RF-01, RF-02, RF-03, RF-04, RF-06 |
| `service/ChangePasswordUseCase.java` [NEW] | Servicio | Crear | Verifica contraseña actual, valida nueva, actualiza hash, revoca sesiones excepto actual | RF-01 |
| `service/ResetPasswordRequestUseCase.java` [NEW] | Servicio | Crear | Localiza usuario, invalida OTP previo, genera OTP `PASSWORD_RESET`, publica outbox | RF-01b |
| `service/ResetPasswordConfirmUseCase.java` [NEW] | Servicio | Crear | Verifica OTP, actualiza hash, revoca todas las sesiones | RF-01b |
| `service/UpdateProfileUseCase.java` [NEW] | Servicio | Crear | Actualiza `full_name`, `email` (con re-verificación), `photo_url` en transacción única | RF-02, RF-03, RF-06 |
| `service/RegenerateFriendCodeUseCase.java` [NEW] | Servicio | Crear | Genera código único, actualiza `friend_code` | RF-04 |
| `service/ResendVerificationUseCase.java` [NEW] | Servicio | Crear | Reenvía OTP `EMAIL_VERIFICATION` a usuario no verificado | RF-05 |
| `dto/ChangePasswordCommand.java` [NEW] | DTO | Crear | `current_password`, `new_password`, `confirm_new_password` con validaciones | RF-01 |
| `dto/UpdateProfileCommand.java` [NEW] | DTO | Crear | `full_name?`, `email?`, `photo_url?` — al menos uno requerido | RF-02, RF-03, RF-06 |
| `dto/PasswordResetRequestCommand.java` [NEW] | DTO | Crear | `email` | RF-01b |
| `dto/PasswordResetConfirmCommand.java` [NEW] | DTO | Crear | `email`, `otp_code`, `new_password`, `confirm_new_password` | RF-01b |
| `dto/ResendVerificationCommand.java` [NEW] | DTO | Crear | `email` | RF-05 |
| `dto/FriendCodeResponseData.java` [NEW] | DTO | Crear | `friend_code` | RF-04 |
| `utils/validation/ValidPhotoUrl.java` [NEW] | Validación | Crear | Anotación `@ValidPhotoUrl` | RF-06 |
| `utils/validation/PhotoUrlValidator.java` [NEW] | Validación | Crear | Verifica esquema `https`, longitud ≤ 2048, acepta `null` | RF-06 |
| `utils/exceptions/WrongCurrentPasswordException.java` [NEW] | Excepción | Crear | Contraseña actual incorrecta en RF-01; mapeada a 401 | RF-01 |
| `utils/exceptions/SamePasswordException.java` [NEW] | Excepción | Crear | Nueva contraseña igual a la actual; mapeada a 400 | RF-01 |
| `utils/exceptions/EmailConflictException.java` [NEW] | Excepción | Crear | Email ya registrado en RF-03; mapeada a 409 | RF-03 |
| `db/migration/V5__add_photo_url_to_users.sql` [NEW] | BD | Crear | `ALTER TABLE users ADD COLUMN photo_url TEXT;` | RF-06 |

## Datos y contratos

- **Modelos de dominio:** `User` incorpora `photoUrl` (String, nullable). El JWT incorpora el claim `sid` (String, UUID del session). El resto de entidades (`Session`, `Otp`, `Outbox`) no cambia.

- **Identificadores y restricciones:** `photo_url` no es UNIQUE; puede ser null. `friend_code` sigue siendo UNIQUE. El `sid` del JWT referencia `session.id` (UUID PK de la tabla `session`).

- **Transformación de datos:**
  - Request → `@Valid` Command DTO → use case → `User`/`Session` entity → repositorio.
  - Response: `User` entity → `UserMapper.toResponseData` → `UserResponseData` → `ApiSuccessResponse`.
  - Para `friend_code`: se devuelve solo en `FriendCodeResponseData`.

- **Persistencia:**
  - Todos los cambios al perfil actualizan `users.updated_at`.
  - Revocación de sesiones: `UPDATE session SET revoked = true, revoked_at = now(), updated_at = now() WHERE user_id = ? AND revoked = false [AND id != currentSessionId]`. Se implementa con `findAllByUserIdAndRevokedFalse` + loop de revocación, igual que `LogoutUseCase`.
  - El OTP anterior del mismo propósito se invalida antes de crear el nuevo (evita OTPs huérfanos activos).

- **Eventos de dominio (outbox):**
  - RF-01b: evento `USER_PASSWORD_RESET_OTP` con payload `{id, email, otp_code}`.
  - RF-03: evento `USER_EMAIL_VERIFICATION` (mismo tipo que el registro) con payload `{id, email, otp_code}`.
  - RF-05: evento `USER_EMAIL_VERIFICATION` con payload `{id, email, otp_code}`.

- **Compatibilidad y migraciones:** `V5__add_photo_url_to_users.sql` añade la columna como nullable sin valor por defecto. Los usuarios existentes tendrán `photo_url = NULL`, lo que es el estado correcto ("sin foto"). La migración es irreversible; no se añade script de rollback porque Hibernate valida contra el esquema (`ddl-auto=validate`).

## Seguridad y validación de entrada

- **Validación de inputs:**
  - `ChangePasswordCommand`: `@NotBlank` en los tres campos; `@ValidPassword` en `new_password`; validación cross-field `@PasswordsMatch` adaptada para `new_password`/`confirm_new_password`; la igualdad con la contraseña actual se verifica en el use case (requiere el hash de BD).
  - `UpdateProfileCommand`: validación condicional — si `full_name` presente, aplicar `@ValidFullName`; si `email` presente, aplicar `@Email` y `@Size(max=254)`; si `photo_url` presente y no nulo, aplicar `@ValidPhotoUrl`; si ningún campo viene, lanzar excepción 400 en el use case.
  - `PasswordResetConfirmCommand`: `@NotBlank` en todos; `@ValidPassword` en `new_password`; `@PasswordsMatch` en `new_password`/`confirm_new_password`.
  - `@ValidPhotoUrl` acepta `null` (foto borrada) y valida esquema `https` + longitud ≤ 2048 cuando no nulo.

- **Autenticación y autorización:**
  - Endpoints protegidos: el `JwtAuthenticationFilter` actualizado valida firma, expiración **y** que la sesión referenciada por `sid` no esté revocada.
  - El `user_id` en los use cases protegidos se extrae del `SecurityContextHolder`; no se acepta del cliente.
  - Endpoints públicos (`/auth/password-reset/**`, `/auth/resend-verification`): añadidos a `permitAll`.

- **Datos sensibles:**
  - `current_password`, `new_password`, `otp_code` nunca se loguean.
  - El mensaje de error cuando la contraseña actual es incorrecta (CA-02) es genérico, igual que `InvalidCredentialsException` en el login.
  - Los endpoints de password reset y resend-verification siempre responden `200 OK` independientemente de si el email existe (prevención de enumeración).

- **Rate limiting:** el `LoginRateLimitFilter` existente solo cubre `POST /auth/login`. Los endpoints de password reset y resend-verification no tienen rate limiting en esta entrega; se documenta como deuda técnica. Si en el futuro se requiere, el patrón está establecido en `LoginRateLimitFilter`.

## Rendimiento y escalabilidad

- **Consulta adicional por request autenticado:** `JwtAuthenticationFilter` añade `SELECT` por `session.id` en cada petición. El índice primario UUID es O(log n). Con TTL de 1 h y sesiones de 7 días, la tabla `session` tendrá un tamaño manejable; la consulta no implica N+1.

- **Revocación de múltiples sesiones:** `findAllByUserIdAndRevokedFalse` carga todas las sesiones activas del usuario (normalmente 1–5) y las revoca en loop. El índice `idx_session_user_id` existente cubre esta consulta.

- **Paginación:** No aplica (no hay listados).

- **Caché:** No aplica en esta entrega. La futura optimización de cachear el estado de sesión por `sessionId` se añadiría en `JwtAuthenticationFilter` sin cambiar el contrato.

## Estado, operaciones y errores

- **Transaccionalidad:**
  - `ChangePasswordUseCase`: transacción única que actualiza `password_hash`, `updated_at` y revoca sesiones.
  - `ResetPasswordConfirmUseCase`: transacción única que verifica OTP, actualiza hash y revoca sesiones.
  - `UpdateProfileUseCase`: transacción única; si el email cambia, incluye OTP y outbox en la misma transacción (patrón de `RegisterUseCase`).
  - `ResetPasswordRequestUseCase`, `ResendVerificationUseCase`: transacción para invalidar OTP anterior y persistir el nuevo + outbox.

- **Idempotencia:**
  - `PATCH /auth/user/me` con el mismo valor: actualiza `updated_at`, devuelve 200. No es error.
  - `POST /auth/password-reset/request` y `POST /auth/resend-verification`: siempre invalidan el OTP anterior e  isomen uno nuevo; siempre 200. No son idempotentes pero son seguros de reintentar.
  - `POST /auth/user/me/friend-code`: siempre genera uno nuevo (no idempotente por diseño).

- **Concurrencia:**
  - Dos peticiones simultáneas de cambio de email con el mismo email nuevo: la constraint UNIQUE de `users.email` en BD actúa como árbitro; la segunda transacción recibe `DataIntegrityViolationException`, que el handler mapea a 409.
  - Dos cambios de contraseña simultáneos: ambos pueden pasar la validación de contraseña actual y escribir hashes distintos. La última escritura gana (last-write-wins). Riesgo aceptable dado que el usuario controla su propia cuenta.

- **Mapeo de errores a HTTP:**

| Excepción | Código HTTP |
| --- | --- |
| `WrongCurrentPasswordException` | 401 |
| `SamePasswordException` | 400 |
| `EmailConflictException` | 409 |
| `InvalidOtpException` (reutilizada) | 400 |
| `TooManyOtpAttemptsException` (reutilizada) | 429 |
| `MethodArgumentNotValidException` | 400 |
| `Exception` (catch-all) | 500 |

## Dependencias y configuración

- **Librerías nuevas:** ninguna. Todo se implementa con las dependencias ya presentes (Spring Boot, Spring Security, jjwt, Hibernate/JPA, Lombok, Bean Validation).

- **Variables de entorno:** ninguna nueva. Las claves JWT, credenciales de BD y configuración de RabbitMQ ya están en `.env`.

- **Migraciones de BD:**
  - `V5__add_photo_url_to_users.sql`: `ALTER TABLE users ADD COLUMN photo_url TEXT;`
  - Irreversible; no se añade script de undo.

- **Feature flags:** No aplica.

## Estrategia de validación

| Criterio | Método y test propuesto | Entorno y estado inicial | Evidencia prevista |
| --- | --- | --- | --- |
| CA-01 | Test de integración `ChangePasswordControllerTest` — cambio exitoso | Testcontainers (Postgres + Rabbit); usuario verificado con sesión activa | HTTP 200; `password_hash` distinto en BD |
| CA-02 | `ChangePasswordControllerTest` — contraseña actual incorrecta | Mismo | HTTP 401; mensaje genérico |
| CA-03 | `ChangePasswordControllerTest` — `new_password` sin símbolo | Mismo | HTTP 400; detalle de campo `new_password` |
| CA-04 | `ChangePasswordControllerTest` — `new_password ≠ confirm` | Mismo | HTTP 400 |
| CA-05 | `ChangePasswordControllerTest` — `new_password == current` | Mismo | HTTP 400; mensaje gracioso |
| CA-06 | `ChangePasswordControllerTest` — verificar sesiones en BD tras cambio | Mismo; usuario con 2 sesiones activas | Solo sesión actual `revoked = false` |
| CA-07 | `PasswordResetControllerTest` — email registrado | Testcontainers; usuario existente (verificado o no) | HTTP 200; OTP `PASSWORD_RESET` en BD |
| CA-08 | `PasswordResetControllerTest` — email no registrado | Testcontainers | HTTP 200; sin OTP en BD |
| CA-09 | `PasswordResetControllerTest` — confirm con OTP válido | OTP `PASSWORD_RESET` en BD | HTTP 200; hash actualizado; sesiones revocadas |
| CA-09b | `PasswordResetControllerTest` — usuario no verificado completa reset; intento de login | Usuario con `verified_at = null` | HTTP 401 en login hasta verificar email |
| CA-09c | `PasswordResetControllerTest` — access token previo al reset | Access token emitido antes del reset | HTTP 401 en endpoint protegido |
| CA-10 | `PasswordResetControllerTest` — OTP incorrecto / agotado | OTP en BD | HTTP 400 / HTTP 429 |
| CA-11 | `UpdateProfileControllerTest` — `full_name` válido | Testcontainers; usuario verificado | HTTP 200; BD actualizada |
| CA-12 | `UpdateProfileControllerTest` — `full_name` inválido | Mismo | HTTP 400 |
| CA-13 | `UpdateProfileControllerTest` — email nuevo válido | Testcontainers | HTTP 200; `verified_at = null`; OTP en BD |
| CA-14 | `UpdateProfileControllerTest` — login tras cambio de email sin verificar | Mismo | HTTP 401 en login |
| CA-15 | `UpdateProfileControllerTest` — email ya existente | Testcontainers con dos usuarios | HTTP 409 |
| CA-16 | `FriendCodeControllerTest` — regeneración | Testcontainers | HTTP 200; código distinto |
| CA-17 | Tests de endpoints protegidos sin token | Reutilizar patrón de `JwtProtectedEndpointTest` | HTTP 401 |
| CA-18 | `ResendVerificationControllerTest` — email con `verified_at = null` | Testcontainers | HTTP 200; nuevo OTP en BD; anterior invalidado |
| CA-19 | `ResendVerificationControllerTest` — email ya verificado | Testcontainers | HTTP 200; sin cambios en BD |
| CA-20 | `UpdateProfileControllerTest` — `photo_url` https válida | Testcontainers | HTTP 200; BD actualizada |
| CA-21 | `UpdateProfileControllerTest` — `photo_url: null` | Testcontainers | HTTP 200; BD con null |
| CA-22 | `UpdateProfileControllerTest` — `photo_url` con esquema `http` | Testcontainers | HTTP 400 |

**Comprobaciones de regresión:** ejecutar los tests existentes (`./mvnw test`) antes y después de la entrega. Los tests que tocan `JwtTokenProvider` y `JwtAuthenticationFilter` (`JwtTokenProviderTest`, `JwtAuthenticationFilterTest`, `JwtProtectedEndpointTest`) deben actualizarse para incluir el claim `sid`.

**Comandos verificados:**
```bash
./mvnw clean package          # build completo con tests
./mvnw test -Dtest=ChangePasswordControllerTest
./mvnw test -Dtest=PasswordResetControllerTest
./mvnw test -Dtest=UpdateProfileControllerTest
```

**Limitaciones del entorno:** los tests de outbox verifican la escritura en la tabla `outbox` pero no la recepción del evento en RabbitMQ (el `OutboxPublisher` se ejecuta de forma asíncrona). La entrega de la notificación por email queda fuera del alcance de los tests automatizados de este servicio.

## Orden de implementación

1. **Migración BD** (`V5__add_photo_url_to_users.sql`) — prerequisito para que `User` y los tests levanten.
2. **Entidad `User`** — añadir `photoUrl`; verificar que `./mvnw clean package -DskipTests` compila.
3. **`JwtTokenProvider`** — añadir `sid` a `generateAccessToken` y `getSessionIdFromToken`.
4. **`JwtAuthenticationFilter`** — validar `sid` contra `SessionRepository`. Actualizar `JwtTokenProviderTest`, `JwtAuthenticationFilterTest`, `JwtProtectedEndpointTest`.
5. **`VerifyLoginOtpUseCase` y `RefreshTokenUseCase`** — pasar `sessionId` a `generateAccessToken`. Ejecutar tests existentes para confirmar regresiones.
6. **`SessionRepository`** — añadir `findAllByUserIdAndRevokedFalse`.
7. **`UserResponseData` y `UserMapper`** — añadir `photo_url` y `updated_at`.
8. **Nuevas excepciones** (`WrongCurrentPasswordException`, `SamePasswordException`, `EmailConflictException`) y handlers en `GlobalExceptionHandler`.
9. **Nuevos validators** (`ValidPhotoUrl`, `PhotoUrlValidator`).
10. **DTOs de request** (`ChangePasswordCommand`, `UpdateProfileCommand`, `PasswordResetRequestCommand`, `PasswordResetConfirmCommand`, `ResendVerificationCommand`, `FriendCodeResponseData`).
11. **Use cases** en orden de dependencias ascendentes: `ChangePasswordUseCase` → `ResetPasswordRequestUseCase` → `ResetPasswordConfirmUseCase` → `UpdateProfileUseCase` → `RegenerateFriendCodeUseCase` → `ResendVerificationUseCase`.
12. **`SecurityConfig`** — añadir `permitAll` para los nuevos endpoints públicos.
13. **Controllers** — `UserProfileController` (nuevo) y extensión de `AuthController`.
14. **Tests de integración** para todos los criterios de aceptación.
15. **Build final**: `./mvnw clean package`; confirmar que todos los tests pasan.

## Riesgos y decisiones pendientes

- **Riesgos y medidas acordadas:**
  - *Breaking change en JWT*: el claim `sid` es un añadido; un token emitido antes de desplegar esta versión no tendrá `sid`. El `JwtAuthenticationFilter` debe tolerar tokens sin `sid` durante un período de transición (p.ej. tratar `sid` ausente como sesión desconocida y rechazar con 401 para forzar re-login), o bien aceptarlos temporalmente. **Se elige rechazar** tokens sin `sid` inmediatamente al desplegar (se invalidan todas las sesiones activas en el despliegue, lo que obliga a re-login). Esto es aceptable dado que el TTL es 1 h.
  - *Migración `V5` irreversible*: la columna `photo_url TEXT NULL` es segura de añadir; no afecta registros existentes.
  - *Rate limiting ausente en password reset y resend-verification*: enumeración y abuso posibles. Documentado como deuda técnica. Mitigación parcial: respuestas siempre `200 OK` sin revelar si el email existe.

- **Decisiones pendientes:** Ninguna.

<!-- ANTES DE SOLICITAR APROBACIÓN
Comprueba que el plan cubre los requisitos, respeta las exclusiones, reutiliza
componentes verificados y permite demostrar todos los criterios de aceptación.
Resuelve dudas y marcadores pendientes. Si la spec cambió, revisa su impacto.
Tras aprobar el plan, deriva TASKS.md con IDs, dependencias, referencias a RF/CA
y comprobaciones. No marques una tarea terminada sin realizar su validación;
si está bloqueada, registra el motivo.
-->
