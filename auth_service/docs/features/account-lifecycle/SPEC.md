# SPEC: Ciclo de vida de la cuenta de usuario

**Estado:** Aprobada <!-- Borrador | En revisión | Aprobada -->

<!-- PARA LA PERSONA
Copia esta plantilla como SPEC.md en una carpeta de la funcionalidad.
Pide al agente que la complete contigo.
SPEC.md define qué debe cumplirse; PLAN.md desarrolla cómo implementarlo;
TASKS.md organiza los pasos de ejecución.
-->

<!-- PARA EL AGENTE
- Lee las instrucciones del proyecto. Inspecciona el
  repositorio para comprobar el comportamiento actual.
- Completa esta spec con la persona: investiga lo comprobable y consulta las
  decisiones pendientes. Haz pocas preguntas por vez y actualiza las respuestas.
- No inventes requisitos ni exclusiones. Distingue propuestas de decisiones
  confirmadas y marca como PENDIENTE lo que aún no esté resuelto.
- Aplica las consideraciones backend relevantes sin ampliar el alcance automáticamente.
- No incluyas diseño de clases, tablas, componentes, archivos o algoritmos:
  esos detalles pertenecen a PLAN.md. Sí registra restricciones explícitas del pedido.
- Mantén el documento breve y proporcional a la funcionalidad. Conserva los comentarios.
- Un documento completo no está aprobado automáticamente. Solicita aprobación
  antes de marcarlo como Aprobada. No implementes durante esta etapa.
-->

## Qué construimos y para quién

Este conjunto de cambios afecta el ciclo de vida básico de la cuenta en el
`auth_service`. Los destinatarios son los usuarios registrados de SplitFlow y los
clientes del API (aplicaciones móviles o frontend web) que consumen el servicio.

Los tres sub-cambios son independientes entre sí pero se agrupan porque
pertenecen al mismo recurso y se despliegan en una sola entrega:

1. **Eliminación de cuenta (soft delete):** el usuario puede solicitar que su
   cuenta quede desactivada. Los datos se conservan en base de datos con una
   marca de borrado (`deleted_at`), pero la cuenta deja de ser operativa.
   El email queda inmediatamente disponible para un nuevo registro.

2. **Migración del endpoint de registro:** el endpoint actual `POST /user/`
   se traslada a `POST /auth/register`. El endpoint antiguo desaparece (rotura
   de contrato).

3. **Campo `photo_url` en el registro:** se añade `photo_url` como campo
   opcional en `RegisterUserCommand`. La columna ya existe en la base de datos
   (`V5__add_photo_url_to_users.sql`) y ya se persiste en el flujo de
   actualización de perfil (`UpdateProfileUseCase`).

## Situación actual

- **Registro:** `POST /user/` (en `UserController`). El `RegisterUserCommand`
  tiene `full_name`, `email`, `password` y `confirm_password`. No acepta
  `photo_url`. El endpoint está abierto (`permitAll`) en `SecurityConfig`.
- **Eliminación:** la columna `deleted_at` existe en `users` (migración V1)
  y el campo `deletedAt` está en la entidad `User`, pero no hay endpoint ni
  lógica de negocio que lo use. La columna `email` tiene restricción `UNIQUE`
  sin condiciones, por lo que el mismo correo no puede reutilizarse tras un
  borrado lógico sin una intervención en el esquema.
- **`photo_url` en registro:** la columna existe, `UserResponseData` la expone
  y `UpdateProfileUseCase` la persiste, pero `RegisterUseCase` nunca la asigna
  porque `RegisterUserCommand` no la incluye.
- **Sesiones activas:** `ChangePasswordUseCase` ya revoca todas las sesiones
  excepto la actual al cambiar contraseña. El patrón es reutilizable para la
  eliminación.
- **Rate limiting:** `LoginRateLimitFilter` limita `POST /auth/login` a 10
  intentos por IP en 60 s. El nuevo endpoint `DELETE /auth/user/me` está
  protegido por JWT, por lo que la protección de autenticación existente es
  suficiente para esta operación.

## Dentro del alcance

<!-- RF-01 a RF-08-d: eliminación de cuenta -->
<!-- RF-09 a RF-10: migración de endpoint -->
<!-- RF-11 a RF-12: photo_url en registro -->

### Eliminación de cuenta (soft delete)

