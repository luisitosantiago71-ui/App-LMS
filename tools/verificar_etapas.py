#!/usr/bin/env python3
"""Pruebas Java de etapas con detecciones completas incluidas. Python 3 + JDK 17.
--reanalyze vuelve a detectar videos con Python 3.10-3.12 y requirements-palabras.txt.
La app no necesita ejecutar este script.
"""
import argparse,hashlib,json,os,shutil,subprocess,tempfile
from pathlib import Path
root=Path(__file__).resolve().parents[1];assets=root/'app/src/main/assets';model=assets/'hand_landmarker.task'
parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--reanalyze',action='store_true');args=parser.parse_args()
rows=[r for m in json.loads((assets/'word_lessons.json').read_text(encoding='utf-8'))['modules'] for r in m['lessons']]
fixtures=root/'tools/fixtures_auto'
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
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
 m=json.loads(meta.read_text())
 if m['videoSha256']!=sha(video) or m['modelSha256']!=sha(model) or m['detectionsSha256']!=sha(path):raise SystemExit(f'Datos de prueba antiguos: {name}. Usa --reanalyze; la app se prepara sola.')
java=shutil.which('java') or str(Path(os.environ.get('JAVA_HOME',''))/'bin/java')
with tempfile.TemporaryDirectory(prefix='lsm_etapas_') as tmp:
 tmp=Path(tmp);inputs=tmp/'inputs';inputs.mkdir();classes=tmp/'classes';classes.mkdir()
 for row in rows:
  name=row['video'];shutil.copyfile(fixtures/f'{name}.txt',inputs/f'{name}.txt')
  (inputs/(name+'.reference.properties')).write_text(str(row.get('recoverSparseMotion',False)).lower())
  for key,suffix in [('tolerance','.properties'),('validation','.validation.properties')]:
   (inputs/(name+suffix)).write_text('\n'.join(f'{k}={v}' for k,v in row.get(key,{}).items()))
 source=root/'app/src/main/java/com/mechrobotix/aprendels'
 files=[source/f'{n}.java' for n in ['JPracticeEngine','WordTolerance','WordStageOptions','WordStagePlan','WordPracticeEngine','WordReferenceBuilder']]
 files += [root/'tools/WordEngineCheck.java',root/'tools/WordStagesCheck.java',root/'tools/WordCompletionCheck.java']
 subprocess.run([java,'com.sun.tools.javac.Main','-d',str(classes),*map(str,files)],check=True)
 subprocess.run([java,'-cp',str(classes),'WordStagesCheck',str(inputs)],check=True)
