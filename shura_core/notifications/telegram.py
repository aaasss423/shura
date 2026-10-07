import json,urllib.parse,urllib.request
class TelegramNotifier:
    def __init__(self,token=None,chat_id=None,timeout=5):self.token=token;self.chat_id=chat_id;self.timeout=timeout
    @property
    def enabled(self):return bool(self.token and self.chat_id)
    def send(self,text):
        if not self.enabled:return False
        data=urllib.parse.urlencode({"chat_id":self.chat_id,"text":text}).encode()
        req=urllib.request.Request(f"https://api.telegram.org/bot{self.token}/sendMessage",data=data)
        try:
            with urllib.request.urlopen(req,timeout=self.timeout) as r:return r.status==200 and json.loads(r.read()).get("ok",False)
        except Exception:return False
