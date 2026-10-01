> Informe histórico de la versión 1.5, anterior al generador automático. Para esta entrega consulta INFORME_REFERENCIAS_AUTOMATICAS.md.

# Informe de pruebas de palabras

Base: AprendeLenguajeDeSenas(10).rar. Actualización 1.5.

## Videos y referencias

Se analizaron los diez MP4 con MediaPipe Tasks Python 0.10.21 y el mismo archivo hand_landmarker.task incluido en la app. Android conserva su dependencia Tasks Vision 0.10.14; el comportamiento entre plataformas y usuarios requiere comprobación en el teléfono. No se modificó ningún MP4.

| Palabra | Inicio ms | Fin ms | Hitos | Hitos con dos manos |
|---|---:|---:|---:|---:|
| Hola | 300 | 2200 | 5 | 0 |
| ¿Cómo estás? | 2000 | 6100 | 11 | 10 |
| Yo bien | 1000 | 3500 | 6 | 0 |
| Yo mal | 700 | 3000 | 6 | 0 |
| Buenos días | 200 | 3900 | 8 | 1 |
| Buenas tardes | 200 | 5100 | 10 | 1 |
| Buenas noches | 200 | 5400 | 10 | 0 |
| Nos vemos pronto | 1400 | 6400 | 11 | 5 |
| Nos vemos mañana | 600 | 5900 | 7 | 4 |
| Nos vemos la próxima | 700 | 5400 | 12 | 11 |

## Motor Java: resultado

- 10/10 secuencias de detecciones del video completaron su propia referencia.
- 10/10 repeticiones con escala 0.70 y traslación también completaron.
- 0/90 combinaciones de videos distintos completaron una lección ajena.
- Se rechazaron manos inmóviles, saltar de inicio a final, secuencias invertidas y ensayos con una sola mano en referencias que requieren dos.
- Un timestamp repetido no avanzó la etapa inicial; la pérdida prolongada de seguimiento reinició el intento.

Hola usa shape=0.70, frente a 0.78 general, porque con 0.78 una parte de Buenos días superaba esa lección. El ajuste conservó el positivo de Hola y eliminó esa confusión en este conjunto.

Estos ensayos reutilizan detecciones de los videos que generaron las referencias. No constituyen una medición independiente de precisión, ni demuestran reconocimiento perfecto con alumnos. No hubo pruebas físicas de cámara, BLE o usuarios desde este entorno.

## Integración

- XML analizados sin errores; IDs utilizados por los nuevos bindings cotejados con layouts.
- Diez videos y diez referencias con SHA-256 coincidente; hitos en orden y catálogo en el orden solicitado.
- Archivos de validación del abecedario (JPracticeEngine, SequencePracticeEngine, LetterMotionEngine, HandOverlayView) y Bluetooth/Bmi160Panel comparados con el archivo original: sin cambios.
- Retiradas las cinco clases y los tres layouts de imágenes personalizadas, con referencias de código y manifiesto actualizadas.
- Archivos Kotlin nuevos examinados con parser de sintaxis sin errores. Esta revisión no reemplaza al compilador Android. El parser tiene limitaciones con dos archivos preexistentes; los mismos avisos aparecen al analizar sus originales.

## Compilación Android pendiente

Se intentó :app:assembleDebug mediante el wrapper del proyecto. No pudo descargar Gradle 9.7.1 desde services.gradle.org: Network is unreachable. No se generó APK ni se afirma que la app completa esté compilada. Se conservaron las versiones de Gradle, AGP, SDK y dependencias del proyecto recibido.

## Reproducir las pruebas

Con Python 3 y JDK 17+ disponibles:

```
python tools/verificar_palabras.py
```

Los detalles para videos nuevos y pruebas con personas están en GUIA_MODULOS_Y_TOLERANCIAS.md.
