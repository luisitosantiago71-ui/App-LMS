# Aprende Lenguaje de Señas · AprendeLS

Aplicación Android para practicar el abecedario de la **Lengua de Señas Mexicana (LSM)** con cámara, guías visuales y seguimiento de manos mediante MediaPipe. Incluye una práctica de movimiento para la letra J y un panel experimental de datos del BMI160 conectado a un ESP32 por Bluetooth.

## Para tu primera prueba

**Puedes probar la cámara sin comprar ni conectar el ESP32 o el BMI160.** Necesitas un teléfono Android 7.0 o superior (API 24), cámara y permiso para utilizarla. Una iluminación uniforme y mantener la mano completa dentro de la imagen ayudan al seguimiento.

Si solo quieres usar la aplicación, pide al responsable el APK. Si quieres generar tu propio APK usando el navegador de tu teléfono, sigue la siguiente sección.

## Compilar desde el teléfono con GitHub Actions

El teléfono inicia el proceso; **GitHub realiza la compilación en la nube**. No necesitas Android Studio en el celular. Necesitas internet y una cuenta de GitHub.

1. Abre este repositorio en el navegador e inicia sesión. Activa «Sitio de escritorio» si no aparecen las opciones.
2. Entra en **Actions → Compilar APK Android**.
3. Pulsa **Run workflow**, selecciona la rama que quieres compilar y confirma **Run workflow**.
4. Abre la ejecución y espera a que termine en verde. La primera puede tardar más porque descarga herramientas y dependencias.
5. En el resumen, busca **Artifacts** y descarga **AprendeLS-debug-N**, donde N es el número de ejecución.
6. Descomprime el ZIP descargado y abre **app-debug.apk**.
7. Si Android lo solicita, permite instalar aplicaciones desde el navegador o administrador de archivos que estás usando. Después de instalar puedes retirar ese permiso.
8. Abre la app y concede permiso de cámara. Para el panel del sensor, también concede el permiso Bluetooth solicitado.

**Acceso:** ejecutar manualmente un workflow requiere permiso de escritura en el repositorio. Si tu compañero solo tiene lectura, el propietario puede ejecutar la compilación y el compañero descargar el resultado iniciando sesión. Si el repositorio es público, otra opción es crear un **Fork**, habilitar Actions en su copia y ejecutar allí el flujo. En repositorios privados, el propietario debe invitar al compañero. Los artefactos de este flujo se conservan durante 14 días, sujetos a la política del repositorio; después se puede volver a compilar.

El archivo `.github/workflows/android-apk.yml` debe estar en la rama predeterminada para que aparezca la opción de ejecución manual. El repositorio debe tener Actions habilitado y disponibilidad de uso según su plan.

### Qué tipo de APK genera

Es un **APK debug para pruebas**, no una publicación de Google Play. No requiere claves privadas de producción. Las firmas de depuración pueden variar entre computadoras o ejecuciones de CI. Si Android indica «conflicto con un paquete existente», puede deberse a otra firma: desinstalar la versión anterior permite instalar la nueva, pero **borra sus ajustes y referencias locales**. No desinstales si necesitas conservar esos datos; coordina primero una firma estable con el responsable.

## Cómo usar la aplicación

### Práctica del abecedario

1. En la pantalla principal pulsa **Practicar con Cámara**.
2. Con **Elegir letra** selecciona directamente la letra deseada.
3. Observa la imagen de referencia e imita la postura frente a la cámara.
4. Usa **Ajustar guía** para cambiar tamaño, posición y rotación; **Espejo** permite invertir la guía.
5. **Revisar imágenes** permite revisar y corregir la referencia. Cierra los ajustes para practicar.
6. Sigue los mensajes y la barra de coincidencia. Al completar la práctica se habilita el avance correspondiente.

### Letra J con movimiento

1. Abre la preparación de J desde la práctica de cámara o selecciona J y pulsa **Practicar J**.
2. Reproduce el video y pulsa **Mapear video**.
3. Revisa los puntos cuadro a cuadro. Que haya detección no garantiza que cada punto esté bien colocado.
4. Selecciona el tramo con **Marcar inicio** y **Marcar final**. Corrige o vuelve a detectar los cuadros que lo necesiten.
5. Confirma la revisión y guarda la referencia; después entra a **Practicar J**.
6. Imita la postura inicial, realiza la curva y termina con la postura final. El ejercicio usa esas fases y tolerancias; no exige copiar exactamente un dibujo del recorrido.
7. Ajusta **Tolerancia** o **Espejo** cuando corresponda y usa **Reintentar** para un nuevo intento.

La referencia preparada y los ajustes se guardan en cada dispositivo: clonar el repositorio o instalar el APK **no copia los ajustes personales del desarrollador**. En un teléfono nuevo hay que preparar y guardar la referencia de J.

### Panel BMI160 / ESP32 (opcional)

El panel de J permite conectar un ESP32 previamente emparejado, revisar aceleración y velocidad de giro y calibrar el cero del giroscopio dejando el sensor inmóvil.

