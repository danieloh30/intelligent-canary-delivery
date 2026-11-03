const $=s=>document.querySelector(s);
const names={metrics:'Metrics Analyst',dependency:'Dependency Investigator',release:'Release Analyst',decision:'Canary Decision Agent'};
const stories={healthy:['What does a healthy release look like?','Establish the stable baseline before introducing a controlled fault.'],slowdown:['Is the release responsible for this slowdown?','Compare stable and canary. A shared dependency problem calls for context, recovery, and a fresh observation window.'],retry:['Can successful requests still hurt the system?','Follow the extra inventory calls. The canary may look healthy while consuming more dependency capacity.'],missing:['When should the platform refuse to decide?','Missing evidence is uncertainty. The policy gate withholds permission to promote.']};
let scene='healthy',state={},tab='overview',busy=false,paused=false,lastCluster=0,releasing=false,clusterView=null,stateGeneration=0,selectionInitialized=false;
function consumeAccess(){const handoff=new URLSearchParams(location.hash.slice(1));if(!handoff.has('access'))return '';const value=handoff.get('access');handoff.delete('access');history.replaceState(null,'',location.pathname+location.search+(handoff.size?'#'+handoff.toString():''));return value||'';}
const access=consumeAccess();
const localHost=['localhost','127.0.0.1','[::1]'].includes(location.hostname);
let token=access||sessionStorage.getItem('canary-token')||(localHost?'demo-local':'');
if(token)sessionStorage.setItem('canary-token',token);
function text(el,value){el.textContent=value;}
function notice(message){$('#notice').hidden=!message;text($('#notice'),message||'');}
function rolloutPhase(phase){
 const badge=$('#rollout-phase'),value=phase.toLowerCase();
 badge.dataset.state=value==='healthy'?'healthy':['paused','progressing','pending','reconciling'].includes(value)?'warning':['degraded','failed','error','aborted'].includes(value)?'danger':'neutral';
 text(badge,'Rollout: '+phase);
}
async function api(path,method='GET'){
 const response=await fetch('/api'+path,{method,headers:token?{Authorization:'Bearer '+token}:{}});
 if(response.status===401){text($('#connection'),'Access required');if(!$('#access-dialog').open)$('#access-dialog').showModal();throw new Error('Enter a valid presenter token.');}
 if(!response.ok){let body=await response.text();throw new Error(body.slice(0,180)||`Request failed (${response.status})`);}
 return response.json();
}
function agentCards(e={}){
 const parent=$('#agents');parent.replaceChildren();Object.entries(names).forEach(([key,name],i)=>{
 const card=document.createElement('article');card.className='agent';
 const number=document.createElement('span');number.className='agent-number';number.textContent=String(i+1).padStart(2,'0');
 const heading=document.createElement('h3');heading.textContent=name;
 const status=document.createElement('span');let s=e.agents?.[key]||'READY';status.className='agent-state '+s.toLowerCase();status.textContent=s;
 card.append(number,heading,status);parent.append(card);
 });
}
function releasePending(){
 const intent=state.releaseIntent,actual=clusterView?.rollout?.spec?.template?.metadata?.annotations?.['demo.canary/released-at'];
 return !!(state.clusterEnabled&&intent?.releasedAt&&actual!==intent.releasedAt&&!(Date.parse(actual)>=Date.parse(intent.releasedAt)));
}
function evidenceCurrent(e){
 if(!e.evidence)return false;
 if(!state.clusterEnabled)return true;
 const r=clusterView?.rollout?.status;
 return !!r&&!releasePending()&&clusterView.candidateVersion===e.evidence.revision&&r.currentStepIndex===e.evidence.rolloutStep&&e.evidence.traces?.some(x=>x.podHash===r.currentPodHash);
}
function render(){
 const e=state.latest||{},evidence=e.evidence,current=evidenceCurrent(e);
 const expired=!!e.decision&&Date.parse(e.decision.expiresAt)<=Date.now();
 agentCards(current?e:{});
 text($('#connection'),state.clusterEnabled?(clusterView?.connected===false?'Cluster unavailable':'Kubernetes connected'):'Offline rehearsal');
 if(!state.clusterEnabled)rolloutPhase('Offline');else if(clusterView?.connected&&releasePending())rolloutPhase('Reconciling');
 text($('#model'),state.mode==='fixture'?'DETERMINISTIC FIXTURES':'gpt-4o-mini');
 if(!state.clusterEnabled)notice(state.mode==='fixture'?'Offline rehearsal · deterministic fixtures, no LLM calls, no rollout actions.':'Offline evidence · live GPT-4o mini agents; no rollout actions.');
 if(evidence)renderEvidence(e);
 if(evidence&&current&&!expired){const c=evidence.canary,s=evidence.stable;
 text($('#stable-version'),evidence.stableRevision.length>12?evidence.stableRevision.slice(0,10):evidence.stableRevision);text($('#canary-version'),evidence.revision);
 text($('#success'),((1-c.errorRate)*100).toFixed(1)+'%');text($('#latency'),c.p95Ms.toFixed(0));text($('#attempts'),c.attemptsPerCheckout.toFixed(2)+'×');text($('#samples'),String(s.requests+c.requests));
 const pass=c.errorRate<=.01&&c.p95Ms<=500;const sv=$('#static-verdict');text(sv,c.requests<30?'Insufficient samples':pass?'PASS':'FAIL');sv.className=c.requests<30?'hold':pass?'good':'bad';
 const d=e.decision;const effective=$('#effective-verdict');text(effective,d?d.effective.replace('_','-'):e.status==='RUNNING'?'Investigating…':'Awaiting investigation');effective.className=d?.effective==='GO'?'good':d?.effective==='NO_GO'?'bad':'hold';
 text($('#policy-reason'),d?.reason||'Specialists are collecting and correlating evidence.');
 text($('#source'),evidence.source.replaceAll('_',' '));text($('#evaluation-id'),e.id+' · '+new Date(evidence.capturedAt).toLocaleTimeString());
 $('#approve').hidden=!d?.approvalRequired;
 }else{
 for(const id of ['success','latency','attempts','samples'])text($('#'+id),'—');
 text($('#static-verdict'),'Awaiting signals');$('#static-verdict').className='hold';
 const baseline=clusterView?.candidateVersion==='v1'&&clusterView?.rollout?.status?.phase==='Healthy'&&clusterView?.rollout?.status?.currentPodHash===clusterView?.rollout?.status?.stableRS;
 const pendingBaseline=state.releaseIntent?.reset&&!baseline&&(releasePending()||clusterView?.candidateVersion==='v1');
 text($('#effective-verdict'),pendingBaseline?'Awaiting baseline':current&&expired?'Decision expired':'Awaiting investigation');$('#effective-verdict').className='hold';
 text($('#policy-reason'),pendingBaseline?'v1 recovery uses the normal rollout gates. Wait for observations, then run Development investigations at 10% and 50%.':baseline?'Healthy v1 baseline. Release v2 when ready to begin.':current&&expired?'This decision has expired. Run a fresh investigation.':evidence?'The previous investigation belongs to another release or rollout stage. Run a fresh investigation.':'No current investigation. Release a candidate and collect observations before investigating.');
 text($('#source'),evidence?'Previous investigation · retained in Evidence explorer':'No observation captured');
 text($('#evaluation-id'),evidence?e.id+' · historical result':'Release-bound decisions expire after 120 seconds.');
 $('#approve').hidden=true;
 if(!evidence){$('#evidence-content').replaceChildren();text($('#evidence-content'),'No current investigation. Release a candidate and run an investigation to inspect its evidence.');}
 if(!state.clusterEnabled){text($('#stable-version'),'v1');text($('#canary-version'),'—');}
 }
 if(state.clusterEnabled){
 text($('#stable-version'),clusterView?.stableVersion||'—');
 const r=clusterView?.rollout?.status||{};
 if(!selectionInitialized&&clusterView?.connected){const version=releasePending()?state.releaseIntent.version:r.currentPodHash!==r.stableRS?clusterView.candidateVersion:'v2';if(version){selectScene(version==='v3'?'retry':'healthy');$('#revision').value=version;selectionInitialized=true;}}
 text($('#canary-version'),releasePending()?state.releaseIntent.version:r.currentPodHash&&r.currentPodHash===r.stableRS?'—':clusterView?.candidateVersion||'—');
 }
 const canaryActive=!['—',''].includes($('#canary-version').textContent);
 $('#canary-identity').dataset.active=String(canaryActive);
 text($('#canary-description'),canaryActive?'Candidate under evaluation':'No active canary');
 $('#reset').disabled=state.resetEnabled===false||busy||releasing||e.status==='RUNNING';
 $('#evaluate').disabled=busy||releasing||e.status==='RUNNING';
 $('#release').disabled=!state.releaseEnabled||busy||releasing||e.status==='RUNNING';
 text($('#release-help'),state.releaseEnabled?'Commits the rollout manifest to Git. Argo CD deploys the change.':'Dashboard releases are disabled. Configure server GitHub access or use ./demo.sh release v2 (or v3).');
 const activity=$('#activity');activity.replaceChildren();(state.events||[]).slice(-15).reverse().forEach(event=>{
 const row=document.createElement('div');row.className='event';const time=document.createElement('time');time.textContent=new Date(event.at).toLocaleTimeString([], {hour12:false});const actor=document.createElement('b');actor.textContent=event.actor;const message=document.createElement('span');message.textContent=event.message;row.append(time,actor,message);activity.append(row);
 });
 if(tab==='logs'&&$('#log-source').value==='agents'&&!paused)text($('#console'),(state.events||[]).map(x=>`${x.at} [${x.actor}] ${x.message}`).join('\n')||'Ready. Run an investigation to follow agent activity.');
}
function renderEvidence(e){
 const host=$('#evidence-content');host.replaceChildren();
 const blocks=[['Observation window',JSON.stringify(e.evidence,null,2)],['Agent recommendation',JSON.stringify(e.recommendation||{status:e.status},null,2)],['Platform policy',JSON.stringify(e.decision||{status:'Pending'},null,2)]];
 for(const [name,f]of Object.entries(e.findings||{})){const section=document.createElement('section');section.className='finding';const h=document.createElement('h3');h.textContent=names[name];const p=document.createElement('p');p.textContent=f.summary;section.append(h,p);host.append(section);}
 for(const [title,body]of blocks){const section=document.createElement('section');section.className='evidence-block';const h=document.createElement('h3');h.textContent=title;const pre=document.createElement('pre');pre.textContent=body;section.append(h,pre);host.append(section);}
}
async function poll(){
 const generation=stateGeneration;
 try{
 const next=await api('/state');if(generation!==stateGeneration)return;state=next;
 if(state.clusterEnabled&&Date.now()-lastCluster>4000){lastCluster=Date.now();const c=await api('/cluster');if(generation!==stateGeneration)return;clusterView=c;
 if(!c.connected){text($('#connection'),'Cluster unavailable');rolloutPhase('Unavailable');notice(c.message);}else{const r=c.rollout?.status||{};rolloutPhase(r.phase||'Reconciling');const weight=r.canary?.weights?.canary?.weight;$('#traffic-weight').style.width=(weight??0)+'%';text($('#traffic-caption'),weight===undefined?'Traffic managed by Argo Rollouts':`${weight}% canary / ${100-weight}% stable`);}}
 render();
 }catch(e){if(state.clusterEnabled){clusterView={connected:false};rolloutPhase('Unavailable');render();}notice(e.message);}
}
async function action(fn){busy=true;$('#evaluate').disabled=true;try{await fn();await poll();}catch(e){notice(e.message);}finally{busy=false;render();}}
function selectScene(value){scene=value;document.querySelectorAll('[data-scene]').forEach(b=>b.classList.toggle('selected',b.dataset.scene===scene));text($('#scene-question'),stories[scene][0]);text($('#scene-description'),stories[scene][1]);$('#revision').value=scene==='retry'?'v3':'v2';}
document.querySelectorAll('[data-tab]').forEach(button=>button.addEventListener('click',()=>{tab=button.dataset.tab;document.querySelectorAll('.tab').forEach(p=>p.classList.toggle('active',p.id===tab));document.querySelectorAll('.nav').forEach(b=>b.classList.toggle('active',b===button));if(tab==='logs')logs();if(tab==='architecture'){if(!window.canaryDiagramsLoaded){window.canaryDiagramsLoaded=true;const script=document.createElement('script');script.src='/diagrams.js';document.body.append(script);}else document.dispatchEvent(new Event('canary-diagrams-visible'));}}));
document.querySelectorAll('[data-scene]').forEach(b=>b.addEventListener('click',()=>selectScene(b.dataset.scene)));
$('#evaluate').addEventListener('click',()=>action(()=>api(`/evaluate?scene=${scene}&profile=${$('#profile').value}&revision=${$('#revision').value}`,'POST')));
$('#release').addEventListener('click',async()=>{
 if(releasing)return;
 releasing=true;render();$('#release-result').hidden=false;text($('#release-result'),'Committing release to Git…');
 try{const result=await api('/release/'+$('#revision').value,'POST');const link=document.createElement('a');link.href=result.url;link.target='_blank';link.rel='noopener noreferrer';link.textContent=result.sha.slice(0,8);$('#release-result').replaceChildren(document.createTextNode(result.version+' committed: '),link,document.createTextNode('. Waiting for Argo CD. Run an investigation after the canary is sampled.'));stateGeneration++;state.latest={};lastCluster=0;await poll();}
 catch(e){text($('#release-result'),e.message);}finally{releasing=false;render();}
});
$('#approve').addEventListener('click',()=>action(()=>api('/evaluations/'+state.latest.id+'/approve','POST')));
$('#inject').addEventListener('click',()=>action(()=>api('/fault/25','POST')));$('#clear').addEventListener('click',()=>action(()=>api('/fault/0','POST')));
$('#connect').addEventListener('click',()=>$('#access-dialog').showModal());$('#save-token').addEventListener('click',()=>{const input=$('#token');if(!input.value)return;token=input.value;sessionStorage.setItem('canary-token',token);setTimeout(poll,0);});
$('#demo-mode').addEventListener('change',()=>{const extended=$('#demo-mode').value==='extended';document.querySelectorAll('.extended').forEach(el=>el.hidden=!extended);if(!extended&&scene==='missing')selectScene('healthy');});
$('#reset').addEventListener('click',()=>action(async()=>{
 const result=await api('/reset','POST');stateGeneration++;state.latest={};clusterView=null;lastCluster=0;
 selectScene('healthy');$('#revision').value='v1';$('#profile').value='development';paused=false;
 text($('#pause-logs'),'Pause updates');$('#release-result').hidden=false;
 text($('#release-result'),result.message||'Reset requested v1 through Git. Wait for canary observations, then run Development investigations at each analysis stage.');
}));
$('#download').addEventListener('click',()=>{if(!state.latest?.id){notice('Run an investigation first.');return;}const blob=new Blob([JSON.stringify(state.latest,null,2)],{type:'application/json'});const a=document.createElement('a');a.href=URL.createObjectURL(blob);a.download='canary-decision-'+state.latest.id+'.json';a.click();URL.revokeObjectURL(a.href);});
$('#pause-logs').addEventListener('click',()=>{paused=!paused;text($('#pause-logs'),paused?'Resume updates':'Pause updates');});
async function logs(){if(tab!=='logs'||paused)return;const source=$('#log-source').value;if(source==='agents'){render();return;}try{text($('#console'),(await api('/logs/'+source)).join('\n'));}catch(e){notice(e.message);}}
$('#log-source').addEventListener('change',logs);
window.addEventListener('hashchange',()=>{const value=consumeAccess();if(!value)return;token=value;sessionStorage.setItem('canary-token',token);if($('#access-dialog').open)$('#access-dialog').close();poll();});
agentCards();poll();setInterval(poll,2000);setInterval(logs,4000);
