# TASKS: Actualización de perfil de usuario

**PLAN de referencia:** `docs/features/user-profile-update/PLAN.md`
**SPEC de referencia:** `docs/features/user-profile-update/SPEC.md`
**Estado:** Completado

---

## Etapa 1 — Migración de base de datos

### T-01 · Migración V5: añadir `photo_url` a `users`
- **Objetivo:** crear `V5__add_photo_url_to_users.sql` con `ALTER TABLE users ADD COLUMN photo_url TEXT;`
- **Alcance:** un único archivo SQL en `src/main/resources/db/migration/`
- **Dependencias:** ninguna
- **Criterios resueltos:** prerequisito de RF-06
- **Validación:** `./mvnw clean package -DskipTests` compila sin error; Hibernate no lanza `SchemaValidationException` al arrancar

- [x] Crear `src/main/resources/db/migration/V5__add_photo_url_to_users.sql`
- [x] Verificar con `./mvnw clean package -DskipTests`

---

## Etapa 2 — Entidad de dominio

### T-02 · Añadir campo `photoUrl` a `User`
- **Objetivo:** añadir `@Column(name = "photo_url") private String photoUrl;` en `domain/entity/User.java`
- **Alcance:** solo la entidad JPA; sin cambios en repositorio ni mapper aún
- **Dependencias:** T-01
- **Criterios resueltos:** RF-06
- **Validación:** proyecto compila; `User.getPhotoUrl()` y `User.setPhotoUrl()` disponibles

- [x] Añadir campo `photoUrl` en `User.java`
- [x] Verificar compilación

---

## Etapa 3 — JWT: claim `sid`

### T-03 · Modificar `JwtTokenProvider` para incluir claim `sid`
- **Objetivo:** `generateAccessToken(UUID userId, UUID sessionId)` emite JWT con claim `sid`; añadir `getSessionIdFromToken(String token)`
- **Alcance:** `config/JwtTokenProvider.java` y `JwtTokenProviderTest.java`
- **Dependencias:** ninguna
- **Criterios resueltos:** prerequisito de CA-09c, CA-17
- **Validación:** `JwtTokenProviderTest` actualizado pasa; el claim `sid` está presente en el token generado

- [x] Modificar `generateAccessToken` para aceptar `sessionId` y emitir claim `sid`
- [x] Añadir método `getSessionIdFromToken(String token) → UUID`
- [x] Actualizar `JwtTokenProviderTest` para cubrir el nuevo claim
- [x] `./mvnw test -Dtest=JwtTokenProviderTest`

---

## Etapa 4 — Filtro: validar `sid` contra BD

### T-04 · Modificar `JwtAuthenticationFilter` para verificar sesión no revocada
- **Objetivo:** extraer `sid` del JWT y rechazar con 401 si la sesión no existe o está revocada
- **Alcance:** `config/JwtAuthenticationFilter.java`; actualizar `JwtAuthenticationFilterTest` y `JwtProtectedEndpointTest`
- **Dependencias:** T-03
- **Criterios resueltos:** CA-09c, CA-17
- **Validación:** tests de filtro actualizados pasan; un token con `sid` de sesión revocada recibe 401

- [x] Inyectar `SessionRepository` en `JwtAuthenticationFilter` (requiere convertirlo a bean Spring o ajustar instanciación en `SecurityConfig`)
- [x] Extraer `sid` del JWT; consultar `SessionRepository.findById(sid)` al validar
- [x] Rechazar con 401 si sesión no existe, revocada, o token sin claim `sid`
- [x] Actualizar `JwtAuthenticationFilterTest` con escenarios: token sin `sid`, sesión revocada, sesión activa
- [x] Actualizar `JwtProtectedEndpointTest`
- [x] `./mvnw test -Dtest=JwtAuthenticationFilterTest,JwtProtectedEndpointTest`

---

## Etapa 5 — Actualizar use cases existentes que emiten tokens

