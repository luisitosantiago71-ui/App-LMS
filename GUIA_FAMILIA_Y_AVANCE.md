# Familia y avance automático — AprendeLS 1.9

## Qué incluye esta entrega

Familia contiene los 33 videos `f_` en el orden solicitado. Se habilita usando el mismo selector y la misma pantalla de práctica. Tras reconocer una palabra, muestra el acierto durante **1,2 segundos** y avanza automáticamente. Esto funciona en **Familia y Saludos y despedidas**, y también en futuros módulos que usen esta pantalla. Después de la última palabra regresa a la lista de módulos.

El botón Siguiente sigue disponible para avanzar antes de esa espera. Repetir ejemplo, pausar/salir de la pantalla o cambiar de palabra cancela cualquier avance pendiente. El motor nuevo de avance impide consumir el mismo acierto más de una vez.

Se conservaron los videos, el diseño, el abecedario, los módulos pendientes y todos los valores que traía Saludos en tu archivo, incluidas tus últimas tolerancias de las despedidas. No se modificaron `WordTolerance.java` ni `WordStagePlan.java`.

## Dónde editar etapas y tolerancias

Abre **`app/src/main/assets/word_lessons.json`**, busca el módulo `"id": "familia"` y después la palabra, por ejemplo `"id": "f_abuela"`.

Dentro de cada palabra:

- `validation.stages`: número de etapas, **3, 4, 5 o 6**. Si la referencia tiene menos hitos, usa los disponibles.
- `validation.pathTolerance`: margen del recorrido. Subirlo facilita; bajarlo exige más precisión. **Cero desactiva la comprobación adicional del recorrido.**
- `validation.finalPoseScale`: precisión del final; subir hacia `1` facilita, bajar exige más precisión.
- `tolerance`: márgenes de forma de la mano, ángulos, movimiento y tiempos de esa palabra.

Ejemplo de campos de `validation` (conserva los demás que ya tenga la palabra):

```json
"stages": 5,
"pathTolerance": 0.5,
"finalPoseScale": 0.9,
"sharedRouteOrigin": true
```

### Ajustes principales

| Campo | Qué controla | Para hacerlo más fácil |
|---|---|---|
| `validation.stages` | Inicio, desplazamientos, posturas intermedias y final | Puedes quitar un control; prueba también intentos incompletos. Más etapas no garantiza más precisión. |
| `validation.pathTolerance` | Error admitido del recorrido, en palmas | Sube de 0.50 a 0.60 y prueba. Rango 0–2. |
| `validation.finalPoseScale` | Multiplica los márgenes de postura final | Sube de 0.90 a 1.00. Rango 0.25–1. |
| `validation.poseHoldMs` | Tiempo válido en posturas intermedias | Bájalo con cuidado; rango 60–1000 ms. |
| `validation.minDisplacement` | Cambio mínimo observable | Bájalo, por ejemplo de 0.25 a 0.20. Ahora admite 0.10–2 para movimientos finos. |
| `validation.motionSamples` | Cambios separados necesarios para un desplazamiento | El mínimo es 2; el máximo, 6. |
| `validation.motionNoise` | Umbral que evita contar temblores pequeños | No menor de 0.03 y siempre menor que `minDisplacement`. Se combina con `tolerance.minMotion`, usando el mayor. |
| `validation.maxFingerMismatches` | Dedos claramente abiertos/cerrados distintos permitidos | Subir de 0 a 1 facilita, pero puede confundir palabras. |
| `tolerance.shape` | Diferencia permitida en la forma de la mano | Subir facilita. |
| `tolerance.meanFlex` / `maxFlex` | Diferencias de ángulos de los dedos | Subir facilita; `maxFlex` debe ser al menos `meanFlex`. |
| `tolerance.endHoldMs` | Tiempo válido sosteniendo el final | Familia usa 200 ms. Subir confirma más tiempo; no reemplaza comprobar el recorrido. |

Cambia **un valor a la vez** y recompila/reinstala. Cambiar estas tolerancias o etapas no obliga a decodificar el video otra vez; se aplica sobre su referencia existente.

### Dos opciones nuevas para Familia

**`validation.sharedRouteOrigin: true`** hace que las manos compartan el origen de la mano principal al iniciar el recorrido. Evita tomar como origen incorrecto una mano que aparece a mitad de una seña, como en Compañero. Está activo en Familia. Su valor predeterminado es `false`, de modo que Saludos conserva el comportamiento anterior.

**`recoverSparseMotion: true`** es un campo de la palabra, al mismo nivel que `id`, `video`, `tolerance` y `validation`. Está activo solamente en Niño y Papá. Sus movimientos finos dejaban menos de cuatro hitos después del filtrado. Esta opción recupera cuadros reales diferentes del video; no inventa posiciones ni duplica cuadros. Si aun así el video no ofrece suficiente información, la app informa que no pudo prepararlo.

