# Aprende Lengua de Señas Mexicana — 1.6

Práctica Android del abecedario y módulos de palabras mediante cámara y videos. La pantalla principal conserva el panel de dos ESP32/BMI160 y el botón **Aprender LMS**.

- Abecedario: 27 letras; J, K, Ñ, Q, X y Z conservan su motor dinámico.
- Saludos y despedidas: diez videos, con referencias generadas automáticamente al abrir cada palabra.
- Familia, Colores, Números y módulos nuevos: se habilitan al registrar sus lecciones en el catálogo.

## Flujo de videos

**Agregar/reemplazar MP4 en res/raw → registrar si es una palabra nueva → compilar e instalar → practicar.** La preparación se ejecuta en el teléfono. El hash del contenido permite regenerar solo la referencia que cambió; las otras se conservan. No necesitas Python, editores ni revisiones cuadro por cuadro.

Abre el proyecto en Android Studio y sincroniza Gradle. Se mantiene `com.mechrobotix.aprendels`, versión 1.6/código 7. Instala con la misma firma para conservar datos.

- [Guía de funcionamiento, archivos y tolerancias](GUIA_REFERENCIAS_AUTOMATICAS.md)
- [Pruebas realizadas y comprobaciones pendientes](INFORME_REFERENCIAS_AUTOMATICAS.md)
- [Instalación](LEEME_ACTUALIZACION.md)

Tolerancias por palabra: `app/src/main/assets/word_lessons.json`. Valores generales: `app/src/main/java/com/mechrobotix/aprendels/WordTolerance.java`.

El detector compara manos y movimiento con el ejemplo; no certifica el significado de LSM, expresiones faciales ni contacto con el cuerpo. El motor actual de palabras requiere movimiento. Los BMI160 siguen disponibles para consulta y no participan en la aprobación de palabras.
