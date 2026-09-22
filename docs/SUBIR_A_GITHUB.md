# Subir el proyecto y compartirlo

## Opción sencilla: GitHub Desktop en una computadora

1. Descomprime `AprendeLS_GitHub.zip`.
2. Instala GitHub Desktop e inicia sesión en tu cuenta.
3. Selecciona **File → Add local repository** y elige la carpeta `AprendeLS` que contiene `app`, `gradlew` y `settings.gradle.kts`.
4. Si indica que aún no es un repositorio, pulsa **create a repository here**. Verifica que la raíz siga siendo esa carpeta, sin crear otra carpeta vacía anidada. No generes una licencia nueva: el proyecto ya incluye `LICENSE`.
5. En Changes revisa los archivos. Deben incluir `.github/workflows/android-apk.yml`, el modelo `.task`, el video `.mp4` y `gradle/wrapper/gradle-wrapper.jar`. No deben incluir `local.properties`, cachés, claves privadas ni carpetas `build`.
6. Escribe «Preparar proyecto Android y compilación con GitHub Actions» y pulsa **Commit to main** si todavía hay cambios pendientes.
7. Pulsa **Publish repository**, elige un nombre y decide si será privado. Conservarlo privado permite compartirlo mediante invitación con tu compañero.
8. En GitHub abre **Settings → Collaborators** e invita a tu compañero. Para que ejecute el workflow manualmente necesita permiso de escritura; para descargar un resultado existente basta acceso de lectura e iniciar sesión.
9. En **Actions → Compilar APK Android → Run workflow** inicia la primera compilación. Comprueba que termine en verde antes de compartir el APK.

Si ya tienes un repositorio con archivos, clónalo primero y copia estos archivos dentro, revisando las diferencias. No uses `push --force` ni reemplaces su historial.

## Alternativa con Git

Crea en GitHub un repositorio vacío, sin README, licencia ni gitignore automáticos. Abre una terminal en la carpeta `AprendeLS` y ejecuta:

```bash
git init -b main
git add .
git status
git commit -m "Preparar app Android y compilacion desde GitHub Actions"
git remote add origin https://github.com/TU_USUARIO/TU_REPOSITORIO.git
git push -u origin main
```

Sustituye la dirección por la real. Git puede pedir configurar tu nombre y correo para los commits; utiliza tu identidad de GitHub o su correo privado `noreply`. La autenticación se realiza con las herramientas de GitHub de tu equipo; no escribas contraseñas ni tokens en archivos del proyecto.

## Si subes desde el navegador

Sube el contenido descomprimido en la raíz del repositorio, **no el ZIP como único archivo**. GitHub no descomprime el proyecto automáticamente. Asegúrate de incluir las carpetas que comienzan con punto, especialmente `.github`, y conservar todas las subcarpetas. Para este proyecto, GitHub Desktop es más cómodo que cargar decenas de archivos desde el teléfono.

## Enlace que debes enviar a tu compañero

Comparte el enlace del repositorio y dile:

> Abre el README. En «Compilar desde el teléfono» están los pasos para iniciar Actions, descargar el ZIP del artefacto e instalar app-debug.apk. Para probar las letras no necesitas el sensor. La referencia de J debes prepararla y guardarla en tu propio teléfono.

## Cambios de esta preparación

- README actualizado al funcionamiento del código recibido.
- Agregado workflow manual `.github/workflows/android-apk.yml`.
- Agregada esta guía de publicación.
- Agregado `.gitattributes` para conservar finales de línea y recursos binarios.
- Ampliado `.gitignore` para excluir configuración privada `.env`.
- Conservados código Kotlin/Java, XML, recursos, Gradle y licencia originales.
- Omitidas cachés, resultados de compilación, configuración del equipo y metadatos del IDE en el paquete limpio.