- **RF-01:** Un usuario autenticado puede solicitar la eliminación de su
  propia cuenta llamando a un nuevo endpoint protegido con JWT.
- **RF-02:** La operación marca el campo `deleted_at` con la marca de tiempo
  actual. No elimina la fila ni ningún dato asociado (sesiones, OTPs, outbox).
- **RF-03:** Al eliminarse la cuenta, todas las sesiones activas del usuario
  quedan revocadas en la misma transacción.
- **RF-04:** Tras el borrado, la cuenta no puede autenticarse (login), ni usar
  sus tokens actuales, ni reenviar verificación. Cualquier intento de login
  devuelve el mismo error que credenciales inválidas (sin revelar que la cuenta
  está eliminada). Los access tokens activos se rechazan activamente en el
  filtro JWT verificando `deleted_at` en cada request.
- **RF-05:** El email del usuario eliminado queda inmediatamente disponible para
  un nuevo registro. La nueva cuenta empieza completamente desde cero (nuevo
  `id`, nuevo `friend_code`, sin relación con la cuenta anterior).
- **RF-06:** El sistema publica un evento de dominio `USER_DELETED` en el outbox
  siguiendo el patrón existente (`USER_REGISTERED`, `USER_LOGIN_OTP`). El payload
  incluye al menos `id` y `email` del usuario eliminado.
- **RF-07:** La respuesta exitosa a la eliminación devuelve `200 OK` con un
  mensaje confirmatorio y sin datos de usuario en el cuerpo.
- **RF-08:** La operación de borrado es idempotente: si la cuenta ya estaba
  eliminada y el token sigue siendo válido en ese instante, el endpoint responde
  `200 OK`.

### Migración de endpoint de registro

- **RF-09:** El endpoint de registro pasa a ser `POST /auth/register`. Devuelve
  `201 Created` con el mismo cuerpo que el endpoint actual. No hay consumidores
  activos conocidos de `POST /user/`, por lo que no se requiere coordinación
  previa.
- **RF-10:** El endpoint `POST /user/` se elimina. No se mantiene redirección
  ni período de compatibilidad.

### Campo `photo_url` en el registro

- **RF-11:** `RegisterUserCommand` acepta un campo opcional `photo_url`. Si se
  proporciona, se persiste en `users.photo_url` al crear la cuenta. Si se omite
  o se envía `null`, el campo queda vacío.
- **RF-12:** Las mismas reglas de validación de `photo_url` que aplica
  `UpdateProfileCommand` (`@ValidPhotoUrl`) se aplican en el registro.

## Fuera de alcance

- Recuperación o reactivación de cuentas eliminadas.
- Eliminación de cuentas por un administrador o rol distinto al propio usuario.
- Exportación de datos antes del borrado (GDPR data-portability).
- Período de gracia o borrado diferido (el borrado es inmediato).
- Cambio en la política de retención de sesiones, OTPs u outbox.
- Cualquier cambio en el flujo de login, 2FA, refresh o reset de contraseña.
- Rate limiting específico para el nuevo endpoint de eliminación (el filtro JWT
  es suficiente para este alcance).

## Flujo del cliente

### Eliminación de cuenta

1. El cliente envía `DELETE /auth/user/me` con `Authorization: Bearer <access_token>` válido.
2. El servicio revoca todas las sesiones activas del usuario y marca `deleted_at`, todo en una sola transacción.
3. El servicio publica el evento `USER_DELETED` en el outbox (payload: al menos `id` y `email`).
4. El servicio responde `200 OK` con mensaje confirmatorio.
5. A partir de ese momento:
   - Cualquier intento de login con ese email devuelve `401 Unauthorized` con el mensaje genérico de credenciales inválidas.
   - Cualquier request con el access token antiguo es rechazado activamente por el filtro JWT (`401 Unauthorized`).
6. El mismo email puede usarse para registrar una nueva cuenta desde cero.

### Registro (ruta migrada, con `photo_url` opcional)

1. El cliente envía `POST /auth/register` con `full_name`, `email`, `password`, `confirm_password` y opcionalmente `photo_url`.
2. Validación Bean Validation en el controlador (incluyendo `@ValidPhotoUrl` si se envía `photo_url`).
3. El use case verifica que el email no esté en uso por una cuenta activa, crea el usuario, persiste la foto si se proporcionó, genera el OTP de verificación de email y publica `USER_REGISTERED` al outbox.
4. La respuesta es `201 Created` con los datos del usuario (incluyendo `photo_url`).