**En esta versión es un diagnóstico: el BMI160 todavía no aprueba ni rechaza la J.** La validación de J usa la cámara. Para futuras pruebas de movimiento, el sensor debe estar sujeto con firmeza y orientación consistente; un sensor suelto mide sus propios movimientos.

El ESP32 debe ejecutar un firmware compatible con el protocolo que espera la app:

```text
IMU1,secuencia,millis,ax,ay,az,gx,gy,gz
```

Cada muestra termina en un salto de línea; aceleración en g y velocidad angular en grados por segundo. Se usa Bluetooth clásico SPP, no BLE. El firmware de Arduino se entrega por separado: este paquete contiene el proyecto Android.

### Otras pantallas y alcance

- **Ir al alfabeto**: catálogo visual que también conserva el envío de comandos Bluetooth de la etapa del guante. No es la misma pantalla que la práctica con cámara.
- **Prueba de movimiento**: ejercicio técnico experimental de seguimiento y ángulos.
- La interfaz aún conserva algunas etiquetas «guante» de la primera etapa del proyecto.
- Aprender palabras con dos manos y fusionar cámara con sensores son objetivos del proyecto; no se presentan aquí como módulos completos ya disponibles. Tampoco se incluye validación de expresiones faciales.

## Compilar en una computadora

Abre la carpeta que contiene `settings.gradle.kts` con una versión de Android Studio compatible con AGP 9.3.1. Instala los componentes solicitados por el SDK Manager y configura Java 21 para Gradle.

| Componente | Configuración de este proyecto |
|---|---|
| Lenguajes | Kotlin y Java |
| Android mínimo | API 24 / Android 7.0 |
| SDK de compilación | Android 36.1 |
| Target SDK | 36 |
| Android Gradle Plugin | 9.3.1 |
| Gradle Wrapper | 9.7.1 |
| JDK para Gradle | 21, según el archivo de criterios del daemon |
| Compatibilidad de código Java | 11 |
| MediaPipe Tasks Vision | 0.10.14 |
| CameraX | 1.3.1 |

En Windows, desde la raíz del proyecto:

```powershell
.\gradlew.bat :app:assembleDebug
```

En Linux/macOS:

```bash
chmod +x gradlew
./gradlew :app:assembleDebug
```

El APK queda en `app/build/outputs/apk/debug/app-debug.apk`. La primera compilación requiere internet. Android Studio genera `local.properties` con la ruta local del SDK; ese archivo no se comparte. Fuera de Android Studio configura `ANDROID_HOME` con la instalación del SDK.

## Archivos importantes

| Ruta | Función |
|---|---|
| `app/src/main/java/com/mechrobotix/aprendels/` | Código de pantallas, validadores y Bluetooth |
| `app/src/main/res/layout/` | Interfaces XML |
| `app/src/main/res/drawable/` | Imágenes del abecedario y recursos visuales |
| `app/src/main/res/raw/letra_j.mp4` | Demostración de J |
| `app/src/main/assets/hand_landmarker.task` | Modelo de seguimiento de manos |
| `.github/workflows/android-apk.yml` | Compilación manual y descarga del APK |
| `docs/SUBIR_A_GITHUB.md` | Publicación inicial y colaboración |

## Solución de problemas

| Problema | Qué revisar |
|---|---|
| No aparece Run workflow | Rama predeterminada, permisos de escritura y Actions habilitado |
| Compilación roja | Abre el paso que falló y comparte su primer error; conserva el enlace a la ejecución |
| No encuentro el APK | Descarga el artefacto de una ejecución verde, no el ZIP del código fuente |
| Falta el modelo o video | Conserva `assets/hand_landmarker.task` y `res/raw/letra_j.mp4` al subir el proyecto |
| No permite practicar J | Primero revisa y guarda la referencia del video en ese teléfono |
| Seguimiento inestable | Mano completa visible, luz uniforme y fondo que permita distinguirla |
| BMI160 no recibe datos | Emparejamiento, permisos, Bluetooth clásico y firmware compatible |
| Error de firma al instalar | Consulta la sección sobre APK debug antes de desinstalar |

## Licencia y recursos

Se conserva el archivo `LICENSE` incluido en el proyecto original, con el texto de GNU GPL v3. El modelo de MediaPipe y otros recursos de terceros mantienen sus propios términos. Antes de hacer público el repositorio, confirma los permisos de redistribución de las imágenes y del video que incorporaste.

## Documentación oficial

- [Ejecutar manualmente GitHub Actions](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/manually-run-a-workflow)
- [Descargar artefactos de una ejecución](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/download-workflow-artifacts)
- [Compatibilidad de Android Gradle Plugin 9.3](https://developer.android.com/build/releases/agp-9-3-0-release-notes)

La preparación de este repositorio conserva el código de la app y sus versiones. La compilación completa debe confirmarse con la primera ejecución de Actions; no se incluye un APK compilado como resultado de esta preparación.
