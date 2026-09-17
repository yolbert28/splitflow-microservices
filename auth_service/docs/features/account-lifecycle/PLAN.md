# PLAN: Ciclo de vida de la cuenta de usuario

**SPEC de referencia:** `docs/features/account-lifecycle/SPEC.md`
**Versión de la spec revisada:** Aprobada — 2026-09-17
**Contrato de API afectado:** Nuevos endpoints `DELETE /auth/user/me` y `POST /auth/register`; eliminación de `POST /user/`
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
| `UserController` | `controller/UserController.java` | Actualmente expone `POST /user/` → se eliminará completo |
| `AuthController` | `controller/AuthController.java` | Recibirá el nuevo endpoint `POST /auth/register` (migración) |
| `UserProfileController` | `controller/UserProfileController.java` | Expone `PATCH /auth/user/me` y `POST /auth/user/me/friend-code`; recibirá `DELETE /auth/user/me` |
| `RegisterUseCase` | `service/RegisterUseCase.java` | Crea usuario, OTP de verificación y evento outbox. Se modificará para aceptar `photo_url` |
| `RegisterUserCommand` | `dto/RegisterUserCommand.java` | DTO de entrada para el registro. Se añadirá `photo_url` opcional con `@ValidPhotoUrl` |
| `User` (entidad) | `domain/entity/User.java` | Ya tiene `deletedAt` (`deleted_at`) y `photoUrl` (`photo_url`) mapeados |
| `UserRepository` | `repository/UserRepository.java` | Tiene `existsByEmail` y `findByEmail`. Se añadirán variantes que ignoran cuentas eliminadas |
| `SessionRepository` | `repository/SessionRepository.java` | Tiene `findAllByUserIdAndRevokedFalse` — reutilizable para revocar todas las sesiones en el borrado |
| `JwtAuthenticationFilter` | `config/JwtAuthenticationFilter.java` | Valida JWT y verifica sesión activa via `isSessionActive`. Rechaza tokens con sesión revocada |
| `SecurityConfig` | `config/SecurityConfig.java` | Reglas `permitAll`. Se eliminará `/user/**` y se añadirá `/auth/register` como pública |
| `GlobalExceptionHandler` | `config/GlobalExceptionHandler.java` | Mapeo de excepciones a HTTP. Se añade handler de `NoResourceFoundException` → `404` (rutas sin handler) |
| `UserMapper` | `mapper/UserMapper.java` | `toResponseData(User)` — ya mapea `photoUrl`. Sin cambios |
| `UserResponseData` | `dto/UserResponseData.java` | Ya incluye `photo_url`. Sin cambios |
| `OutboxRepository` | `repository/OutboxRepository.java` | Persiste eventos outbox. Se reutiliza sin cambios |
| `PhotoUrlValidator` | `utils/validation/PhotoUrlValidator.java` | Valida URL absoluta `https`, máx. 2048 chars. Se reutilizará en `RegisterUserCommand` |
| `V1__create_users_table.sql` | `db/migration/V1__create_users_table.sql` | Define `email VARCHAR NOT NULL UNIQUE` — restricción a reemplazar con índice parcial |

**Convenciones y patrón de referencia:** `RegisterUseCase` para el flujo completo de persistencia + OTP + outbox; `ChangePasswordUseCase` para la revocación de sesiones; `LogoutUseCase` para la revocación individual de sesión.

---

## Solución propuesta

### 1. Soft delete y liberación del email (RF-01..RF-08)

**Problema del email único:** la columna `email` en `users` tiene una restricción `UNIQUE` incondicional. Tras un soft delete, el mismo email no puede reutilizarse. La solución es **reemplazar la restricción `UNIQUE` por un índice parcial `UNIQUE WHERE deleted_at IS NULL`** vía una migración Flyway. Este índice garantiza que solo puede haber un usuario activo con un email dado, pero permite múltiples filas con el mismo email si las anteriores están eliminadas.

**Endpoint de eliminación:** `DELETE /auth/user/me` se aloja en `UserProfileController` (ya gestiona `/auth/user/me*` con JWT). El nuevo use case `DeleteAccountUseCase`:
1. Carga el usuario por `userId` del token JWT.
2. Si ya tiene `deleted_at` seteado, retorna sin error (idempotencia — RF-08).
3. En una sola `@Transactional`:
   - Revoca todas las sesiones activas del usuario (igual que `ChangePasswordUseCase`).
   - Establece `deleted_at = now()` y `updated_at = now()`.
   - Persiste el usuario.
   - Escribe el evento `USER_DELETED` al outbox con payload `{id, email}`.
