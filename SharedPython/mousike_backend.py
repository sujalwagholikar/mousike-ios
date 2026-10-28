"""Persistent on-device InnerTube backend. No yt-dlp, shell, watch-page scraping or remote executable updates."""
import copy
import json
import re
import secrets
import threading
import time
from collections import OrderedDict
from urllib.parse import urlparse
import httpx
from innertube import InnerTube, InnerTubeAdaptor
from innertube.models import ClientContext
from pytubefix.innertube import InnerTube as PytubeInnerTube

VERSION = '3.7.1'
ID = re.compile(r'^[A-Za-z0-9_-]{11}$')
UA = 'Mozilla/5.0 (Macintosh; Intel Mac OS X 25_6_0) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/25.6 Safari/605.1.15'
_tls = threading.local()
_cache = OrderedDict()
_lock = threading.Lock()
_visitor = ''
CACHE_ENTRIES = 8
CACHE_BYTES = 3 * 1024 * 1024
CACHE_TTL = 90
try:
    from java import jclass
    _network = jclass('com.mousike.app.playback.PythonNetwork')
except ImportError:
    _network = None

class AndroidTransport(httpx.BaseTransport):
    def handle_request(self, request):
        status, body = str(_network.request(getattr(_tls, 'token', ''), str(request.url), json.dumps(dict(request.headers)), request.content.decode('utf-8'))).split('\n', 1)
        return httpx.Response(int(status), headers={'Content-Type':'application/json'}, content=body.encode('utf-8'), request=request)

def _session():
    if not hasattr(_tls, 'session'):
        _tls.session = httpx.Client(transport=AndroidTransport() if _network else None,
            timeout=httpx.Timeout(7, connect=3), limits=httpx.Limits(max_connections=4, max_keepalive_connections=4), follow_redirects=False, trust_env=_network is None)
    return _tls.session

class Adaptor(InnerTubeAdaptor):
    def _request(self, endpoint, params=None, body=None):
        # Preserve the publisher profile and use the actual innertube context/header API.
        from innertube.api import contextualise
        payload = contextualise(self.context, copy.deepcopy(body or {}))
        host = 'music.youtube.com' if self.context.client_name == 'WEB_REMIX' else 'www.youtube.com'
        self.session.headers.pop('X-Goog-Visitor-Id', None)
        headers = self.context.headers()
        visitor = payload.get('context', {}).get('client', {}).get('visitorData')
        if visitor: headers['X-Goog-Visitor-Id'] = visitor
        query = {**self.context.params(), 'prettyPrint':'false', **(params or {})}
        if str(endpoint).strip('/') == 'player' and self.context.client_name == 'VISIONOS':
            query.update(t=secrets.token_urlsafe(9), id=payload.get('videoId',''))
            headers['X-Goog-Api-Format-Version'] = '2'
        response = self.session.post('https://' + host + '/youtubei/v1/' + str(endpoint).strip('/'),
            params=query, json=payload, headers=headers)
        response.raise_for_status()
        return response

def _adaptor(name):
    music = name == 'WEB_REMIX'
    ctx = ClientContext(client_name=name, client_version='1.20261021.01.00' if music else '1.02',
        client_id=67 if music else 101, user_agent='Mozilla/5.0' if music else UA,
        referer='https://music.youtube.com/' if music else 'https://www.youtube.com/')
    return Adaptor(ctx, _session())

def _body(name):
    client = {'hl':'en', 'gl':'IN', 'utcOffsetMinutes':0}
    if name == 'VISIONOS':
        client.update(deviceMake='Apple', deviceModel='RealityDevice14,1', osName='visionOS', osVersion='25.6.0.23O471')
    with _lock:
        if _visitor: client['visitorData'] = _visitor
    return {'context':{'client':client, 'request':{'useSsl':True,'internalExperimentFlags':[]}, 'user':{'lockedSafetyMode':False}}}

def _post(name, route, params):
    global _visitor
    data = _body(name)
    data.update(copy.deepcopy(params))
    root = _adaptor(name).dispatch(route, body=data)
    value = root.get('responseContext', {}).get('visitorData')
    if value:
        with _lock: _visitor = value
    return root

def _check_id(video_id):
    if not isinstance(video_id, str) or not ID.fullmatch(video_id): raise ValueError('Invalid video ID')

def _remember(video_id, root):
    # Store one compact representation, not 64 deep object graphs. Decode outside the lock.
    encoded = json.dumps(root, ensure_ascii=False, separators=(',', ':'))
    size = len(encoded.encode('utf-8'))
    if size > CACHE_BYTES // 2: return
    now = time.monotonic()
    with _lock:
        expired = [key for key, value in _cache.items() if now - value[0] > CACHE_TTL]
        for key in expired: _cache.pop(key, None)
        _cache[video_id] = (now, encoded, size)
        _cache.move_to_end(video_id)
        while len(_cache) > CACHE_ENTRIES or sum(value[2] for value in _cache.values()) > CACHE_BYTES:
            _cache.popitem(last=False)

