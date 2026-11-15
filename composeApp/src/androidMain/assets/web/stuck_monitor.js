(function(){
  if(window.__nuvioStuckMon)return;
  window.__nuvioStuckMon=true;
  var state=new Map();
  var FIRE_AFTER=9000;
  function fire(v,src){
    if(v.__nuvioStuckFired)return;
    v.__nuvioStuckFired=true;
    try{NuvioWebBridge.stuck(src);}catch(e){}
  }
  function tick(){
    try{
      document.querySelectorAll('video').forEach(function(v){
        try{
          var src=v.currentSrc||v.src||(v.querySelector('source')&&v.querySelector('source').src)||'';
          if(!src||src.indexOf('http')!==0){state.delete(v);return;}
          var prev=v.__nuvioSrc;
          if(prev!==src){v.__nuvioSrc=src;v.__nuvioStuckFired=false;state.delete(v);}
          var stuck=(v.error!==null)||(!v.paused&&v.readyState<2&&!v.videoWidth);
          if(stuck){
            var st=state.get(v);
            if(!st){st={since:Date.now()};state.set(v,st);}
            if(Date.now()-st.since>=FIRE_AFTER)fire(v,src);
          }else{
            state.delete(v);
          }
        }catch(e){}
      });
    }catch(e){}
  }
  setInterval(tick,2000);
  tick();
})();