4. Responde `200 OK`.

**Rechazo activo del JWT tras el borrado (RF-04, DP-03):** el `JwtAuthenticationFilter` ya verifica que la sesión exista y no esté revocada. Al revocar todas las sesiones en el borrado (RF-03), el filtro rechazará automáticamente todos los tokens cuya sesión quede revocada con `401`. **No se necesita verificar `deleted_at` explícitamente en el filtro** porque la revocación de sesiones actúa como barrera suficiente, sin coste de consulta adicional a `users` por request.

> `LoginUseCase` sí debe verificar `deleted_at IS NULL` explícitamente al buscar el usuario por email, para devolver el error genérico de credenciales inválidas sin revelar el motivo (RF-04, CA-02).

### 2. Migración del endpoint de registro (RF-09, RF-10)

`UserController` se elimina íntegramente. El endpoint `POST /auth/register` se añade a `AuthController`. Internamente llama al mismo `RegisterUseCase` sin cambios en la lógica de negocio. En `SecurityConfig` se añade `permitAll` para `/auth/register`. La ruta muerta `POST /user/**` se conserva como pública para que el request llegue a Spring MVC y se responda `404` (CA-10); si se dejase fuera de `permitAll`, Spring Security respondería `401` a peticiones anónimas.

### 3. `photo_url` opcional en el registro (RF-11, RF-12)

Se añade el campo `photo_url` a `RegisterUserCommand` con `@ValidPhotoUrl` (nullable — `null` es válido). En `RegisterUseCase.execute`, el `User` se construye incluyendo `command.getPhotoUrl()`. `UserMapper.toResponseData` ya mapea `photoUrl` sin cambios.

---

## Contrato de API

| Método | Ruta | Autenticación / autorización | Códigos de estado esperados | Requisito relacionado |
| --- | --- | --- | --- | --- |
| `POST` | `/auth/register` | Público (`permitAll`) | 201, 400, 409, 500 | RF-09, RF-11, RF-12 |
| `DELETE` | `/auth/user/me` | JWT (usuario autenticado, owner) | 200, 401, 500 | RF-01..RF-08 |
| ~~`POST`~~ | ~~`/user/`~~ | — eliminado — | — | RF-10 |

### POST /auth/register — esquema de request

```json
{
  "full_name": "María García",
  "email": "maria@example.com",
  "password": "P@ssw0rd!",
  "confirm_password": "P@ssw0rd!",
  "photo_url": "https://cdn.example.com/avatar.jpg"
}
```

`photo_url` es opcional; puede omitirse o enviarse como `null`.

### POST /auth/register — esquema de response (201)

```json
{
  "message": "Cuenta creada. Revisa tu correo para verificar tu cuenta.",
  "data": {
    "id": "uuid",
    "full_name": "María García",
    "email": "maria@example.com",
    "friend_code": "ABC123XYZ0",
    "photo_url": "https://cdn.example.com/avatar.jpg",
    "verified": false,
    "created_at": "...",
    "updated_at": "..."
  }
}
```

Response `400`: `ApiErrorResponse` con lista de `errors` por campo (validación Bean Validation o email duplicado).

### DELETE /auth/user/me — esquema de response (200)

```json
{ "message": "Cuenta eliminada exitosamente." }
```

Response `401`: JWT ausente, inválido o sesión revocada — `ApiErrorResponse` con mensaje "No autorizado."

**Compatibilidad hacia atrás:** `POST /user/` se elimina (rotura de contrato). No hay consumidores activos conocidos (DP-04).

---

## Módulos y componentes afectados

No aplica la tabla de módulos (proyecto de un solo módulo sin modularización formal).