### T-05 · `VerifyLoginOtpUseCase`: pasar `sessionId` a `generateAccessToken`
- **Objetivo:** en `issueTokens`, pasar `session.getId()` al llamar `generateAccessToken`
- **Alcance:** `service/VerifyLoginOtpUseCase.java`; actualizar `AuthControllerLogin2faTest`
- **Dependencias:** T-03
- **Criterios resueltos:** base para CA-09c
- **Validación:** `AuthControllerLogin2faTest` pasa; el token devuelto contiene `sid`

- [x] Pasar `session.getId()` en la llamada a `jwtTokenProvider.generateAccessToken` dentro de `issueTokens`
- [x] Actualizar o verificar `AuthControllerLogin2faTest`
- [x] `./mvnw test -Dtest=AuthControllerLogin2faTest`

### T-06 · `RefreshTokenUseCase`: pasar `sessionId` a `generateAccessToken`
- **Objetivo:** pasar `newSession.getId()` al emitir el access token tras la rotación
- **Alcance:** `service/RefreshTokenUseCase.java`; actualizar `RefreshTokenControllerTest`
- **Dependencias:** T-03
- **Criterios resueltos:** base para CA-09c
- **Validación:** `RefreshTokenControllerTest` pasa

- [x] Pasar `newSession.getId()` en la llamada a `generateAccessToken`
- [x] Actualizar `RefreshTokenControllerTest`
- [x] `./mvnw test -Dtest=RefreshTokenControllerTest`

---

## Etapa 6 — Repositorio

### T-07 · Añadir `findAllByUserIdAndRevokedFalse` a `SessionRepository`
- **Objetivo:** método derivado de Spring Data para obtener todas las sesiones activas de un usuario
- **Alcance:** `repository/SessionRepository.java`
- **Dependencias:** ninguna
- **Criterios resueltos:** prerequisito de T-10, T-13
- **Validación:** compilación correcta; Spring Data genera la consulta sin errores

- [x] Añadir `List<Session> findAllByUserIdAndRevokedFalse(UUID userId);` en `SessionRepository`
- [x] Verificar compilación

---

## Etapa 7 — DTO de respuesta y mapper

### T-08 · Actualizar `UserResponseData` y `UserMapper`
- **Objetivo:** incluir `photo_url` y `updated_at` en la respuesta de usuario
- **Alcance:** `dto/UserResponseData.java` y `mapper/UserMapper.java`
- **Dependencias:** T-02
- **Criterios resueltos:** RF-06, RF-02 (campo `updated_at` visible)
- **Validación:** el mapper devuelve `photoUrl` y `updatedAt` correctamente; tests que usan `UserResponseData` siguen pasando

- [x] Añadir `@JsonProperty("photo_url") private String photoUrl;` en `UserResponseData`
- [x] Añadir `@JsonProperty("updated_at") private LocalDateTime updatedAt;` en `UserResponseData`
- [x] Actualizar `UserMapper.toResponseData` para incluir ambos campos
- [x] Verificar que `UserControllerTest` sigue pasando (`./mvnw test -Dtest=UserControllerTest`)

---

## Etapa 8 — Excepciones de dominio y manejadores

### T-09 · Crear nuevas excepciones y registrarlas en `GlobalExceptionHandler`
- **Objetivo:** excepciones tipadas para los nuevos casos de error; mapeadas a los códigos HTTP correctos
- **Alcance:** `utils/exceptions/` (3 clases nuevas) y `config/GlobalExceptionHandler.java`
- **Dependencias:** ninguna
- **Criterios resueltos:** CA-02, CA-05, CA-15
- **Validación:** compilación; los handlers están cubiertos por los tests de integración de las etapas posteriores

- [x] Crear `WrongCurrentPasswordException` (extends `RuntimeException`) → mapeada a 401 en handler
- [x] Crear `SamePasswordException` (extends `RuntimeException`) → mapeada a 400 en handler; mensaje: `"Tu nueva contraseña no puede ser la misma que ya utilizas"`
- [x] Crear `EmailConflictException` (extends `RuntimeException`) → mapeada a 409 en handler
- [x] Añadir los tres `@ExceptionHandler` en `GlobalExceptionHandler`
- [x] Verificar compilación

