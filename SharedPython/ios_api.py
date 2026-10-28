"""iOS boundary; the Android 3.9 InnerTube/pytubefix implementation is unmodified.

Only normalization, native transport attachment and Apple-playable format policy
live here. No subprocesses, server, yt-dlp, login automation or downloaded code.
"""
import json
import re
from urllib.parse import urlparse, parse_qsl, urlencode, urlunparse
import mousike_backend as backend

try:
    import _mousike_ios
except ImportError:  # Host tests use the same Python API without Apple's bridge.
    _mousike_ios = None
if _mousike_ios:
    backend._network = _mousike_ios

SONG_TYPES = {'MUSIC_VIDEO_TYPE_ATV', 'MUSIC_VIDEO_TYPE_OMV'}
VIDEO_ID = re.compile(r'^[A-Za-z0-9_-]{11}$')
LONG_FORM = re.compile(r'\b(full album|compilation|non.?stop|jukebox|podcast|interview|movie|10 hours?|1 hour|live stream)\b', re.I)


def text(value):
    if isinstance(value, str):
        return value
    if isinstance(value, dict):
        return value.get('simpleText') or ''.join(x.get('text', '') for x in value.get('runs', []))
    return ''


def walk(value):
    if isinstance(value, dict):
        yield value
        for child in value.values():
            yield from walk(child)
    elif isinstance(value, list):
        for child in value:
            yield from walk(child)


def duration(value):
    try:
        total = 0
        for part in value.split(':'):
            total = total * 60 + int(part)
        return total
    except (ValueError, AttributeError):
        return 0


def artwork(value):
    images = [i for node in walk(value) for i in node.get('thumbnails', [])
              if isinstance(i, dict) and i.get('url', '').startswith('https://')]
    # Modest decoded size avoids full-resolution image memory pressure.
    images.sort(key=lambda x: abs(x.get('width', 256) - 512))
    return images[0]['url'] if images else ''


def catalog(root):
    tracks, albums, artists = {}, {}, {}
    for node in walk(root):
        row = (node.get('musicResponsiveListItemRenderer') or node.get('musicTwoRowItemRenderer')
               or node.get('musicMultiRowListItemRenderer'))
        if not row:
            # YouTube Music next queue uses a distinct renderer.
            row = node.get('playlistPanelVideoRenderer')
        if not row:
            continue
        cols = [x.get('musicResponsiveListItemFlexColumnRenderer', {}).get('text', {})
                for x in row.get('flexColumns', [])]
        title = text(row.get('title')) or (text(cols[0]) if cols else '')
        subtitle = row.get('subtitle') or row.get('longBylineText') or (cols[1] if len(cols) > 1 else {})
        endpoints = [n['watchEndpoint'] for n in walk(row) if isinstance(n.get('watchEndpoint'), dict)]
        video = row.get('videoId') or next((n.get('videoId') for n in endpoints if n.get('videoId')), '')
        types = [n.get('musicVideoType') for n in walk(row) if n.get('musicVideoType')]
        times = [duration(text(n)) for n in walk(row) if re.fullmatch(r'\d{1,2}:\d{2}(?::\d{2})?', text(n))]
        seconds = duration(text(row.get('lengthText'))) or next((n for n in reversed(times) if n), 0)
        thumb = artwork(row)
        if video and VIDEO_ID.fullmatch(video) and title:
            # Strict discovery policy: typed songs/music videos only. Long-form
            # albums open through browse, never enter automatic song radio.
            if not any(t in SONG_TYPES for t in types) or LONG_FORM.search(title) or seconds > 720:
                continue
            artist = text(subtitle).split(' • ')[0].split(' · ')[0]
            tracks.setdefault(video, {'id': video, 'title': title, 'artist': artist,
                                     'cover': thumb, 'duration': seconds,
                                     'localFile': None, 'sourceLabel': ''})
        else:
            navigation = row.get('navigationEndpoint') or next((n.get('navigationEndpoint') for n in walk(cols[0] if cols else row.get('title', {})) if n.get('navigationEndpoint')), {})
            for n in walk(navigation):
                browse = n.get('browseEndpoint')
                if not browse:
                    continue
                bid = browse.get('browseId', '')
                if bid.startswith(('MPRE', 'OLAK')) and title:
                    albums.setdefault(bid, {'id': bid, 'title': title, 'artist': text(subtitle), 'cover': thumb})
                elif bid.startswith('UC') and title:
                    artists.setdefault(bid, {'id': bid, 'title': title, 'artist': '', 'cover': thumb})
    return {'tracks': list(tracks.values())[:180], 'albums': list(albums.values())[:80],
            'artists': list(artists.values())[:60]}


def safe_media(url):
    if not isinstance(url, str):
        return False
    try:
        u = urlparse(url)
        return (u.scheme == 'https' and u.port in (443, None) and not u.username and not u.password
                and (u.hostname or '').endswith('.googlevideo.com')
                and not any(k == 'n' for k, _ in parse_qsl(u.query, keep_blank_values=True)))
    except ValueError:
        return False


