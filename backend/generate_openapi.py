"""Deterministic OpenAPI source. No credentials or provider fixtures are emitted."""
import json
from pathlib import Path

S={'type':'string'}; I={'type':'integer','format':'int64'}; B={'type':'boolean'}; O={'type':'object','additionalProperties':True}
def obj(props,required=()):return {'type':'object','properties':props,'required':list(required)}
def ref(name):return {'$ref':'#/components/schemas/'+name}
def arr(item):return {'type':'array','items':item}
schemas={
 'ErrorDetail':obj({'code':S,'message':S},['code','message']),
 'ErrorBody':obj({'error':ref('ErrorDetail')},['error']),
 'User':obj({'id':S,'name':S,'role':{'type':'string','enum':['admin','user','guest']},'username':S,'device_limit':I},['id','name','role']),
 'Device':obj({'id':S,'name':S,'user_id':S,'created_at':S,'capabilities':O},['id']),
 'AuthSession':obj({'access_token':S,'refresh_token':S,'expires_in':I,'user':ref('User'),'device':ref('Device')},['access_token','refresh_token','expires_in','user','device']),
 'Product':obj({'id':S,'name':S,'description':S,'price':I,'currency':S,'duration_days':I,'features':arr(S)},['id','name','price','currency','duration_days']),
 'Order':obj({'id':S,'user_id':S,'status':{'type':'string','enum':['pending','paid','failed','cancelled']},'product_id':S,'amount':I,'currency':S,'checkout_url':S,'sandbox':B,'created_at':S,'duplicate':B},['id','status','product_id','amount','currency','checkout_url','sandbox']),
 'Directive':{**obj({'type':S,'payload':{**O,'description':'PhoneCall uses either phone_number (+? and 3..15 digits) or contact_name (2..80 characters), resolved only on-device after explicit confirmation. Ambiguous contacts require foreground user selection. Receipts omit contact names and phone numbers.','properties':{'phone_number':{'type':'string','pattern':'^\\+?[0-9]{3,15}$'},'contact_name':{'type':'string','minLength':2,'maxLength':80}}}},['type','payload']),'description':'A requested device action, not evidence of execution.'},
 'SourceCitation':obj({'title':S,'url':{'type':'string','format':'uri'}},['title','url']),
 'AssistantResponse':obj({'session_id':S,'request_id':S,'conversation_id':S,'replayed':B,'confirmation_required':B,'confirmation_token':S,'source':S,'text':S,'utterance':S,'directives':arr(ref('Directive')),'sources':arr(ref('SourceCitation')),'provider_latency_ms':I,'provider_raw':O},['session_id','text','utterance','directives','provider_latency_ms']),
 'SpeechEvent':obj({'type':{'type':'string','enum':['ready','partial','final','error']},'text':S,'request_id':S,'error':ref('ErrorDetail'),'provider_raw':O},['type']),
 'SpeechResult':obj({'text':S,'request_id':S,'provider_latency_ms':I},['text','request_id','provider_latency_ms']),
 'TTSResult':obj({'url':S,'mime_type':S,'provider_latency_ms':I},['url','provider_latency_ms']),
 'Entitlement':obj({'id':S,'product_id':S,'source':S,'starts_at':S,'expires_at':S,'duplicate':B},['id']),
 'LocalPremium':obj({'enabled':B,'local':B,'user_id':S,'upstream_granted':B,'starts_at':S,'expires_at':S},['enabled','local','user_id','upstream_granted']),
 'AccountPlan':obj({'kind':{'type':'string','enum':['basic','premium','admin']},'expires_at':{'type':'string','format':'date-time','nullable':True},'server_time':{'type':'string','format':'date-time'}},['kind','expires_at','server_time']),
 'Me':obj({'user':ref('User'),'device':ref('Device'),'entitlements':arr(ref('Entitlement')),'premium_features':{**O,'description':'Effective TN capabilities. A stored admin role grants every TN Premium capability; other accounts require active catalog entitlements. This does not grant paid upstream access or imply provider readiness.'},'local_premium':ref('LocalPremium'),'account_plan':ref('AccountPlan'),'device_limit':I},['user','device','entitlements','device_limit']),
 'Settings':obj({'map_package':S,'music_package':S,'video_package':S,'tv_package':S,'radio_package':S,'media_package':S,'conversation_idle_timeout_ms':{'type':'integer','enum':[5000,10000,15000,30000,60000],'default':10000},'command_end_silence_ms':{'type':'integer','enum':[500,1000,1500,2000,3000],'default':1000},'ai_mode':{'type':'string','enum':['commands','qa','tools'],'description':'Basic commands; Premium defaults qa. tools is opt-in.'},'wake_word_enabled':{'type':'boolean','default':False,'description':'Requires an active Premium wake_word entitlement to enable.'},'wake_word_data_sharing':{'type':'boolean','default':False,'description':'Preference for sharing wake-word usage data, separate from microphone activation.'},'welcome_enabled':B,'volume':I,'traffic_enabled':B,'traffic_warning_mode':{'type':'string','enum':['none','display','sound','voice']},'traffic_overspeed_offset':{'type':'integer','enum':[-5,0,5]},'traffic_always_on_map':B,'private_mode':B}),
 'HistoryEntry':obj({'id':S,'kind':S,'text':S,'result':O,'created_at':S},['id','kind','text']),
 'Content':obj({'id':S,'kind':S,'title':S,'body':S,'url':S,'published':B},['id','kind','title']),
 'Diagnostic':obj({'id':S,'kind':S,'message':S,'details':O,'user_id':S,'created_at':S},['id','kind','message']),
 'Release':obj({'id':S,'available':B,'application_id':S,'version_code':I,'version_name':S,'url':S,'sha256':S,'min_sdk':I,'notes':S,'published':B}),
 'Link':obj({'code':S,'status':S,'expires_at':S,'qr_payload':S,'source':S,'reauth_required':B},['code','expires_at']),
 'ActivationCode':obj({'id':S,'code':S,'product_id':S,'uses':I,'expires_at':S,'created_at':S},['id','code','product_id','uses']),
 'Provider':obj({'mode':{'type':'string','enum':['legacy_tn','generic']},'url':S,'stream_url':S,'token_env':S,'device_token_env':S,'api_key_env':S,'secret_env':S,'uid':S,'client_app_type':S,'version':S,'header_env':{'type':'object','additionalProperties':S},'options':O,'configured':B},['mode','url']),
 'Providers':obj({k:ref('Provider') for k in ['asr','nlu','tts','zalo','traffic','wake_word','driving','action_log','welcome']}),
 'Health':obj({'status':S,'sandbox':B,'providers':O},['status','sandbox','providers']),
 'GuestRequest':obj({'device_id':S,'device_name':S},['device_id','device_name']),
 'LoginRequest':obj({'username':S,'password':S},['username','password']),
 'RefreshRequest':obj({'refresh_token':S},['refresh_token']),
 'ZaloStartRequest':obj({'device_id':{'type':'string','minLength':8,'maxLength':128},'device_name':{'type':'string','minLength':1,'maxLength':256}},['device_id','device_name']),
 'ZaloStartResponse':obj({'url':{'type':'string','format':'uri','description':'Official Zalo authorization URL with PKCE challenge and state. Never contains client_proof or app secret.'},'qr_payload':{'type':'string','format':'uri','description':'Same-origin HTTPS TN pairing page to encode as a QR code.'},'user_code':{'type':'string','pattern':'^[0-9]{6}$','description':'Short confirmation code shown on both TN Assistant and the pairing page.'},'state':{'type':'string','minLength':43,'maxLength':43},'client_proof':{'type':'string','minLength':43,'maxLength':43,'description':'Keep only in the initiating client until completion. Never log or place in a browser URL.'},'callback_url':{'type':'string','format':'uri'},'expires_in':{'type':'integer','enum':[600]}},['url','qr_payload','user_code','state','client_proof','callback_url','expires_in']),
 'ZaloStatusRequest':obj({'state':{'type':'string','minLength':43,'maxLength':43},'client_proof':{'type':'string','minLength':43,'maxLength':43},'device_id':{'type':'string','minLength':8,'maxLength':128}},['state','client_proof','device_id']),
 'ZaloStatusResponse':obj({'status':{'type':'string','enum':['pending','authorized','denied','expired']}},['status']),
 'ZaloCompleteRequest':obj({'state':{'type':'string','minLength':43,'maxLength':43},'client_proof':{'type':'string','minLength':43,'maxLength':43},'device_id':{'type':'string','minLength':8,'maxLength':128},'code':{'type':'string','minLength':1,'maxLength':4096,'description':'Authorization code received at the registered callback; may be omitted only after the backend callback has stored the same code.'}},['state','client_proof','device_id']),
 'AssistantRequest':obj({'text':S,'session_id':S,'request_id':S,'conversation_id':S,'mode':{'type':'string','enum':['commands','qa','tools']},'confirmation_token':S,'device_id':S,'lat':{'type':'number'},'lon':{'type':'number'},'asr_request_id':S,'session_state':S},['text']),
 'SpeechStart':obj({'type':{'type':'string','enum':['start']},'session_id':S,'sample_rate':I,'channels':I,'encoding':S},['type','session_id','sample_rate','channels','encoding']),
 'OK':obj({'ok':B},['ok']),
}
schemas['AIProvider']=obj({'id':S,'kind':{'type':'string','enum':['gemini','claude','openai','perplexity','custom']},'model':S,'endpoint':S,'enabled':B,'priority':I,'max_requests_per_day':I,'max_output_tokens':I,'api_key':{'type':'string','writeOnly':True,'description':'Replacement key; omitted or empty preserves key only for unchanged identity and endpoint.'},'key_configured':{'type':'boolean','readOnly':True}},['id','kind','model','enabled'])
schemas['Settings']['properties']['agent_voice']={'type':'string','enum':['south','north'],'default':'south','description':'Optional saved assistant voice choice. Settings responses show south when no valid saved choice exists; omission from PUT preserves the saved choice and does not persist this response default. With TN_TTS_PROVIDER=original, independent POST /v1/tts uses saved south as speaker 5 and north as speaker 11. Missing or invalid saved choice retains the configured provider speaker; other TTS providers and managed-news synthesis keep their existing voice configuration.'}
schemas['AIProviders']=obj({'providers':arr(ref('AIProvider'))},['providers'])
schemas['LocationAddress']=obj({'lat':{'type':'number','minimum':-90,'maximum':90},'lon':{'type':'number','minimum':-180,'maximum':180},'name':S,'fullAddress':S},['lat','lon'])
schemas['LocationParserResult']=obj({'lat':{'type':'number','minimum':-90,'maximum':90},'lon':{'type':'number','minimum':-180,'maximum':180},'name':S,'address':S,'type':S,'is_closed':B,'rating':{'type':'number','minimum':0,'maximum':5}},['lat','lon'])
schemas['LocationParserResult']['additionalProperties']=False
schemas['AssistantLocationRequest']=obj({'request_id':S,'action_id':S,'operation':{'type':'string','enum':['address','gg_parser']},'parser_result':ref('LocationParserResult')},['request_id','action_id','operation'])
schemas['AssistantLocationRequest']['additionalProperties']=False
schemas['AssistantLocationResponse']=obj({'request_id':S,'action_id':S,'operation':{'type':'string','enum':['address','gg_parser']},'status':{'type':'string','enum':['resolved','speech']},'replayed':B,'address':ref('LocationAddress'),'direction':O,'speech':O},['request_id','action_id','operation','status','replayed'])
schemas['TrafficViolationRow']=obj({key:{'type':'string','maxLength':2000} for key in ['dateTime','location','offence','amount','status']})
schemas['TrafficViolationRow']['additionalProperties']=False
schemas['AssistantTrafficUploadRequest']=obj({'request_id':S,'action_id':S,'violations':{'type':'array','items':ref('TrafficViolationRow'),'maxItems':500}},['request_id','action_id','violations'])
schemas['AssistantTrafficUploadRequest']['additionalProperties']=False
schemas['AssistantTrafficUploadResponse']=obj({'request_id':S,'action_id':S,'status':{'type':'string','enum':['submitted']},'transport_ack':B,'replayed':B},['request_id','action_id','status','transport_ack','replayed'])
for name in ['User','Device','Product','Order','HistoryEntry','Content','Diagnostic','Release','ActivationCode']:
 schemas[name+'List']=obj({'items':arr(ref(name))},['items'])
