# SPEC: Actualización de perfil de usuario

**Estado:** Aprobado <!-- Borrador | En revisión | Aprobada -->

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

Un usuario autenticado de SplitFlow puede actualizar su propio perfil desde la aplicación.
Los datos actualizables son:

- **Contraseña** — el usuario puede cambiar su contraseña actual por una nueva (flujo
  autenticado), o restablecerla cuando la ha olvidado (flujo por OTP).
- **Datos básicos** — nombre completo (`full_name`) y dirección de email (`email`).
- **Friend code** — el usuario puede regenerar su código de amigo para compartirlo;
  las relaciones de amistad existentes no se ven afectadas.

## Situación actual

El servicio de autenticación gestiona usuarios, sesiones y eventos de dominio.
Actualmente expone los siguientes recursos:

- `POST /user/` — registro de una cuenta nueva.
- `POST /auth/verify-email` — verificación del correo con OTP.
- `POST /auth/login` + `POST /auth/login/verify-2fa` — autenticación en dos pasos por email.
- `POST /auth/refresh` / `POST /auth/logout` — gestión de sesión.

La tabla `otp` ya define el propósito `PASSWORD_RESET` (enum en BD), pero no existe ningún
endpoint ni use case que lo implemente. No existe ningún endpoint para modificar datos de
un usuario ya registrado.

## Dentro del alcance

- **RF-01 — Cambio de contraseña autenticado:** el usuario proporciona su contraseña actual
  y la nueva; el servicio la verifica, aplica las mismas reglas de complejidad existentes,
  actualiza el hash y revoca todas las sesiones activas excepto la actual.
- **RF-01b — Restablecimiento de contraseña por olvido:** el usuario que no recuerda su
  contraseña solicita un OTP enviado a su email registrado; tras verificarlo, puede establecer
  una nueva contraseña. Todas las sesiones activas quedan revocadas y sus access tokens
  asociados quedan inválidos. El reset está disponible tanto para usuarios verificados como
  para no verificados; si el usuario no estaba verificado, al hacer login después del reset
  deberá verificar su email antes de completar la autenticación.
- **RF-02 — Cambio de nombre completo:** el usuario actualiza su `full_name` con las mismas
  reglas de validación existentes.
- **RF-03 — Cambio de email:** el usuario proporciona un nuevo email; el servicio verifica
  que no esté en uso y exige re-verificación OTP antes de considerar la cuenta completamente
  verificada. Mientras el nuevo email no esté verificado (`verified_at = null`), el usuario
  no puede iniciar sesión.
- **RF-04 — Regeneración de friend code:** el servicio genera un nuevo código aleatorio de
  10 caracteres `[A-Z0-9]` y lo persiste garantizando unicidad. Las relaciones de amistad
  preexistentes no se ven afectadas.
- **RF-05 — Reenvío de OTP de verificación de email:** el usuario puede solicitar que se
  le envíe un nuevo OTP de verificación de email (p.ej. si el código del registro o del
  cambio de email no llegó o expiró). El endpoint es público y no revela si el email existe.
- **RF-06 — Actualización de foto de perfil:** el usuario puede establecer o eliminar la URL
  de su foto de perfil (`photo_url`). El servicio almacena la URL proporcionada sin procesar
  ni almacenar el archivo de imagen.

## Fuera de alcance

- Migración del endpoint `POST /user/` (queda como está).
- Eliminación de cuenta.
- Carga, almacenamiento o procesamiento de archivos de imagen (el servicio solo persiste la URL).
- Gestión de relaciones de amistad.
- Sincronización activa con otros microservicios más allá del outbox.
- Administración de perfiles por parte de otros roles.

## Flujo del cliente

### RF-01 · Cambio de contraseña autenticado

1. El cliente envía `PATCH /auth/user/me/password` con `Authorization: Bearer <access_token>`
   y el cuerpo `{ "current_password", "new_password", "confirm_new_password" }`.
