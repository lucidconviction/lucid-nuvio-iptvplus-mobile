(function(){
  if(window.__nuvioHlsAttach)return;
  window.__nuvioHlsAttach=true;
  function attach(v){
    try{
      if(v.hasAttribute('data-nuvio-browser'))return;
      v.setAttribute('playsinline','');
      v.setAttribute('webkit-playsinline','');
      if(v.__nuvioHlsAttached)return;
      var src=v.currentSrc||v.src||(v.querySelector('source')&&v.querySelector('source').src)||(v.getAttribute('data-src')||'');
      if(!src||src.indexOf('.m3u8')<0)return;
      if(src.indexOf('blob:')===0)return;
      if(v.canPlayType('application/vnd.apple.mpegurl'))return;
      if(!window.Hls||!window.Hls.isSupported())return;
      var hls=new window.Hls({enableWorker:true,lowLatencyMode:true});
      hls.loadSource(src);
      hls.attachMedia(v);
      v.__nuvioHlsAttached=hls;
    }catch(e){}
  }
  function scan(){
    try{
      var vs=document.querySelectorAll('video');
      for(var i=0;i<vs.length;i++)attach(vs[i]);
      var ss=document.querySelectorAll('video source');
      for(var i=0;i<ss.length;i++){
        var s=ss[i];
        if(s.src&&s.src.indexOf('.m3u8')>=0&&s.parentElement)attach(s.parentElement);
      }
    }catch(e){}
  }
  scan();
  setInterval(scan,2000);
  try{
    new MutationObserver(scan).observe(document.body||document.documentElement,{childList:true,subtree:true});
  }catch(e){}
})();
