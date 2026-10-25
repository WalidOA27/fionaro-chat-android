# Fionaro Chat

**Fionaro Chat** es un fork independiente de [Element X Android](https://github.com/element-hq/element-x-android), un cliente Matrix construido con el [Matrix Rust SDK](https://github.com/matrix-org/matrix-rust-sdk), Jetpack Compose y Appyx. Se conecta a un servidor Matrix propio y no usa servicios de Google.

> Este proyecto **no está afiliado** a Element ni al proyecto Element X.

## Características

- Cliente Matrix completo: chats, salas, llamadas y notificaciones.
- Sin Firebase ni Google Play Services. El sabor `fdroid` es el predeterminado.
- Notificaciones con UnifiedPush mediante un servidor ntfy propio.
- Llamadas con Element Call y LiveKit.
- Interfaz en español.
- Búsqueda de usuarios del servidor desde «Nuevo chat».

## Privacidad

- No incluye PostHog, Sentry, Firebase Crashlytics, Google Analytics ni otros SDK de terceros.
- La app envía estadísticas de uso anónimas (nombres de eventos y pantallas, y un identificador aleatorio del dispositivo, nunca contenido de mensajes) únicamente a un servidor Umami propio. Se pueden desactivar en Ajustes → Analíticas.
- Todo el tráfico de Matrix, llamadas, notificaciones y analíticas va a infraestructura controlada por el proyecto.

## Instalación

Descarga el APK firmado desde [Releases](https://github.com/WalidOA27/fionaro-chat-android/releases/latest), o añade el repositorio a [Obtainium](https://github.com/ImranR98/Obtainium) para recibir las actualizaciones.

## Compilar

Requisitos: JDK 21, Android SDK (API 37, `minSdk` 24).

```bash
# Versión de depuración (no necesita claves)
./gradlew assembleFdroidDebug

# Versión de release firmada
export FIONARO_KEYSTORE_PATH=/ruta/al/keystore.jks
export FIONARO_KEYSTORE_PASSWORD=...
export FIONARO_KEY_PASSWORD=...
./gradlew assembleFdroidRelease
```

Las tareas de release fallan con un error explícito si falta alguna de las tres variables. Las claves nunca se guardan en el repositorio. El workflow `release.yml` las toma de los secrets de GitHub `FIONARO_KEYSTORE_BASE64`, `FIONARO_KEYSTORE_PASSWORD` y `FIONARO_KEY_PASSWORD`.

El APK queda en `app/build/outputs/apk/fdroid/release/`.

## Documentación

- [PROJECT-REFERENCE.md](PROJECT-REFERENCE.md): arquitectura, ramas, versiones y convenciones del fork.
- [AGENTS.md](AGENTS.md): convenciones de código heredadas de Element X.

## Licencia

Este fork hereda la doble licencia de Element X Android:

- GNU AGPL v3 o posterior, ver [LICENSE](LICENSE).
- Licencia comercial de Element.

Este fork no concede ningún derecho sobre la marca, los logotipos ni la infraestructura de Element. Todo el branding de Element se ha sustituido por el de Fionaro.

---

Basado en [element-hq/element-x-android](https://github.com/element-hq/element-x-android) en la etiqueta `v26.06.4`.
