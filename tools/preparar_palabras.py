#!/usr/bin/env python3
"""Genera referencias de palabras desde res/raw sin editor de fotogramas.
Python 3.10–3.12: python -m pip install -r tools/requirements-palabras.txt
Uso: python tools/preparar_palabras.py [--video s_hola] [--project RUTA]
No descarga modelos: usa assets/hand_landmarker.task del proyecto.
"""
import argparse, hashlib, itertools, json, math, os
from pathlib import Path
os.environ.setdefault('TF_CPP_MIN_LOG_LEVEL','3')

def palm(hand):
    p=hand['points']
    return max(math.dist(p[a],p[b]) for a,b in [(0,5),(0,9),(0,13),(0,17),(5,17)])
def track(frames):
    """Asocia por proximidad para no cambiar de mano solo por un cambio de etiqueta."""
    previous={};out=[]
    for frame in frames:
        time=frame['timeMs'];detected=list(frame['hands'].items())
        recent={k:v for k,v in previous.items() if time-v[0]<=1100}
        best=None
        for assignment in itertools.permutations(['Left','Right'],len(detected)):
            cost=0
            for (label,h),side in zip(detected,assignment):
                if side in recent:
                    old=recent[side][1]
                    cost+=min(8,math.dist(old['points'][0],h['points'][0])/max(8,palm(old)))
                    cost+=sum(abs(a-b) for a,b in zip(old['flex'],h['flex']))/1800
                else:cost+=12.0+(0 if label==side else .8)
            if best is None or cost<best[0]:best=(cost,assignment)
        hands={side:h for (_,h),side in zip(detected,best[1] if best else [])}
        for side,h in hands.items():previous[side]=(time,h)
        out.append({'timeMs':time,'hands':hands})
    return out

def change(a,b):
    return max([sum(math.dist(p,q) for p,q in zip(h['points'],b['hands'][k]['points']))/21/palm(h)
                for k,h in a['hands'].items() if k in b['hands']]+[0])

def detect(video,model,android_compatible=False):
    import cv2
    import mediapipe as mp
    capture=cv2.VideoCapture(str(video));fps=capture.get(cv2.CAP_PROP_FPS)
    if fps<=0:raise ValueError(f'No se pudo leer {video.name}')
    duration=round(capture.get(cv2.CAP_PROP_FRAME_COUNT)/fps*1000)
    if not 1000<=duration<=60000:raise ValueError('Usa videos de 1 a 60 segundos con una sola persona')
    options=mp.tasks.vision.HandLandmarkerOptions(
        base_options=mp.tasks.BaseOptions(model_asset_path=str(model)),
        running_mode=mp.tasks.vision.RunningMode.VIDEO,num_hands=2,
        min_hand_detection_confidence=.45,min_hand_presence_confidence=.45,min_tracking_confidence=.45)
    frames=[]
    try:
        with mp.tasks.vision.HandLandmarker.create_from_options(options) as detector:
            for time in range(0,duration,100):
                capture.set(cv2.CAP_PROP_POS_MSEC,time);ok,img=capture.read()
                if not ok:raise ValueError(f'Fotograma ilegible en {time} ms')
                height,width=img.shape[:2];scale=min(1,960/max(width,height))
                img=cv2.resize(img,(round(width*scale),round(height*scale)))
                result=detector.detect_for_video(mp.Image(image_format=mp.ImageFormat.SRGB,data=cv2.cvtColor(img,cv2.COLOR_BGR2RGB)),time)
                hands={}
                for coords,world,categories in zip(result.hand_landmarks,result.hand_world_landmarks,result.handedness):
                    points=[[round(v.x*width/height*1000,3),round(v.y*1000,3)] for v in coords]
                    if android_compatible:
                        if any(not math.isfinite(v.x) or not math.isfinite(v.y) or not 0<=v.x<=1 or not 0<=v.y<=1 for v in coords):continue
                        if max(math.dist(points[a],points[b]) for a,b in [(0,5),(0,9),(0,13),(0,17),(5,17)])<8:continue
                    elif math.dist(points[0],points[9])<8:continue
                    flex=[]
                    for a,b,c in [(1,2,3),(2,3,4),(5,6,7),(6,7,8),(9,10,11),(10,11,12),(13,14,15),(14,15,16),(17,18,19),(18,19,20)]:
                        u=[getattr(world[a],k)-getattr(world[b],k) for k in ['x','y','z']]
                        v=[getattr(world[c],k)-getattr(world[b],k) for k in ['x','y','z']]
                        norm=math.sqrt(sum(x*x for x in u)*sum(x*x for x in v))
                        if norm<1e-12:break
                        flex.append(round(180-math.degrees(math.acos(max(-1,min(1,sum(x*y for x,y in zip(u,v))/norm)))),3))
                    if len(flex)!=10:continue
                    side=categories[0].category_name
                    # Dos detecciones con igual etiqueta se conservan en ranuras distintas.
                    if side in hands:side='Right' if side=='Left' else 'Left'
                    hands[side]={'points':points,'flex':flex}
                frames.append({'timeMs':time,'hands':hands})
    finally:capture.release()
    return {'durationMs':duration,'frames':frames}

