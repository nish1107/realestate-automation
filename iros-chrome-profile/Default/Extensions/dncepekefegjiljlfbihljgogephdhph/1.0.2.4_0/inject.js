const src = chrome.runtime.getURL("contentscript.js");
const injectScriptTag = document.createElement("script");
const pageurl = document.location.origin + document.location.pathname;
injectScriptTag.src = src;
injectScriptTag.onload = () => {
    injectScriptTag.remove();

    const PREFIX = "touchenex";
    const EVENT_FROM_PAGE = "__" + PREFIX + "__rw_chrome_ext_" + btoa(pageurl);
    const EVENT_REPLY = "__" + PREFIX + "__rw_chrome_ext_reply_" + btoa(pageurl);

    document.addEventListener(EVENT_FROM_PAGE, (e) => {
        if (!e) return;
        var transporter = e.target;
        if (transporter) {
            var request = JSON.parse(transporter.getAttribute("data"));
            transporter.removeAttribute("data");
            request.id = transporter.id;

            try {
                chrome.runtime.sendMessage(request, function (response) {
                    if (!response) {
                        return;
                    }
                    var event = document.createEvent("Events");
                    event.initEvent(EVENT_REPLY, false, false);
                    transporter.setAttribute("result", JSON.stringify(response));
                    transporter.dispatchEvent(event);
                });
            } catch (e) {}
        }
    });

    chrome.runtime.onMessage.addListener((request, sender, sendResponse) => {
        if (!request) return;
        if (request.error != null) {
            sendResponse(false);
        } else {
            var SEND_EVENT_REPLY = EVENT_REPLY;
            if(request.response.callback && request.response.callback.indexOf("__callback__") > 0) {
                var SEND_EVENT_REPLY = request.response.callback.split("__callback__")[0];
                request.response.callback = request.response.callback.split("__callback__")[1];
            }
            if(SEND_EVENT_REPLY == EVENT_REPLY) {
                var eventid = request.response.id;
                var transporter = document.getElementById(eventid);
                if (transporter != undefined) {
                    var event = document.createEvent("Events");
                    event.initEvent(EVENT_REPLY, false, false);
                    transporter.setAttribute("result", JSON.stringify(request.response));
                    transporter.dispatchEvent(event);
                }
                sendResponse(true);
            } else {
                sendResponse(false);
            }
        }
    });
};

const touchenexContentsNullthrows = (v) => {
      if (v == null) throw new Error("<head> or documentElement need");
      return v;
};

if (document.location.origin.indexOf("misumi-ec.com") > 0) {
    window.addEventListener('DOMContentLoaded', () => {
        if(document.head){
            touchenexContentsNullthrows(document.head).appendChild(injectScriptTag);
        } else {
            let touchenexContentloadloopCnt = 0
            let touchenexContentloadloop = setInterval(()=>{
                touchenexContentloadloopCnt++;
                if(document.head){
                    touchenexContentsNullthrows(document.head).appendChild(injectScriptTag);
                    clearInterval(touchenexContentloadloop);
                }
            }, 1);
        }
    });
} else {
    touchenexContentsNullthrows(document.head || document.documentElement).appendChild(injectScriptTag);
}