# TASKS: Ciclo de vida de la cuenta de usuario

**PLAN de referencia:** `docs/features/account-lifecycle/PLAN.md`
**SPEC de referencia:** `docs/features/account-lifecycle/SPEC.md`
**Estado general:** Completado

---

## Etapa 1 — Migración de base de datos (índice parcial de email)

### T-01 · Crear migración V6
- **Objetivo:** reemplazar la restricción `UNIQUE` incondicional de `users.email` por un índice parcial `UNIQUE WHERE deleted_at IS NULL`, para que el mismo email pueda reutilizarse tras un soft delete.
- **Alcance:**
  - Crear `src/main/resources/db/migration/V6__replace_email_unique_with_partial_index.sql`.
  - Contenido:
    ```sql
    ALTER TABLE users DROP CONSTRAINT users_email_key;
    CREATE UNIQUE INDEX idx_users_email_active ON users (email)
        WHERE deleted_at IS NULL;
    ```
- **Dependencias:** ninguna.
- **Criterios resueltos:** RF-05 (email reutilizable tras borrado).
- **Validación:** `./mvnw clean package -DskipTests` — el build debe pasar y Hibernate validar el esquema sin error (`ddl-auto=validate`).
- **Estado:** `[X]`

---

## Etapa 2 — Repositorio y use cases de lectura

### T-02 · Actualizar `UserRepository`
- **Objetivo:** añadir queries que ignoran cuentas eliminadas para login y check de duplicado en registro.
- **Alcance:**
  - Añadir a `UserRepository`:
    - `Optional<User> findByEmailAndDeletedAtIsNull(String email)`
    - `boolean existsByEmailAndDeletedAtIsNull(String email)`
- **Dependencias:** T-01 (el índice parcial debe existir antes de ejecutar tests).
- **Criterios resueltos:** RF-04, RF-05.
- **Validación:** `./mvnw test` — los tests existentes de `UserRepository` siguen en verde.
- **Estado:** `[X]`

### T-03 · Actualizar `LoginUseCase`
- **Objetivo:** que el login falle con el mismo mensaje genérico cuando la cuenta existe pero está eliminada (sin revelar el motivo).
- **Alcance:**
  - Sustituir `userRepository.findByEmail(...)` por `userRepository.findByEmailAndDeletedAtIsNull(...)` en `LoginUseCase.execute`.
  - El mensaje de error y el comportamiento observable son idénticos a los de un email no registrado.
- **Dependencias:** T-02.
- **Criterios resueltos:** RF-04, CA-02.
- **Validación:** test de integración en `AuthControllerLoginTest#login_deletedAccount_returnsGenericError` — cuenta con `deleted_at` seteado + credenciales correctas → `401` con mensaje genérico.
- **Estado:** `[X]`

### T-04 · Actualizar `RegisterUseCase` — check de duplicado
- **Objetivo:** que el registro no bloquee un email de cuenta eliminada.
- **Alcance:**
  - Sustituir `userRepository.existsByEmail(...)` por `userRepository.existsByEmailAndDeletedAtIsNull(...)` en `RegisterUseCase.execute`.
- **Dependencias:** T-02.
- **Criterios resueltos:** RF-05, CA-04.
- **Validación:** test de integración en `RegisterControllerTest#registerUser_withEmailOfDeletedAccount_createsNewAccount` — registro con email de cuenta eliminada → `201 Created` con nuevo `id`.
- **Estado:** `[X]`

---

## Etapa 3 — `photo_url` opcional en el registro

### T-05 · Añadir `photo_url` a `RegisterUserCommand`
- **Objetivo:** aceptar el campo opcional `photo_url` en el cuerpo del request de registro.
- **Alcance:**
  - Añadir el campo `photo_url` (tipo `String`, nullable) a `RegisterUserCommand`.
  - Anotarlo con `@ValidPhotoUrl` (reutiliza `PhotoUrlValidator` existente).
  - Añadir `@JsonProperty("photo_url")` para respetar el naming convention del API.
  - Añadir getter `getPhotoUrl()`.
- **Dependencias:** ninguna (paralela a T-03/T-04).
- **Criterios resueltos:** RF-11, RF-12.
- **Validación:** `./mvnw clean package -DskipTests`.
- **Estado:** `[X]`

### T-06 · Persistir `photo_url` en `RegisterUseCase`
- **Objetivo:** que el `photo_url` del command se persista al crear el usuario.
- **Alcance:**
  - En `RegisterUseCase.execute`, incluir `.photoUrl(command.getPhotoUrl())` al construir el `User` con el builder.
- **Dependencias:** T-05.
- **Criterios resueltos:** RF-11, CA-11, CA-12.
- **Validación:**
  - Test CA-11: `POST /auth/register` con `photo_url` válida → `201`; campo en BD y en respuesta.
  - Test CA-12: `POST /auth/register` sin `photo_url` → `201`; `"photo_url": null` en respuesta.
  - Test CA-13: `POST /auth/register` con `photo_url` de esquema `http` → `400`; detalle en campo `photo_url`.
