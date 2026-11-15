(function(){
  if(window.__nuvioSniffer)return;
  window.__nuvioSniffer=true;
  var seen={};
  function poster(){
    try{
      var m=document.querySelector('meta[property="og:image"]')||document.querySelector('meta[name="twitter:image"]');
      return m&&m.content?m.content:'';
    }catch(e){return ''}
  }
  function report(url,kind,extra){
    if(!url||typeof url!=='string')return;
    if(url.indexOf('http')!==0)return;
    var key=kind+'|'+url;
    if(seen[key])return;
    seen[key]=true;
    var body={kind:kind,url:url,title:document.title||'',poster:poster()};
    if(extra){for(var k in extra)body[k]=extra[k]}
    try{NuvioWebBridge.sniff(JSON.stringify(body))}catch(e){}
  }
  function extract(text){
    if(!text||typeof text!=='string'||text.length>524288)return;
    try{
      var cleaned=text.replace(/\\\//g,'/');
      var re=/https?:\/\/[^\s"'<>]+?\.(?:m3u8|mpd|mp4|webm|mkv|mov|m4v)(?:\?[^\s"'<>]*)?/gi;
      var match,count=0;
      while((match=re.exec(cleaned))&&count<25){
        report(match[0],'page',{});
        count++;
      }
    }catch(e){}
  }
  function scanPage(){
    try{
      // Serializing a huge DOM is expensive and allocates several MB. The DOM/performance
      // scans below already cover those pages, so only regex the HTML on smaller documents.
      try{
        if(document.getElementsByTagName('*').length<4000){
          extract(document.documentElement?document.documentElement.innerHTML:'');
        }
      }catch(e){}
      var links=document.querySelectorAll('video source,video,a[href],link[rel="preload"]');
      for(var i=0;i<links.length&&i<120;i++){
        var el=links[i];
        var u=el.currentSrc||el.src||el.href||el.getAttribute('data-src')||'';
        if(u)report(u,'page',{});
      }
      var metas=document.querySelectorAll('meta[property="og:video"],meta[property="og:video:url"],meta[name="twitter:player:stream"]');
      for(var j=0;j<metas.length;j++){if(metas[j].content)report(metas[j].content,'page',{})}
    }catch(e){}
  }
  try{
    var origFetch=window.fetch;
    if(origFetch){
      window.fetch=function(input,init){
        var url=typeof input==='string'?input:(input&&input.url)||'';
        var headers={};
        try{
          if(init&&init.headers){
            if(init.headers.forEach)init.headers.forEach(function(v,k){headers[k]=v});
            else for(var k in init.headers)headers[k]=init.headers[k];
          }
        }catch(e){}
        report(url,'fetch',{headers:headers});
        var promise=origFetch.apply(window,arguments);
        try{
          promise.then(function(res){
            var ct=(res&&res.headers&&res.headers.get('content-type'))||'';
            report(url,'fetch',{headers:headers,contentType:ct,status:res?res.status:0});
            if(/text|json|javascript|mpegurl|dash/i.test(ct)){
              res.clone().text().then(extract).catch(function(){});
            }
          }).catch(function(){});
        }catch(e){}
        return promise;
      };
    }
  }catch(e){}
  try{
    var XHR=window.XMLHttpRequest;
    if(XHR){
      var origOpen=XHR.prototype.open;
      var origSend=XHR.prototype.send;
      var origSet=XHR.prototype.setRequestHeader;
      XHR.prototype.open=function(method,url){
        this.__nuvioUrl=url;
        this.__nuvioHeaders={};
        return origOpen.apply(this,arguments);
      };
      XHR.prototype.setRequestHeader=function(key,value){
        try{this.__nuvioHeaders=this.__nuvioHeaders||{};this.__nuvioHeaders[key]=value}catch(e){}
        return origSet.apply(this,arguments);
      };
      XHR.prototype.send=function(body){
        var xhr=this;
        try{
          report(xhr.__nuvioUrl,'xhr',{headers:xhr.__nuvioHeaders||{}});
          xhr.addEventListener('load',function(){
            try{
              var ct=xhr.getResponseHeader('content-type')||'';
              report(xhr.__nuvioUrl,'xhr',{headers:xhr.__nuvioHeaders||{},contentType:ct,status:xhr.status});
              if(/text|json|javascript|mpegurl|dash/i.test(ct)){
                var text=xhr.responseText||'';
                if(text.length<524288)extract(text);
              }
            }catch(e){}
          });
        }catch(e){}
        return origSend.apply(this,arguments);
      };
    }
  }catch(e){}
  try{
    var origCreate=URL.createObjectURL;
    URL.createObjectURL=function(object){
      var value=origCreate.apply(URL,arguments);
      try{
        if(window.MediaSource&&object instanceof MediaSource){
          window.__nuvioBlobMap=window.__nuvioBlobMap||{};
          window.__nuvioBlobMap[value]=true;
        }
      }catch(e){}
      return value;
    };
  }catch(e){}
  try{
    var lastPerformanceScan=0;
    setInterval(function(){
      var now=Date.now();
      if(now-lastPerformanceScan<3000)return;
      lastPerformanceScan=now;
      try{
        performance.getEntriesByType('resource').forEach(function(entry){
          var u=entry&&entry.name?entry.name:'';
          if(!u||u.indexOf('http')!==0)return;
          if(/\.(m3u8|mpd|mp4|webm|mkv|mov|m4v)(\?|$)/i.test(u)||/(master|manifest|playlist)/i.test(u)){
            report(u,'performance',{});
          }
        });
      }catch(e){}
    },3000);
  }catch(e){}
  try{
    document.addEventListener('DOMContentLoaded',scanPage);
    window.addEventListener('load',scanPage);
    setTimeout(scanPage,2500);
  }catch(e){}
})();
