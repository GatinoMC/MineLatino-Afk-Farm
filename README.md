# MineLatino AFK Farm

Mod cliente de automatización controlada para MineLatino. La configuración se abre desde el
botón **AFK Farm** del menú de pausa y se guarda localmente en
`config/minelatino-afk-farm/afk-farm.json`.

## Asistente IA

La pantalla nativa **Asistente MineLatino** se abre desde AFK Farm o con la tecla
configurable **Abrir asistente IA MineLatino** (apóstrofo por defecto). Reutiliza la
sesión ya vinculada de Cosméticos MineLatino, admite mensajes multilínea, historial,
scroll, copiado, cancelación y conversaciones nuevas. Abrirla detiene de forma segura
la automatización y nunca la reanuda por sí sola.

El mod canjea la sesión de juego por credenciales Bearer separadas y de corta duración:
una exclusiva para `ai:chat` y otra persistente y renovable para `afk:usage`. La clave
del proveedor no existe en el JAR ni en el launcher: vive solamente en el backend privado.
Sin una cuenta conectada se muestra
`Vuelve a vincular tu cuenta MineLatino`.

## Compatibilidad

| Minecraft | Fabric | Forge |
| --- | --- | --- |
| 1.21.4 | Sí | Sí |
| 1.21.11 | Sí | Sí |
| 26.2 | Sí | Sí |

Minecraft 1.21.x requiere Java 21 y Minecraft 26.2 requiere Java 25. Todos los
módulos vienen desactivados por defecto.

## Flujo

1. Espera a que mundo, jugador y conexión estén listos durante 20 ticks consecutivos.
2. Aplica la espera posterior a la conexión y ejecuta hasta 10 comandos configurados.
3. Conserva el estado pendiente si un `/warp` cambia al jugador de host y espera a que el
   nuevo mundo vuelva a estar listo.
4. Espera el intervalo de movimiento y reproduce el recorrido grabado punto por punto,
   incluyendo saltos y cambios de altura.
5. Al terminar, busca únicamente mobs o animales seleccionados desde la lista visual.

Los recorridos se graban caminando desde la pestaña **Recorrido**, pueden guardarse con nombre,
reutilizarse y eliminarse. La versión 2 del JSON migra automáticamente configuraciones anteriores.
Durante la reproducción se usa una mira adelantada para suavizar curvas, se resincronizan puntos
sobrepasados y los saltos son pulsos breves. Si no existe progreso real durante seis segundos, el
flujo se detiene con un diagnóstico en lugar de quedarse girando indefinidamente.

Abrir otra pantalla, usar las teclas de movimiento o cancelar desde el menú detiene la
secuencia y libera las teclas simuladas.

## Seguridad del ataque automático

El ataque solo puede activarse en `play.minelatino.com`. Además exige simultáneamente:

- módulo de ataque habilitado;
- categoría e identificador elegidos en el selector visual;
- objetivo vivo, cercano y con línea de visión;
- nunca jugadores ni mascotas domesticadas;
- fuerza de ataque real `>= 0.95`;
- intervalo interno mínimo de 5 ticks (máximo 4 intentos por segundo).

La frecuencia no existe en la interfaz, el JSON ni el backend. Solo puede cambiarse publicando
una nueva compilación oficial. Consulta [docs/attack-policy.md](docs/attack-policy.md).

Cuando no encuentra un objetivo, el HUD informa el tipo real que recibió el cliente, si está fuera
de alcance, sin línea de visión o sin seleccionar. Los mobs de MythicMobs disfrazados como jugador
se mantienen excluidos porque el cliente no puede diferenciarlos de un usuario real sin un puente
autorizado del servidor.

## Prueba de tiempos web

El panel administrativo incluye **Launcher → AFK Farm**. Allí se asigna o establece tiempo por
cuenta MineLatino y también se pueden validar hasta 10 comandos y simular sus esperas. El saldo
real permanece en el backend; el mod muestra cuánto queda y no inicia el flujo cuando llega a cero.
Mientras está activo abre una única sesión por cuenta y envía un heartbeat cada 20 segundos. El
servidor calcula el consumo con su propio reloj y detiene permisos vencidos o sin saldo.

Mientras una sesión AFK autorizada está activa, el cliente mantiene 35 FPS tanto en primer plano
como al usar Alt+Tab, sin pausar al perder el foco. La automatización sigue gobernada por ticks y
no por cuadros renderizados. Al detenerse o completar el flujo se restauran inmediatamente las
preferencias anteriores del usuario.

## Compilar

```powershell
$env:JAVA_HOME = 'ruta-a-java-21'
./gradlew.bat :common:test :fabric:build -PmcVersion=1.21.4 --no-daemon
./forge/gradlew.bat -p forge build -PmcVersion=1.21.4 --no-daemon
```

Cambie `1.21.4` por `1.21.11` o `26.2` para las otras versiones. Para no escribir artefactos en una carpeta
sincronizada puede definir `MINELATINO_AFK_BUILD_ROOT`.

## Distribución

`deploy.ps1` compila los seis JAR, publica una versión inmutable de GitHub y genera
`mods.json` con nombre, tamaño y SHA-1. El backend del launcher distribuye ese manifiesto y el
launcher verifica el archivo nuevo antes de eliminar versiones anteriores.