---

## Etapa 9 — Validadores de entrada

### T-10 · Crear `@ValidPhotoUrl` y `PhotoUrlValidator`
- **Objetivo:** anotación de validación Bean Validation que acepta `null` y valida esquema `https` + longitud ≤ 2048
- **Alcance:** `utils/validation/ValidPhotoUrl.java` y `utils/validation/PhotoUrlValidator.java`
- **Dependencias:** ninguna
- **Criterios resueltos:** CA-22
- **Validación:** test unitario del validator cubre: `null` → válido; `https://...` → válido; `http://...` → inválido; URL > 2048 chars → inválido

- [x] Crear anotación `@ValidPhotoUrl` (meta-anotada con `@Constraint`)
- [x] Crear `PhotoUrlValidator implements ConstraintValidator<ValidPhotoUrl, String>`; acepta null; valida `https` scheme; longitud ≤ 2048
- [x] Escribir test unitario de `PhotoUrlValidator`
- [x] `./mvnw test -Dtest=PhotoUrlValidatorTest`

---

## Etapa 10 — DTOs de request

### T-11 · Crear DTOs de request para los nuevos endpoints
- **Objetivo:** un DTO por endpoint, con validaciones Bean Validation
- **Alcance:** `dto/` (6 clases nuevas)
- **Dependencias:** T-09, T-10
- **Criterios resueltos:** validación de entrada para todos los RF
- **Validación:** compilación; las validaciones se ejercitan en los tests de integración

- [x] `ChangePasswordCommand`: campos `current_password` (`@NotBlank`), `new_password` (`@NotBlank`, `@ValidPassword`), `confirm_new_password` (`@NotBlank`); validación cross-field `@PasswordsMatch` adaptada a `new_password`/`confirm_new_password`
- [x] `UpdateProfileCommand`: campos opcionales `full_name` (`@ValidFullName` si presente), `email` (`@Email`, `@Size(max=254)` si presente), `photo_url` (`@ValidPhotoUrl` si presente); validación a nivel de clase que exija al menos un campo no nulo
- [x] `PasswordResetRequestCommand`: campo `email` (`@NotBlank`, `@Email`)
- [x] `PasswordResetConfirmCommand`: campos `email`, `otp_code`, `new_password` (`@ValidPassword`), `confirm_new_password`; `@PasswordsMatch` en `new_password`/`confirm_new_password`
- [x] `ResendVerificationCommand`: campo `email` (`@NotBlank`, `@Email`)
- [x] `FriendCodeResponseData`: campo `friend_code`
- [x] Verificar compilación

---

## Etapa 11 — Use cases

### T-12 · `ChangePasswordUseCase` (RF-01)
- **Objetivo:** cambio de contraseña autenticado; revoca sesiones excepto la actual
- **Alcance:** `service/ChangePasswordUseCase.java`
- **Dependencias:** T-07, T-08, T-09
- **Criterios resueltos:** CA-01 – CA-06
- **Validación:** test de integración `ChangePasswordControllerTest` (Etapa 14)

- [x] Recibir `currentSessionId` (UUID) además del command
- [x] Cargar usuario por `userId`; verificar `current_password` con BCrypt → lanzar `WrongCurrentPasswordException` si falla
- [x] Comparar `new_password` con hash actual → lanzar `SamePasswordException` si coinciden
- [x] Actualizar `password_hash` y `updated_at` en transacción
- [x] Llamar `sessionRepository.findAllByUserIdAndRevokedFalse(userId)`; revocar todas excepto `currentSessionId`
- [x] Compilar

### T-13 · `ResetPasswordRequestUseCase` (RF-01b paso 1)
- **Objetivo:** genera OTP `PASSWORD_RESET` para cualquier usuario registrado; siempre responde 200
- **Alcance:** `service/ResetPasswordRequestUseCase.java`
- **Dependencias:** T-09
- **Criterios resueltos:** CA-07, CA-08
- **Validación:** test `PasswordResetControllerTest`