| Componente o ruta | Capa | Acción | Cambio y responsabilidad | Requisito relacionado |
| --- | --- | --- | --- | --- |
| `controller/UserController.java` | Controller | **Eliminar** | Endpoint `POST /user/` desaparece | RF-10 |
| `controller/AuthController.java` | Controller | **Modificar** | Añadir `POST /auth/register` delegando a `RegisterUseCase` | RF-09 |
| `controller/UserProfileController.java` | Controller | **Modificar** | Añadir `DELETE /auth/user/me` delegando a `DeleteAccountUseCase` | RF-01, RF-07 |
| `service/DeleteAccountUseCase.java` | Servicio | **Crear** | Soft delete: revocar sesiones, marcar `deleted_at`, publicar `USER_DELETED` | RF-01..RF-08 |
| `service/RegisterUseCase.java` | Servicio | **Modificar** | Aceptar `photo_url` de `RegisterUserCommand` y persistirlo | RF-11 |
| `service/LoginUseCase.java` | Servicio | **Modificar** | Usar `findByEmailAndDeletedAtIsNull` para ignorar cuentas eliminadas | RF-04, CA-02 |
| `dto/RegisterUserCommand.java` | DTO | **Modificar** | Añadir campo `photo_url` opcional con `@ValidPhotoUrl` | RF-11, RF-12 |
| `repository/UserRepository.java` | Repositorio | **Modificar** | Añadir `existsByEmailAndDeletedAtIsNull` y `findByEmailAndDeletedAtIsNull` | RF-04, RF-05 |
| `config/SecurityConfig.java` | Configuración | **Modificar** | Añadir `permitAll` de `/auth/register` y mantener `POST /user/**` pública (ruta muerta → `404`, CA-10) | RF-09, RF-10 |
| `config/GlobalExceptionHandler.java` | Configuración | **Modificar** | Mapear `NoResourceFoundException` → `404` (rutas sin handler; evita el `500` del catch-all) | RF-10, CA-10 |
| `db/migration/V6__replace_email_unique_with_partial_index.sql` | Migración | **Crear** | Reemplazar restricción `UNIQUE` de `email` por índice parcial `WHERE deleted_at IS NULL` | RF-05 |

---

## Datos y contratos

- **Modelos de dominio:** `User` ya tiene `deletedAt` y `photoUrl`. No se modifica la entidad.
- **Identificadores:** `DELETE /auth/user/me` obtiene el `userId` del claim `sub` del JWT (igual que `UserProfileController` actualmente).
- **Transformaciones:** `POST /auth/register` → `RegisterUseCase` → `User` (con `photoUrl`) → `UserMapper.toResponseData` → `UserResponseData`. El mapper ya mapea `photoUrl`; sin cambios en él.
- **Persistencia y consultas:**
  - `existsByEmailAndDeletedAtIsNull` — para check de duplicado en el registro (sustituye a `existsByEmail`).
  - `findByEmailAndDeletedAtIsNull` — para el login (sustituye a `findByEmail`).
  - Ambas son queries derivadas que Spring Data JPA genera automáticamente; aprovechan el índice parcial de `email`.
- **Consistencia entre servicios:** el evento `USER_DELETED` en el outbox sigue el patrón de `USER_REGISTERED`. `OutboxPublisher` lo relayará sin cambios.
- **Migraciones:** una sola migración `V6`. Lógicamente reversible (se puede eliminar el índice parcial y restaurar el UNIQUE simple), aunque Flyway no tiene undo automático.

---

## Seguridad y validación de entrada

- **Validación de inputs:**
  - `POST /auth/register`: `@NotBlank`, `@Email`, `@Size`, `@ValidPassword`, `@PasswordsMatch`, `@ValidPhotoUrl` (nuevo, nullable — `null` es válido).
  - `DELETE /auth/user/me`: sin cuerpo; el filtro JWT valida el token.
- **Autenticación y autorización:**
  - `POST /auth/register` es público (`permitAll`). Sin JWT.
  - `DELETE /auth/user/me` requiere JWT válido con sesión activa. El usuario solo puede eliminar su propia cuenta (`userId` extraído del token, no del cuerpo).
- **Datos sensibles:** el payload del outbox `USER_DELETED` incluye `id` y `email`. No incluye `password_hash` ni datos de sesión. Los logs del use case no deben incluir el email del usuario.
- **Enumeración de cuentas:** `LoginUseCase` devuelve el mismo mensaje genérico tanto para email no registrado, contraseña incorrecta como para cuenta eliminada (RF-04).
- **Rate limiting:** la protección JWT es suficiente para `DELETE /auth/user/me` en este alcance.

---

## Rendimiento y escalabilidad

