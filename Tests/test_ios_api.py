import copy
import hashlib
import json
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(ROOT/'SharedPython'), str(ROOT/'SharedPython/packages')]
import ios_api as api

ID = 'HcOc7P5BMi4'
def source(itag, bitrate, mime='audio/mp4; codecs="mp4a.40.2"', **extra):
    return dict(itag=itag, bitrate=bitrate, mimeType=mime,
                url=f'https://rr1---sn.googlevideo.com/videoplayback?itag={itag}&expire=9999999999', **extra)
def player(formats=None):
    return {'playabilityStatus': {'status': 'OK'}, 'videoDetails': {'videoId': ID},
            'streamingData': {'adaptiveFormats': formats or [source(139,48000),source(140,128000),source(141,256000)],'expiresInSeconds':'600'}}
def row(video=ID, title='A song', kind='MUSIC_VIDEO_TYPE_ATV', length='3:40'):
    return {'musicResponsiveListItemRenderer': {
        'flexColumns': [{'musicResponsiveListItemFlexColumnRenderer': {'text':{'runs':[{'text':title}]}}},
                        {'musicResponsiveListItemFlexColumnRenderer': {'text':{'runs':[{'text':'A musician'}]}}}],
        'fixedColumns':[{'musicResponsiveListItemFixedColumnRenderer':{'text':{'simpleText':length}}}],
        'navigationEndpoint':{'watchEndpoint':{'videoId':video,'watchEndpointMusicSupportedConfigs':{'watchEndpointMusicConfig':{'musicVideoType':kind}}}},
        'thumbnail':{'thumbnails':[{'url':'https://i.ytimg.com/vi/'+video+'/hqdefault.jpg','width':480}]}}}

class FormatPolicyTests(unittest.TestCase):
    def test_low_selects_at_most_96k_when_available(self):
        self.assertEqual(api.select_stream(player(), ID, 'Low')['bitrate'],48000)
    def test_high_supports_real_256k(self):
        self.assertEqual(api.select_stream(player(), ID, 'High')['bitrate'],256000)
    def test_super_high_uses_highest_compatible_source(self):
        self.assertEqual(api.select_stream(player([source(140,128000),source(141,320000)]), ID, 'Super high')['bitrate'],320000)
    def test_no_transcoding_or_fake_lossless_label(self):
        s=api.select_stream(player(), ID, 'Super high')
        self.assertEqual(s['label'],'AAC · 256 kbps'); self.assertNotIn('lossless',s['label'].lower())
    def test_webm_not_sent_to_old_apple_decoder(self):
        s=api.select_stream(player([source(251,320000,'audio/webm; codecs="opus"'),source(140,128000)]),ID,'Super high')
        self.assertEqual(s['bitrate'],128000)
    def test_original_language_not_dub(self):
        p=player([source(140,128000),source(141,256000,audioTrack={'audioIsDefault':False})])
        self.assertEqual(api.select_stream(p,ID,'High')['bitrate'],128000)
    def test_reject_otf_streams(self):
        p=player([source(140,128000,type='FORMAT_STREAM_TYPE_OTF')])
        with self.assertRaises(ValueError):api.select_stream(p,ID,'High')
    def test_reject_segmented_otf(self):
        p=player([source(140,128000,targetDurationSec=5)])
        with self.assertRaises(ValueError):api.select_stream(p,ID,'High')
    def test_hls_supported(self):
        p=player();p['streamingData']={'hlsManifestUrl':'https://manifest.googlevideo.com/api/manifest/hls_playlist/id/a'}
        self.assertTrue(api.select_stream(p,ID,'High')['hls'])
    def test_matching_metadata_required(self):
        with self.assertRaises(ValueError):api.select_stream(player(),'abcdefghijk','High')
    def test_live_not_inserted(self):
        p=player();p['videoDetails']['isLiveContent']=True
        with self.assertRaises(ValueError):api.select_stream(p,ID,'High')
    def test_provider_restriction_has_readable_reason(self):
        p=player();p['playabilityStatus']={'status':'LOGIN_REQUIRED','reason':'Sign in required'}
        with self.assertRaisesRegex(ValueError,'Sign in required'):api.select_stream(p,ID,'High')
    def test_cpn_preserved(self):
        p=player();p['_mousikeCpn']='nonce-123'
        self.assertIn('cpn=nonce-123',api.select_stream(p,ID,'High')['url'])
    def test_unknown_quality_rejected(self):
        with self.assertRaises(ValueError):api.select_stream(player(),ID,'Lossless YouTube')
    def test_urls_are_https_googlevideo_only(self):
        for u in ['http://a.googlevideo.com/a','https://evilgooglevideo.com/a','https://a.googlevideo.com.evil/a','https://u:p@a.googlevideo.com/a','https://a.googlevideo.com:8443/a','https://a.googlevideo.com/a?n=','https://a.googlevideo.com/a?n=xyz','file:///a','https://a.googlevideo.com:bad/a']:
            with self.subTest(u=u):self.assertFalse(api.safe_media(u))