def _cached(video_id):
    with _lock:
        value = _cache.get(video_id)
        if not value: return None
        if time.monotonic() - value[0] > CACHE_TTL:
            _cache.pop(video_id, None)
            return None
        _cache.move_to_end(video_id)
        encoded = value[1]
    return json.loads(encoded)

def _player(video_id, fresh=False):
    _check_id(video_id)
    if not fresh:
        cached = _cached(video_id)
        if cached is not None: return cached
    # A visitor is protocol state, not a login or a user API key.
    with _lock: needs_visitor = not _visitor
    if needs_visitor: _post('VISIONOS', 'visitor_id', {})
    nonce = secrets.token_urlsafe(12)
    root = _post('VISIONOS', 'player', {'videoId':video_id,'cpn':nonce,'contentCheckOk':True,'racyCheckOk':True})
    root['_mousikeCpn'] = nonce
    _remember(video_id, root)
    return root

class AndroidPytube(PytubeInnerTube):
    """Use the vendored low-level pytubefix API with pooled, cancellable Android I/O."""
    def _call_api(self, endpoint, query, data):
        if not endpoint.startswith('https://www.youtube.com/youtubei/v1/'):
            raise ValueError('Unsupported API endpoint')
        response = _session().post(endpoint, params=dict(query), json=copy.deepcopy(data), headers={**self.header,'Content-Type':'application/json'})
        response.raise_for_status()
        return response.json()

def _fallback(video_id):
    _check_id(video_id)
    # Native profiles only: desktop Node.js and challenge-token generation are not supported on Android.
    client = AndroidPytube(client='VISION_OS', use_oauth=False, allow_cache=False, use_po_token=False)
    client.innertube_context = copy.deepcopy(client.innertube_context)
    nonce = secrets.token_urlsafe(12)
    client.innertube_context['cpn'] = nonce
    root = client.player(video_id)
    root['_mousikeCpn'] = nonce
    _remember(video_id, root)
    return root

def metadata(video_id):
    root = _cached(video_id) or _player(video_id)
    details = root.get('videoDetails', {})
    if details.get('videoId') != video_id: raise ValueError('No matching song metadata')
    chapters = []
    def walk(obj):
        if isinstance(obj, list):
            for x in obj: walk(x)
        elif isinstance(obj, dict):
            chapter = obj.get('chapterRenderer')
            if chapter and chapter.get('timeRangeStartMillis') is not None:
                title = chapter.get('title', {})
                chapters.append({'title':title.get('simpleText') or ''.join(x.get('text','') for x in title.get('runs',[])), 'start_time':float(chapter['timeRangeStartMillis'])/1000})
            for value in obj.values():
                if isinstance(value, (dict,list)): walk(value)
    walk(root)
    return {'id':video_id,'title':details.get('title',''),'uploader':details.get('author',''),
        'duration':float(details.get('lengthSeconds') or 0),'description':details.get('shortDescription',''),
        'thumbnail':(details.get('thumbnail',{}).get('thumbnails') or [{}])[-1].get('url',''),'chapters':chapters}

def dispatch(operation, payload_json='{}', token=''):
    global _visitor
    _tls.token = token
    try:
        params = json.loads(payload_json)
        if not isinstance(params, dict): raise ValueError('Invalid payload')
        if operation == 'warmup':
            restored = params.get('visitor')
            if isinstance(restored,str) and 0 < len(restored) < 8192:
                with _lock:
                    if not _visitor: _visitor = restored
            _session()
            result = {'version':VERSION,'engine':'Python InnerTube / pytubefix API','ready':True}
        elif operation == 'catalog':
            route = params.pop('route')
            if route not in ('search','browse','next','music/get_search_suggestions'): raise ValueError('Unsupported catalog route')
            result = _post('WEB_REMIX',route,params)
        elif operation == 'player': result = _player(params['id'],params.get('fresh',False))
        elif operation == 'fallback': result = _fallback(params['id'])
        elif operation == 'metadata': result = metadata(params['id'])
        elif operation == 'trim':
            with _lock: _cache.clear()
            result = {'ready':True}
        elif operation == 'reset':
            with _lock:
                _cache.clear()
                _visitor = ''
            result = {'ready':True,'version':VERSION}
        else: raise ValueError('Unsupported operation')
        return json.dumps(result,ensure_ascii=False,separators=(',',':'))
    finally: _tls.token = ''
