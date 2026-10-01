#!/usr/bin/env python3
"""Verifica el selector Java con detecciones de videos completos. JDK 17+ y Python 3.
Por defecto usa las detecciones incluidas; --reanalyze las renueva con MediaPipe.
Estas pruebas son opcionales: la app genera referencias sin Python.
"""
import argparse,hashlib,json,os,shutil,subprocess,tempfile
from pathlib import Path

def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
root=Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--reanalyze',action='store_true');args=parser.parse_args()
assets=root/'app/src/main/assets';model=assets/'hand_landmarker.task'
rows=[r for m in json.loads((assets/'word_lessons.json').read_text(encoding='utf-8'))['modules'] for r in m['lessons']]
java=shutil.which('java')
if not java and os.environ.get('JAVA_HOME'):java=str(Path(os.environ['JAVA_HOME'])/'bin'/('java.exe' if os.name=='nt' else 'java'))
if not java:raise SystemExit('Configura JDK 17+; puedes usar el jbr de Android Studio.')
fixtures=root/'tools/fixtures_auto';fixtures.mkdir(exist_ok=True)
for row in rows:
 name=row['video'];video=root/'app/src/main/res/raw'/f'{name}.mp4';path=fixtures/f'{name}.txt';meta=fixtures/f'{name}.metadata.json'
 if args.reanalyze:
  from preparar_palabras import detect,track
  a=detect(video,model,android_compatible=True);frames=track(a['frames'])
  def encode(f):
   v=[str(f['timeMs']),str(len(f['hands']))]
   for side,h in f['hands'].items():v += [side]+[str(x) for xy in h['points'] for x in xy]+[str(x) for x in h['flex']]
   return ' '.join(v)
  path.write_text(str(a['durationMs'])+'\n'+str(len(frames))+'\n'+'\n'.join(map(encode,frames))+'\n')
  meta.write_text(json.dumps({'videoSha256':sha(video),'modelSha256':sha(model),'detectionsSha256':sha(path)}))
 if not path.exists() or not meta.exists():raise SystemExit(f'Faltan datos de prueba para {name}. Usa --reanalyze (opcional; la app no necesita este paso).')
 m=json.loads(meta.read_text())
 if m['videoSha256']!=sha(video) or m['modelSha256']!=sha(model) or m['detectionsSha256']!=sha(path):
  raise SystemExit(f'Cambiaron los datos de {name}. Renueva solo las pruebas de escritorio con --reanalyze; la app se prepara sola.')
with tempfile.TemporaryDirectory(prefix='lsm_auto_') as tmp:
 tmp=Path(tmp);inputs=tmp/'inputs';inputs.mkdir();classes=tmp/'classes';classes.mkdir()
 for row in rows:
  name=row['video'];shutil.copyfile(fixtures/f'{name}.txt',inputs/f'{name}.txt')
  (inputs/f'{name}.properties').write_text('\n'.join(f'{k}={v}' for k,v in row.get('tolerance',{}).items()))
 source=root/'app/src/main/java/com/mechrobotix/aprendels'
 files=[source/f'{n}.java' for n in ['JPracticeEngine','WordTolerance','WordPracticeEngine','WordReferenceBuilder']]
 files += [root/'tools/WordEngineCheck.java',root/'tools/WordAutoReferenceCheck.java']
 subprocess.run([java,'com.sun.tools.javac.Main','-d',str(classes),*map(str,files)],check=True)
 subprocess.run([java,'-cp',str(classes),'WordAutoReferenceCheck',str(inputs),str(tmp/'generated')],check=True)
