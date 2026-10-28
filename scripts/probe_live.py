#!/usr/bin/env python3
"""Opt-in host network probe. Never substitutes for audible iPhone playback."""
from pathlib import Path
import json
import os
import sys
import time

ROOT=Path(__file__).resolve().parents[1]
sys.path[:0]=[str(ROOT/'SharedPython'),str(ROOT/'SharedPython/packages')]
import ios_api
import httpx

def probe():
    result={'environment':'host Python, not iOS','audiblePlaybackVerified':False,'appleNativeBridgeTested':False}
    start=time.monotonic()
    try:
        catalog=json.loads(ios_api.dispatch('search',json.dumps({'query':'Arijit Singh music'})))
        result['search']={'status':'ok','songs':len(catalog['tracks']),'albums':len(catalog['albums'])}
    except Exception as error:result['search']={'status':'unavailable','reason':str(error)[:500]}
    result['searchSeconds']=round(time.monotonic()-start,3)
    start=time.monotonic()
    try:
        source=json.loads(ios_api.dispatch('resolve',json.dumps({'id':'HcOc7P5BMi4','quality':'High'})))
        result['resolve']={'status':'ok','label':source['label']}
        with httpx.Client(timeout=8,follow_redirects=False) as client:
            with client.stream('GET',source['url'],headers={'Range':'bytes=0-4095','User-Agent':ios_api.backend.UA,'Referer':'https://www.youtube.com/'}) as response:
                sample=next(response.iter_bytes(),b'')[:4096]
                result['media']={'status':response.status_code,'contentType':response.headers.get('content-type'),'bytesSampled':len(sample),'isoMediaHeader':b'ftyp' in sample[:64]}
    except Exception as error:result['resolve']={'status':'unavailable','reason':str(error)[:500]}
    result['resolveSeconds']=round(time.monotonic()-start,3)
    return result
if __name__=='__main__':
    output=probe();target=ROOT/'docs/LIVE_HOST_PROBE.json';target.write_text(json.dumps(output,indent=2));print(json.dumps(output,indent=2))
