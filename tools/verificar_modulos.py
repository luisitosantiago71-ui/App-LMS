#!/usr/bin/env python3
"""Prueba el catálogo actual (Python 3 + JDK 17). --module familia/saludos_despedidas.
--reanalyze actualiza solamente detecciones faltantes o cuyos hashes cambiaron.
La app genera sus propias referencias automáticamente; este script es para escritorio.
"""
import argparse,hashlib,json,os,shutil,subprocess,tempfile
from pathlib import Path
root=Path(__file__).resolve().parents[1];assets=root/'app/src/main/assets'
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--module');p.add_argument('--reanalyze',action='store_true');p.add_argument('--report',type=Path);args=p.parse_args()
modules=json.loads((assets/'word_lessons.json').read_text(encoding='utf-8'))['modules']
if args.module and not any(m['id']==args.module for m in modules):p.error('Módulo desconocido')
rows=[r for m in modules if not args.module or m['id']==args.module for r in m['lessons']]
if not rows:p.error('El módulo no contiene palabras')
fixtures=root/'tools/fixtures_auto';fixtures.mkdir(exist_ok=True);model=assets/'hand_landmarker.task'
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
model_hash=sha(model)
for row in rows:
 name=row['video'];video=root/'app/src/main/res/raw'/f'{name}.mp4';path=fixtures/f'{name}.txt';meta=path.with_suffix('.metadata.json')
 expected={'videoSha256':sha(video),'modelSha256':model_hash}
 try:
  stored=json.loads(meta.read_text());current=all(stored.get(k)==v for k,v in expected.items()) and stored.get('detectionsSha256')==sha(path)
 except (OSError,ValueError):current=False
 if not current:
  if not args.reanalyze:raise SystemExit(f'Detecciones faltantes/antiguas: {name}. Ejecuta de nuevo con --reanalyze.')
  from preparar_palabras import detect,track
  a=detect(video,model,android_compatible=True);frames=track(a['frames'])
  def encode(f):
   v=[str(f['timeMs']),str(len(f['hands']))]
   for side,h in f['hands'].items():v += [side]+[str(x) for xy in h['points'] for x in xy]+[str(x) for x in h['flex']]
   return ' '.join(v)
  path.write_text(str(a['durationMs'])+'\n'+str(len(frames))+'\n'+'\n'.join(map(encode,frames))+'\n')
  meta.write_text(json.dumps(dict(expected,detectionsSha256=sha(path))))
java=shutil.which('java') or str(Path(os.environ.get('JAVA_HOME',''))/'bin/java')
with tempfile.TemporaryDirectory(prefix='lsm_modulos_') as tmp:
 tmp=Path(tmp);inputs=tmp/'inputs';inputs.mkdir();classes=tmp/'classes';classes.mkdir()
 for row in rows:
  name=row['video'];shutil.copyfile(fixtures/f'{name}.txt',inputs/f'{name}.txt')
  (inputs/(name+'.reference.properties')).write_text(str(row.get('recoverSparseMotion',False)).lower())
  for key,suffix in [('tolerance','.properties'),('validation','.validation.properties')]:
   (inputs/(name+suffix)).write_text('\n'.join(f'{k}={str(v).lower() if isinstance(v,bool) else v}' for k,v in row.get(key,{}).items()))
 source=root/'app/src/main/java/com/mechrobotix/aprendels'
 names=['JPracticeEngine','WordTolerance','WordStageOptions','WordStagePlan','WordPracticeEngine','WordReferenceBuilder','WordAutoAdvance']
 files=[source/f'{n}.java' for n in names]+[root/'tools'/f'{n}.java' for n in ['WordEngineCheck','WordStagesCheck','WordCompletionCheck','WordModuleCheck','WordAutoAdvanceCheck']]
 subprocess.run([java,'com.sun.tools.javac.Main','-d',str(classes),*map(str,files)],check=True)
 subprocess.run([java,'-cp',str(classes),'WordAutoAdvanceCheck'],check=True)
 result=subprocess.run([java,'-cp',str(classes),'WordModuleCheck',str(inputs)],text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
 print(result.stdout,end='')
 if args.report:args.report.parent.mkdir(parents=True,exist_ok=True);args.report.write_text(result.stdout,encoding='utf-8')
 raise SystemExit(result.returncode)