- **Estado:** `[X]`

---

## Etapa 4 — Migración del endpoint de registro

### T-07 · Añadir `POST /auth/register` en `AuthController`
- **Objetivo:** exponer el registro en la nueva ruta `/auth/register`.
- **Alcance:**
  - Añadir el método `register` en `AuthController`, con la misma firma, lógica de delegación y respuesta que el actual `UserController.register`. Inyectar `RegisterUseCase` mediante constructor.
  - Responde `201 Created` con `ApiSuccessResponse<UserResponseData>`.
- **Dependencias:** T-06 (para que `photo_url` funcione desde el primer día en la nueva ruta).
- **Criterios resueltos:** RF-09, CA-09.
- **Validación:** test de integración — `POST /auth/register` con payload mínimo → `201 Created`.
- **Estado:** `[X]`

### T-08 · Eliminar `UserController`
- **Objetivo:** retirar el endpoint `POST /user/` del servicio.
- **Alcance:**
  - Eliminar el archivo `src/main/java/dev/yolbert/auth_service/controller/UserController.java`.
- **Dependencias:** T-07 (la nueva ruta debe estar operativa antes de eliminar la antigua).
- **Criterios resueltos:** RF-10, CA-10.
- **Validación:** `./mvnw clean package -DskipTests` debe compilar sin referencias a `UserController`. Prueba manual `curl -X POST http://localhost:8080/user/` → `404`.
- **Estado:** `[X]`

### T-09 · Actualizar `SecurityConfig`
- **Objetivo:** que `/auth/register` sea pública y que `POST /user/` devuelva `404` (y no `401`) al haber desaparecido el endpoint.
- **Alcance:**
  - Añadir `.requestMatchers(HttpMethod.POST, "/auth/register").permitAll()` en `SecurityConfig.filterChain`.
  - Mantener `POST /user/**` como pública (ruta muerta): sin esta regla Spring Security interceptaría la petición y respondería `401`; con ella el request llega a Spring MVC y responde `404` (CA-10).
  - Mapear `NoResourceFoundException` → `404` en `GlobalExceptionHandler` (sin ello, el catch-all respondería `500` para rutas sin handler).
- **Dependencias:** T-07, T-08.
- **Criterios resueltos:** RF-09, RF-10, CA-10.
- **Validación:** `./mvnw test` — `RegisterControllerTest.registerUser_legacyEndpoint_returns404` (POST `/user/` → `404`); tests de `AuthController` y `JwtProtectedEndpointTest` siguen en verde.
- **Estado:** `[X]`

---

## Etapa 5 — Soft delete de cuenta

### T-10 · Crear `DeleteAccountUseCase`
- **Objetivo:** implementar la lógica de soft delete: revocar sesiones, marcar `deleted_at` y publicar el evento.
- **Alcance:**
  - Crear `src/main/java/dev/yolbert/auth_service/service/DeleteAccountUseCase.java`.
  - Inyectar por constructor: `UserRepository`, `SessionRepository`, `OutboxRepository`.
  - Método `execute(UUID userId)` con `@Transactional`:
    1. Cargar el usuario por `userId` (lanzar `IllegalArgumentException` si no existe — caso inesperado, el token ya lo validó).
    2. Si `user.getDeletedAt() != null`, retornar inmediatamente (idempotencia — RF-08).
    3. Revocar todas las sesiones activas (`findAllByUserIdAndRevokedFalse`) marcando `revoked = true`, `revokedAt = now`, `updatedAt = now`.
    4. Marcar `user.setDeletedAt(LocalDateTime.now())` y `user.setUpdatedAt(LocalDateTime.now())`.
    5. Persistir el usuario.
    6. Escribir evento `USER_DELETED` al outbox con payload `{"id":"<uuid>","email":"<email>"}` siguiendo el patrón de `RegisterUseCase.buildPayload`.
- **Dependencias:** T-01, T-02.
- **Criterios resueltos:** RF-01, RF-02, RF-03, RF-06, RF-07, RF-08, CA-01, CA-05, CA-08.
- **Validación:**
  - Test CA-01: usuario con sesión activa → `deleted_at` seteado; sesiones revocadas; outbox con `USER_DELETED`.
  - Test CA-05: outbox contiene `event_type = 'USER_DELETED'` con `aggregate_id` correcto.
  - Test CA-08: usuario ya eliminado → retorna sin modificar BD ni duplicar evento.
- **Estado:** `[X]`

### T-11 · Añadir `DELETE /auth/user/me` en `UserProfileController`
- **Objetivo:** exponer el endpoint de eliminación de cuenta.
- **Alcance:**
  - Inyectar `DeleteAccountUseCase` en `UserProfileController` mediante constructor.
  - Añadir el método `deleteAccount()` con `@DeleteMapping("/me")`:
    - Extrae `userId` de `SecurityContextHolder` (igual que los otros métodos del controlador).
    - Llama a `deleteAccountUseCase.execute(userId)`.
    - Responde `200 OK` con `ApiSuccessResponse.<Void>builder().message("Cuenta eliminada exitosamente.").build()`.
