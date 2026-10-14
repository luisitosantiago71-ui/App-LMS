> **Actualización 1.9:** Familia (33 palabras), avance automático y perfiles por palabra. Consulta [instalación](LEEME_FAMILIA_1_9.md), [guía](GUIA_FAMILIA_Y_AVANCE.md) e [informe actual](INFORME_FAMILIA_1_9.md). El contenido siguiente corresponde a la versión anterior.

# Aprende Lengua de Señas Mexicana — 1.8

Práctica de abecedario y palabras mediante cámara y videos. Las palabras admiten entre tres y seis etapas. Las tres despedidas incluyen seis controles y verificación adicional del recorrido para impedir los casos reproducidos de aprobación anticipada.

- [Cómo ajustar etapas, tolerancias y hacer pruebas](GUIA_ETAPAS_Y_CIERRE.md)
- [Resultados y limitaciones de esta entrega](INFORME_PRUEBAS_CIERRE.md)
- [Instalación](LEEME_ACTUALIZACION.md)

Edita `app/src/main/assets/word_lessons.json` para configurar cada palabra. Al reemplazar un video se mantiene la regeneración automática de referencias por hash. No hay mapeo manual.

Pruebas de escritorio: 30/30 variantes positivas y 30/30 interrupciones correctamente rechazadas. Persisten 5/90 cruces aceptados entre palabras distintas; es un modo de práctica, no una calificación estricta. La compilación Android quedó bloqueada por la resolución de un plugin existente; falta comprobar el teléfono.