paths={}
def route(path,method,result='OK',request=None,public=False,status='200',description=''):
 response=ref(result) if isinstance(result,str) else result
 operation={'operationId':method+'_'+path.strip('/').replace('/','_').replace('{','').replace('}','').replace('-','_'),'description':description,'responses':{status:{'description':'Success','content':{'application/json':{'schema':response}}},'400':{'description':'Invalid request','content':{'application/json':{'schema':ref('ErrorBody')}}},'401':{'description':'Missing, expired, or revoked token','content':{'application/json':{'schema':ref('ErrorBody')}}},'403':{'description':'Role or ownership denied','content':{'application/json':{'schema':ref('ErrorBody')}}},'503':{'description':'Provider unavailable or not configured','content':{'application/json':{'schema':ref('ErrorBody')}}}}}
 if public:operation['security']=[]
 if request:operation['requestBody']={'required':True,'content':{'application/json':{'schema':ref(request) if isinstance(request,str) else request}}}
 params=[]
 for part in path.split('/'):
  if part.startswith('{'):params.append({'name':part[1:-1],'in':'path','required':True,'schema':S})
 if params:operation['parameters']=params
 paths.setdefault(path,{})[method]=operation
route('/v1/health','get','Health',public=True)
for name,req in [('guest','GuestRequest'),('login','LoginRequest'),('refresh','RefreshRequest')]:route('/v1/auth/'+name,'post','AuthSession',req,True)
route('/v1/auth/logout','post','OK','RefreshRequest',True)
route('/v1/me','get','Me');route('/v1/me','delete')
route('/v1/devices','get','DeviceList');route('/v1/devices/{id}','delete')
route('/v1/settings','get','Settings',description='Authenticated account settings. agent_voice is south or north; south is the response fallback when no valid voice choice has been saved. Reading settings does not persist that fallback.')
route('/v1/settings','put','Settings','Settings',description='Authenticated partial settings update. agent_voice accepts only exact south or north strings; invalid values reject the entire update with INVALID_SETTINGS. Omitted agent_voice preserves the saved choice and does not select the response default.')
for path in ['config','welcome','traffic/config']:route('/v1/'+path,'get',O)
route('/v1/welcome/rule','get',O,description='Owner-scoped startup welcome rules from the configured provider. No fabricated rule fallback.')
for area in ['speedlimit','speedcam']:
 route('/v1/driving/area/'+area,'get',O,description='Authenticated driving area provider. Local Premium does not change upstream authorization. Requires location and provider configuration.')
