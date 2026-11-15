(function(){
  if(window.__nuvioShield)return;
  window.__nuvioShield=true;
  try{
    if(window.__NUVIO_BLOCK_NOTIFICATIONS&&window.Notification&&Notification.requestPermission){
      var origPermission=Notification.requestPermission.bind(Notification);
      Notification.requestPermission=function(){
        try{
          if(window.__NUVIO_BLOCK_NOTIFICATIONS===false)return origPermission();
          if(!(navigator.userActivation&&navigator.userActivation.isActive))return Promise.resolve('denied');
        }catch(e){}
        return origPermission();
      };
    }
  }catch(e){}
})();