## Datos y reglas de negocio

- `photo_url` en el registro es **opcional**. Se valida con `@ValidPhotoUrl`
  (URL absoluta con esquema `https`, máx. 2048 caracteres).
- Un email de cuenta eliminada puede usarse para un nuevo registro. La nueva
  cuenta no tiene ninguna relación con la anterior.
- Tras la eliminación, el usuario no puede re-autenticarse ni llamar a endpoints
  protegidos. Los access tokens emitidos antes del borrado son rechazados
  activamente.
- La operación de borrado es **atómica**: la revocación de sesiones y el
  marcado de `deleted_at` ocurren en la misma transacción o ninguno ocurre.
- La operación de borrado es **idempotente**: si la cuenta ya estaba eliminada,
  el endpoint responde `200 OK` sin error.

## Comportamiento y casos alternativos

| Situación | Comportamiento esperado |
| --- | --- |
| Token JWT ausente o inválido en `DELETE /auth/user/me` | `401 Unauthorized` — "No autorizado." |
| Cuenta eliminada intenta autenticarse | `401 Unauthorized` — mensaje genérico de credenciales inválidas |
| Access token válido usado tras el borrado de la cuenta | `401 Unauthorized` (filtro JWT verifica `deleted_at`) |
| `POST /auth/register` con email de cuenta eliminada | `201 Created` con nueva cuenta completamente nueva |
| `photo_url` con formato inválido en el registro | `400 Bad Request` con detalle del campo |
| `POST /user/` tras la migración | `404 Not Found` |
| Eliminación cuando el usuario no tiene sesiones activas | `200 OK` — cero sesiones que revocar, sin error |
| Cuenta ya eliminada; segundo request de borrado con token aún válido | `200 OK` — operación idempotente |

**Puntos de la guía no aplicables y motivo:**
- Internacionalización: los mensajes siguen la convención actual (español fijo).
- Paginación: ninguna operación devuelve colecciones.
- Trabajo en segundo plano: el borrado es síncrono; el outbox sigue el patrón existente.
- Cancelación/desconexión del cliente: comportamiento estándar de Spring MVC.

## Restricciones del pedido

- El borrado es **soft delete**: `deleted_at` en la tabla `users`. No se borra la fila.
- El endpoint de registro pasa a `POST /auth/register` y `POST /user/` desaparece sin período de compatibilidad.
- `photo_url` es opcional en el registro.
- La validación de `photo_url` reutiliza `@ValidPhotoUrl` (ya existente).
- Persistencia: PostgreSQL + Flyway. Se requiere al menos una migración para resolver la restricción `UNIQUE` del email, de modo que el mismo correo pueda usarse en una nueva cuenta tras el borrado.
- La verificación de `deleted_at` en el filtro JWT se añade para rechazar activamente tokens de cuentas eliminadas.

## Criterios de aceptación

### Eliminación de cuenta

- **CA-01 · RF-01, RF-02, RF-03:** Dado un usuario autenticado con sesiones activas, cuando llama a `DELETE /auth/user/me` con un token válido, entonces la respuesta es `200 OK`, `deleted_at` queda poblado en la fila del usuario, y todas sus sesiones tienen `revoked = true`.
- **CA-02 · RF-04:** Dado un usuario cuya cuenta ha sido eliminada, cuando intenta autenticarse con `POST /auth/login`, entonces recibe `401 Unauthorized` con el mismo mensaje que para credenciales incorrectas.
- **CA-03 · RF-04:** Dado un usuario cuya cuenta ha sido eliminada, cuando usa el access token previo en cualquier endpoint protegido, entonces recibe `401 Unauthorized`.
- **CA-04 · RF-05, RF-09:** Dado el email de una cuenta eliminada, cuando se llama a `POST /auth/register` con ese email, entonces se crea una nueva cuenta con un `id` y `friend_code` distintos y la respuesta es `201 Created`.
- **CA-05 · RF-06:** Dado un usuario que elimina su cuenta, cuando la operación concluye, entonces existe en el outbox un evento `USER_DELETED` con el `id` y `email` del usuario.
- **CA-06 · RF-07:** Dado un usuario autenticado, cuando llama a `DELETE /auth/user/me`, entonces el cuerpo de la respuesta contiene solo el mensaje confirmatorio, sin datos del usuario.
- **CA-07 · RF-01:** Dado un request sin `Authorization` o con token inválido, cuando llama a `DELETE /auth/user/me`, entonces la respuesta es `401 Unauthorized`.
- **CA-08 · RF-08:** Dado que la cuenta ya está eliminada y el token aún no ha expirado, cuando el usuario llama de nuevo a `DELETE /auth/user/me`, entonces la respuesta es `200 OK`.