route('/v1/driving/log','post',O,O,description='Authenticated owner-scoped action-log gateway. logs contains either one encrypted media envelope (action_code 10; Basic allowed), one road envelope (12), or distinct road/feedback envelopes (12 and 30; driving_info Premium required). Media cannot be mixed with road/feedback. Each envelope requires log_content plus meta.content_format=encrypted_array and RSA-wrapped encryption_setting.iv/secret_key. Numeric lat/lon and session_key are required. Despite its legacy field name, client session_key is the current TN conversation ID, empty before the first assistant session. At each send the backend resolves the unexpired provider-issued NLU key bound to this owner, authenticated device, conversation and current compatible NLU provider identity. Missing, stale, foreign, private-mode or incompatible bindings send an empty upstream session; client strings are never passed through as provider credentials. Media uses the Original UNKNOWN location sentinel -1/-1 until an actual fix exists. Only the configured owner may use provider credentials; outer user_id/device_id and app_type follow the configured provider identity while ciphertext and encryption metadata stay unchanged. Provider response determines acknowledgment.')
route('/v1/car/models','get',obj({'items':arr(O)},['items']))
route('/v1/products','get','ProductList')
route('/v1/activation/redeem','post','Entitlement',obj({'code':S},['code']))
route('/v1/link/start','post','Link');route('/v1/link/{code}','get','Link')
route('/v1/link/{code}/complete','post','Link',obj({'user_id':S},['user_id']),description='Administrator-only local linking; source=admin, not Zalo OAuth')
for method in ['get','post']:
 route('/v1/auth/zalo/start',method,'ZaloStartResponse','ZaloStartRequest' if method=='post' else None,public=True,description='Start registered TN User OAuth v4 with a fresh PKCE verifier and 10-minute state. An existing registered device requires its owner Bearer session; fresh devices can start anonymously. Configuration must supply TN app_id, app secret environment reference and registered HTTPS redirect_uri. No upstream Original app identity or Premium entitlement is reused.')
 operation=paths['/v1/auth/zalo/start'][method]
 operation['security']=[{}, {'bearerAuth':[]}]
 for status,description in [('409','Device already registered or ownership changed'),('429','Pending sign-in limit reached')]:operation['responses'][status]={'description':description,'content':{'application/json':{'schema':ref('ErrorBody')}}}
 if method=='get':operation['parameters']=[{'name':key,'in':'query','required':True,'schema':value} for key,value in schemas['ZaloStartRequest']['properties'].items()]
