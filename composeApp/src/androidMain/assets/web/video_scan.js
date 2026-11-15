(function(){
  var metaPoster='';
  try{
    var og=document.querySelector('meta[property="og:image"]')||document.querySelector('meta[name="twitter:image"]');
    if(og&&og.content)metaPoster=og.content;
  }catch(e){}
  function send(v){
    try{
      var src=v.currentSrc||v.src||(v.querySelector('source')&&v.querySelector('source').src)||(v.getAttribute('data-src')||'');
      if(!src||src.indexOf('blob:')===0)return;
      var r=v.getBoundingClientRect();
      var iw=window.innerWidth||1,ih=window.innerHeight||1;
      if(r.width<80||r.height<45||r.bottom<0||r.top>ih)return;
      var rect=[Math.max(0,r.left/iw),Math.max(0,r.top/ih),Math.min(1,r.right/iw),Math.min(1,r.bottom/ih)];
      var poster=v.poster||v.getAttribute('data-poster')||metaPoster||'';
      var title=v.title||v.getAttribute('aria-label')||document.title||'';
      var audioOnly=!v.videoWidth&&!v.videoHeight&&!!(v.audioTracks&&v.audioTracks.length);
      NuvioWebBridge.mediaMeta(JSON.stringify({
        url:src,
        width:v.videoWidth||0,
        height:v.videoHeight||0,
        duration:isFinite(v.duration)?v.duration:0,
        poster:poster,
        title:title,
        rect:rect,
        audioOnly:audioOnly
      }));
    }catch(e){}
  }
  try{
    document.querySelectorAll('video').forEach(send);
    document.querySelectorAll('video source').forEach(function(s){
      if(s.src&&s.src.indexOf('blob:')!==0)send(s.parentElement);
    });
  }catch(e){}
})();
