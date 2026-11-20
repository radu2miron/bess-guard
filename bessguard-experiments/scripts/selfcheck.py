#!/usr/bin/env python3
"""Offline structural validation. Does NOT run Fabric, RabbitMQ or JMeter."""
from pathlib import Path
import ast
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
files = list((ROOT / 'scripts').glob('*.py'))
for file in files:
    ast.parse(file.read_text(encoding='utf-8'), filename=str(file))

path = ROOT / 'jmeter' / 'e1_concurrent_reservations.jmx'
root = ET.parse(path).getroot()
tg = root.find('.//ThreadGroup')
csv = root.find('.//CSVDataSet')
timer = root.find('.//SyncTimer')
sampler = root.find('.//HTTPSamplerProxy')
assert tg is not None and tg.find("./stringProp[@name='ThreadGroup.num_threads']").text == '2'
assert timer is not None and timer.find("./intProp[@name='groupSize']").text == '2'
assert csv is not None and csv.find("./stringProp[@name='shareMode']").text == 'shareMode.all'
assert csv.find("./boolProp[@name='recycle']").text == 'false'
assert sampler is not None and sampler.find("./stringProp[@name='HTTPSampler.path']").text == '/api/dispatch'
assert sampler.find("./stringProp[@name='HTTPSampler.method']").text == 'POST'
assert sampler.find("./boolProp[@name='HTTPSampler.postBodyRaw']").text == 'true'
assert '${commandId}' in sampler.find(".//stringProp[@name='Argument.value']").text
print(f"OFFLINE SELF-CHECK OK: {len(files)} Python files parse, JMX XML valid, synchronized 2-thread POST configured.")
print('Live JMeter execution and Fabric/RabbitMQ experiments have NOT been run here.')
