# Actualización 1.6 — referencias automáticas

Base: AprendeLS_Modulos_Palabras(1).zip. Versión de aplicación 1.6, código 7.

1. Descomprime esta entrega en una carpeta nueva y abre AprendeLenguajeDeSenas en Android Studio.
2. Configura el SDK local y sincroniza Gradle.
3. Compila e instala sobre la app existente con la misma firma. No es necesario desinstalar ni borrar datos.
4. Abre Aprender LMS → Saludos y despedidas. La primera apertura de cada palabra prepara su referencia automáticamente y muestra el progreso en la pantalla de práctica.
5. Las siguientes aperturas reutilizan la referencia. Si sustituyes el MP4 y vuelves a compilar e instalar, esa palabra se prepara otra vez.

Esta primera actualización al generador automático crea nuevas cachés de palabras. Los antiguos JSON en assets se conservan exclusivamente como datos históricos de pruebas y no se cargan en la práctica. No ejecutes preparar_palabras.py para publicar videos: ya no es necesario.

La guía completa está en GUIA_REFERENCIAS_AUTOMATICAS.md. Consulta INFORME_REFERENCIAS_AUTOMATICAS.md: los controles Java y las reproducciones de detecciones pasaron, pero no se pudo compilar un APK ni ejecutar la prueba Android aquí porque la descarga de Gradle está bloqueada.
