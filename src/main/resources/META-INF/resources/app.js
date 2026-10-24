const $=s=>document.querySelector(s);
const names={metrics:'Metrics Analyst',dependency:'Dependency Investigator',release:'Release Analyst',decision:'Canary Decision Agent'};
const stories={healthy:['What does a healthy release look like?','Establish the stable baseline before introducing a controlled fault.'],slowdown:['Is the release responsible for this slowdown?','Compare stable and canary. A shared dependency problem calls for context, recovery, and a fresh observation window.'],retry:['Can successful requests still hurt the system?','Follow the extra inventory calls. The canary may look healthy while consuming more dependency capacity.'],missing:['When should the platform refuse to decide?','Missing evidence is uncertainty. The policy gate withholds permission to promote.']};
let scene='healthy',state={},tab='overview',busy=false,paused=false,lastCluster=0,elapsed=0,ticking=false;
function consumeAccess(){const handoff=new URLSearchParams(location.hash.slice(1));if(!handoff.has('access'))return '';const value=handoff.get('access');handoff.delete('access');history.replaceState(null,'',location.pathname+location.search+(handoff.size?'#'+handoff.toString():''));return value||'';}
const access=consumeAccess();
const localHost=['localhost','127.0.0.1','[::1]'].includes(location.hostname);
let token=access||sessionStorage.getItem('canary-token')||(localHost?'demo-local':'');
if(token)sessionStorage.setItem('canary-token',token);
function text(el,value){el.textContent=value;}
function notice(message){$('#notice').hidden=!message;text($('#notice'),message||'');}
async function api(path,method='GET'){
 const response=await fetch('/api'+path,{method,headers:{Authorization:'Bearer '+token}});
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
function render(){
 const e=state.latest||{},evidence=e.evidence;agentCards(e);
 text($('#connection'),state.clusterEnabled?'Kubernetes connected':'Offline rehearsal');
 text($('#model'),state.mode==='fixture'?'DETERMINISTIC FIXTURES':'gpt-4o-mini');
 if(!state.clusterEnabled)notice(state.mode==='fixture'?'Offline rehearsal · deterministic fixtures, no LLM calls, no rollout actions.':'Offline evidence · live GPT-4o mini agents; no rollout actions.');
 if(evidence){const c=evidence.canary,s=evidence.stable;
 text($('#stable-version'),evidence.stableRevision.length>12?evidence.stableRevision.slice(0,10):evidence.stableRevision);text($('#canary-version'),evidence.revision);
 text($('#success'),((1-c.errorRate)*100).toFixed(1)+'%');text($('#latency'),c.p95Ms.toFixed(0));text($('#attempts'),c.attemptsPerCheckout.toFixed(2)+'×');text($('#samples'),String(s.requests+c.requests));
 const pass=c.errorRate<=.01&&c.p95Ms<=500;const sv=$('#static-verdict');text(sv,c.requests<30?'Insufficient samples':pass?'PASS':'FAIL');sv.className=c.requests<30?'hold':pass?'good':'bad';
 const d=e.decision;const effective=$('#effective-verdict');text(effective,d?d.effective.replace('_','-'):e.status==='RUNNING'?'Investigating…':'Awaiting investigation');effective.className=d?.effective==='GO'?'good':d?.effective==='NO_GO'?'bad':'hold';
 text($('#policy-reason'),d?.reason||'Specialists are collecting and correlating evidence.');
 text($('#source'),evidence.source.replaceAll('_',' '));text($('#evaluation-id'),e.id+' · '+new Date(evidence.capturedAt).toLocaleTimeString());
 $('#approve').hidden=!d?.approvalRequired;
 renderEvidence(e);
 }
 $('#evaluate').disabled=busy||e.status==='RUNNING';
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
async function poll(){if(!token)return;try{state=await api('/state');render();if(state.clusterEnabled&&Date.now()-lastCluster>8000){lastCluster=Date.now();const c=await api('/cluster');if(!c.connected){text($('#connection'),'Cluster unavailable');notice(c.message);}else{const r=c.rollout?.status||{};text($('#rollout-phase'),r.phase||'Reconciling');const weight=r.canary?.weights?.canary?.weight;$('#traffic-weight').style.width=(weight??0)+'%';text($('#traffic-caption'),weight===undefined?'Traffic managed by Argo Rollouts':`${weight}% canary / ${100-weight}% stable`);}}}catch(e){notice(e.message);}}
async function action(fn){busy=true;$('#evaluate').disabled=true;try{await fn();await poll();}catch(e){notice(e.message);}finally{busy=false;$('#evaluate').disabled=state.latest?.status==='RUNNING';}}
function selectScene(value){scene=value;document.querySelectorAll('[data-scene]').forEach(b=>b.classList.toggle('selected',b.dataset.scene===scene));text($('#scene-question'),stories[scene][0]);text($('#scene-description'),stories[scene][1]);$('#revision').value=scene==='retry'?'v3':'v2';}
document.querySelectorAll('[data-tab]').forEach(button=>button.addEventListener('click',()=>{tab=button.dataset.tab;document.querySelectorAll('.tab').forEach(p=>p.classList.toggle('active',p.id===tab));document.querySelectorAll('.nav').forEach(b=>b.classList.toggle('active',b===button));if(tab==='logs')logs();if(tab==='architecture'){if(!window.canaryDiagramsLoaded){window.canaryDiagramsLoaded=true;const script=document.createElement('script');script.src='/diagrams.js';document.body.append(script);}else document.dispatchEvent(new Event('canary-diagrams-visible'));}}));
document.querySelectorAll('[data-scene]').forEach(b=>b.addEventListener('click',()=>selectScene(b.dataset.scene)));
$('#evaluate').addEventListener('click',()=>action(()=>api(`/evaluate?scene=${scene}&profile=${$('#profile').value}&revision=${$('#revision').value}`,'POST')));
$('#approve').addEventListener('click',()=>action(()=>api('/evaluations/'+state.latest.id+'/approve','POST')));
$('#inject').addEventListener('click',()=>action(()=>api('/fault/25','POST')));$('#clear').addEventListener('click',()=>action(()=>api('/fault/0','POST')));
$('#connect').addEventListener('click',()=>$('#access-dialog').showModal());$('#save-token').addEventListener('click',()=>{const input=$('#token');if(!input.value)return;token=input.value;sessionStorage.setItem('canary-token',token);setTimeout(poll,0);});
$('#duration').addEventListener('change',()=>document.querySelectorAll('.extended').forEach(el=>el.hidden=$('#duration').value!=='15'));
$('#timer').addEventListener('click',()=>{ticking=!ticking;text($('#timer'),ticking?'Pause timer':'Resume timer');});
setInterval(()=>{if(!ticking)return;elapsed++;text($('#clock'),String(Math.floor(elapsed/60)).padStart(2,'0')+':'+String(elapsed%60).padStart(2,'0'));$('#clock').classList.toggle('bad',elapsed>Number($('#duration').value)*60);},1000);
$('#download').addEventListener('click',()=>{if(!state.latest?.id){notice('Run an investigation first.');return;}const blob=new Blob([JSON.stringify(state.latest,null,2)],{type:'application/json'});const a=document.createElement('a');a.href=URL.createObjectURL(blob);a.download='canary-decision-'+state.latest.id+'.json';a.click();URL.revokeObjectURL(a.href);});
$('#pause-logs').addEventListener('click',()=>{paused=!paused;text($('#pause-logs'),paused?'Resume updates':'Pause updates');});
async function logs(){if(tab!=='logs'||paused||!token)return;const source=$('#log-source').value;if(source==='agents'){render();return;}try{text($('#console'),(await api('/logs/'+source)).join('\n'));}catch(e){notice(e.message);}}
$('#log-source').addEventListener('change',logs);
window.addEventListener('hashchange',()=>{const value=consumeAccess();if(!value)return;token=value;sessionStorage.setItem('canary-token',token);if($('#access-dialog').open)$('#access-dialog').close();poll();});
agentCards();poll();if(!token)$('#access-dialog').showModal();setInterval(poll,2000);setInterval(logs,4000);