Ejemplo de ubicación — fragmento, no reemplaces toda la palabra con él:

```json
"id": "f_papa",
"title": "Papá",
"video": "f_papa",
"recoverSparseMotion": true
```

Papá usa `minDisplacement: 0.10`, Niño `0.15`; ambos usan `motionNoise: 0.03` y `tolerance.minMotion: 0.04`. El resto de Familia mantiene el mínimo general de 0.25.

## Reemplazar videos y agregar palabras

Para reemplazar un ejemplo:

1. Sustituye su MP4 en `app/src/main/res/raw/`, manteniendo el nombre; por ejemplo `f_hermano.mp4`.
2. Recompila e instala la app actualizada. Los recursos de una APK instalada no cambian al editar la carpeta de tu computadora.
3. Abre esa palabra. La app calcula el SHA-256 del contenido; si cambió, descarta la referencia antigua de ese video y la genera nuevamente.
4. El resto de las referencias sigue disponible. No necesitas borrar datos ni abrir una pantalla de mapeo.

Cambiar solo el nombre no es necesario: se compara el contenido. También se detectan cambios del modelo y del perfil de generación. El perfil histórico conserva su versión; el perfil de movimientos finos tiene su propia identidad para no invalidar las referencias existentes de Saludos.

Para agregar una palabra distinta, copia el MP4 a `raw` y registra una entrada nueva con `id` único, título, nombre de video sin `.mp4` y sus ajustes en `word_lessons.json`. El orden de las entradas determina el orden de práctica. El prefijo `f_` sirve para organizar archivos; por sí solo no registra una palabra en la app.

Los videos nuevos se analizan automáticamente en el teléfono al abrir cada lección por primera vez. Puede aparecer «Preparando ejemplo automáticamente…». Las detecciones incluidas en `tools/fixtures_auto` son datos para pruebas de escritorio; no son una caché que el teléfono deba importar.

El generador extrae una referencia visual del ejemplo, pero no conoce el significado de una palabra ni certifica que el video enseñe la seña lingüísticamente correcta. Un cambio de video puede necesitar ajustes de tolerancia, aunque su mapeo sea automático.

## Cómo probarlo tú

En la raíz del proyecto, con Python 3 y JDK 17:

```bash
python tools/verificar_modulos.py --module familia
python tools/verificar_modulos.py --module saludos_despedidas
```

Para ambos módulos, guardar resultados y revisar cruces:

```bash
python tools/verificar_modulos.py --report tools/mis_resultados.txt
```

Las detecciones incluidas corresponden a los MP4 actuales y el script verifica sus hashes. No requiere MediaPipe para repetir estas pruebas. Después de reemplazar/agregar videos, usa Python 3.10–3.12, instala las dependencias en un entorno virtual y vuelve a detectar solo los archivos faltantes o modificados:

```bash
python -m pip install -r tools/requirements-palabras.txt
python tools/verificar_modulos.py --module familia --reanalyze
```

Esto es opcional para pruebas de escritorio; la app prepara sus referencias sin ejecutar Python. `REFERENCE` describe los hitos; `RESULT` muestra exacto, amplitud reducida/ruido, rápido, inmóvil, salto inicio/final e invertido. `CROSS` indica que una lección aceptó el ejemplo de otra. Los cruces e inversiones son diagnósticos y no hacen fallar el script; los errores al generar referencias, fallos positivos o aceptar los negativos básicos sí devuelven error.

Las herramientas antiguas (`verificar_etapas.py` y `WordCompletionCheck.java`) conservan pruebas estrictas de la entrega anterior. Pueden señalar fallos con tus tolerancias actuales o señas repetitivas invertidas; no sustituyen el informe del catálogo actual ni deben interpretarse como una certificación de todas las palabras nuevas.

En el teléfono:

- Recorre Familia y comprueba los títulos y el orden. Tras el acierto debe aparecer la siguiente palabra sin tocar Siguiente.
- Repite la misma comprobación en Saludos y despedidas, incluida la última palabra.
- Durante el mensaje de acierto toca Repetir; comprueba que no cambia de palabra después. También prueba salir de la pantalla durante esa espera.
- Haz cada palabra completa varias veces y luego detente antes de terminar, omite una mano necesaria y prueba una palabra parecida. No basta probar solo los aciertos.
- Reemplaza un MP4 conservando su nombre, reinstala y abre la lección; debe preparar la referencia nueva sin borrar las demás.

## Archivos que se tocaron

El paquete de código es `app/src/main/java/com/mechrobotix/aprendels/`.