route('/v1/auth/zalo/continue','get',S,public=True,description='Same-origin, no-store QR landing page. Shows the short confirmation code and links only to the official Zalo OAuth authorization URL.')
paths['/v1/auth/zalo/continue']['get']['responses']['200']={'description':'Pairing confirmation page','content':{'text/html':{'schema':S}}}
paths['/v1/auth/zalo/continue']['get']['responses']['410']={'description':'Pairing expired','content':{'text/html':{'schema':S}}}
paths['/v1/auth/zalo/continue']['get']['parameters']=[{'name':'state','in':'query','required':True,'schema':{'type':'string','minLength':43,'maxLength':43}}]
route('/v1/auth/zalo/status','post','ZaloStatusResponse','ZaloStatusRequest',public=True,description='Poll a QR sign-in using its private client proof and device ID; returns only pending, authorized, denied or expired. Never returns the OAuth code or TN session.')
paths['/v1/auth/zalo/status']['post']['responses']['409']={'description':'State, proof, device or configuration changed','content':{'application/json':{'schema':ref('ErrorBody')}}}
route('/v1/auth/zalo/callback','get',S,public=True,description='Registered HTTPS browser callback. Validates state and stores its one-time authorization code, but never issues a TN session or exposes the code in the response. The initiating client must still complete using its private client_proof.')
callback=paths['/v1/auth/zalo/callback']['get']
callback['parameters']=[
 {'name':'state','in':'query','required':True,'schema':S},
 {'name':'code','in':'query','required':False,'schema':S},
 {'name':'error','in':'query','required':False,'schema':S},
 {'name':'error_reason','in':'query','required':False,'schema':S},
]
callback['responses']['200']={'description':'Return to TN Assistant to complete sign-in','content':{'text/html':{'schema':S}}}
callback['responses']['409']={'description':'State expired, already used, configuration changed or callback code conflicts','content':{'application/json':{'schema':ref('ErrorBody')}}}
route('/v1/auth/zalo/complete','post','AuthSession','ZaloCompleteRequest',public=True,description='Consume the state once using the initiating client proof and exact device ID. Exchanges code+PKCE at official Zalo OAuth v4, verifies /me identity, then commits an ordinary TN session transaction. Existing source session must remain active before and after exchange. Device ownership and account limit are rechecked. A new guest identity upgrades in place; an already known app-scoped Zalo identity retains its existing account. No automatic Premium grant. Provider/transport failure consumes the attempt: start sign-in again, never replay the authorization code.')
for status,description in [('409','State/proof/device/source-session/ownership conflict or account device limit'),('502','Zalo token/profile service unavailable or response invalid')]:paths['/v1/auth/zalo/complete']['post']['responses'][status]={'description':description,'content':{'application/json':{'schema':ref('ErrorBody')}}}
route('/v1/payments','get','OrderList');route('/v1/payments','post','Order',obj({'product_id':S},['product_id']),status='201')
route('/v1/payments/{id}','get','Order');route('/v1/payments/{id}/sandbox','post','Order',obj({'status':{'type':'string','enum':['paid','failed','cancelled']}},['status']))
route('/v1/payments/webhook','post','Order',obj({'event_id':S,'order_id':S,'status':S},['event_id','order_id','status']),public=True,description='HMAC SHA256 of exact raw body via X-TN-Signature; TN_WEBHOOK_SECRET. Event id and paid fulfillment idempotent.')
route('/v1/history','get','HistoryEntryList');route('/v1/history','post','HistoryEntry',obj({'kind':S,'text':S,'result':O},['kind','text']),status='201')
route('/v1/content','get','ContentList');route('/v1/traffic/check','post',O,obj({'plate':S},['plate']))
route('/v1/weather/current','get',O,description='Authenticated MET Norway Locationforecast proxy. Coordinates are truncated to four decimals, responses are cached according to upstream expiry and identify MET Norway attribution; the client never calls MET Norway directly.')
paths['/v1/weather/current']['get']['parameters']=[{'name':'lat','in':'query','required':True,'schema':{'type':'number','minimum':-90,'maximum':90}},{'name':'lon','in':'query','required':True,'schema':{'type':'number','minimum':-180,'maximum':180}}]
route('/v1/diagnostics','get','DiagnosticList');route('/v1/diagnostics','post','Diagnostic',obj({'kind':S,'message':S,'details':O},['kind','message']),status='201')
route('/v1/releases/latest','get','Release',public=True,description='Public read-only endpoint. Returns the newest published release for application_id. Supported IDs: vn.tn.assistant and vn.tn.assistant.preview. Unpublished drafts are never returned. If omitted, returns only legacy catalog entries without an application_id; channel-specific releases are never mixed.')
paths['/v1/releases/latest']['get']['responses'].pop('401',None)
paths['/v1/releases/latest']['get']['responses'].pop('403',None)
paths['/v1/releases/latest']['get']['parameters']=[{'name':'application_id','in':'query','required':False,'schema':{'type':'string','enum':['vn.tn.assistant','vn.tn.assistant.preview']}}]
route('/v1/assistant/text','post','AssistantResponse','AssistantRequest',description='Premium AI fallback after commands; tools mode opt-in. Validated tools: open app, navigate, call, search/play through the configured media app, set an Android alarm, and create a calendar reminder. Calls, alarms, and reminders require a single-use confirmation_token. Live weather/news/market/lottery/TV facts require a configured live provider; otherwise the assistant must state that current data is unavailable. Client reuses request_id on retry and MUST skip directives when replayed=true. Context uses authenticated account/device, 12 messages, 30 min TTL. Processing budget 45 seconds.');route('/v1/tts','post','TTSResult',obj({'text':S,'speaker_id':S},['text']))
route('/v1/assistant/location','post','AssistantLocationResponse','AssistantLocationRequest',description='Resolve only an original MapDirection action from a completed assistant request belonging to the authenticated user and device. The client cannot supply URL or callback payload. Callback stays on the bound NLU origin, uses original common signing and cached replay; actions expire after 30 minutes. Private callback content is volatile for 5 minutes and never persisted. status=speech (original code1) does not authorize navigation.')
paths['/v1/tts']['post']['description']='Authenticated synthesis. In independent runtime with TN_TTS_PROVIDER=original, use the authenticated account saved agent_voice: south maps to speaker 5, north to speaker 11. Missing or invalid saved choice retains the configured provider speaker. Request speaker_id and agent_voice fields do not override this selection. Other TTS providers retain their existing voice configuration. Audio remains on the authenticated TN origin.'
route('/v1/assistant/traffic-violations','post','AssistantTrafficUploadResponse','AssistantTrafficUploadRequest',description='Upload observed five-field violations only for a completed, non-cached-only CheckTrafficViolation action of the authenticated account/device. Registration number comes solely from the stored original action; target path is fixed to Original update-traffic-violations under bound NLU origin. Same request/action data replays cached acknowledgment; changed rows conflict. Submitted/transport_ack means Original HTTP2xx acknowledgment, not independently verified upstream cache storage. Private content remains volatile only.')
route('/v1/tts/audio/{id}','get',{'type':'string','format':'binary'})
for path,result in [('/v1/speech/decode','SpeechResult'),('/v1/speech/wake-word/detect',O)]:
 route(path,'post',result);paths[path]['post']['requestBody']={'required':True,'content':{mime:{'schema':{'type':'string','format':'binary'}} for mime in ['audio/wav','audio/pcm']}}