- **Dependencias:** T-10.
- **Criterios resueltos:** RF-01, RF-07, CA-06, CA-07.
- **Validación:**
  - Test CA-06: respuesta sin campo `data` (solo `message`).
  - Test CA-07: request sin `Authorization` → `401`.
- **Estado:** `[X]`

### T-12 · Verificar rechazo de tokens post-borrado
- **Objetivo:** confirmar que el token de una sesión revocada es rechazado activamente (comportamiento ya cubierto por `JwtAuthenticationFilter`, pero verificar explícitamente en un test).
- **Alcance:**
  - Añadir test en `DeleteAccountControllerTest` (`deleteAccount_thenPreviousTokenRejected`): tras eliminar la cuenta, usar el access token previo en `GET /auth/ping` → `401`.
  - No se requiere cambio de código (el filtro existente ya rechaza sesiones revocadas).
- **Dependencias:** T-11.
- **Criterios resueltos:** RF-04, CA-03.
- **Validación:** test automatizado — token de sesión revocada → `401 Unauthorized`.
- **Estado:** `[X]`

---

## Etapa 6 — Validación final

### T-13 · Build y suite completa de tests
- **Objetivo:** verificar que todos los cambios integran correctamente y no hay regresiones.
- **Alcance:**
  - Ejecutar `./mvnw clean package` (incluye compilación + tests).
  - Confirmar que todos los criterios CA-01..CA-13 tienen su test en verde o evidencia de verificación registrada.
- **Dependencias:** T-01..T-12 completadas.
- **Criterios resueltos:** todos (CA-01..CA-13).
- **Validación:** output de `./mvnw clean package` sin errores; tabla de resultados actualizada abajo.
- **Estado:** `[X]`

### T-14 · Verificación manual de CA-10
- **Objetivo:** confirmar que `POST /user/` devuelve `404` con el servicio en ejecución.
- **Alcance:**
  - Levantar el servicio con Docker Compose (`./mvnw spring-boot:run`).
  - Ejecutar `curl -s -o /dev/null -w "%{http_code}" -X POST http://localhost:8080/user/` → debe devolver `404`.
- **Dependencias:** T-08, T-09.
- **Criterios resueltos:** CA-10.
- **Validación:** código HTTP `404` observado (servicio real en `:8080`, 2026-09-17).
- **Estado:** `[X]`

---

## Resultados de validación (2026-09-17)

`./mvnw clean package` → **BUILD SUCCESS**, **85 tests, 0 fallos**.

| Criterio | Evidencia automatizada | Resultado |
| --- | --- | --- |
| CA-01 | `DeleteAccountControllerTest#deleteAccount_success` | OK |
| CA-02 | `AuthControllerLoginTest#login_deletedAccount_returnsGenericError` | OK |
| CA-03 | `DeleteAccountControllerTest#deleteAccount_thenPreviousTokenRejected` | OK |
| CA-04 | `RegisterControllerTest#registerUser_withEmailOfDeletedAccount_createsNewAccount` | OK |
| CA-05 | `DeleteAccountControllerTest#deleteAccount_success` (outbox `USER_DELETED`) | OK |
| CA-06 | `DeleteAccountControllerTest#deleteAccount_success` (respuesta sin `data`) | OK |
| CA-07 | `DeleteAccountControllerTest#deleteAccount_withoutToken_unauthorized` | OK |
| CA-08 | `DeleteAccountControllerTest#deleteAccount_alreadyDeleted_idempotent` | OK |
| CA-09 | `RegisterControllerTest#registerUser_success` | OK |
| CA-10 | `RegisterControllerTest#registerUser_legacyEndpoint_returns404` + verificación manual (T-14): `curl -X POST :8080/user/` → `404` | OK |
| CA-11 | `RegisterControllerTest#registerUser_withValidPhotoUrl_persistsIt` | OK |
| CA-12 | `RegisterControllerTest#registerUser_withoutPhotoUrl_photoUrlIsNull` | OK |
| CA-13 | `RegisterControllerTest#registerUser_invalidPhotoUrl_badRequest` | OK |

## Resumen de criterios de aceptación

| Criterio | Tarea(s) | Estado |
| --- | --- | --- |
| CA-01 | T-10, T-11 | `[X]` |
| CA-02 | T-03 | `[X]` |
| CA-03 | T-12 | `[X]` |
| CA-04 | T-04 | `[X]` |
| CA-05 | T-10 | `[X]` |
| CA-06 | T-11 | `[X]` |
| CA-07 | T-11 | `[X]` |
| CA-08 | T-10 | `[X]` |
| CA-09 | T-07 | `[X]` |
| CA-10 | T-08, T-14 | `[X]` |
| CA-11 | T-06 | `[X]` |
| CA-12 | T-06 | `[X]` |
| CA-13 | T-05, T-06 | `[X]` |