# Done — Fionaro Chat: Incidentes resueltos

> Registro de incidentes y arreglos aplicados a la infraestructura Fionaro.
> Última actualización: 2026-08-03

---

## Incidente 2026-08-03: Media rota + call.fionaro.pw 502 tras reboot

### Síntomas reportados
- "Fionaro Chat no funciona, no se mandan los mensajes, hay alertas"
- Media (imágenes/archivos) no se subía — `PermissionError` repetido en logs de Synapse
- `call.fionaro.pw` devolvía **502**
- Alertas Prometheus: scanopy + flaresolverr (NO relacionados con Fionaro)

### Diagnóstico

**1. Los mensajes de texto NUNCA dejaron de enviarse** — HTTP 200 en `PUT /send/m.room.encrypted` durante todo el incidente. Lo que fallaba era la **subida de media**.

**2. Causa raíz media:** El homelab se reinició (2 de agosto 01:19 local). Docker recreó los contenedores **antes de que el RAID md0 se montara** sobre `/mnt/storage` (el fstab usa `nofail` → el boot no bloquea).

- Los binds de Docker se anclan al inode del path en el momento de creación
- Synapse quedó escribiendo a un **directorio fantasma** en el disco raíz `nvme0n1p3` (root-owned, uid 991 no podía crear `local_content`)
- El contenido real (164 carpetas, 79MB) estaba **intacto en md0**, pero invisible para el contenedor
- Evidencia: `mountinfo` del contenedor mostraba `/dev/nvme0n1p3` en vez de `/dev/md0`; `touch` en contenedor no aparecía en el host

**3. Causa raíz call.fionaro.pw (502):** el recreate tras el reboot cambió la IP de `element_call` en la red `traefik_proxy` (172.20.0.22 → **172.20.0.15**) y Traefik tenía la IP vieja hardcodeada en `elementcall.yml`.

### Comandos de arreglo aplicados

```bash
# 1. Recrear Synapse con md0 YA montado (re-ancla los binds al RAID)
ssh homelab "cd /home/walid/comunicacion/matrix && docker compose up -d --force-recreate synapse"

# 2. Verificar que el bind apunta a md0
docker exec matrix_synapse sh -c 'grep media_store /proc/self/mountinfo'   # → /dev/md0 rw

# 3. Fix call.fionaro.pw: actualizar IP en Traefik (hot-reload automático)
ssh homelab "sed -i 's|http://172.20.0.22:8080|http://172.20.0.15:8080|' /home/walid/traefik/dynamic/elementcall.yml"
```

### Verificación post-arreglo

- `docker exec matrix_synapse curl http://localhost:8008/health` → `OK`
- 0 `PermissionError` en logs tras el recreate
- `touch` de prueba como uid 991 → `WRITE_OK`
- `https://call.fionaro.pw/` → HTTP 200
- Probes `blackbox_fionaro` en Prometheus → **5/5 UP** (livekit, chat, matrix, call, push)

### Lecciones aprendidas (PENDIENTE de aplicar)

Este bug va a **repetirse en cada reboot**. Acciones preventivas pendientes:

1. **Usar Docker DNS en vez de IPs hardcodeadas** en `/home/walid/traefik/dynamic/elementcall.yml`:
   ```yaml
   services:
     elementcall:
       loadBalancer:
         servers:
           - url: "http://element_call:8080"
   ```
   (requiere que element_call esté en la red `traefik_proxy` y que el contenedor use un nombre DNS estable — verificar `docker network` alias)

2. **Añadir regla de alerta en Prometheus** que detecte binds rotos:
   ```
   SynapsePermissionErrors: increase(synapse..._error[5m]) > 0
   ```
   o un probe que verifique `PermissionError` en logs de Synapse.

3. **Asegurar orden de montaje**: considerar `mounts` de systemd que esperen md0 antes de que Docker arranque contenedores (o `RequiredMountsFor=/mnt/storage` en docker.service).

4. **Documentar en PROJECT-REFERENCE.md §1.4** que las IPs de contenedores NO son estables tras recreates.

---

## Incidente 2026-08-03 (2º): Failed to connect to Livekit server (InternalError)

### Síntomas reportados
- "Failed to connect to Livekit server — An error occurred while connecting to the Livekit server (Reason: InternalError)" al hacer llamadas

### Diagnóstico

**El pipeline JWT funcionaba perfectamente** (200, room creada, JWT generado, participante se unía a la señalización). El fallo estaba en el **ICE/media path**:

- El log de LiveKit mostraba candidates del cliente todos **"filtered"** (NAT estricta), `connectionType: unknown`, `sessionDuration: 0s`
- Candidates del servidor: `udp REDACTED-IP:7882` + **`tcp REDACTED-IP:7881`** (fallback TCP)
- **Causa raíz:** `livekit.yaml` define `tcp_port: 7881` (fallback TCP para NATs estrictas) y el túnel FRP `livekit-tcp-7881` existía, pero **el contenedor livekit NO publicaba el puerto 7881** en el host → frpc daba `connection refused` a `127.0.0.1:7881` → el cliente sin vía de conexión → `InternalError`
- Evidencia: `journalctl -u frpc` mostraba `[livekit-tcp-7881] dial tcp 127.0.0.1:7881: connect: connection refused` repetido cada ~2 min; `docker port livekit` solo publicaba 7880 y 7882

### Comandos de arreglo aplicados

```bash
# 1. Añadir el puerto 7881 al compose de livekit
ssh homelab "sed -i 's|      - \"7882:7882/tcp\"|      - \"7882:7882/tcp\"\n      - \"7881:7881\"|' /home/walid/comunicacion/livekit/docker-compose.yml"

# 2. Recrear el contenedor (publica 7881)
ssh homelab "cd /home/walid/comunicacion/livekit && docker compose up -d --force-recreate livekit"

# 3. Corregir IP muerta de Traefik (172.20.0.13 → 172.20.0.3)
ssh homelab "sed -i 's|http://172.20.0.13:7880|http://172.20.0.3:7880|' /home/walid/traefik/dynamic/livekit.yml"
```

frpc reconectó solo al retry (no requirió restart; el intento de `systemctl restart frpc` falló porque sudo pide password interactivo).

### Verificación post-arreglo
- `docker port livekit` → 7880, **7881**, 7882 (tcp+udp) publicados
- `TCP REDACTED-IP:7881` conecta vía FRP (antes `connection refused`)
- frpc sin errores de 7881 (0 en últimos 3 min)
- Probes `blackbox_fionaro` → 5/5 UP
- **Llamada de prueba confirmada por el usuario: FUNCIONA ✅**

### Lección aprendida

El `tcp_port: 7881` de LiveKit (fallback para NAT estricta) es **esencial** para clientes móviles — debe publicarse SIEMPRE en el compose. La config de LiveKit (`livekit.yaml`) es la fuente de verdad de los puertos; el compose debe reflejarlos todos.

---

## Notas generales

- **Alerts scanopy + flaresolverr**: pendientes de revisión, no afectan a Fionaro
- **Nunca correr** `docker compose down` en el stack matrix si md0 no está montado — verificar primero `mount | grep md0`
- **Verificar tras cada reboot**: `docker port livekit` (debe listar 7880/7881/7882), `journalctl -u frpc | grep 'connection refused'`, y los `url:` de `/home/walid/traefik/dynamic/*.yml` contra las IPs reales (`docker inspect <container> --format '{{range .NetworkSettings.Networks}}{{.IPAddress}} {{end}}'`)
