#!/usr/bin/env python3
"""Pruebas del motor con las detecciones incluidas. Requiere Java/JDK 17+ y Python 3.
No necesita MediaPipe para volver a comprobar tolerancias. Ejecutar desde cualquier carpeta.
"""
import hashlib,json,os,shutil,subprocess,tempfile
from pathlib import Path
root=Path(__file__).resolve().parents[1];source=root/'app/src/main/java/com/mechrobotix/aprendels'
assets=root/'app/src/main/assets';catalog=json.loads((assets/'word_lessons.json').read_text(encoding='utf-8'))
java=shutil.which('java')
if not java and os.environ.get('JAVA_HOME'):
    candidate=Path(os.environ['JAVA_HOME'])/'bin'/('java.exe' if os.name=='nt' else 'java')
    if candidate.exists():java=str(candidate)
if not java:raise SystemExit('Instala JDK 17+ o configura JAVA_HOME (puedes usar el jbr de Android Studio).')
with tempfile.TemporaryDirectory(prefix='lsm_pruebas_') as tmp:
    tmp=Path(tmp);fixtures=tmp/'fixtures';fixtures.mkdir();classes=tmp/'classes';classes.mkdir()
    for module in catalog['modules']:
        for row in module['lessons']:
            name=row['video'];original=root/'tools/fixtures'/name
            meta=json.loads(original.with_suffix('.metadata.json').read_text())
            for path,key in [(root/'app/src/main/res/raw'/f'{name}.mp4','videoSha256'),(assets/'word_references'/f'{name}.json','referenceSha256')]:
                if hashlib.sha256(path.read_bytes()).hexdigest()!=meta[key]:raise SystemExit(f'Genera otra vez la referencia y pruebas de {name}: cambió el archivo.')
            shutil.copyfile(original.with_suffix('.txt'),fixtures/(name+'.txt'))
            (fixtures/(name+'.properties')).write_text('\n'.join(f'{k}={v}' for k,v in row.get('tolerance',{}).items()))
    files=[source/'JPracticeEngine.java',source/'WordTolerance.java',source/'WordPracticeEngine.java',root/'tools/WordEngineCheck.java']
    subprocess.run([java,'com.sun.tools.javac.Main','-d',str(classes),*map(str,files)],check=True)
    subprocess.run([java,'-cp',str(classes),'WordEngineCheck',str(fixtures)],check=True)