- **Índice parcial:** `CREATE UNIQUE INDEX idx_users_email_active ON users (email) WHERE deleted_at IS NULL` garantiza unicidad en cuentas activas con O(log n) lookup, sin penalizar las filas eliminadas.
- **Consultas en el borrado:** lectura del usuario + consulta de sesiones activas + N escrituras (sesiones + usuario + outbox), todo en transacción. El número de sesiones activas es acotado (uso normal: 1-3 dispositivos).
- **Impacto en el filtro JWT:** el rechazo se logra vía revocación de sesiones (consulta por PK de sesión, ya existente). No hay consulta adicional a `users` por request.
- **Paginación / caché:** no aplica.

---

## Estado, operaciones y errores

- **Transaccionalidad:** `DeleteAccountUseCase.execute` lleva `@Transactional`. Si falla cualquier paso, se revierte todo.
- **Idempotencia (RF-08):** si `user.getDeletedAt() != null`, el use case retorna sin modificar nada ni publicar evento duplicado. Responde `200 OK`.
- **Concurrencia (DP-05):** dos borrados simultáneos del mismo usuario: el primero completa la transacción marcando `deleted_at`; el segundo lo lee ya marcado y sale por la rama idempotente. Sin condición de carrera.
- **Errores:**

| Condición | Mecanismo | HTTP |
| --- | --- | --- |
| JWT ausente o inválido en endpoint protegido | `JwtAuthenticationFilter` + Spring Security | 401 |
| Sesión revocada (post-borrado) | `JwtAuthenticationFilter` → sin autenticación | 401 |
| Cuenta eliminada intenta login | `LoginUseCase` → `InvalidCredentialsException` | 401 |
| `photo_url` inválida en registro | Bean Validation → `MethodArgumentNotValidException` → `GlobalExceptionHandler` | 400 |
| Email en uso por cuenta activa en registro | `EmailAlreadyExistsException` → `GlobalExceptionHandler` | 400 |
| Error inesperado | `GlobalExceptionHandler` catch-all | 500 |

---

## Dependencias y configuración

- **Librerías o servicios nuevos:** ninguno. Todos los componentes necesarios ya están en el classpath.
- **Variables de entorno nuevas:** ninguna.
- **Migración de base de datos:**

  ```sql
  -- V6__replace_email_unique_with_partial_index.sql
  ALTER TABLE users DROP CONSTRAINT users_email_key;
  CREATE UNIQUE INDEX idx_users_email_active ON users (email)
      WHERE deleted_at IS NULL;
  ```

  No reversible automáticamente con Flyway, pero la operación inversa es trivial si es necesario.

- **Feature flags:** no aplica.

---

## Estrategia de validación

<!-- Una fila por criterio de la spec. Selecciona el método capaz de demostrarlo:
test unitario, integración o prueba manual. No todos requieren todos los métodos.
Identifica tests existentes y separa los nuevos propuestos. Incluye regresiones relevantes.
Un test unitario aislado no demuestra el comportamiento end-to-end del endpoint;
una respuesta 200 no demuestra los efectos secundarios esperados (persistencia,
eventos, notificaciones). -->

| Criterio | Método y test existente o propuesto | Entorno, datos y estado inicial necesario | Evidencia prevista |
| --- | --- | --- | --- |
| CA-01 | Test de integración nuevo `DeleteAccountUseCaseTest` | Testcontainers; usuario + sesión activa precreados | HTTP 200; `deleted_at` ≠ null en BD; sesión con `revoked = true` |
| CA-02 | Test de integración nuevo en `LoginUseCaseTest` | Testcontainers; usuario con `deleted_at` seteado | HTTP 401; mensaje genérico |
| CA-03 | Test de integración nuevo en `JwtProtectedEndpointTest` | Testcontainers; sesión revocada | HTTP 401 en endpoint protegido |
| CA-04 | Test de integración nuevo en `RegisterUseCaseTest` | Testcontainers; usuario eliminado con mismo email | HTTP 201; nuevo `id` distinto |
| CA-05 | Incluido en `DeleteAccountUseCaseTest` | Testcontainers | Fila en `outbox` con `event_type='USER_DELETED'` y `aggregate_id` correcto |
| CA-06 | Incluido en `DeleteAccountUseCaseTest` — verificar cuerpo | Testcontainers | Cuerpo sin campo `data` |
| CA-07 | Test de integración nuevo en `DeleteAccountUseCaseTest` | Testcontainers; request sin JWT | HTTP 401 |
| CA-08 | Test de integración nuevo en `DeleteAccountUseCaseTest` | Testcontainers; usuario con `deleted_at` seteado; token con sesión activa | HTTP 200 (idempotente) |
| CA-09 | Test de integración nuevo o adaptar `RegisterUseCaseTest` | Testcontainers | HTTP 201; campos correctos en `/auth/register` |
| CA-10 | Test de integración nuevo en `RegisterControllerTest` (`POST /user/` → `404`) + verificación manual opcional | Testcontainers | HTTP 404 |
| CA-11 | Test de integración nuevo en `RegisterUseCaseTest` | Testcontainers | HTTP 201; `photo_url` en respuesta y en BD |
| CA-12 | Test de integración nuevo en `RegisterUseCaseTest` | Testcontainers | HTTP 201; `"photo_url": null` |
| CA-13 | Test de integración nuevo en `RegisterUseCaseTest` | Testcontainers | HTTP 400; detalle en campo `photo_url` |

