# Referencias automáticas y tolerancias — versión 1.6

## Lo que cambia

El flujo para palabras ahora es: **agregar/reemplazar video → registrar la palabra si es nueva → compilar e instalar → practicar**. No hay pantalla de preparación, edición de hitos, sliders ni revisión cuadro por cuadro. No necesitas ejecutar Python ni enviarme el video para generar su referencia.

La preparación se ejecuta **en el teléfono la primera vez que abres esa palabra**, después de instalar la app. No ocurre durante la compilación. La misma pantalla de práctica muestra temporalmente “Preparando ejemplo automáticamente…”. El tiempo depende del video y del teléfono. Una vez preparado, comienza la práctica habitual.

## Reemplazar un video existente

1. Sustituye el MP4 en `app/src/main/res/raw/`, conservando exactamente su nombre; por ejemplo, `s_hola.mp4`.
2. Compila e instala la app actualizada. Cambiar un archivo del proyecto no modifica por sí solo la app que ya está instalada.
3. Abre la palabra. La app detecta los bytes nuevos y reconstruye únicamente su referencia.

No cambies el catálogo si el nombre y la palabra siguen iguales. No hace falta borrar datos, desinstalar ni eliminar archivos JSON. Una actualización con la misma firma conserva las referencias de los videos que no cambiaron. Al desinstalar o borrar los datos de la app se pierden las referencias locales; se vuelven a crear según abras cada palabra.

## Agregar una palabra o un módulo

Guarda, por ejemplo, `f_mama.mp4` en `res/raw`. Dentro de `lessons` del módulo `familia` en `app/src/main/assets/word_lessons.json`, agrega:

```json
{
  "id": "f_mama",
  "title": "Mamá",
  "video": "f_mama",
  "tolerance": {}
}
```

El campo `video` no lleva `.mp4`. Usa nombres únicos, minúsculas sin espacios ni acentos y guiones bajos. El título sí admite acentos. El orden del arreglo es el orden de práctica. Un módulo con lecciones se habilita automáticamente. Puedes añadir otro objeto a `modules` con `id`, `title` y `lessons`.

Se retiraron los tiempos fijos `startMs/endMs` del catálogo: el generador determina el tramo visible de cada video. No tienes que ajustar esos tiempos al reemplazarlo. El selector usa una heurística visual, no comprensión lingüística; conviene que el archivo contenga solo una ejecución de la palabra, con poco tiempo de reposo antes y después.

## Cómo se genera y valida una referencia

1. Se calcula SHA-256 del video incluido en la app y del modelo `hand_landmarker.task`.
2. Se busca la caché privada de ese nombre de video. Deben coincidir video, modelo, esquema y versión del generador. No se usa el nombre como única identidad.
3. Si coincide y el JSON está íntegro, se utiliza. Si falta, está dañado o no coincide, se analiza el video de nuevo en un hilo de trabajo.
4. MediaPipe detecta hasta dos manos aproximadamente cada 100 ms. Se conservan los 21 puntos de cada mano y 10 ángulos de flexión. Se asocian las manos por continuidad para reducir cambios de etiqueta.
5. Se elige automáticamente el tramo con manos visibles y una secuencia ordenada de hitos de movimiento; se preservan fases sostenidas de dos manos.
6. Se comprueba duración de 1–60 segundos, suficientes observaciones válidas, tramo visible de al menos 900 ms, ausencia de huecos de detección total mayores de 700 ms, movimiento suficiente y al menos cuatro hitos distintos.
7. Solo una referencia terminada se guarda mediante escritura atómica. Un video ilegible o sin suficientes manos produce un mensaje y no habilita “Siguiente”. Nunca se practica contra la referencia vieja si los bytes cambiaron.

La caché está en el almacenamiento privado, `noBackupFilesDir/word_references_auto/<nombre>.json`. No se suben videos ni se requiere conexión. Si cambias el modelo o incrementas `WordReferenceBuilder.VERSION`, las referencias se regeneran al usarse. Si solo cambias una tolerancia, se reutilizan los hitos y se aplica el margen nuevo.

Para los videos de J, K, Ñ, Q, X y Z también se compara la huella del recurso incluido con la referencia guardada al abrir la práctica, y se utiliza su generador existente si cambió. Las letras estáticas siguen utilizando sus plantillas y reglas actuales; sustituir un video ilustrativo no crea una regla nueva para una letra estática.

