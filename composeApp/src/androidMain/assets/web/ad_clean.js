(function(){
  if(window.__nuvioAdClean)return;
  window.__nuvioAdClean=true;
  // Cosmetic filtering is intentionally narrow. Blocking generic "ad-container" divs hid
  // real player wrappers before the player was injected, so only unmistakable ad iframes,
  // ad <ins> units and Google ad slots are hidden.
  var SEL=[
    'ins.adsbygoogle',
    '.adsbygoogle',
    '[id^="google_ads"]',
    'iframe[src*="doubleclick.net"]',
    'iframe[src*="googlesyndication.com"]',
    'iframe[src*="googleads"]',
    'iframe[src*="2mdn.net"]',
    'iframe[src*="popads.net"]',
    'iframe[src*="popcash.net"]',
    'iframe[src*="propellerads.com"]',
    'iframe[src*="adsterra.com"]',
    'iframe[src*="exoclick.com"]',
    'iframe[src*="juicyads.com"]',
    'iframe[src*="trafficjunky.net"]',
    'iframe[src*="adnxs.com"]'
  ];
  function safe(el){
    if(!el)return false;
    try{
      if(el.querySelector('video,audio,iframe,[class*="player"],[class*="menu"],[role="dialog"],[role="menu"],form,input[type="password"]'))return false;
      var style=getComputedStyle(el);
      if(style.position==='fixed'&&el.querySelector('video,iframe'))return false;
      return true;
    }catch(e){return false}
  }
  function clean(){
    if(document.hidden)return;
    for(var i=0;i<SEL.length;i++){
      try{
        document.querySelectorAll(SEL[i]).forEach(function(el){
          if(safe(el))el.style.setProperty('display','none','important');
        });
      }catch(e){}
    }
  }
  var queued=false;
  function queueClean(){
    if(queued)return;
    queued=true;
    setTimeout(function(){queued=false;clean()},600);
  }
  function start(){
    clean();
    setInterval(clean,4000);
    try{
      new MutationObserver(queueClean).observe(document.documentElement||document.body,{childList:true,subtree:true});
    }catch(e){}
  }
  try{
    if(document.readyState==='loading'){
      document.addEventListener('DOMContentLoaded',start,{once:true});
    }else{
      start();
    }
  }catch(e){}
})();
