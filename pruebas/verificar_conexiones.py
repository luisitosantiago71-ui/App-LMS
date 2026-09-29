"""Compila el cliente real contra dobles Android y prueba dos sockets simulados."""
from pathlib import Path
from tempfile import TemporaryDirectory
from urllib.request import urlretrieve
from concurrent.futures import ThreadPoolExecutor
import os, subprocess
here=Path(__file__).resolve().parent
source=here.parent/'app/src/main/java/com/mechrobotix/aprendels'
items=[('org.jetbrains.kotlin','kotlin-compiler-embeddable','2.2.0'),('org.jetbrains.kotlin','kotlin-stdlib','2.2.0'),('org.jetbrains.kotlin','kotlin-script-runtime','2.2.0'),('org.jetbrains.kotlin','kotlin-reflect','1.6.10'),('org.jetbrains.kotlin','kotlin-daemon-embeddable','2.2.0'),('org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm','1.8.0'),('org.jetbrains','annotations','13.0'),('org.eclipse.jdt','ecj','3.32.0')]
with TemporaryDirectory(prefix='aprendels-test-') as directory:
    work=Path(directory);deps=work/'deps';deps.mkdir();classes=work/'classes';classes.mkdir()
    def download(item):
        g,a,v=item
        urlretrieve('https://repo.maven.apache.org/maven2/'+g.replace('.','/')+'/'+a+'/'+v+'/'+a+'-'+v+'.jar',deps/(a+'.jar'))
    with ThreadPoolExecutor(max_workers=4) as executor:
        list(executor.map(download,items))
    def run(args):subprocess.run([str(x) for x in args],check=True)
    run(['java','-jar',deps/'ecj.jar','-11','-proc:none','-d',classes,source/'Bmi160Sample.java',source/'Bmi160Calibration.java'])
    cp=os.pathsep.join([str(classes),str(deps/'kotlin-stdlib.jar')])
    run(['java','-cp',str(deps/'*'),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','11','-classpath',cp,'-d',classes,here/'stubs',source/'Esp32BluetoothClient.kt',here/'DualClientTest.kt',here/'CalibrationTest.kt'])
    run(['java','-cp',cp,'DualClientTestKt'])
    run(['java','-cp',cp,'CalibrationTestKt'])