class CatalogTests(unittest.TestCase):
    def test_normalizes_music_and_duration(self):
        p=api.catalog(row());self.assertEqual(len(p['tracks']),1);self.assertEqual(p['tracks'][0]['duration'],220)
    def test_rejects_long_videos(self):
        self.assertFalse(api.catalog(row(length='45:00'))['tracks'])
    def test_rejects_jukebox(self):
        self.assertFalse(api.catalog(row(title='Full album jukebox'))['tracks'])
    def test_rejects_untyped_video(self):
        self.assertFalse(api.catalog(row(kind='MUSIC_VIDEO_TYPE_UGC'))['tracks'])
    def test_deduplicates_song(self):
        self.assertEqual(len(api.catalog([row(),row()])['tracks']),1)
    def test_album_opens_as_collection(self):
        p=api.catalog({'musicTwoRowItemRenderer':{'title':{'runs':[{'text':'Album'}]},'navigationEndpoint':{'browseEndpoint':{'browseId':'MPREabc'}}}})
        self.assertEqual(p['albums'][0]['id'],'MPREabc');self.assertFalse(p['tracks'])
    def test_artist_normalization(self):
        p=api.catalog({'musicTwoRowItemRenderer':{'title':{'runs':[{'text':'Artist'}]},'navigationEndpoint':{'browseEndpoint':{'browseId':'UCabc'}}}})
        self.assertEqual(p['artists'][0]['title'],'Artist')
    def test_album_navigation_inside_title(self):
        p=api.catalog({'musicResponsiveListItemRenderer':{'flexColumns':[{'musicResponsiveListItemFlexColumnRenderer':{'text':{'runs':[{'text':'Album','navigationEndpoint':{'browseEndpoint':{'browseId':'MPREabc'}}}]}}}]}})
        self.assertEqual(p['albums'][0]['title'],'Album')
    def test_results_are_bounded(self):
        data=[row(video=f'{n:011}') for n in range(400)]
        self.assertEqual(len(api.catalog(data)['tracks']),180)
    def test_artwork_chooses_moderate_resolution(self):
        p={'thumbnails':[{'url':'https://example/a','width':4000},{'url':'https://example/b','width':480}]}
        self.assertEqual(api.artwork(p),'https://example/b')

class DispatchTests(unittest.TestCase):
    def test_empty_search_does_not_call_network(self):
        with patch.object(api.backend,'dispatch') as call:
            self.assertFalse(json.loads(api.dispatch('search','{"query":" "}'))['tracks']);call.assert_not_called()
    def test_search_passes_exact_route(self):
        with patch.object(api.backend,'dispatch',return_value=json.dumps(row())) as call:
            result=json.loads(api.dispatch('search','{"query":"music"}','token'))
            self.assertEqual(len(result['tracks']),1);self.assertEqual(json.loads(call.call_args.args[1])['route'],'search')
    def test_browse_continuation_is_bounded(self):
        p={'contents':[row()], 'nextContinuationData':{'continuation':'next'}}
        with patch.object(api.backend,'dispatch',return_value=json.dumps(p)) as call:
            api.dispatch('browse','{"id":"MPREabc"}');self.assertEqual(call.call_count,5)
    def test_resolve_keeps_pytubefix_api_path(self):
        bad=player();bad['playabilityStatus']={'status':'ERROR'}
        with patch.object(api.backend,'dispatch',side_effect=[json.dumps(bad),json.dumps(player())]) as call:
            self.assertEqual(json.loads(api.dispatch('resolve',json.dumps({'id':ID})))['bitrate'],256000)
            self.assertEqual([c.args[0] for c in call.call_args_list],['player','fallback'])
    def test_invalid_browse_id(self):
        with self.assertRaises(ValueError):api.dispatch('browse','{"id":"../../x"}')
    def test_invalid_track_id(self):
        with self.assertRaises(ValueError):api.dispatch('resolve','{"id":"bad"}')
    def test_unknown_operation(self):
        with self.assertRaises(ValueError):api.dispatch('shell','{}')
    def test_payload_object_required(self):
        with self.assertRaises(ValueError):api.dispatch('search','[]')
    def test_original_backend_unchanged(self):
        manifest=json.loads((ROOT/'docs/ANDROID_CORE_SHA256.json').read_text())
        for name,expected in manifest.items():self.assertEqual(hashlib.sha256((ROOT/'SharedPython'/name).read_bytes()).hexdigest(),expected)

if __name__=='__main__':unittest.main()