def compile_reference(video,row,analysis):
    frames=track(analysis['frames'])
    visible=[f for f in frames if f['hands'] and any(h['points'][0][1]<850 for h in f['hands'].values())]
    if len(visible)<10:raise ValueError('No hay suficientes manos visibles; cambia encuadre o video')
    start=row.get('startMs',visible[0]['timeMs']);end=row.get('endMs',visible[-1]['timeMs'])
    if not 0<=start<end<=analysis['durationMs']:raise ValueError('startMs/endMs fuera de la duración del video')
    selected=[f for f in frames if start<=f['timeMs']<=end and f['hands']]
    if len(selected)<10 or selected[-1]['timeMs']-selected[0]['timeMs']<900:raise ValueError('Tramo de práctica demasiado corto')
    longest_gap=max(b['timeMs']-a['timeMs'] for a,b in zip(selected,selected[1:]))
    if longest_gap>700:raise ValueError(f'Se pierden todas las manos durante {longest_gap} ms; mejora el video')
    lengths=[0.0]
    for a,b in zip(selected,selected[1:]):lengths.append(lengths[-1]+min(change(a,b),2.0))
    if lengths[-1]<.6:raise ValueError('No se distingue suficiente movimiento del ruido de detección')
    count=min(12,max(5,round((end-start)/650)+2))
    indices={0,len(selected)-1}
    for k in range(1,count-1):
        indices.add(next(i for i,v in enumerate(lengths) if v>=lengths[-1]*k/(count-1)))
    # No perder un tramo de dos manos aunque el muestreo de movimiento caiga fuera de él.
    runs=[];run=[]
    for i,f in enumerate(selected):
        if len(f['hands'])==2:run.append(i)
        elif run:runs.append(run);run=[]
    if run:runs.append(run)
    for run in runs:
        if len(run)>=2:indices.add(run[len(run)//2])
    keys=[]
    for i in sorted(indices):
        f=selected[i]
        if keys and change(keys[-1],f)<.13 and set(keys[-1]['hands'])==set(f['hands']):continue
        keys.append(f)
    if keys[-1]['timeMs']!=selected[-1]['timeMs']:
        if change(keys[-1],selected[-1])<.13:keys[-1]=selected[-1]
        else:keys.append(selected[-1])
    if len(keys)<4:raise ValueError('Se necesitan al menos cuatro hitos distintos')
    primary=max(keys[0]['hands'],key=lambda s:sum(s in f['hands'] for f in selected))
    for f in keys:f['hands']={s:f['hands'][s] for s in [primary,'Left' if primary=='Right' else 'Right'] if s in f['hands']}
    reference={'schema':1,'video':video.stem,'sha256':hashlib.sha256(video.read_bytes()).hexdigest(),
        'durationMs':analysis['durationMs'],'startMs':keys[0]['timeMs'],'endMs':keys[-1]['timeMs'],
        'primary':primary,'keys':keys,'quality':{'sampledFrames':len(selected),'keyCount':len(keys),'largestGapMs':longest_gap,
        'twoHandKeys':sum(len(f['hands'])==2 for f in keys)}}
    return reference,selected

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--project',type=Path,default=Path(__file__).resolve().parents[1]);parser.add_argument('--video')
    # Caché opcional para repetir la selección de hitos sin ejecutar el modelo otra vez.
    parser.add_argument('--cache',type=Path)
    args=parser.parse_args();assets=args.project/'app/src/main/assets';raw=args.project/'app/src/main/res/raw'
    catalog=json.loads((assets/'word_lessons.json').read_text(encoding='utf-8'))
    rows=[row for m in catalog['modules'] for row in m['lessons'] if not args.video or row['video']==args.video]
    if not rows:parser.error('No existe ese video en word_lessons.json')
    output=assets/'word_references';output.mkdir(exist_ok=True)
    for row in rows:
        name=row['video'];video=raw/(name+'.mp4')
        cached=args.cache/(name+'.json') if args.cache else None
        current_hash=hashlib.sha256(video.read_bytes()).hexdigest()
        if cached and cached.exists():
            analysis=json.loads(cached.read_text())
            if analysis.get('sha256')!=current_hash:raise ValueError(f'Caché de otro video: {name}; borra solo su archivo de caché y repite')
        else:
            analysis=detect(video,assets/'hand_landmarker.task');analysis['sha256']=current_hash
            if cached:cached.parent.mkdir(parents=True,exist_ok=True);cached.write_text(json.dumps(analysis))
        reference,selected=compile_reference(video,row,analysis)
        target=output/(name+'.json');temp=target.with_suffix('.tmp')
        temp.write_text(json.dumps(reference,ensure_ascii=False,separators=(',',':'))+'\n',encoding='utf-8');temp.replace(target)
        # Datos reproducibles para verificar el motor Java sin instalar Android Studio.
        fixtures=args.project/'tools/fixtures';fixtures.mkdir(exist_ok=True)
        def encode(frame):
            values=[str(frame['timeMs']),str(len(frame['hands']))]
            for side,hand in frame['hands'].items():
                values+=[side]+[str(x) for xy in hand['points'] for x in xy]+[str(x) for x in hand['flex']]
            return ' '.join(values)
        (fixtures/(name+'.txt')).write_text(str(len(reference['keys']))+'\n'+'\n'.join(map(encode,reference['keys']))+'\n'+str(len(selected))+'\n'+'\n'.join(map(encode,selected))+'\n')
        (fixtures/(name+'.metadata.json')).write_text(json.dumps({'videoSha256':current_hash,'referenceSha256':hashlib.sha256(target.read_bytes()).hexdigest()}))
        print(f"{name}: {len(reference['keys'])} hitos, {reference['quality']['twoHandKeys']} con ambas manos, {reference['startMs']}–{reference['endMs']} ms",flush=True)
    print('Referencias generadas. Compila la app y prueba aciertos y errores reales; esto no certifica la corrección lingüística de la seña.')
if __name__=='__main__':main()
