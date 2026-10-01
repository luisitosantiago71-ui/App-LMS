# Informe de pruebas — referencias automáticas 1.6

Base recibida: `AprendeLS_Modulos_Palabras(1).zip`. Ejecución: 30 de septiembre de 2026 (America/Ciudad_Juarez).

## Resultado

Se implementó generación en el teléfono, caché por contenido de video/modelo/versión, selección automática de etapas y sustitución de referencias obsoletas. La referencia se prepara al abrir una palabra, no durante la compilación.

Las comprobaciones de Java y las pruebas con detecciones de los diez videos completos pasaron. **No se generó un APK ni se comprobó cámara/reproducción en un teléfono en este entorno.**

## Ejecutado

- Compilación del código real `WordReferenceBuilder.java`, `WordPracticeEngine.java`, `WordTolerance.java` y `JPracticeEngine.java` con el compilador del JDK 17.
- Nueva extracción de manos sobre los diez MP4 completos usando el modelo incluido, MediaPipe Python 0.10.21 en CPU, muestreo cada 100 ms y controles de límites de imagen compatibles con `WordHandSamples`. Android conserva MediaPipe 0.10.14: puede haber diferencias de decodificación/detección entre plataformas.
- El selector Java usado en Android construyó referencias sin los recortes manuales anteriores.
- **10/10** replays de sus propias detecciones completaron la palabra.
- **10/10** replays con escala 0.70, traslación e imitación espejo completaron la palabra.
- **0/90** replays de otras palabras fueron aceptados con las tolerancias finales.
- Pruebas negativas: mano inmóvil, saltar de inicio a final, secuencia al revés, omitir segunda mano requerida, timestamps repetidos y pérdida larga del seguimiento.
- Controles del generador: rechazo de video sin manos, duración demasiado corta/larga, mano inmóvil, orden temporal inválido y pérdida total prolongada.
- Regresión del motor con las referencias históricas incluidas: diez replays correctos y cero aceptaciones cruzadas antes del ajuste específico de Hola.

Los replays positivos sostienen artificialmente las muestras inicial/final para cumplir los tiempos de la práctica. No son ensayos con usuarios independientes ni una medida de precisión en uso real.

### Referencias del selector automático en las pruebas de escritorio

| Video | Hitos | Tramo (ms) |
|---|---:|---:|
| s_hola | 7 | 0–3000 |
| s_como_estas | 12 | 1200–6700 |
| s_yo_bien | 7 | 700–4100 |
| s_yo_mal | 7 | 300–3600 |
| s_buenos_dias | 10 | 0–4300 |
| s_buenas_tardes | 13 | 0–5600 |
| s_buenas_noches | 11 | 0–6100 |
| d_nos_vemos_pronto | 12 | 1100–7000 |
| d_nos_vemos_manana | 11 | 300–6700 |
| d_nos_vemos_proxima | 12 | 500–6200 |

Puede haber más de doce hitos si se añaden fases sostenidas de dos manos. El teléfono calcula sus propias referencias; estas cifras son resultados de la extracción de escritorio.

### Ajuste derivado de una prueba fallida

Con el recorrido automático completo y `shape: 0.70`, el replay de Buenos días completaba Hola. Con `shape: 0.65` solo en Hola, esa aceptación desapareció y el replay correcto siguió pasando. No se aumentaron indiscriminadamente los márgenes de las demás palabras. Se entrega un perfil opcional más flexible en la guía, que requiere probar también intentos incorrectos.

## Inspección adicional

Se validó la sintaxis de los archivos Kotlin nuevos/modificados de palabras y de la prueba Android con un parser; se parsearon los XML y el catálogo. Esto no sustituye al compilador Android. El archivo heredado PracticeSignActivity presenta los mismos tres errores de parser en el original y en la actualización. Se verificó que todos los videos raw y los motores del abecedario/BLE conservan sus bytes originales.

El registro reproducible está en RESULTADO_PRUEBAS_AUTOMATICAS.txt y la lista de archivos cambiados en CAMBIOS_REFERENCIAS_AUTOMATICAS.txt.

## Pendiente en Android Studio/teléfono

El comando `./gradlew :app:assembleDebug --no-daemon` intentó descargar Gradle 9.7.1 de services.gradle.org y falló con `java.net.SocketException: Network is unreachable`. El fallo ocurre antes de compilar la app; no confirma ni descarta errores del compilador Android.

Se incluye `WordAutoReferenceTest.kt`, aún **sin ejecutar**, para comprobar con Android real:

- Preparación inicial y lectura de la misma referencia sin recalcular.
- Cambio del contenido conservando el mismo nombre lógico.
- Otra entrada de caché permanece intacta.
- Cancelación sin sobrescribir la última referencia válida.
- Recuperación automática de JSON dañado.

También falta comprobar rotación/cierre de pantalla durante preparación, permisos, cámara frontal, reproducción y consumo de memoria/tiempo en el teléfono objetivo. Para las letras dinámicas, probar sustituir un video raw y confirmar la regeneración con su motor existente. Los motores del abecedario y la comunicación BLE no se modificaron.

## Alcance

No se asegura corrección lingüística por detectar manos. El sistema no conoce el significado del título, no evalúa cara/cuerpo y puede perder manos o distinguir mal señales parecidas. La selección automática del tramo es una heurística visual. Los ejemplos deben contener una sola seña y manos visibles. Los clips completamente estáticos requieren una futura modalidad específica; el motor actual de palabras exige movimiento.

## Reproducción de las pruebas

`python tools/verificar_referencias_automaticas.py` usa las detecciones completas incluidas y comprueba sus hashes. Requiere Python 3 y JDK 17+. Si cambias un video, la opción `--reanalyze` renueva estas pruebas de escritorio con MediaPipe. Ninguno de estos comandos es obligatorio para generar referencias en la app.

## Documentación técnica consultada

- [MediaPipe Hand Landmarker para Android](https://ai.google.dev/edge/mediapipe/solutions/vision/hand_landmarker/android): modo VIDEO, timestamps, conversión de bitmap y ejecución fuera del hilo de interfaz.
- [MediaMetadataRetriever](https://developer.android.com/reference/android/media/MediaMetadataRetriever): extracción de fotogramas por tiempo y compatibilidad de getScaledFrameAtTime desde API 27; se conserva fallback para API 24–26.