| Archivo | Cambio |
|---|---|
| `app/src/main/assets/word_lessons.json` | Las 33 entradas de Familia, su orden y ajustes individuales. Saludos queda igual al archivo recibido. |
| `WordPracticeActivity.kt` | `render()`, `tick` y `advanceLesson()`: avance tras el éxito y cancelación al repetir, cargar, pausar o salir. |
| `WordAutoAdvance.java` — nuevo | Espera de un solo uso. Para cambiar los 1,2 segundos, modifica `WordAutoAdvance(1200L)` en la actividad. |
| `WordCatalog.kt` | Lee `sharedRouteOrigin` y `recoverSparseMotion`. |
| `WordStageOptions.java` | Opción de origen común; admite mínimo de movimiento 0.10 para palabras finas, sin cambiar el valor predeterminado. |
| `WordPracticeEngine.java` | Rama opcional en `routeMatches()` para el origen común. La rama anterior sigue siendo el valor predeterminado. |
| `WordReferenceBuilder.java` | Sobrecarga de `build()` para recuperar hitos finos cuando se solicita. La llamada histórica conserva su selección. |
| `WordVideoReferenceGenerator.kt` | Pasa el perfil de la palabra al generador. |
| `WordReferenceStore.kt` | Incluye el perfil y su versión en la identidad de caché, además del hash existente. |
| `app/build.gradle.kts` | Versión 1.9, código 10. |
| `tools/verificar_modulos.py`, `WordModuleCheck.java`, `WordAutoAdvanceCheck.java` — nuevos | Pruebas actuales del catálogo y avance único/cancelación. |
| `tools/WordStagesCheck.java`, `tools/verificar_etapas.py` | Lectura de opciones booleanas y del perfil fino en los datos de prueba. |
| `app/src/androidTest/.../WordAutoReferenceTest.kt` | Caso adicional de cambio de perfil y aislamiento de cachés. Requiere teléfono/emulador; no se ejecutó aquí. |

No se cambiaron layouts, videos, recursos del abecedario ni sus actividades. El informe enumera los resultados y límites. La compilación Android y las pruebas reales de cámara/reproducción siguen pendientes.

## Orden y configuración de Familia

| Nº | Palabra | Recurso en raw | Etapas | Margen del recorrido |
|---:|---|---|---:|---:|
| 1 | Familia | `f_familia.mp4` | 3 | 0.50 |
| 2 | Hombre | `f_hombre.mp4` | 3 | 0.50 |
| 3 | Mujer | `f_mujer.mp4` | 3 | 0.50 |
| 4 | Niño | `f_nino.mp4` | 3 | 0.50 |
| 5 | Niña | `f_nina.mp4` | 5 | 0.50 |
| 6 | Joven | `f_joven.mp4` | 3 | 0.50 |
| 7 | Compañero | `f_companero.mp4` | 5 | 0.50 |
| 8 | Compañera | `f_companera.mp4` | 5 | 0.50 |
| 9 | Papá | `f_papa.mp4` | 3 | 0.50 |
| 10 | Mamá | `f_mama.mp4` | 3 | 0.50 |
| 11 | Esposo | `f_esposo.mp4` | 5 | 0.50 |
| 12 | Esposa | `f_esposa.mp4` | 3 | 0.90 |
| 13 | Novio | `f_novio.mp4` | 5 | 0.50 |
| 14 | Novia | `f_novia.mp4` | 5 | 0.50 |
| 15 | Hijo | `f_hijo.mp4` | 5 | 0.80 |
| 16 | Hija | `f_hija.mp4` | 5 | 1.00 |
| 17 | Hermano | `f_hermano.mp4` | 5 | 0.50 |
| 18 | Hermana | `f_hermana.mp4` | 5 | 0.50 |
| 19 | Abuelo | `f_abuelo.mp4` | 5 | 0.50 |
| 20 | Abuela | `f_abuela.mp4` | 6 | 0.50 |
| 21 | Nieto | `f_nieto.mp4` | 5 | 0.50 |
| 22 | Nieta | `f_nieta.mp4` | 5 | 0.50 |
| 23 | Tío | `f_tio.mp4` | 5 | 0.50 |
| 24 | Tía | `f_tia.mp4` | 5 | 0.50 |
| 25 | Sobrino | `f_sobrino.mp4` | 5 | 0.50 |
| 26 | Sobrina | `f_sobrina.mp4` | 5 | 0.50 |
| 27 | Primo | `f_primo.mp4` | 5 | 0.50 |
| 28 | Prima | `f_prima.mp4` | 5 | 0.50 |
| 29 | Yerno | `f_yerno.mp4` | 3 | 0.50 |
| 30 | Nuera | `f_nuera.mp4` | 3 | 0.50 |
| 31 | Cuñado | `f_cunado.mp4` | 3 | 0.50 |
| 32 | Cuñada | `f_cunada.mp4` | 5 | 0.50 |
| 33 | Divorciado | `f_divorciado.mp4` | 3 | 0.50 |