- [x] Buscar usuario por email; si no existe, retornar sin error (no revelar existencia)
- [x] Si existe, invalidar OTP `PASSWORD_RESET` pendiente anterior (poner `EXPIRED`)
- [x] Generar nuevo OTP con TTL 15 min, max 5 intentos, propósito `PASSWORD_RESET`
- [x] Publicar evento `USER_PASSWORD_RESET_OTP` al outbox con payload `{id, email, otp_code}`
- [x] Todo en `@Transactional`; compilar

### T-14 · `ResetPasswordConfirmUseCase` (RF-01b paso 4)
- **Objetivo:** verifica OTP y actualiza contraseña; revoca **todas** las sesiones
- **Alcance:** `service/ResetPasswordConfirmUseCase.java`
- **Dependencias:** T-07, T-09
- **Criterios resueltos:** CA-09, CA-09b, CA-09c, CA-10
- **Validación:** test `PasswordResetControllerTest`

- [x] Buscar usuario por email; lanzar `InvalidOtpException` si no existe (mensaje genérico)
- [x] Cargar OTP más reciente de propósito `PASSWORD_RESET`; aplicar las mismas reglas de intentos y expiración que `VerifyEmailUseCase`
- [x] Actualizar `password_hash` y `updated_at`
- [x] Revocar **todas** las sesiones activas del usuario
- [x] Todo en `@Transactional(noRollbackFor = {...})` igual que los use cases OTP existentes; compilar

### T-15 · `UpdateProfileUseCase` (RF-02, RF-03, RF-06)
- **Objetivo:** actualiza `full_name`, `email` y/o `photo_url` en una transacción; si cambia email, gestiona re-verificación
- **Alcance:** `service/UpdateProfileUseCase.java`
- **Dependencias:** T-07, T-09
- **Criterios resueltos:** CA-11 – CA-15, CA-20 – CA-22
- **Validación:** test `UpdateProfileControllerTest`

- [x] Cargar usuario por `userId`
- [x] Si llega `full_name`: actualizar campo
- [x] Si llega `photo_url` (incluido null): actualizar campo
- [x] Si llega `email` distinto al actual:
  - verificar unicidad → lanzar `EmailConflictException` si ya existe en otro usuario
  - actualizar `email`, poner `verified_at = null`
  - invalidar OTP `EMAIL_VERIFICATION` pendiente anterior
  - generar nuevo OTP `EMAIL_VERIFICATION` con TTL 15 min
  - publicar evento `USER_EMAIL_VERIFICATION` al outbox
- [x] Si ningún campo llegó: lanzar excepción 400
- [x] Actualizar `updated_at`; todo en `@Transactional`; devolver `UserResponseData` actualizado
- [x] Compilar

### T-16 · `RegenerateFriendCodeUseCase` (RF-04)
- **Objetivo:** genera un nuevo `friend_code` único y lo persiste
- **Alcance:** `service/RegenerateFriendCodeUseCase.java`
- **Dependencias:** T-07
- **Criterios resueltos:** CA-16
- **Validación:** test `FriendCodeControllerTest`

- [x] Cargar usuario por `userId`
- [x] Generar código con `FriendCodeGenerator.generate()`; reintentar si colisiona (máx 10 intentos → 500 si supera)
- [x] Actualizar `friend_code` y `updated_at`
- [x] Devolver `FriendCodeResponseData` con el nuevo código
- [x] Compilar

### T-17 · `ResendVerificationUseCase` (RF-05)
- **Objetivo:** reenvía OTP `EMAIL_VERIFICATION` a usuarios no verificados; siempre responde 200
- **Alcance:** `service/ResendVerificationUseCase.java`
- **Dependencias:** T-09
- **Criterios resueltos:** CA-18, CA-19
- **Validación:** test `ResendVerificationControllerTest`

- [x] Buscar usuario por email; si no existe o `verified_at != null`, retornar sin error
- [x] Invalidar OTP `EMAIL_VERIFICATION` pendiente anterior
- [x] Generar nuevo OTP `EMAIL_VERIFICATION` con TTL 15 min
- [x] Publicar evento `USER_EMAIL_VERIFICATION` al outbox
- [x] Todo en `@Transactional`; compilar

