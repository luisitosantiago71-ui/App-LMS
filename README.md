# Aprende Lengua de Señas Mexicana

Aplicación Android para aprender el abecedario de LSM con referencias visuales y práctica mediante la cámara. La pantalla principal mantiene el panel de dos ESP32/BMI160 y ofrece accesos al abecedario y a Mis señas con imágenes.

## Funciones

- Practicar las 27 letras del abecedario desde una sola pantalla.
- Practicar J, K, Ñ, Q, X y Z con su video y validación por inicio, recorrido y postura final.
- Elegir la tolerancia Precisa, Normal o Flexible; la app inicia en Flexible para facilitar la práctica.
- Ampliar la referencia de la letra tocándola en la pantalla de práctica.
- Guardar localmente las referencias de movimiento generadas a partir de los videos integrados. Las referencias que ya existan se mantienen.
- Ajustar posición, tamaño, rotación y espejo de la guía por letra.
- Conservar Mis señas con imágenes: importar, nombrar, mapear, revisar puntos y silueta, guardar y practicar.
- Consultar datos de dos ESP32/BMI160. Los sensores son complementarios; la validación visual usa la cámara.

## Código principal

| Área | Archivos |
| --- | --- |
| Pantalla inicial y sensores | MainActivity, Bmi160Panel, Esp32BluetoothClient |
| Práctica del abecedario | PracticeSignActivity, HandOverlayView, ReferenceTemplate |
| Práctica de letras dinámicas | BundledMotionMapper, MotionReferenceStore, LetterMotionEngine, SequencePracticeEngine |
| Trazado de mano de referencia | MotionHandOverlayView, HandLandmarkSamples |
| Mis señas con imágenes | ImageSignEditorActivity, ImageSignStore, ImageSignsActivity |
| Compatibilidad con referencias anteriores de J | JReferenceStore |

JPracticeEngine conserva el detector de trayectoria particular de J y aporta los tipos compartidos de etapa, tolerancia y lectura de mano. JReferenceStore permite abrir referencias de J creadas por versiones anteriores. Las pantallas separadas para preparar movimientos se retiraron; los nombres históricos restantes no corresponden a opciones del menú.

## Referencias dinámicas

La primera vez que se practica una letra dinámica sin referencia guardada, el teléfono analiza el video incluido y guarda una referencia en el almacenamiento privado de la app. La próxima vez se carga la referencia guardada. Este primer análisis puede tardar unos segundos.

## Actualizar sin borrar información

Abre el proyecto en Android Studio y ejecútalo sobre la aplicación existente. No desinstales la app ni borres sus datos. Para instalar un APK manual, compílalo con el mismo identificador de aplicación y la misma clave de firma de la versión instalada. Consulta LEEME_ACTUALIZACION.md.

## Pruebas

El proyecto incluye pruebas de las etapas del movimiento, espejo, tolerancias, pérdida de seguimiento, rechazos de intentos sin recorrido y flujo de demostración. Revisa la práctica en el teléfono para confirmar que la cámara reconoce de forma estable los videos integrados y la mano del usuario.