## Cómo se valida al alumno

`WordPracticeEngine.java` mantiene tres etapas:

- **Inicio:** imitar la postura inicial y sostenerla brevemente.
- **Movimiento:** recorrer los hitos en orden, respetando aproximadamente forma, flexión, trayectoria y presencia de la segunda mano cuando se observó en el ejemplo.
- **Final:** sostener la postura final. Solo entonces se habilita avanzar.

Se comparan distancias relativas al tamaño de la palma, para admitir diferencias de encuadre y tamaño de mano. La cámara usa la imitación como espejo. Se permite perder brevemente el seguimiento; una pérdida larga reinicia el intento.

Esto es comparación visual con el ejemplo. No determina si el video realmente corresponde al título, no certifica la corrección lingüística de LSM y no evalúa cara, labios, contacto con el cuerpo ni sensores BMI160. Por ahora el motor de palabras requiere una secuencia con movimiento: un clip de una postura completamente estática, aunque sea una seña válida, puede ser rechazado. Para números estáticos haría falta añadir una modalidad de postura sostenida.

## Dónde cambiar tolerancias

**Una palabra:** `app/src/main/assets/word_lessons.json`, dentro de su objeto `tolerance`.

**Todas las palabras por defecto:** `app/src/main/java/com/mechrobotix/aprendels/WordTolerance.java`.

Los valores de una palabra sobrescriben los generales. Si dejas `"tolerance": {}`, se usan todos los generales. Recompila e instala después de editar. No se cambian las tolerancias del abecedario.

| Parámetro | General actual | Para facilitar | Qué controla |
|---|---:|---|---|
| `shape` | 0.78 | Subir, por ejemplo a 0.95 | Diferencia de forma/orientación relativa de la mano |
| `meanFlex` | 50 | Subir a 60 | Diferencia media de flexión, grados |
| `maxFlex` | 105 | Subir a 125 | Diferencia máxima de flexión; debe ser ≥ `meanFlex` |
| `travel` | 1.25 | Subir a 1.60 | Margen de posición de la trayectoria, en palmas |
| `travelRelative` | 0.25 | Subir a 0.35 | Margen extra en recorridos amplios |
| `minMotionRatio` | 0.25 | Bajar a 0.20 | Fracción mínima del cambio entre hitos |
| `minMotion` | 0.10 | Bajar con cuidado | Movimiento mínimo para evitar aprobar estando quieto |
| `directionCosine` | -0.05 | Bajar, por ejemplo a -0.15 | Exigencia de dirección; -1 prácticamente la elimina |
| `minTravelProgress` | 0.50 | Bajar a 0.40 | Cuánto acercarse al destino antes de avanzar |
| `stability` | 0.40 | Subir a 0.55 | Movimiento permitido al sostener inicio/final |
| `startHoldMs` | 220 | Bajar a 150 | Tiempo de sostener el inicio |
| `endHoldMs` | 280 | Bajar a 200 | Tiempo de sostener el final |
| `lostGraceMs` | 1100 | Subir a 1500 | Tiempo para recuperar una mano perdida |
| `maxAttemptMs` | 45000 | Subir | Tiempo máximo del intento |
| `minAttemptMs` | 900 | Bajar con cuidado | Duración mínima para completar |

Los rangos aceptados están en `WordTolerance.validate()`. No uses cero para eliminar controles. Empieza cambiando solo el parámetro relacionado con el mensaje donde se atasca la práctica.

Ejemplo de un perfil **más permisivo para probar**, no un ajuste certificado para todos los videos:

```json
"tolerance": {
  "shape": 0.95,
  "meanFlex": 60,
  "maxFlex": 125,
  "travel": 1.60,
  "stability": 0.55,
  "startHoldMs": 150,
  "endHoldMs": 200,
  "lostGraceMs": 1500
}
```

Este bloque va dentro de la palabra correspondiente. No lo pegues en la raíz del catálogo. No se aplicó este perfil globalmente: ampliar márgenes también puede aprobar señas equivocadas. “Hola” conserva una excepción más estricta (`shape: 0.65`), porque con 0.70 el nuevo recorrido automático aceptó un replay de “Buenos días” durante las pruebas. Si reemplazas el ejemplo de Hola, revisa si esa excepción todavía es útil.