---

## Etapa 12 — Seguridad: endpoints públicos

### T-18 · Actualizar `SecurityConfig` con los nuevos endpoints públicos
- **Objetivo:** declarar `permitAll` para los tres endpoints públicos nuevos
- **Alcance:** `config/SecurityConfig.java`
- **Dependencias:** ninguna
- **Criterios resueltos:** prerequisito de todos los tests de RF-01b, RF-05
- **Validación:** los endpoints nuevos responden sin token (verificado en tests de integración)

- [x] Añadir `.requestMatchers(HttpMethod.POST, "/auth/password-reset/request").permitAll()`
- [x] Añadir `.requestMatchers(HttpMethod.POST, "/auth/password-reset/confirm").permitAll()`
- [x] Añadir `.requestMatchers(HttpMethod.POST, "/auth/resend-verification").permitAll()`
- [x] Compilar

---

## Etapa 13 — Controllers

### T-19 · Crear `UserProfileController`
- **Objetivo:** controller bajo `/auth/user` para los tres endpoints autenticados de perfil
- **Alcance:** `controller/UserProfileController.java` (nuevo)
- **Dependencias:** T-11, T-12, T-15, T-16, T-18
- **Criterios resueltos:** RF-01, RF-02, RF-03, RF-04, RF-06
- **Validación:** compilación; tests de integración de Etapa 14

- [x] `@RestController @RequestMapping("/auth/user")` con constructor injection de los use cases
- [x] `PATCH /me/password` → extrae `sessionId` del JWT en `SecurityContextHolder` → llama `ChangePasswordUseCase`; responde `200 OK`
- [x] `PATCH /me` → extrae `userId` de `SecurityContextHolder` → llama `UpdateProfileUseCase`; responde `200 OK` con `UserResponseData`
- [x] `POST /me/friend-code` → extrae `userId` → llama `RegenerateFriendCodeUseCase`; responde `200 OK` con `FriendCodeResponseData`
- [x] Compilar

### T-20 · Ampliar `AuthController` con endpoints públicos nuevos
- **Objetivo:** añadir los tres endpoints públicos a `AuthController`
- **Alcance:** `controller/AuthController.java`
- **Dependencias:** T-11, T-13, T-14, T-17, T-18
- **Criterios resueltos:** RF-01b, RF-05
- **Validación:** tests de integración de Etapa 14

- [x] `POST /auth/password-reset/request` → llama `ResetPasswordRequestUseCase`; responde `200 OK`
- [x] `POST /auth/password-reset/confirm` → llama `ResetPasswordConfirmUseCase`; responde `200 OK`
- [x] `POST /auth/resend-verification` → llama `ResendVerificationUseCase`; responde `200 OK`
- [x] Compilar

---

## Etapa 14 — Tests de integración

### T-21 · Tests de `ChangePasswordControllerTest` (CA-01 a CA-06)
- **Dependencias:** T-04, T-12, T-19
- [x] CA-01: cambio exitoso → 200; hash diferente en BD
- [x] CA-02: contraseña actual incorrecta → 401
- [x] CA-03: `new_password` sin símbolo → 400
- [x] CA-04: `new_password ≠ confirm` → 400
- [x] CA-05: `new_password == current_password` → 400 con mensaje gracioso
- [x] CA-06: usuario con 2 sesiones activas; tras cambio → solo sesión actual activa en BD
- [x] `./mvnw test -Dtest=ChangePasswordControllerTest`

### T-22 · Tests de `PasswordResetControllerTest` (CA-07 a CA-10)
- **Dependencias:** T-13, T-14, T-20
- [x] CA-07: email registrado (verificado y no verificado) → 200; OTP en BD
- [x] CA-08: email no registrado → 200; sin OTP en BD
- [x] CA-09: OTP válido + nueva contraseña → 200; hash actualizado; todas las sesiones revocadas
- [x] CA-09b: usuario no verificado completa reset; login posterior → 401 (flujo existente)
- [x] CA-09c: access token previo al reset → 401 en endpoint protegido
- [x] CA-10: OTP incorrecto < 5 intentos → 400; ≥ 5 intentos → 429
- [x] `./mvnw test -Dtest=PasswordResetControllerTest`

