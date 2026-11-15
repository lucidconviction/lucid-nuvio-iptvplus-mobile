(function(){
  if(window.__nuvioAdSkip)return;
  window.__nuvioAdSkip=true;
  var cfg=window.__NUVIO_AD_CONFIG||{};
  var mode=cfg.mode||'ClickAndFastForward';
  if(mode==='Off')return;
  var speed=Math.max(2,Math.min(16,Number(cfg.speed)||8));
  var state={skipped:0,fastForwarded:0,saved:0};
  var clicked=new WeakSet();
  var adStates=new WeakMap();
  var lastReport=0;
  function visible(el){
    if(!el)return false;
    try{
      var r=el.getBoundingClientRect();
      if(r.width<3||r.height<3)return false;
      var cs=getComputedStyle(el);
      return cs.display!=='none'&&cs.visibility!=='hidden'&&cs.opacity!=='0';
    }catch(e){return false}
  }
  function inPlayer(el){
    var p=el;
    for(var i=0;i<9&&p;i++,p=p.parentElement){
      var s=((p.className&&p.className.baseVal)||p.className||'')+' '+(p.id||'');
      s=String(s).toLowerCase();
      if(/player|video|jw|plyr|dplayer|shaka|html5/.test(s))return true;
    }
    return false;
  }
  function safeButton(el){
    if(!visible(el))return false;
    try{
      if(el.closest('form,input[type="password"],[class*="login"],[class*="signin"],[class*="payment"],[class*="age-gate"],[id*="age-gate"]'))return false;
    }catch(e){}
    return true;
  }
  function clickIfMatch(texts,kind){
    var nodes=document.querySelectorAll('button,a,[role="button"],div,span');
    for(var i=0;i<nodes.length;i++){
      var el=nodes[i];
      if(clicked.has(el)||!safeButton(el)||!inPlayer(el))continue;
      var text=(el.innerText||el.textContent||'').replace(/\s+/g,' ').trim().toLowerCase();
      if(!text||text.length>42)continue;
      for(var j=0;j<texts.length;j++){
        if(text===texts[j]||text.indexOf(texts[j])===0){
          try{
            el.click();
            clicked.add(el);
            state.skipped++;
            state.saved+=kind==='intro'?20:15;
            report();
            return true;
          }catch(e){}
        }
      }
    }
    return false;
  }
  var SKIP_TEXTS=['skip ad','skip ads','skip','continue'];
  var INTRO_TEXTS=['skip intro','skip opening','skip previously'];
  var RECAP_TEXTS=['skip recap'];
  var NEXT_TEXTS=['next episode','play next'];
  function adRoot(v){
    var el=v;
    for(var i=0;i<9&&el;i++,el=el.parentElement){
      var s=String(((el.className&&el.className.baseVal)||el.className||'')+' '+(el.id||'')).toLowerCase();
      if(/(^|[\s_-])(ad-showing|ad-overlay|preroll|midroll|ad-container|video-ads|ytp-ad)([\s_-]|$)/.test(s))return el;
    }
    return null;
  }
  function hasSkipNear(){
    var nodes=document.querySelectorAll('button,a,[role="button"]');
    for(var i=0;i<nodes.length;i++){
      if(!visible(nodes[i]))continue;
      var text=(nodes[i].innerText||nodes[i].textContent||'').trim().toLowerCase();
      if(text.indexOf('skip')===0||text==='continue')return true;
    }
    return false;
  }
  function isAd(v,root){
    if(root)return true;
    if(v.__nuvioAdSrc&&v.__nuvioAdSrc===v.currentSrc)return true;
    var d=v.duration;
    if(isFinite(d)&&d>0&&d<45&&hasSkipNear()){
      v.__nuvioAdSrc=v.currentSrc;
      return true;
    }
    return false;
  }
  function restore(v){
    var st=adStates.get(v);
    if(!st)return;
    try{
      v.muted=st.muted;
      v.volume=st.volume;
      v.playbackRate=st.rate;
    }catch(e){}
    adStates.delete(v);
  }
  function fastForward(v){
    var st=adStates.get(v);
    if(!st){
      st={rate:v.playbackRate,muted:v.muted,volume:v.volume,done:false};
      adStates.set(v,st);
    }
    if(st.done)return;
    st.done=true;
    try{
      if(cfg.mute!==false)v.muted=true;
      if(isFinite(v.duration)&&v.duration>0.5){
        v.currentTime=Math.max(0,v.duration-0.35);
        state.fastForwarded++;
        state.saved+=Math.max(5,Math.round(v.duration));
      }else{
        v.playbackRate=speed;
        state.fastForwarded++;
        state.saved+=10;
      }
      report();
    }catch(e){}
  }
  function report(){
    var now=Date.now();
    if(now-lastReport<800)return;
    lastReport=now;
    if(!state.skipped&&!state.fastForwarded&&!state.saved)return;
    try{
      NuvioWebBridge.adSkip(JSON.stringify({
        skipped:state.skipped,
        fastForwarded:state.fastForwarded,
        savedSeconds:state.saved
      }));
      state.skipped=0;
      state.fastForwarded=0;
      state.saved=0;
    }catch(e){}
  }
  function tick(){
    if(document.hidden)return;
    cfg=window.__NUVIO_AD_CONFIG||cfg;
    mode=cfg.mode||mode;
    if(mode==='Off')return;
    speed=Math.max(2,Math.min(16,Number(cfg.speed)||speed));
    clickIfMatch(SKIP_TEXTS,'ad');
    if(cfg.skipIntros)clickIfMatch(INTRO_TEXTS,'intro');
    if(cfg.skipRecaps)clickIfMatch(RECAP_TEXTS,'intro');
    if(cfg.autoNext&&mode==='Aggressive')clickIfMatch(NEXT_TEXTS,'next');
    if(mode==='ClickAndFastForward'||mode==='Aggressive'){
      var videos=document.querySelectorAll('video');
      for(var i=0;i<videos.length;i++){
        var v=videos[i];
        var root=adRoot(v);
        if(isAd(v,root))fastForward(v);
        else restore(v);
      }
    }
    report();
  }
  try{
    tick();
    setInterval(tick,500);
    new MutationObserver(tick).observe(document.documentElement||document.body,{childList:true,subtree:true});
  }catch(e){}
})();