### Migración de endpoint

- **CA-09 · RF-09:** Dado el payload habitual de registro, cuando se llama a `POST /auth/register`, entonces la respuesta es `201 Created` con los datos del usuario.
- **CA-10 · RF-10:** Dado cualquier payload, cuando se llama a `POST /user/`, entonces la respuesta es `404 Not Found`.

### `photo_url` en registro

- **CA-11 · RF-11:** Dado un payload de registro con `photo_url` válida, cuando se llama a `POST /auth/register`, entonces el campo se persiste y aparece en la respuesta bajo la clave `photo_url`.
- **CA-12 · RF-11:** Dado un payload de registro sin `photo_url`, cuando se llama a `POST /auth/register`, entonces la respuesta incluye `"photo_url": null`.
- **CA-13 · RF-12:** Dado un payload de registro con `photo_url` de formato inválido, cuando se llama a `POST /auth/register`, entonces la respuesta es `400 Bad Request` con un detalle indicando el campo `photo_url`.

## Cómo se comprueba el comportamiento

<!-- La selección de tests, herramientas, comandos y evidencias se desarrolla en PLAN.md.
No marques los criterios como superados durante la especificación. -->

| Criterio | Condiciones y pasos | Resultado esperado |
| --- | --- | --- |
| CA-01 | Usuario autenticado con sesión activa; llamar `DELETE /auth/user/me` | `200 OK`; `deleted_at` ≠ null; todas las sesiones `revoked = true` |
| CA-02 | Cuenta eliminada; llamar `POST /auth/login` con credenciales válidas | `401` con mensaje genérico |
| CA-03 | Cuenta eliminada; usar access token previo en endpoint protegido | `401 Unauthorized` |
| CA-04 | Email de cuenta eliminada; llamar `POST /auth/register` | `201 Created`; nuevo `id` y `friend_code` distintos al anterior |
| CA-05 | Tras eliminación; consultar outbox | Existe fila con `event_type = 'USER_DELETED'`, `aggregate_id` = id del usuario |
| CA-06 | Usuario autenticado; llamar `DELETE /auth/user/me` | `200 OK`; cuerpo sin datos de usuario |
| CA-07 | Sin `Authorization`; llamar `DELETE /auth/user/me` | `401 Unauthorized` |
| CA-08 | Cuenta ya eliminada; llamar de nuevo `DELETE /auth/user/me` con token aún válido | `200 OK` |
| CA-09 | Payload válido; llamar `POST /auth/register` | `201 Created` |
| CA-10 | Cualquier payload; llamar `POST /user/` | `404 Not Found` |
| CA-11 | Payload con `photo_url` válida; llamar `POST /auth/register` | `photo_url` en respuesta y en BD |
| CA-12 | Payload sin `photo_url`; llamar `POST /auth/register` | `"photo_url": null` en respuesta |
| CA-13 | Payload con `photo_url` inválida; llamar `POST /auth/register` | `400` con detalle del campo `photo_url` |

## Decisiones pendientes

Ninguna. Todas las decisiones han sido resueltas y reflejadas en el documento:

| ID | Decisión tomada |
| --- | --- |
| DP-01 | Re-registro empieza desde cero (nuevo `id`, nuevo `friend_code`, sin relación con la cuenta anterior) |
| DP-02 | Se publica `USER_DELETED` al outbox; payload incluye al menos `id` y `email` |
| DP-03 | Los access tokens activos se rechazan activamente verificando `deleted_at` en el filtro JWT |
| DP-04 | No hay consumidores activos de `POST /user/`; el endpoint se elimina directamente |
| DP-05 | La operación de borrado es idempotente: si la cuenta ya está eliminada, responde `200 OK` |

<!-- ANTES DE SOLICITAR APROBACIÓN
Comprueba que el alcance está acordado, los flujos son coherentes, los puntos
backend relevantes están cubiertos y cada requisito tiene criterios comprobables.
Resuelve las dudas y los marcadores pendientes. Mantén el diseño técnico en PLAN.md.
-->