### T-23 · Tests de `UpdateProfileControllerTest` (CA-11 a CA-15, CA-20 a CA-22)
- **Dependencias:** T-15, T-19
- [x] CA-11: `full_name` válido → 200; BD actualizada
- [x] CA-12: `full_name` inválido → 400
- [x] CA-13: email nuevo → 200; `verified_at = null`; OTP en BD
- [x] CA-14: usuario con email cambiado intenta login → 401
- [x] CA-15: email ya registrado por otro usuario → 409
- [x] CA-20: `photo_url` https válida → 200; BD actualizada
- [x] CA-21: `photo_url: null` → 200; campo null en BD
- [x] CA-22: `photo_url` con esquema `http` → 400
- [x] `./mvnw test -Dtest=UpdateProfileControllerTest`

### T-24 · Tests de `FriendCodeControllerTest` (CA-16)
- **Dependencias:** T-16, T-19
- [x] CA-16: regeneración → 200; código nuevo de 10 chars `[A-Z0-9]` distinto al anterior
- [x] `./mvnw test -Dtest=FriendCodeControllerTest`

### T-25 · Tests de `ResendVerificationControllerTest` (CA-18, CA-19)
- **Dependencias:** T-17, T-20
- [x] CA-18: email con `verified_at = null` → 200; nuevo OTP en BD; anterior invalidado
- [x] CA-19: email ya verificado → 200; sin cambios en BD
- [x] `./mvnw test -Dtest=ResendVerificationControllerTest`

### T-26 · Verificar CA-17 (autorización) para todos los endpoints protegidos nuevos
- **Dependencias:** T-04, T-19
- [x] Request sin `Authorization` a `PATCH /auth/user/me` → 401
- [x] Request sin `Authorization` a `PATCH /auth/user/me/password` → 401
- [x] Request sin `Authorization` a `POST /auth/user/me/friend-code` → 401
- [x] Añadir casos a `JwtProtectedEndpointTest` o a un test dedicado
- [x] `./mvnw test -Dtest=JwtProtectedEndpointTest`

---

## Etapa 15 — Build final y regresiones

### T-27 · Build completo y suite de regresión
- **Objetivo:** confirmar que ningún test previo se ha roto y que el build es limpio
- **Dependencias:** todas las tareas anteriores
- **Criterios resueltos:** todos (CA-01 a CA-22)
- **Validación:** salida `BUILD SUCCESS`; 0 tests en rojo

- [x] `./mvnw clean package`
- [x] Revisar salida: todos los tests pasan, incluyendo los preexistentes (`AuthControllerTest`, `AuthControllerLoginTest`, `AuthControllerLogin2faTest`, `LogoutControllerTest`, `RefreshTokenControllerTest`, `UserControllerTest`, `LoginRateLimitFilterTest`)
- [x] Registrar el resultado y las evidencias en este archivo

---

## Resumen de estado

| Etapa | Tareas | Estado |
| --- | --- | --- |
| 1 · Migración BD | T-01 | [x] |
| 2 · Entidad `User` | T-02 | [x] |
| 3 · JWT `sid` | T-03 | [x] |
| 4 · Filtro JWT | T-04 | [x] |
| 5 · Use cases existentes | T-05, T-06 | [x] |
| 6 · Repositorio | T-07 | [x] |
| 7 · DTO respuesta + mapper | T-08 | [x] |
| 8 · Excepciones | T-09 | [x] |
| 9 · Validators | T-10 | [x] |
| 10 · DTOs request | T-11 | [x] |
| 11 · Use cases nuevos | T-12 – T-17 | [x] |
| 12 · SecurityConfig | T-18 | [x] |
| 13 · Controllers | T-19, T-20 | [x] |
| 14 · Tests integración | T-21 – T-26 | [x] |
| 15 · Build final | T-27 | [x] |
