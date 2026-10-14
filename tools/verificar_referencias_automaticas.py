#!/usr/bin/env python3
"""Compatibilidad: ejecuta las pruebas vigentes del motor por etapas."""
import runpy
from pathlib import Path
runpy.run_path(str(Path(__file__).with_name('verificar_etapas.py')),run_name='__main__')
