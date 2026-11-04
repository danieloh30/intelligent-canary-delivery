const $=s=>document.querySelector(s);
const names={metrics:'Metrics Analyst',dependency:'Dependency Investigator',release:'Release Analyst',decision:'Canary Decision Agent'};
const stories={healthy:['What does a healthy release look like?','Establish the stable baseline before introducing a controlled fault.'],slowdown:['Is the release responsible for this slowdown?','Compare stable and canary. A shared dependency problem calls for context, recovery, and a fresh observation window.'],retry:['Can successful requests still hurt the system?','Follow the extra inventory calls. The canary may look healthy while consuming more dependency capacity.'],missing:['When should the platform refuse to decide?','Missing evidence is uncertainty. The policy gate withholds permission to promote.']};
let scene='healthy',state={},tab='overview',busy=false,paused=false,lastCluster=0,releasing=false,clusterView=null,stateGeneration=0,selectionInitialized=false,progressView=null,progressReceivedAt=0,progressLoading=false,canInvestigate=false;
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
function retainedNoGo(e){
 const r=clusterView?.rollout?.status;
 return !!(state.clusterEnabled&&clusterView?.connected&&!releasePending()&&e.decision?.effective==='NO_GO'&&e.evidence
  &&(r?.abort===true||['Degraded','Failed','Error'].includes(r?.phase))&&clusterView.candidateVersion===e.evidence.revision
  &&e.evidence.traces?.some(x=>x.podHash===r.currentPodHash&&x.cohort!=='stable'));
}
function renderProgress(){
 const p=progressView,r=clusterView?.rollout?.status;
 const stale=!p||Date.now()-progressReceivedAt>12000||Date.now()-Date.parse(p.capturedAt)>15000;
 const coherent=p&&r&&p.phase===r.phase&&p.podHash===r.currentPodHash&&p.stepIndex===r.currentStepIndex&&p.revision===clusterView?.candidateVersion;
 const pending=releasePending();
 let kind=!state.clusterEnabled?'OFFLINE':pending||releasing?'RECONCILING':p&&p.connected===false?'UNAVAILABLE':stale||!coherent?'REFRESHING':p.state;
 const selected=p&&$('#revision').value===p.revision;
 if(['READY','HOLD'].includes(kind)&&!selected)kind='SELECT_CANDIDATE';
 if(state.latest?.status==='RUNNING'&&evidenceCurrent(state.latest))kind='INVESTIGATING';
 const titles={OFFLINE:'Offline rehearsal',RECONCILING:'Waiting for GitOps reconciliation',REFRESHING:'Refreshing rollout progress',BASELINE:'Baseline ready',COMPLETE:'Release fully promoted',WARMUP:'Warm-up before analysis',COLLECTING:'Waiting for analysis readiness',READY:'Ready to investigate',INVESTIGATING:'Java agents are investigating',ADVANCING:'Waiting for Argo to advance',HOLD:'HOLD — investigate again after recovery',APPROVAL:'Presenter approval required',FAILED:'Rollout blocked',INCONCLUSIVE:'Analysis finished — fresh release required',UNAVAILABLE:'Live progress unavailable',SELECT_CANDIDATE:'Select the active candidate'};
 const suffix=p?.targetWeight&&!['BASELINE','COMPLETE','OFFLINE','RECONCILING','REFRESHING'].includes(kind)?` · ${p.targetWeight}% canary`:'';
 text($('#progress-title'),(titles[kind]||'Waiting for live progress')+suffix);
 text($('#progress-message'),kind==='OFFLINE'?'Rehearsal investigations use fixture or offline evidence; they do not advance a rollout.':kind==='RECONCILING'?'The release is committed. Wait here while Argo CD applies it.':kind==='REFRESHING'?'Waiting for a fresh snapshot of the current rollout stage.':kind==='SELECT_CANDIDATE'?`Choose candidate ${p.revision} below to investigate this stage.`:p?.message||'Live rollout progress is unavailable.');
 $('#investigation-cue').dataset.state=['READY'].includes(kind)?'ready':['FAILED','INCONCLUSIVE'].includes(kind)?'danger':['WARMUP','COLLECTING','HOLD','APPROVAL','SELECT_CANDIDATE'].includes(kind)?'waiting':'neutral';
 const list=$('#rollout-stages');list.replaceChildren();
 if(p?.stages?.length){
 const resetStages=['RECONCILING','REFRESHING','UNAVAILABLE'].includes(kind);
 const baseline=document.createElement('li');baseline.className='rollout-stage '+(resetStages?'pending':kind==='BASELINE'?'active':'complete');baseline.textContent='Stable baseline';list.append(baseline);
 for(const stage of p.stages){const item=document.createElement('li');item.className='rollout-stage '+(resetStages?'pending':stage.state);item.textContent=stage.weight===100?'100% rollout':stage.weight+'% canary';if(!resetStages&&stage.state==='active')item.setAttribute('aria-current','step');list.append(item);}
 }
 if(state.clusterEnabled&&['RECONCILING','REFRESHING','UNAVAILABLE'].includes(kind)){$('#traffic-weight').style.width='0%';text($('#traffic-caption'),kind==='RECONCILING'?'New release pending · waiting for GitOps reconciliation':'Waiting for current rollout observations');}
 if(state.clusterEnabled&&!stale&&coherent&&!pending&&!releasing){$('#traffic-weight').style.width=(p.state==='BASELINE'?0:p.targetWeight)+'%';text($('#traffic-caption'),p.state==='BASELINE'?'Stable baseline · no active candidate':p.state==='COMPLETE'?'Rollout complete · candidate is now stable':`Target: ${p.targetWeight}% canary · replica-based, approximate request share`);}
 const samples=state.clusterEnabled&&!stale&&coherent&&!pending&&!releasing&&p?.samplesAvailable;
 $('#live-samples').hidden=!samples;
 if(samples){for(const [id,count] of [['stable-samples',p.stableRequests],['canary-samples',p.canaryRequests]]){text($('#'+id),Number(count).toLocaleString());$('#'+id).className=count>=30?'good':'hold';}text($('#sample-window'),'Live rolling 60-second window · read '+new Date(p.capturedAt).toLocaleTimeString());}
 text($('#progress-source'),state.clusterEnabled?'Live cluster · refresh every 4s':'Offline rehearsal');
 text($('#analysis-status'),p?.analysisName&&!stale&&coherent&&!pending&&!releasing?`Analysis ${p.analysisPhase} · ${p.checksCompleted} / ${p.checksTotal||'unbounded'} checks · ${p.interval||'configured'} interval · ${p.analysisName}`:'');
 const intervalMatch=/^(\d+)(s|m|h)$/.exec(p?.interval||'');
 const estimate=intervalMatch&&p.checksTotal>0?` (about ${Math.round(p.checksTotal*Number(intervalMatch[1])*({s:1,m:60,h:3600}[intervalMatch[2]])/60)} minutes)` : '';
 const budget=p?.checksTotal>0?`${p.checksTotal} HTTP checks at ${p.interval} intervals${estimate}`:'its configured analysis checks';
 text($('#analysis-timing'),p?.analysisPhase==='Inconclusive'?'This gate has completed. Running agents again cannot restart it; request a fresh release or reset.':`${p?.pauseDuration||'The configured'} warm-up pause precedes the gate. At the gate, Argo waits for a decision for up to ${budget}; the pause is not a click deadline.`);
 if(p?.checksTotal>0&&p.checksCompleted>=p.checksTotal*.85&&!['Successful','Inconclusive','Failed','Error'].includes(p.analysisPhase))text($('#analysis-timing'),'Analysis is close to its check limit. If it finishes Inconclusive, request a fresh release; do not bypass the gate.');
 const eligible=state.clusterEnabled?!stale&&coherent&&!pending&&p?.connected&&['READY','HOLD'].includes(kind)&&!!p?.ready&&selected:true;
 canInvestigate=eligible&&!busy&&!releasing&&state.latest?.status!=='RUNNING';
 $('#evaluate').disabled=!canInvestigate;
 const waitingLabels={BASELINE:'Release a candidate first',COMPLETE:'Release a new candidate',WARMUP:'Warm-up in progress',COLLECTING:'Waiting for samples',REFRESHING:'Refreshing progress',UNAVAILABLE:'Progress unavailable',RECONCILING:'Waiting for Argo CD',INVESTIGATING:'Agents are running',ADVANCING:'Waiting for next stage',APPROVAL:'Approval required below',INCONCLUSIVE:'Start a fresh release',FAILED:'Review rollout outcome',SELECT_CANDIDATE:'Select the active candidate'};
 text($('#evaluate'),!state.clusterEnabled?'Run investigation':canInvestigate&&p?`Investigate ${p.revision} at ${p.targetWeight}%`:(waitingLabels[kind]||'Waiting for stage readiness'));
 text($('#policy-help'),$('#profile').value==='production'?'Production: eligible GO requires human approval. Applies to your next investigation; existing decisions are unchanged.':'Development: eligible GO needs no approval. Applies to your next investigation; starting agents is still manual.');
 $('#evaluate').classList.toggle('investigation-ready',state.clusterEnabled&&canInvestigate);
}
async function pollProgress(){
 if(!state.clusterEnabled||progressLoading)return;
 const generation=stateGeneration;progressLoading=true;
 try{const p=await api('/progress');if(generation!==stateGeneration||!state.clusterEnabled)return;progressView=p;progressReceivedAt=Date.now();render();}
 catch(e){if(generation===stateGeneration){progressView={connected:false,state:'UNAVAILABLE',message:'Cannot refresh live telemetry. Wait for progress to reconnect before investigating.',capturedAt:new Date().toISOString(),stages:[]};render();}}
 finally{progressLoading=false;}
}
function render(){
 const e=state.latest||{},evidence=e.evidence,current=evidenceCurrent(e);
 const expired=!!e.decision&&Date.parse(e.decision.expiresAt)<=Date.now();
 const retained=retainedNoGo(e);
 agentCards(current||retained?e:{});
 text($('#connection'),state.clusterEnabled?(clusterView?.connected===false?'Cluster unavailable':'Kubernetes connected'):'Offline rehearsal');
 if(!state.clusterEnabled)rolloutPhase('Offline');else if(clusterView?.connected&&releasePending())rolloutPhase('Reconciling');
 text($('#model'),state.mode==='fixture'?'DETERMINISTIC FIXTURES':'gpt-4o-mini');
 if(!state.clusterEnabled)notice(state.mode==='fixture'?'Offline rehearsal · deterministic fixtures, no LLM calls, no rollout actions.':'Offline evidence · live GPT-4o mini agents; no rollout actions.');
 if(evidence)renderEvidence(e);
 if(evidence&&(current&&!expired||retained)){const c=evidence.canary,s=evidence.stable;
 text($('#stable-version'),evidence.stableRevision.length>12?evidence.stableRevision.slice(0,10):evidence.stableRevision);text($('#canary-version'),evidence.revision);
 text($('#success'),((1-c.errorRate)*100).toFixed(1)+'%');text($('#latency'),c.p95Ms.toFixed(0));text($('#attempts'),c.attemptsPerCheckout.toFixed(2)+'×');text($('#samples'),String(s.requests+c.requests));
 const pass=c.errorRate<=.01&&c.p95Ms<=500;const sv=$('#static-verdict');text(sv,c.requests<30?'Insufficient samples':pass?'PASS':'FAIL');sv.className=c.requests<30?'hold':pass?'good':'bad';
 const d=e.decision;const effective=$('#effective-verdict');text(effective,d?d.effective.replace('_','-'):e.status==='RUNNING'?'Investigating…':'Awaiting investigation');effective.className=d?.effective==='GO'?'good':d?.effective==='NO_GO'?'bad':'hold';
 text($('#policy-reason'),retained?`Recorded NO-GO for this aborted rollout. ${d.reason}${expired?' Evidence expired; retained as an outcome, not fresh authorization.':''}`:d?.reason||'Specialists are collecting and correlating evidence.');
 text($('#source'),retained?'Recorded aborted-rollout outcome · frozen evidence':evidence.source.replaceAll('_',' '));text($('#evaluation-id'),e.id+' · '+new Date(evidence.capturedAt).toLocaleTimeString()+(retained?' · retained NO-GO':''));
 $('#approve').hidden=retained||!d?.approvalRequired;
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
 if(!evidence){evidenceRenderKey='';$('#evidence-content').replaceChildren();text($('#evidence-content'),'No current investigation. Release a candidate and run an investigation to inspect its evidence.');}
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
 $('#profile').disabled=busy||releasing||e.status==='RUNNING';
 $('#reset').disabled=state.resetEnabled===false||busy||releasing||e.status==='RUNNING';
 renderProgress();
 $('#release').disabled=!state.releaseEnabled||busy||releasing||e.status==='RUNNING';
 text($('#release-help'),state.releaseEnabled?'Commits the rollout manifest to Git. Argo CD deploys the change.':'Dashboard releases are disabled. Configure server GitHub access or use ./demo.sh release v2 (or v3).');
 const activity=$('#activity');activity.replaceChildren();(state.events||[]).slice(-15).reverse().forEach(event=>{
 const row=document.createElement('div');row.className='event';const time=document.createElement('time');time.textContent=new Date(event.at).toLocaleTimeString([], {hour12:false});const actor=document.createElement('b');actor.textContent=event.actor;const message=document.createElement('span');message.textContent=event.message;row.append(time,actor,message);activity.append(row);
 });
 if(tab==='logs'&&$('#log-source').value==='agents'&&!paused)text($('#console'),(state.events||[]).map(x=>`${x.at} [${x.actor}] ${x.message}`).join('\n')||'Ready. Run an investigation to follow agent activity.');
}
let evidenceRenderKey='';
function evidenceNode(tag,cls,value){const el=document.createElement(tag);if(cls)el.className=cls;if(value!==undefined)el.textContent=value;return el;}
function highlightFinding(el,value){
 const parts=String(value||'').split(/(\bsignificantly (?:lower|higher|faster|slower)\b|\b(?:low|high|higher|lower|elevated) latenc(?:y|ies)\b|\bno retr(?:y|ies)\b|\bretry amplification\b|\b(?:no |unresolved )?missing evidence\b|\bunresolved limitations\b|\bshared (?:slowdown|degradation|dependency)\b|\bno error(?:s| rates)?\b|\b(?:NO_GO|NO-GO|HOLD|GO)\b)/gi);
 el.replaceChildren();parts.forEach((part,i)=>el.append(i%2?evidenceNode('mark','evidence-keyword',part):document.createTextNode(part)));return el;
}
function evidenceList(parent,title,items){parent.append(evidenceNode('h4','',title));const list=evidenceNode('ul','evidence-list');for(const item of items)list.append(evidenceNode('li','',item));parent.append(list);}
function renderEvidence(e){
 const v=e.evidence,expired=!!e.decision&&Date.parse(e.decision.expiresAt)<=Date.now(),current=evidenceCurrent(e);
 const retained=retainedNoGo(e);
 const key=JSON.stringify([e,expired,current,retained]);if(key===evidenceRenderKey)return;evidenceRenderKey=key;
 const host=$('#evidence-content');host.replaceChildren();
 const num=n=>Number.isFinite(Number(n))?Number(n).toFixed(2):'Unavailable';
 const pct=n=>Number.isFinite(Number(n))?(Number(n)*100).toFixed(2)+'%':'Unavailable';
 const stable=v.stable||{},canary=v.canary||{},traces=v.traces||[],missing=v.missing||[];
 const snapshot=evidenceNode('section','evidence-snapshot');snapshot.append(evidenceNode('span','eyebrow','FROZEN INVESTIGATION EVIDENCE'),evidenceNode('h3','',`Candidate ${v.revision} · rollout step ${v.rolloutStep}`));
 snapshot.append(evidenceNode('p','',`Captured ${new Date(v.capturedAt).toLocaleString()} · ${v.source}. These observations do not update as traffic recovers. ${retained?'Recorded rejection of this rollout; request a fresh release to investigate another run.':'Run another investigation to capture a new window.'}`));
 snapshot.append(evidenceNode('p','mono',`Investigation ${e.id} · stable identity ${v.stableRevision}`));
 snapshot.append(evidenceNode('p',expired||!current?'hold':'good',retained?'Recorded NO-GO for this aborted rollout. '+(expired?'Evidence expired; the rejection remains part of the rollout history.':'The rejection remains part of the rollout history.'):expired?'Decision expired — retained for inspection; fresh evidence is required for a new decision.':!current?'Historical investigation — this snapshot does not authorize the current rollout stage.':e.decision?`Decision freshness deadline: ${new Date(e.decision.expiresAt).toLocaleTimeString()} (120 seconds from capture).`:'Investigation in progress — findings appear as the agents finish.'));
 host.append(snapshot);
 const decision=evidenceNode('section','evidence-decision');decision.append(evidenceNode('h3','','Recommendation → Decision Policy'));
 const verdictLine=evidenceNode('p','decision-verdict-line');
 const verdictNode=value=>evidenceNode('strong','evidence-verdict '+(value==='NO_GO'?'bad':value==='GO'?'good':'hold'),(value||'Pending').replaceAll('_','-'));
 verdictLine.append(document.createTextNode('Agent recommendation: '),verdictNode(e.recommendation?.verdict),document.createTextNode(' → effective decision: '),verdictNode(e.decision?.effective));decision.append(verdictLine);
 if(expired)decision.append(evidenceNode('p','muted','Evidence expired · recorded decision retained for inspection. This does not authorize a new rollout action.'));
 decision.append(highlightFinding(evidenceNode('p'),e.recommendation?.summary||'The decision agent has not returned a recommendation yet.'));
 decision.append(evidenceNode('p','',`Policy reason: ${e.decision?.reason||'Awaiting Decision Policy validation'}`));
 decision.append(evidenceNode('p','muted',`Policy: ${e.decision?.policy||e.profile||'Unknown'} · human approval ${e.decision?.approvalRequired?'required':e.decision?'not required by this decision':'not assessed'}. Saved policy output is authoritative; the comparisons below explain the evidence.`));
 if(e.recommendation)evidenceList(decision,'Recommendation limitations',e.recommendation.limitations?.length?e.recommendation.limitations:['No limitations reported by the decision agent; this is not a guarantee that evidence is complete.']);
 host.append(decision);
 const comparison=evidenceNode('section','evidence-comparison');comparison.append(evidenceNode('h3','','Stable and canary · same frozen window'));
 const scroll=evidenceNode('div','evidence-table-wrap'),table=evidenceNode('table','evidence-table');const caption=evidenceNode('caption','','Compare request health and dependency work before assigning the cause to the release.');table.append(caption);
 const head=evidenceNode('thead'),hr=evidenceNode('tr');for(const title of ['Signal','Stable','Canary','Interpretation'])hr.append(evidenceNode('th','',title));head.append(hr);table.append(head);
 const body=evidenceNode('tbody');
 const delta=Number(canary.p95Ms)-Number(stable.p95Ms);
 const rows=[['Requests',stable.requests??'Unavailable',canary.requests??'Unavailable',stable.requests>=30&&canary.requests>=30?'Both cohorts meet the 30-request minimum.':'Insufficient observations: both cohorts need at least 30 requests.'],['p95 latency',num(stable.p95Ms)+' ms',num(canary.p95Ms)+' ms',`${delta>=0?'+':''}${num(delta)} ms canary minus stable. Canary GO safety limit: 500 ms.`],['Error rate',pct(stable.errorRate),pct(canary.errorRate),'Canary GO safety limit: 1%; the hard error cap is 10%.'],['Inventory attempts / checkout',num(stable.attemptsPerCheckout)+'×',num(canary.attemptsPerCheckout)+'×','Extra attempts reveal dependency work even when HTTP requests succeed.']];
 for(const row of rows){const tr=evidenceNode('tr');row.forEach((value,i)=>tr.append(evidenceNode(i===0?'th':'td','',value)));body.append(tr);}table.append(body);scroll.append(table);comparison.append(scroll);
 const context=[];
 if(stable.requests>=30&&canary.requests>=30){
 if(stable.p95Ms>500&&canary.p95Ms>500)context.push('Both cohorts exceed 500 ms. This is consistent with shared degradation; it does not by itself prove that the release caused the slowdown. Inspect dependency findings and limitations.');
 else if(canary.p95Ms>500)context.push('Canary exceeds 500 ms while stable does not. Investigate a candidate-specific regression before promotion.');
 else context.push('Both p95 values are below 500 ms. A relative latency increase alone is not an absolute safety-limit breach; inspect errors, retries, and limitations as well.');
 if(canary.attemptsPerCheckout>stable.attemptsPerCheckout)context.push('Canary uses more inventory attempts per checkout than stable. Successful responses can conceal retry amplification.');
 }
 context.push(missing.length?'Evidence gaps are recorded below; missing evidence withholds GO.':'No collection gaps were recorded. Agent-reported limitations are separate and can still withhold GO.');
 evidenceList(comparison,'Snapshot context · calculated from captured signals',context);host.append(comparison);
 const roles={metrics:['Compare aggregate request counts, latency, errors, and dependency attempts.',['metrics-tool']],dependency:['Inspect the sampled requests and logs for shared dependency behavior and retry amplification.',['trace-tool','log-tool']],release:['Check release metadata, runbook prerequisites, and evidence completeness.',['release-tool','runbook-tool']]};
 for(const name of ['metrics','dependency','release']){
 const f=e.findings?.[name],section=evidenceNode('section','finding evidence-specialist');section.dataset.agent=name;
 section.append(evidenceNode('span','eyebrow','SPECIALIST FINDING'),evidenceNode('h3','',names[name]),evidenceNode('p','muted',roles[name][0]),highlightFinding(evidenceNode('p','agent-summary'),f?.summary||'This specialist has not returned a finding yet.'));
 evidenceList(section,'Recorded tool reads',roles[name][1].map(tool=>`${tool}: ${(e.toolReads||[]).includes(tool)?'read this frozen investigation':'no read recorded'}`));
 evidenceList(section,'Evidence references',f?.evidenceIds?.length?f.evidenceIds.map(id=>`${id}${id===v.id?' · matches this snapshot':' · does not match this snapshot'}`):['No evidence references returned yet.']);
 evidenceList(section,'Limitations',f?.limitations?.length?f.limitations:[f?'No limitations reported by this specialist.':'Not assessed yet.']);
 if(name==='dependency')section.append(evidenceNode('p','muted',`${traces.length} sampled request observations and ${(v.logs||[]).length} trace-linked log entries. Recent samples are a subset of the rolling window; their latency can differ from the aggregate p95. These are checkout request observations, not a complete distributed trace graph.`));
 if(name==='release')section.append(evidenceNode('p','',`Release notes: ${v.releaseNotes||'Not available'}. Collection gaps: ${missing.length?missing.join('; '):'none recorded'}.`));
 host.append(section);
 }
 const raw=evidenceNode('section','evidence-raw');raw.append(evidenceNode('h3','','Inspect the underlying record'));
 for(const [title,value]of [['Observation window · signals, sampled requests and logs',v],['Agent recommendation',e.recommendation||{status:e.status}],['Decision Policy',e.decision||{status:'Pending'}]]){const details=evidenceNode('details','evidence-block');details.append(evidenceNode('summary','',title),evidenceNode('pre','',JSON.stringify(value,null,2)));raw.append(details);}host.append(raw);
}
async function poll(){
 const generation=stateGeneration;
 try{
 const next=await api('/state');if(generation!==stateGeneration)return;state=next;if(!progressView)pollProgress();
 if(state.clusterEnabled&&Date.now()-lastCluster>4000){lastCluster=Date.now();const c=await api('/cluster');if(generation!==stateGeneration)return;clusterView=c;
 if(!c.connected){text($('#connection'),'Cluster unavailable');rolloutPhase('Unavailable');notice(c.message);}else{const r=c.rollout?.status||{};rolloutPhase(r.phase||'Reconciling');const weight=r.canary?.weights?.canary?.weight;$('#traffic-weight').style.width=(weight??0)+'%';text($('#traffic-caption'),weight===undefined?'Traffic managed by Argo Rollouts':`${weight}% canary / ${100-weight}% stable`);}}
 render();
 }catch(e){if(state.clusterEnabled){clusterView={connected:false};rolloutPhase('Unavailable');render();}notice(e.message);}
}
async function action(fn){busy=true;$('#evaluate').disabled=true;try{await fn();await poll();}catch(e){notice(e.message);}finally{busy=false;render();}}
function selectScene(value){scene=value;selectionInitialized=true;sessionStorage.setItem('canary-scene',scene);document.querySelectorAll('[data-scene]').forEach(b=>b.classList.toggle('selected',b.dataset.scene===scene));text($('#scene-question'),stories[scene][0]);text($('#scene-description'),stories[scene][1]);$('#revision').value=scene==='retry'?'v3':'v2';}
document.querySelectorAll('[data-tab]').forEach(button=>button.addEventListener('click',()=>{tab=button.dataset.tab;sessionStorage.setItem('canary-tab',tab);document.querySelectorAll('.tab').forEach(p=>p.classList.toggle('active',p.id===tab));document.querySelectorAll('.nav').forEach(b=>b.classList.toggle('active',b===button));if(tab==='logs')logs();if(tab==='architecture'){if(!window.canaryDiagramsLoaded){window.canaryDiagramsLoaded=true;const script=document.createElement('script');script.src='/diagrams.js'+new URL(import.meta.url).search;document.body.append(script);}else document.dispatchEvent(new Event('canary-diagrams-visible'));}}));
document.querySelectorAll('[data-scene]').forEach(b=>b.addEventListener('click',()=>selectScene(b.dataset.scene)));
function investigate(){if(!canInvestigate)return;return action(()=>api(`/evaluate?scene=${scene}&profile=${$('#profile').value}&revision=${$('#revision').value}`,'POST'));}
$('#evaluate').addEventListener('click',investigate);$('#profile').addEventListener('change',render);
$('#revision').addEventListener('change',render);
$('#release').addEventListener('click',async()=>{
 if(releasing)return;
 releasing=true;render();$('#release-result').hidden=false;text($('#release-result'),'Committing release to Git…');
 try{const result=await api('/release/'+$('#revision').value,'POST');const link=document.createElement('a');link.href=result.url;link.target='_blank';link.rel='noopener noreferrer';link.textContent=result.sha.slice(0,8);$('#release-result').replaceChildren(document.createTextNode(result.version+' committed: '),link,document.createTextNode(result.refreshRequested==='true'?'. Immediate Argo CD refresh requested. Waiting for the new rollout; investigate after the canary is sampled.':result.refreshRequested==='false'?'. Git commit succeeded, but immediate Argo CD refresh was unavailable. Automatic reconciliation may take a few minutes; do not release again just to refresh.':'. Waiting for Argo CD. Run an investigation after the canary is sampled.'));stateGeneration++;state.latest={};state.releaseIntent={version:result.version,releasedAt:result.releasedAt};progressReceivedAt=0;lastCluster=0;render();await poll();}
 catch(e){text($('#release-result'),e.message);}finally{releasing=false;render();}
});
$('#approve').addEventListener('click',()=>action(()=>api('/evaluations/'+state.latest.id+'/approve','POST')));
let faultUntil=Number(sessionStorage.getItem('canary-fault-until')||0),faultMessage='',faultError=false,faultBusy=false;
function renderFault(){
 const remaining=Math.max(0,Math.ceil((faultUntil-Date.now())/1000));
 const host=$('#fault-result');host.hidden=!faultUntil&&!faultMessage;
 host.dataset.state=faultError?'error':remaining?'active':'ended';
 text(host,remaining?`Shared slowdown active · about ${remaining}s remaining. Inventory adds 650 ms per call for both stable and canary. Allow affected requests to appear, then investigate at the ready gate.`:faultMessage||'Slowdown expired. Affected requests can remain in the 60-second observation window; investigate now to capture them, or wait for fresh healthy evidence to show recovery.');
 $('#inject').classList.toggle('fault-active',remaining>0);
 text($('#inject'),faultBusy?'Updating dependency fault…':remaining?'Restart 25s shared slowdown':'Inject 25s shared slowdown');
 $('#inject').disabled=faultBusy;$('#clear').disabled=faultBusy;
}
async function setFault(seconds){
 if(faultBusy)return;faultBusy=true;renderFault();
 try{await api('/fault/'+seconds,'POST');faultUntil=seconds?Date.now()+seconds*1000:0;sessionStorage.setItem('canary-fault-until',String(faultUntil));faultError=false;faultMessage=seconds?'':'Dependency fault cleared. Allow affected requests to drain from the 60-second window before investigating recovery.';}
 catch(e){faultError=true;faultMessage='Could not update the dependency fault: '+e.message;faultUntil=0;sessionStorage.removeItem('canary-fault-until');}
 finally{faultBusy=false;renderFault();}
}
$('#inject').addEventListener('click',()=>setFault(25));$('#clear').addEventListener('click',()=>setFault(0));
renderFault();setInterval(renderFault,1000);
$('#connect').addEventListener('click',()=>$('#access-dialog').showModal());$('#save-token').addEventListener('click',()=>{const input=$('#token');if(!input.value)return;token=input.value;sessionStorage.setItem('canary-token',token);setTimeout(poll,0);});
$('#demo-mode').addEventListener('change',()=>{sessionStorage.setItem('canary-demo-mode',$('#demo-mode').value);const extended=$('#demo-mode').value==='extended';document.querySelectorAll('.extended').forEach(el=>el.hidden=!extended);if(!extended&&scene==='missing')selectScene('healthy');});
$('#reset').addEventListener('click',()=>action(async()=>{
 const result=await api('/reset','POST');faultUntil=0;faultMessage='';sessionStorage.removeItem('canary-fault-until');renderFault();stateGeneration++;state.latest={};clusterView=null;progressView=null;progressReceivedAt=0;lastCluster=0;
 selectScene('healthy');$('#revision').value='v1';$('#profile').value='development';paused=false;
 text($('#pause-logs'),'Pause updates');$('#release-result').hidden=false;
 text($('#release-result'),result.message||'Reset requested v1 through Git. Wait for canary observations, then run Development investigations at each analysis stage.');
}));
$('#download').addEventListener('click',()=>{if(!state.latest?.id){notice('Run an investigation first.');return;}const blob=new Blob([JSON.stringify(state.latest,null,2)],{type:'application/json'});const a=document.createElement('a');a.href=URL.createObjectURL(blob);a.download='canary-decision-'+state.latest.id+'.json';a.click();URL.revokeObjectURL(a.href);});
$('#pause-logs').addEventListener('click',()=>{paused=!paused;text($('#pause-logs'),paused?'Resume updates':'Pause updates');});
async function logs(){if(tab!=='logs'||paused)return;const source=$('#log-source').value;if(source==='agents'){render();return;}try{text($('#console'),(await api('/logs/'+source)).join('\n'));}catch(e){notice(e.message);}}
$('#log-source').addEventListener('change',logs);
window.addEventListener('hashchange',()=>{const value=consumeAccess();if(!value)return;token=value;sessionStorage.setItem('canary-token',token);if($('#access-dialog').open)$('#access-dialog').close();poll();});
if(sessionStorage.getItem('canary-demo-mode')==='extended'){$('#demo-mode').value='extended';$('#demo-mode').dispatchEvent(new Event('change'));}
const savedScene=sessionStorage.getItem('canary-scene');if(Object.hasOwn(stories,savedScene)&&!(savedScene==='missing'&&$('#demo-mode').value!=='extended'))selectScene(savedScene);
const savedTab=sessionStorage.getItem('canary-tab');const savedButton=[...document.querySelectorAll('[data-tab]')].find(button=>button.dataset.tab===savedTab);if(savedButton)savedButton.click();
agentCards();poll();setInterval(poll,2000);setInterval(logs,4000);setInterval(pollProgress,4000);