2. El servicio verifica el JWT y extrae `user_id` del claim `sub`.
3. Carga el usuario y compara `current_password` con el hash almacenado (BCrypt).
4. Si la contraseña actual es incorrecta, responde `401 Unauthorized` con mensaje genérico.
5. Verifica que `new_password == confirm_new_password` y que cumple las reglas de complejidad.
6. Verifica que `new_password` **no** sea igual a la contraseña actual (comparación BCrypt).
   Si lo es, responde `400 Bad Request` con mensaje gracioso (p.ej. _"Tu nueva contraseña
   no puede ser la misma que ya utilizas 🙃"_).
7. Actualiza `password_hash` y `updated_at`.
8. Revoca todas las sesiones activas del usuario **excepto** la sesión asociada al access
   token usado en la petición.
9. Responde `200 OK` con mensaje de confirmación.

### RF-01b · Restablecimiento de contraseña por olvido

1. El cliente envía `POST /auth/password-reset/request` con `{ "email" }` (sin JWT).
2. El servicio busca el email. Si no existe, responde `200 OK` sin revelar que el email
   no existe.
3. Si el usuario existe (verificado o no), invalida cualquier OTP `PASSWORD_RESET` pendiente
   anterior, genera uno nuevo (TTL 15 minutos, máx. 5 intentos) y publica al outbox para
   que el notificador lo envíe por email.
4. El cliente envía `POST /auth/password-reset/confirm` con
   `{ "email", "otp_code", "new_password", "confirm_new_password" }`.
5. El servicio verifica el OTP con las mismas reglas que los OTP existentes (estado `PENDING`,
   no expirado, ≤ 5 intentos).
6. Verifica que `new_password == confirm_new_password` y cumple las reglas de complejidad.
7. Actualiza `password_hash` y `updated_at`.
8. Revoca **todas** las sesiones activas del usuario; los access tokens asociados a esas
   sesiones quedan inválidos (ver §Restricciones del pedido — invalidación de access tokens).
9. Responde `200 OK`.

> **Nota:** Si el usuario que realizó el reset no tenía el email verificado (`verified_at = null`),
> al intentar iniciar sesión después del reset el flujo de login le exigirá verificar el email
> antes de completar la autenticación (mismo comportamiento que el flujo de registro sin verificar).

### RF-02 · Cambio de nombre completo

1. El cliente envía `PATCH /auth/user/me` con `Authorization: Bearer <access_token>`
   y `{ "full_name": "..." }` (al menos un campo requerido en el body).
2. El servicio verifica el JWT, valida `full_name` con las mismas reglas de `FullNameValidator`.
3. Actualiza `full_name` y `updated_at`.
4. Responde `200 OK` con los datos actualizados del usuario.

### RF-03 · Cambio de email

1. El cliente envía `PATCH /auth/user/me` con `{ "email": "nuevo@ejemplo.com" }`.
2. El servicio verifica el JWT y comprueba que el email no esté ya registrado por otro usuario.
3. Si el email pertenece a otro usuario, responde `409 Conflict`.
4. Actualiza `email` y `updated_at`; pone `verified_at = null`.
5. Invalida cualquier OTP `EMAIL_VERIFICATION` pendiente y genera uno nuevo para el nuevo email.
   Publica al outbox.
6. A partir de este momento el usuario no puede iniciar sesión hasta verificar el nuevo email
   con `POST /auth/verify-email` (mismo flujo que tras el registro).
7. Las sesiones de refresh actualmente activas permanecen intactas, pero el próximo login
   requerirá el nuevo email verificado.
8. Responde `200 OK` con los datos actualizados.

### RF-04 · Regeneración de friend code

1. El cliente envía `POST /auth/user/me/friend-code` con `Authorization: Bearer <access_token>`.
2. El servicio genera un nuevo código con `FriendCodeGenerator`, garantizando unicidad
   (reintenta en colisión, igual que en el registro).
3. Actualiza `friend_code` y `updated_at`.
4. Responde `200 OK` con el nuevo código.

### RF-05 · Reenvío de OTP de verificación de email

1. El cliente envía `POST /auth/resend-verification` con `{ "email" }` (sin JWT).
2. El servicio busca el email. Si no existe o el usuario ya está verificado
   (`verified_at != null`), responde `200 OK` sin revelar el motivo.
3. Si el usuario existe y no está verificado, invalida cualquier OTP `EMAIL_VERIFICATION`
   pendiente anterior, genera uno nuevo y publica al outbox.
4. Responde `200 OK`.

### RF-06 · Actualización de foto de perfil

1. El cliente envía `PATCH /auth/user/me` con `Authorization: Bearer <access_token>`
   y `{ "photo_url": "https://..." }` (puede ser `null` para eliminar la foto).
2. El servicio verifica el JWT y valida el valor de `photo_url` si no es `null`.
3. Actualiza `photo_url` y `updated_at`.
4. Responde `200 OK` con los datos actualizados del usuario.

## Datos y reglas de negocio

### Contraseña (RF-01 y RF-01b)

- Debe cumplir: ASCII imprimible (0x20–0x7E), 10–128 caracteres, al menos una mayúscula,
  una minúscula, un dígito y un símbolo.
- La contraseña nueva y su confirmación deben ser idénticas.
- La contraseña nueva **no puede ser igual a la contraseña actual** (verificación BCrypt).
  Mensaje de error: _"Tu nueva contraseña no puede ser la misma que ya utilizas"_ (tono
  informal/gracioso).
- En RF-01b, el error de OTP incorrecto/expirado es genérico y no revela si el email existe.
- El OTP de `PASSWORD_RESET` sigue las mismas reglas que `LOGIN_2FA`: TTL 15 minutos,
  máx. 5 intentos, propósito distinto almacenado en `otp.purpose`.

### Foto de perfil (RF-06)

- El campo `photo_url` es opcional; puede ser `null` (sin foto).
- Si se proporciona un valor no nulo, debe ser una URL absoluta con esquema `https`.
- Longitud máxima: 2048 caracteres.
- El servicio no valida que la URL apunte a una imagen accesible ni la descarga;
  solo persiste el valor.

### Nombre completo (RF-02)

- Solo letras Unicode, palabras separadas por un espacio simple, cada palabra de ≥ 2 letras.
- Máximo 150 caracteres.

### Email (RF-03)

- Dirección de email válida (formato RFC 5321), máximo 254 caracteres.
- No puede estar registrado por otro usuario activo → `409 Conflict`.
- Tras la actualización: `verified_at = null`, login bloqueado hasta verificar.

### Friend code (RF-04)

- 10 caracteres `[A-Z0-9]`, generado con `SecureRandom` (implementado en `FriendCodeGenerator`).
- Único en `users.friend_code`; reintentar en colisión.

### Autorización

- `PATCH /auth/user/me`, `PATCH /auth/user/me/password` y `POST /auth/user/me/friend-code`
  requieren JWT válido en `Authorization: Bearer`. El `user_id` se extrae del claim `sub`.
- `POST /auth/password-reset/request` y `POST /auth/password-reset/confirm` son públicos
  (sin JWT).
- JWT expirado o inválido en endpoints protegidos → `401 Unauthorized`.

### Invalidación de sesiones y access tokens

- **RF-01 (autenticado):** revocar todas las sesiones del usuario excepto la actualmente
  asociada a la petición; los access tokens ligados a las sesiones revocadas quedan inválidos.
- **RF-01b (por olvido):** revocar **todas** las sesiones activas del usuario; todos los
  access tokens asociados quedan inválidos.
- **RF-03 (cambio de email):** no se revocan sesiones; el access token vigente expira
  naturalmente (TTL 1 hora); el re-login exigirá el nuevo email verificado.

> La invalidación de access tokens implica que el servicio debe ser capaz de rechazar un
> JWT formalmente válido cuya sesión ha sido revocada. El mecanismo técnico concreto
> (lista de JTI revocados, claim de versión en el token, o validación contra BD en cada
> petición) se decide en PLAN.md.

### Idempotencia

- `PATCH /auth/user/me` con el mismo valor ya almacenado actualiza `updated_at` y devuelve
  `200 OK` sin error.
- `POST /auth/user/me/friend-code` siempre genera un nuevo código (no es idempotente).
- `POST /auth/password-reset/request` siempre invalida el OTP anterior y emite uno nuevo;
  siempre responde `200 OK`.

## Comportamiento y casos alternativos

| Situación | Comportamiento esperado |
| --- | --- |
| JWT ausente o inválido en endpoints protegidos | `401 Unauthorized` con mensaje genérico |
| JWT expirado | `401 Unauthorized`; el cliente renueva con `/auth/refresh` |
| Contraseña actual incorrecta (RF-01) | `401 Unauthorized` con mensaje genérico |
| `new_password` no cumple complejidad | `400 Bad Request` con detalle de campo `new_password` |
| `new_password` igual a la contraseña actual | `400 Bad Request` con mensaje gracioso |
| `new_password != confirm_new_password` | `400 Bad Request` con detalle de campo |
| Email ya registrado por otro usuario (RF-03) | `409 Conflict` |
| OTP de password reset incorrecto | `400 Bad Request` con mensaje genérico (no revela si el email existe) |
| OTP de password reset expirado o > 5 intentos | `429 Too Many Requests` |
| `POST /auth/password-reset/request` con email no registrado | `200 OK` sin revelar si el usuario existe |
| `POST /auth/password-reset/request` con usuario no verificado | `200 OK`; se genera OTP; tras el reset el login exigirá verificación de email |
| `POST /auth/resend-verification` con email no registrado o ya verificado | `200 OK` sin revelar el motivo |
| Access token presentado tras revocación de su sesión (RF-01 / RF-01b) | `401 Unauthorized` |
| Colisión de friend code tras N reintentos | `500 Internal Server Error` con mensaje genérico |
| `photo_url` con esquema distinto a `https` o longitud > 2048 (RF-06) | `400 Bad Request` con detalle de campo `photo_url` |
| `PATCH /auth/user/me` sin ningún campo válido | `400 Bad Request` |
| Error de base de datos durante la actualización | `500 Internal Server Error` con mensaje genérico |
| Usuario con `verified_at = null` intenta login | `401 Unauthorized` (igual que usuario no verificado en el flujo actual) |

**Puntos de la guía no aplicables:**
- *Internacionalización:* los campos no requieren localización de formato.
- *Long-running work / cancellation:* todas las operaciones son síncronas.
- *Paginación:* no hay listados.

## Restricciones del pedido

- La arquitectura en capas existente (controller → use case → repository) debe respetarse.
- El campo `photo_url` se añade a la tabla `users` mediante una nueva migración Flyway
  (`V5__add_photo_url_to_users.sql`). La columna admite `NULL` y no tiene restricción `UNIQUE`.
- Reutilizar `PasswordValidator`, `FullNameValidator` y `FriendCodeGenerator` existentes.
- El mecanismo de revocación de sesiones debe usar el campo `revoked` de la tabla `session`,
  igual que `LogoutUseCase`.
- **Invalidación de access tokens:** cuando una sesión se revoca (RF-01 / RF-01b), el access
  token JWT asociado debe quedar inválido aunque su firma y expiración sean correctas. El
  mecanismo concreto se elige en PLAN.md, pero el resultado observable debe ser que el token
  recibido sea rechazado con `401 Unauthorized` en cualquier endpoint protegido.
- El patrón outbox debe usarse para cualquier evento de dominio publicado (OTPs de reset y
  verificación de nuevo email).
- No se usa field injection; únicamente constructor injection.
- Los endpoints públicos nuevos (`POST /auth/password-reset/request`,
  `POST /auth/password-reset/confirm`, `POST /auth/resend-verification`) deben añadirse
  explícitamente a `permitAll` en `SecurityConfig`.
- `POST /user/` (registro) no se modifica.

## Criterios de aceptación

- **CA-01 · RF-01:** Dado un usuario autenticado con JWT válido, cuando envía
  `PATCH /auth/user/me/password` con `current_password` correcta, `new_password` distinta
  de la actual que cumple las reglas, y `confirm_new_password` igual a `new_password`,
  entonces `200 OK` y el hash almacenado en BD es diferente al anterior.
- **CA-02 · RF-01:** Dado un usuario autenticado, cuando `current_password` es incorrecta,
  entonces `401 Unauthorized` con mensaje genérico.
- **CA-03 · RF-01:** Dado un usuario autenticado, cuando `new_password` no cumple las reglas
  de complejidad, entonces `400 Bad Request` con detalle de campo `new_password`.
- **CA-04 · RF-01:** Dado un usuario autenticado, cuando `new_password != confirm_new_password`,
  entonces `400 Bad Request` con detalle de campo.
- **CA-05 · RF-01:** Dado un usuario autenticado, cuando `new_password` es igual a la
  contraseña actual, entonces `400 Bad Request` con el mensaje gracioso.
- **CA-06 · RF-01:** Dado un usuario autenticado que cambia su contraseña exitosamente, cuando
  se consultan sus sesiones en BD, entonces todas están revocadas excepto la asociada a la
  petición.
- **CA-07 · RF-01b:** Dado un email registrado (verificado o no), cuando el usuario llama
  `POST /auth/password-reset/request`, entonces `200 OK` y se genera un OTP `PASSWORD_RESET`
  en la BD.
- **CA-08 · RF-01b:** Dado un email no registrado, cuando el usuario llama
  `POST /auth/password-reset/request`, entonces `200 OK` sin generar OTP ni revelar que el
  email no existe.
- **CA-09 · RF-01b:** Dado un OTP de password reset válido, cuando el usuario llama
  `POST /auth/password-reset/confirm` con datos correctos, entonces `200 OK`, el hash cambia
  y todas las sesiones del usuario quedan revocadas.
- **CA-09b · RF-01b:** Dado que el reset se completó y el usuario tenía `verified_at = null`,
  cuando intenta iniciar sesión, entonces el flujo de login le exige verificar el email antes
  de completar la autenticación.
- **CA-09c · RF-01b:** Dado que el reset revocó todas las sesiones, cuando se usa un access
  token previamente emitido, entonces `401 Unauthorized`.
- **CA-10 · RF-01b:** Dado un OTP de password reset incorrecto (< 5 intentos), entonces
  `400 Bad Request` con mensaje genérico. Dado OTP expirado o ≥ 5 intentos, entonces
  `429 Too Many Requests`.
- **CA-11 · RF-02:** Dado un usuario autenticado, cuando envía `PATCH /auth/user/me` con un
  `full_name` válido, entonces `200 OK` y el nombre queda actualizado en BD.
- **CA-12 · RF-02:** Dado un usuario autenticado, cuando envía un `full_name` inválido
  (dígitos, palabra de 1 letra, etc.), entonces `400 Bad Request` con detalle de campo.
- **CA-13 · RF-03:** Dado un usuario autenticado, cuando envía un `email` nuevo no existente
  en el sistema, entonces `200 OK`, el campo se actualiza, `verified_at = null` y se genera
  un OTP `EMAIL_VERIFICATION` para el nuevo email.
- **CA-14 · RF-03:** Dado un usuario cuyo email fue cambiado pero aún no verificado, cuando
  intenta iniciar sesión, entonces `401 Unauthorized`.
- **CA-15 · RF-03:** Dado un usuario autenticado, cuando envía un `email` ya registrado por
  otro usuario, entonces `409 Conflict`.
- **CA-16 · RF-04:** Dado un usuario autenticado, cuando llama
  `POST /auth/user/me/friend-code`, entonces `200 OK` con un nuevo friend code de 10
  caracteres `[A-Z0-9]` distinto del anterior.
- **CA-17 · Autorización:** Dado un request sin `Authorization` o con token inválido/expirado
  a cualquier endpoint protegido, entonces `401 Unauthorized`.
- **CA-20 · RF-06:** Dado un usuario autenticado, cuando envía `PATCH /auth/user/me` con una
  `photo_url` válida (https, ≤ 2048 caracteres), entonces `200 OK` y el campo queda
  actualizado en BD.
- **CA-21 · RF-06:** Dado un usuario autenticado, cuando envía `photo_url: null`, entonces
  `200 OK` y el campo queda a `null` en BD (foto eliminada).
- **CA-22 · RF-06:** Dado un usuario autenticado, cuando envía una `photo_url` con esquema
  `http` o longitud superior a 2048, entonces `400 Bad Request` con detalle de campo.
- **CA-18 · RF-05:** Dado un email registrado con `verified_at = null`, cuando el usuario
  llama `POST /auth/resend-verification`, entonces `200 OK` y se genera un nuevo OTP
  `EMAIL_VERIFICATION` en la BD (el anterior queda invalidado).
- **CA-19 · RF-05:** Dado un email no registrado o ya verificado, cuando el usuario llama
  `POST /auth/resend-verification`, entonces `200 OK` sin generar OTP ni revelar el motivo.

## Cómo se comprueba el comportamiento

| Criterio | Condiciones y pasos | Resultado esperado |
| --- | --- | --- |
| CA-01 | JWT válido; `current_password` correcta; `new_password` cumple reglas; `confirm` coincide | HTTP 200; hash en BD distinto al anterior |
| CA-02 | JWT válido; `current_password` incorrecta | HTTP 401; mensaje genérico |
| CA-03 | JWT válido; `new_password` sin símbolo | HTTP 400; detalle en campo `new_password` |
| CA-04 | JWT válido; `new_password ≠ confirm_new_password` | HTTP 400; detalle de campo |
| CA-05 | JWT válido; `new_password == current_password` | HTTP 400; mensaje gracioso |
| CA-06 | JWT válido; cambio exitoso; consultar tabla `session` del usuario | Solo la sesión actual con `revoked = false`; resto `revoked = true` |
| CA-07 | Email registrado (verificado o no); `/auth/password-reset/request` | HTTP 200; OTP con `purpose = PASSWORD_RESET` en BD |
| CA-08 | Email no registrado; `/auth/password-reset/request` | HTTP 200; ningún OTP generado en BD |
| CA-09 | OTP `PASSWORD_RESET` válido; `new_password` cumple reglas | HTTP 200; hash actualizado; todas las sesiones revocadas |
| CA-09b | Usuario no verificado completa reset; intento de login | Flujo de login exige verificación de email antes de emitir tokens |
| CA-09c | Access token previo al reset; petición a endpoint protegido | HTTP 401 |
| CA-10 | OTP incorrecto (3 intentos); luego OTP expirado | HTTP 400 en intentos < 5; HTTP 429 al agotar intentos |
| CA-11 | JWT válido; `full_name` válido en `PATCH /auth/user/me` | HTTP 200; nombre en BD actualizado |
| CA-12 | JWT válido; `full_name` con dígito | HTTP 400; detalle de campo |
| CA-13 | JWT válido; email nuevo no existente en BD | HTTP 200; email actualizado; `verified_at = null`; OTP `EMAIL_VERIFICATION` creado |
| CA-14 | Usuario con `verified_at = null`; intento de login | HTTP 401 |
| CA-15 | JWT válido; email ya registrado por otro usuario | HTTP 409 |
| CA-16 | JWT válido; `POST /auth/user/me/friend-code` | HTTP 200; friend code nuevo de 10 chars `[A-Z0-9]` |
| CA-17 | Request sin `Authorization` a endpoint protegido | HTTP 401 |
| CA-18 | Email con `verified_at = null`; `/auth/resend-verification` | HTTP 200; nuevo OTP `EMAIL_VERIFICATION` en BD; anterior invalidado |
| CA-19 | Email no registrado o ya verificado; `/auth/resend-verification` | HTTP 200; sin cambios en BD |
| CA-20 | JWT válido; `photo_url` con `https` y ≤ 2048 chars | HTTP 200; campo actualizado en BD |
| CA-21 | JWT válido; `photo_url: null` | HTTP 200; campo `null` en BD |
| CA-22 | JWT válido; `photo_url` con esquema `http` | HTTP 400; detalle de campo |

## Decisiones pendientes

Ninguna. Todas las decisiones funcionales han sido resueltas:

| Decisión | Resolución |
| --- | --- |
| Sesiones tras cambio de contraseña autenticado | Revocar todas excepto la sesión actualmente en uso; sus access tokens quedan inválidos |
| Sesiones tras reset por olvido | Revocar todas; sus access tokens quedan inválidos |
| Restablecimiento por olvido en esta spec | Incluido (RF-01b) |
| Password reset para usuarios no verificados | Permitido; al hacer login tras el reset se exige verificación de email |
| Re-verificación de email | Sí: `verified_at = null`, OTP al nuevo email, login bloqueado |
| Diseño de rutas | `PATCH /auth/user/me`, `PATCH /auth/user/me/password`, `POST /auth/user/me/friend-code`, `POST /auth/password-reset/request`, `POST /auth/password-reset/confirm`, `POST /auth/resend-verification` |
| Nueva contraseña igual a la actual | No permitida; mensaje gracioso en el error |
| Re-autenticación adicional en endpoints protegidos | No requerida; el JWT válido es suficiente |
| Login durante re-verificación de email | Bloqueado hasta verificar |
| Invalidación de access tokens al revocar sesión | Requerida; mecanismo técnico a definir en PLAN.md |
| Reenvío de OTP de verificación (RF-05) | Incluido; endpoint público `POST /auth/resend-verification` |
| Foto de perfil (RF-06) | Incluida; campo `photo_url` nullable en `users`; solo `https`; máx. 2048 chars |

<!-- ANTES DE SOLICITAR APROBACIÓN
Comprueba que el alcance está acordado, los flujos son coherentes, los puntos
backend relevantes están cubiertos y cada requisito tiene criterios comprobables.
Resuelve las dudas y los marcadores pendientes. Mantén el diseño técnico en PLAN.md.
-->
