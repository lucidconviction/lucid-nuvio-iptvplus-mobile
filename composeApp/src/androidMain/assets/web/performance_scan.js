(function(){
  try{
    performance.getEntriesByType('resource').forEach(function(e){
      var u=e&&e.name?e.name:'';
      if(!u||u.indexOf('http')!==0)return;
      if(/\.(m3u8|mpd|mp4|webm|mkv|mov|m4v)(\?|$)/i.test(u) ||
         /(master|manifest|playlist)/i.test(u)) {
        try{NuvioWebBridge.media(u)}catch(e){}
      }
    });
  }catch(e){}
})();
