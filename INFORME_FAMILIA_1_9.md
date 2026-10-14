# Informe de Familia y avance automático — versión 1.9

Entrada: `AprendeLenguajeDeSenas(10).rar`, compartido en esta solicitud. Salida: proyecto versión 1.9, código 10. Se trabajó sobre este archivo, incluyendo las tolerancias que ya habías modificado.

## Resultado de las pruebas de escritorio

| Prueba | Familia | Saludos y despedidas |
|---|---:|---:|
| Videos registrados y con referencia generada | 33/33 | 10/10 |
| Recorridos exactos aceptados | 33/33 | 10/10 |
| Amplitud al 80 % con ruido aceptada | 33/33 | 10/10 |
| Replay más rápido aceptado | 33/33 | 10/10 |
| Mano inicial inmóvil: correctamente rechazados | 33/33 | 10/10 |
| Salto directo inicio/final: correctamente rechazados | 33/33 | 10/10 |
| Recorridos invertidos aceptados | 4/33 | 0/10 |
| Cruces entre palabras del mismo módulo aceptados | 55/1056 | 5/90 |

Total: **129/129 variantes positivas aceptadas y 86/86 negativos básicos rechazados**. Entre módulos se aceptaron otros 19 cruces de 660, dando 79/1806 cruces aceptados en el catálogo completo. Un cruce significa que se practicaba una palabra objetivo y el motor aceptó el ejemplo de otra; no es una medida de precisión en usuarios reales.

Las cuatro inversiones aceptadas corresponden a Hombre, Niño, Novio y Papá. Los ejemplos contienen movimientos repetidos o parecidos entre inicio y final, y el motor flexible no distingue todas esas inversiones. Entre los objetivos con más cruces dentro de Familia aparecen Novio (8), Hombre (6), Niño (6), Novia (6), Papá (6) y Esposo (5). Los pares exactos están en las líneas `CROSS` del registro incluido.

Estas limitaciones importan especialmente con el avance automático: una aceptación incorrecta también hará pasar a la siguiente palabra. Se requiere probar con personas y ajustar los márgenes por palabra. No se certifica la corrección lingüística de los videos ni se promete impedir cualquier aprobación anticipada.

## Qué se verificó

- Se detectaron las manos de los 33 MP4 nuevos con MediaPipe Python 0.10.21, modelo del proyecto y muestreo cada 100 ms. Se aplicó el seguimiento de manos y las restricciones de coordenadas compatibles con el generador Android.
- Se generaron las referencias mediante el `WordReferenceBuilder` Java de la entrega; no se crearon referencias manuales.
- Se verificaron hashes de los 43 videos de lecciones, el modelo y los archivos de detecciones.
- Se verificó que todos los hitos correspondan a cuadros reales, y que los planes de 3, 4, 5 y 6 etapas estén ordenados y tengan objetivos válidos.
- Se compiló y ejecutó el motor Java con JDK 17. El código Android usa MediaPipe 0.10.14: el detector/decodificador del teléfono puede producir diferencias respecto a Python.
- Se probó el controlador de avance: espera inicial de 1200 ms, avance único, resultados repetidos sin saltar palabras, cancelación y reinicio para la siguiente palabra.
- Se comparó el catálogo de Saludos antes/después: es idéntico. También se ejecutaron su motor original y el actualizado sobre las mismas detecciones; sus referencias resumidas, resultados positivos/negativos y cruces fueron idénticos.

Las variantes positivas provienen de los mismos ejemplos empleados para construir la referencia. La prueba con ruido reduce el recorrido de la muñeca al 80 %, añade ruido de puntos y flexión, y aplica escala, traslación y espejo. La prueba rápida toma uno de cada dos cuadros. El replay sostiene el inicio y el final para permitir las comprobaciones temporales. Esto valida funcionamiento y regresiones; no sustituye una evaluación independiente con distintos usuarios y condiciones de cámara.

## Ajustes por los problemas encontrados

Niño y Papá no alcanzaban cuatro hitos después de filtrar movimientos pequeños. Se añadió un perfil opcional que recupera cuadros reales descartados. Solo estas palabras lo activan. Papá usa un mínimo de movimiento de 0.10 palmas y Niño 0.15.

Compañero mostró un problema al fijar el origen de una mano que entraba a mitad de la seña. Familia ahora activa un origen compartido para el recorrido; Saludos conserva la opción anterior por defecto.

Abuela utiliza seis etapas. Esposa utiliza tres etapas visibles y comprobación adicional de todos los hitos del recorrido, porque la postura intermedia elegida con cinco etapas exigía una mano que desaparecía durante la transición. Los márgenes de recorrido de Esposa, Hijo e Hija se ajustaron individualmente a 0.90, 0.80 y 1.00; los demás usan 0.50. Tío usa `minTravelProgress=0.08`.

Las etapas y los márgenes se pueden seguir editando en `word_lessons.json`. Los valores elegidos son un punto de partida comprobado en estos replays, no una configuración definitiva para cualquier persona.

## Compilación Android y pruebas pendientes

Se intentó `:app:assembleDebug`. El entorno no pudo descargar Gradle 9.7.1 y devolvió **Network is unreachable**, antes de compilar el módulo Android. No se generó ni se verificó un APK nuevo. Se incluye el registro `tools/compilacion_android_1_9.txt`.

Queda pendiente en Android Studio/teléfono:

1. Sincronizar y compilar el proyecto.
2. Verificar detección con cámara frontal y reproducción de los 33 videos.
3. Verificar avance automático, salida al terminar cada módulo y cancelación al repetir/salir.
4. Sustituir un MP4 conservando el nombre y confirmar que solo se regenera su referencia.
5. Ejecutar el test instrumental de caché, incluido el caso nuevo de cambio de perfil.
6. Probar señas completas, incompletas y parecidas con distintas personas, luz y distancias.

## Archivos y conservación

Se conservan todos los MP4 originales, incluidos los recursos anteriores, layouts y código del abecedario. Los valores de Saludos y los módulos pendientes permanecen iguales. El detalle de archivos modificados y controles está en `GUIA_FAMILIA_Y_AVANCE.md` y `tools/cambios_familia_1_9.json`.

El ZIP contiene el proyecto completo, modelo, videos, fuentes, detecciones y pruebas. Se excluyen cachés/build de Android Studio y `local.properties`, que debe apuntar al SDK de tu computadora. Los informes anteriores se conservan como documentación histórica; el resultado vigente de esta entrega es este informe y `tools/resultados_modulos_1_9.txt`.