Si aparece “No se pudo preparar esta palabra”, las tolerancias del alumno no solucionan necesariamente el video: esas tolerancias actúan después de generar la referencia. Comprueba primero encuadre, manos completas, ausencia de cortes y movimiento. Los límites de calidad están en `WordReferenceBuilder.java`; confianza de detección y tamaño de fotogramas en `WordVideoReferenceGenerator.kt`. Si modificas esos algoritmos, incrementa `VERSION` para invalidar cachés anteriores. Bajar confianza no es equivalente a dar más margen a un alumno.

## Archivos de implementación

Rutas de clases relativas a `app/src/main/java/com/mechrobotix/aprendels/`:

| Archivo | Cambio |
|---|---|
| `WordVideoReferenceGenerator.kt` | Nuevo: decodifica y analiza el video local en segundo plano, con cancelación y liberación de recursos |
| `WordReferenceBuilder.java` | Nuevo: selecciona y valida hitos automáticamente, sin Android para poder probarlo |
| `WordReferenceStore.kt` | Modificado: hash, caché por video, regeneración y escritura atómica |
| `WordHandSamples.kt` | Modificado: permite obtener coordenadas de referencia sin reflejar; cámara conserva modo espejo |
| `WordPracticeActivity.kt` | Modificado: conecta la preparación automática, progreso y manejo de errores/cancelación |
| `PracticeSignActivity.kt` | Modificado: comprueba cambio del recurso de letras dinámicas antes de reutilizar su referencia |
| `app/src/main/assets/word_lessons.json` | Modificado: sin recortes antiguos; excepción de Hola ajustada |
| `app/build.gradle.kts` | Modificado: versión 1.6, código 7; dependencias conservadas |

`WordCatalog.kt`, `WordTolerance.java` y `WordPracticeEngine.java` no necesitan cambios para activar la generación; el catálogo ya permite sobrescribir tolerancias. Los JSON antiguos en `assets/word_references/` quedan como datos históricos para pruebas: **la práctica de palabras ya no los carga**. No tienes que actualizarlos al reemplazar un video.

Herramientas nuevas: `tools/WordAutoReferenceCheck.java`, `tools/verificar_referencias_automaticas.py`, `tools/fixtures_auto/` y `app/src/androidTest/java/com/mechrobotix/aprendels/WordAutoReferenceTest.kt`. El detector opcional de `tools/preparar_palabras.py` se adaptó para que las pruebas apliquen los límites de imagen de Android; su generación antigua de JSON ya no es parte del flujo de publicación de la app.

## Cómo probar por tu cuenta

Para cada video nuevo, abre su lección y comprueba:

1. Primera apertura: se prepara y aparece el ejemplo; segunda apertura: reutiliza la referencia.
2. Haz la seña correctamente varias veces, con distintas distancias y velocidades.
3. Prueba quedarte quieto, saltarte el recorrido, hacerlo al revés y hacer otra palabra parecida. No deberían completar la lección.
4. Si requiere ambas manos, oculta una durante esa fase. Si pierdes el seguimiento por mucho tiempo, debe pedir comenzar otra vez.
5. Sustituye el archivo por otro ejemplo con el mismo nombre, recompila e instala sobre la app existente. Esa palabra debe prepararse nuevamente; las que no cambiaron deben conservar su caché.

Para ejecutar pruebas de escritorio opcionales con los diez videos de esta entrega:

```text
python tools/verificar_referencias_automaticas.py
```

Requiere Python 3 y JDK 17+. Si agregaste o reemplazaste videos y quieres renovar esas pruebas de escritorio, usa Python 3.10–3.12:

```text
python -m pip install -r tools/requirements-palabras.txt
python tools/verificar_referencias_automaticas.py --reanalyze
```

**Esas herramientas son opcionales para desarrollo; la app funciona sin ejecutarlas.** Un fallo de pruebas cruzadas indica que una palabra se confunde con otra con esos márgenes; revisa el ejemplo o reduce el margen específico.

En Android Studio, con un teléfono o emulador compatible conectado, ejecuta `WordAutoReferenceTest` desde `app/src/androidTest`. Comprueba el decodificador Android, MediaPipe, reutilización, reemplazo con el mismo nombre, integridad de otra entrada, cancelación y recuperación de JSON dañado. Esta prueba se entrega escrita, pero no se pudo ejecutar en este entorno. Consulta el informe para distinguir lo probado de lo pendiente.
