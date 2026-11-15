(function(){
  if(window.__nuvioAdClean)return;
  window.__nuvioAdClean=true;
  var SEL=[
    '[id^="google_ads"]','.adsbygoogle','ins.adsbygoogle',
    'iframe[src*="doubleclick.net"]','iframe[src*="googlesyndication.com"]',
    'iframe[src*="googleads"]','iframe[src*="2mdn.net"]',
    'iframe[src*="popads.net"]','iframe[src*="popcash.net"]',
    'iframe[src*="propellerads.com"]','iframe[src*="adsterra.com"]',
    'iframe[src*="exoclick.com"]','iframe[src*="juicyads.com"]',
    'iframe[src*="trafficjunky.net"]','iframe[src*="adnxs.com"]',
    'div[data-ad]','[data-ad-slot]','[data-ad-client]',
    '[class*="ad-banner"]','[id*="ad-banner"]','[class*="ad-placeholder"]',
    '[class*="ad-slot"]','[id*="ad-slot"]','[class*="ads-container"]',
    '[class*="ad-container"]','[id*="ad-container"]','[class*="ad-wrapper"]'
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
    for(var i=0;i<SEL.length;i++){
      try{
        document.querySelectorAll(SEL[i]).forEach(function(el){
          if(safe(el))el.style.setProperty('display','none','important');
        });
      }catch(e){}
    }
  }
  clean();
  setInterval(clean,2500);
  new MutationObserver(clean).observe(document.documentElement||document.body,{childList:true,subtree:true});
})();