**Comprobaciones de regresión:** `./mvnw test` completo tras cada etapa para detectar regresiones en `RegisterUseCase`, `LoginUseCase`, `JwtProtectedEndpointTest` y `AuthController`.

**Comandos verificados para compilar y ejecutar tests:**
```bash
./mvnw clean package -DskipTests
./mvnw test
./mvnw test -Dtest=DeleteAccountUseCaseTest
```

**Limitaciones del entorno:** CA-10 se cubre con test automatizado (`POST /user/` → `404`); además puede verificarse manualmente con `curl` contra el servicio local. Para que la respuesta sea `404` (y no `401`) es necesario que `POST /user/**` siga siendo pública en `SecurityConfig` (ver sección de solución).

---

## Orden de implementación

1. **Migración V6** — crear `V6__replace_email_unique_with_partial_index.sql`. Verificar compilación y validación del esquema con `./mvnw clean package -DskipTests`.

2. **`UserRepository` + use cases de lectura** — añadir `existsByEmailAndDeletedAtIsNull` y `findByEmailAndDeletedAtIsNull`. Actualizar `LoginUseCase` y `RegisterUseCase` para usar las nuevas queries. Ejecutar `./mvnw test` para verificar que los tests existentes siguen en verde.

3. **`photo_url` en registro** — añadir el campo a `RegisterUserCommand` con `@ValidPhotoUrl`. Modificar `RegisterUseCase` para persistirlo. Añadir tests CA-11, CA-12, CA-13.

4. **Migración de endpoint (`/auth/register`)** — añadir `POST /auth/register` en `AuthController`. Eliminar `UserController`. Actualizar `SecurityConfig`. Verificar CA-09 y CA-10.

5. **Soft delete** — crear `DeleteAccountUseCase`. Añadir `DELETE /auth/user/me` en `UserProfileController`. Añadir tests CA-01..CA-08. Ejecutar `./mvnw test` completo.

---

## Riesgos y decisiones pendientes

- **Riesgos y medidas acordadas:**
  - **Migración de índice `email`:** reemplazar el UNIQUE simple por el parcial es sencillo, pero si se revierte manualmente, podrían existir filas con el mismo email (cuentas eliminadas + activa nueva). La operación inversa requiere limpiar duplicados primero. Mitigación: no hay cuentas eliminadas en este momento; el riesgo es teórico.
  - **Rotura de `POST /user/`:** sin período de compatibilidad. Cualquier cliente externo que lo use recibirá `404`. Mitigado por DP-04 (no hay consumidores activos conocidos).
  - **Tokens sin `sid`:** si en el futuro se emiten tokens sin claim `sid`, no quedarían bloqueados por la revocación de sesiones. Hoy todos los tokens de login llevan `sid`; el riesgo no aplica en la práctica actual.

- **Decisiones pendientes:** Ninguna.

<!-- ANTES DE SOLICITAR APROBACIÓN
Comprueba que el plan cubre los requisitos, respeta las exclusiones, reutiliza
componentes verificados y permite demostrar todos los criterios de aceptación.
Resuelve dudas y marcadores pendientes. Si la spec cambió, revisa su impacto.
Tras aprobar el plan, deriva TASKS.md con IDs, dependencias, referencias a RF/CA
y comprobaciones. No marques una tarea terminada sin realizar su validación;
si está bloqueada, registra el motivo.
-->