def select_stream(root, video_id, quality):
    status = root.get('playabilityStatus', {})
    if status.get('status') != 'OK':
        raise ValueError(status.get('reason') or 'This recording is unavailable. Try another recording.')
    details = root.get('videoDetails', {})
    if details.get('videoId') != video_id or details.get('isLiveContent'):
        raise ValueError('No matching on-demand music source.')
    data = root.get('streamingData', {})
    # AAC/MP4 and HLS are broadly supported by AVFoundation on the deployment
    # range. Android's Opus/WebM decoder is not present on all supported iPhones.
    audio = [f for f in data.get('adaptiveFormats', [])
             if f.get('mimeType', '').startswith('audio/mp4')
             and not f.get('targetDurationSec') and f.get('type') != 'FORMAT_STREAM_TYPE_OTF'
             and 'source/yt_otf' not in f.get('url', '') and safe_media(f.get('url'))
             and f.get('audioTrack', {}).get('audioIsDefault', True)]
    audio.sort(key=lambda f: f.get('bitrate', 0))
    if quality == 'Low':
        chosen = next((f for f in reversed(audio) if f.get('bitrate', 0) <= 96000), audio[0] if audio else None)
    elif quality == 'High':
        chosen = next((f for f in reversed(audio) if f.get('bitrate', 0) <= 256000), audio[0] if audio else None)
    elif quality == 'Super high':
        chosen = audio[-1] if audio else None
    else:
        raise ValueError('Unknown quality')
    url = chosen.get('url') if chosen else data.get('hlsManifestUrl')
    if not safe_media(url):
        raise ValueError('No Apple-compatible direct AAC/HLS source. Playback availability depends on YouTube.')
    if chosen and root.get('_mousikeCpn'):
        u = urlparse(url)
        q = [(k, v) for k, v in parse_qsl(u.query) if k != 'cpn']
        url = urlunparse(u._replace(query=urlencode(q + [('cpn', root['_mousikeCpn'])])))
    bitrate = chosen.get('bitrate', 0) if chosen else 0
    return {'url': url, 'bitrate': bitrate,
            'label': f'AAC · {round(bitrate/1000)} kbps' if bitrate else 'HLS · source quality',
            'expiresIn': min(int(data.get('expiresInSeconds') or 600), 21600),
            'hls': chosen is None}


def dispatch(operation, payload_json='{}', token=''):
    p = json.loads(payload_json)
    if not isinstance(p, dict):
        raise ValueError('Payload must be an object')
    def call(op, body):
        return json.loads(backend.dispatch(op, json.dumps(body), token))
    if operation == 'resolve':
        video = p.get('id', '')
        backend._check_id(video)
        # Same InnerTube then vendored pytubefix source API as Android 3.9.
        root = call('player', {'id': video, 'fresh': p.get('fresh', False)})
        try:
            result = select_stream(root, video, p.get('quality', 'High'))
        except ValueError:
            root = call('fallback', {'id': video})
            result = select_stream(root, video, p.get('quality', 'High'))
    elif operation in ('search', 'browse', 'radio', 'home'):
        if operation == 'search':
            query = p.get('query', '').strip()[:180]
            if not query:
                return json.dumps({'tracks': [], 'albums': [], 'artists': []})
            body = {'route': 'search', 'query': query}
        elif operation == 'radio':
            backend._check_id(p.get('id'))
            body = {'route': 'next', 'videoId': p['id'], 'playlistId': 'RDAMVM' + p['id'], 'isAudioOnly': True}
        else:
            bid = p.get('id', 'FEmusic_home')
            if not isinstance(bid, str) or len(bid) > 200 or not re.fullmatch(r'[\w-]+', bid):
                raise ValueError('Invalid browse ID')
            body = {'route': 'browse', 'browseId': bid}
        root = call('catalog', body)
        result = catalog(root)
        # Same bounded continuation policy as classic Android album browsing.
        if operation == 'browse':
            for _ in range(4):
                continuation = next((n.get('nextContinuationData', {}).get('continuation') for n in walk(root) if n.get('nextContinuationData', {}).get('continuation')), None)
                if not continuation:
                    break
                root = call('catalog', {'route': 'browse', 'continuation': continuation})
                page = catalog(root)
                for key, limit in [('tracks', 180), ('albums', 80), ('artists', 60)]:
                    merged = {item['id']: item for item in result[key]}
                    merged.update((item['id'], item) for item in page[key])
                    result[key] = list(merged.values())[:limit]
    elif operation in ('warmup', 'trim', 'reset'):
        result = call(operation, p)
    else:
        raise ValueError('Unsupported iOS operation')
    return json.dumps(result, ensure_ascii=False, separators=(',', ':'))