route('/v1/speech/stream','get','SpeechEvent',description='WebSocket upgrade. Bearer header. Start SpeechStart JSON, PCM16 binary packets, end/cancel JSON. Events SpeechEvent. Missing WSS with configured HTTP ASR: STREAMING_UNSUPPORTED; missing all ASR: PROVIDER_NOT_CONFIGURED.')
for kind,model in [('users','User'),('devices','Device'),('products','Product'),('activation-codes','ActivationCode'),('content','Content'),('releases','Release')]:
 route('/v1/admin/'+kind,'get',model+'List',description='Administrator only')
 req=O if kind=='users' else ref(model)
 if kind=='activation-codes':req=obj({'product_id':S,'count':I,'uses':I,'expires_at':S},['product_id'])
 route('/v1/admin/'+kind,'post',model+'List' if kind=='activation-codes' else model,req,status='201' if kind=='activation-codes' else '200')
 route('/v1/admin/'+kind+'/{id}','put',model,O);route('/v1/admin/'+kind+'/{id}','delete')
for kind,model in [('payments','Order'),('diagnostics','Diagnostic'),('history','HistoryEntry')]:route('/v1/admin/'+kind,'get',model+'List')
route('/v1/admin/overview','get',O)
route('/v1/admin/premium-local/{id}','get','LocalPremium',description='Administrator only. Account-scoped local Premium status; upstream_granted is always false.')
route('/v1/admin/premium-local/{id}','put','LocalPremium',obj({'duration_days':{'type':'integer','minimum':1,'maximum':365}},['duration_days']),description='Administrator only. Grant or replace local Premium expiry, without payment. Audited; does not grant upstream provider rights.')
route('/v1/admin/premium-local/{id}','delete','LocalPremium',description='Administrator only. Revoke local rights only, preserving other entitlements and audit history.')
for kind,model in [('config',O),('providers','Providers')]:route('/v1/admin/'+kind,'get',model);route('/v1/admin/'+kind,'put',model,model)
route('/v1/admin/ai/providers','get','AIProviders',description='Administrator only. Keys are write-only; encrypted ciphertext is never returned.')
route('/v1/admin/ai/providers','put','AIProviders','AIProviders',description='Administrator only. Replace ordered providers; AES-GCM keys use a private master-key file outside SQLite.')
route('/v1/admin/ai/providers/test','post',O,obj({'id':S},['id']),description='Administrator only. Counts against provider quota; no provider response text or credentials returned.')
# Owner-managed roads and licensed news; no invented coverage or scraped content.
managed_road=obj({'id':S,'kind':{'type':'string','enum':['road','camera','sign']},'latitude':{'type':'number'},'longitude':{'type':'number'},'bearing':{'type':'number','minimum':0,'exclusiveMaximum':True,'maximum':360},'max_speed':{'type':'number','minimum':1,'maximum':200,'nullable':True},'name':S,'type':S,'source':S,'license':S,'source_updated_at':{'type':'string','format':'date-time'}},['kind','latitude','longitude','bearing','source','license','source_updated_at'])
managed_news=obj({'id':S,'title':S,'text':S,'source':S,'license':S,'source_updated_at':{'type':'string','format':'date-time'},'rights_confirmed':B,'published':B},['title','text','source','license','source_updated_at','rights_confirmed'])
schemas['ManagedRoad']=managed_road;schemas['ManagedNews']=managed_news
schemas['NewsSource']=obj({'id':S,'name':S,'url':S,'region':S,'category':S,'language':{'type':'string','enum':['vi','en','multi']}},['id','name','url','region','category','language'])
schemas['SavedAddress']=obj({'id':S,'name':S,'address':S,'lat':{'type':'number','minimum':-90,'maximum':90},'lon':{'type':'number','minimum':-180,'maximum':180},'created_at':{'type':'string','format':'date-time'},'updated_at':{'type':'string','format':'date-time'}},['id','name','address','created_at','updated_at'])
managed_config=obj({'driving_enabled':B,'local_tts_url':S,'tts_voice':S})
route('/v1/admin/managed/config','get',managed_config);route('/v1/admin/managed/config','put',managed_config,managed_config)
route('/v1/admin/managed/roads','get',obj({'items':arr(ref('ManagedRoad'))}));route('/v1/admin/managed/roads','post',O,'ManagedRoad')
route('/v1/admin/managed/roads/{id}','put',O,'ManagedRoad');route('/v1/admin/managed/roads/{id}','delete')
route('/v1/admin/managed/roads/import','post',O,obj({'items':arr(ref('ManagedRoad'))},['items']),description='Administrator only. 1..2000 records per batch; source, ODbL attribution/license and source date required.')
route('/v1/admin/managed/feedback','get',O);route('/v1/admin/managed/feedback/{id}','patch',O,obj({'status':{'type':'string','enum':['accepted','rejected']},'review_note':S},['status']))
route('/v1/driving/feedback','post',O,obj({'road_id':S,'message':S},['message']))
route('/v1/admin/managed/news','get',obj({'items':arr(ref('ManagedNews'))}));route('/v1/admin/managed/news','post',O,'ManagedNews')
route('/v1/admin/managed/news/{id}','get',O);route('/v1/admin/managed/news/{id}','put',O,'ManagedNews');route('/v1/admin/managed/news/{id}','delete')
route('/v1/admin/managed/news/{id}/audio','post',O,description='Administrator only. Queue local TTS generation of owned/licensed content.')
route('/v1/admin/managed/news/{id}/job','get',O,description='Administrator only. queued/running/ready/failed with completed_chunks, total_chunks, error_code.')
route('/v1/news/managed','get',O,description='Premium only. Published owner-managed licensed news.')
route('/v1/news/sources','get',obj({'items':arr(ref('NewsSource')),'count':I,'content_policy':S}),description='Premium only. Curated publisher homepage directory (47 sources); returns attribution and external links, not article text or audio. Third-party article text/audio requires separate rights confirmation.')
route('/v1/news/managed/{id}/play','post',O,description='Premium only. Returns PlayerPodcast directive; audio endpoint requires the same bearer authentication.')
for audio_method in ['get','head']:route('/v1/news/managed/{id}/audio',audio_method,{'type':'string','format':'binary'},description='Premium only. Authenticated WAV audio with Range support.')
route('/v1/addresses','get',obj({'items':arr(ref('SavedAddress'))}),description='Authenticated account-scoped saved destinations. GET and DELETE remain available after Premium expiry so users can access or remove their own data.')
route('/v1/addresses','post',obj({'item':ref('SavedAddress')}),'SavedAddressInput',description='Premium address_book entitlement required. Creates or updates by normalized destination name; maximum 20 entries per account.')
route('/v1/addresses/{id}','put',obj({'item':ref('SavedAddress')}),'SavedAddressInput',description='Premium address_book entitlement required. Updates only the authenticated owner entry.')
route('/v1/addresses/{id}','delete',O,description='Deletes only the authenticated owner entry; remains available after Premium expiry.')
schemas['SavedAddressInput']=obj({'name':{'type':'string','minLength':1,'maxLength':40},'address':{'type':'string','minLength':3,'maxLength':500},'lat':{'type':'number','minimum':-90,'maximum':90},'lon':{'type':'number','minimum':-180,'maximum':180}},['name','address'])
route('/v1/admin/link/{code}/complete','post','Link',obj({'user_id':S},['user_id']))
spec={'openapi':'3.0.3','info':{'title':'TN Assistant backend','version':'1.0.0','description':'Local backend, sandbox payments; real provider credentials required, no simulated cloud recognition.'},'servers':[{'url':'http://127.0.0.1:8080'}],'security':[{'bearerAuth':[]}],'paths':paths,'components':{'securitySchemes':{'bearerAuth':{'type':'http','scheme':'bearer'}},'schemas':schemas}}
target=Path(__file__).resolve().parent.parent/'api'/'openapi.json';target.parent.mkdir(exist_ok=True);target.write_text(json.dumps(spec,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print('OpenAPI: '+str(len(paths))+' paths, '+str(len(schemas))+' schemas')
