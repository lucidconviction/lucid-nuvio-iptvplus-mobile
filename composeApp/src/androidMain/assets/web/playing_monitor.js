(function(){
  if(window.__nuvioPlayingMon)return;
  window.__nuvioPlayingMon=true;
  var last=false;
  setInterval(function(){
    try{
      var p=false;
      document.querySelectorAll('video').forEach(function(v){
        if(!v.paused&&!v.ended&&v.readyState>=2)p=true;
      });
      if(p!==last){last=p;try{NuvioWebBridge.playing(p)}catch(e){}}
    }catch(e){}
  },1500);
})();
