const company_id = "raon";
const product_id = "touchenex";
const appid = "kr.co." + company_id + "." + product_id;

let port = null;
let errormsg = "";
let managed_tabs = new Map();

chrome.runtime.onInstalled.addListener((details) => {
    chrome.tabs.query({ url: ["*://*/*"] }, (tabs) => {
        for (let tab of tabs) {
            try {
                chrome.scripting.executeScript({
                    target: {
                        tabId: tab.id,
                        allFrames: true
                    },
                    files: ["inject.js"]
                }, () => {});

                if(details.reason == "install"){
                    chrome.scripting.executeScript({
						target: {
							tabId: tab.id,
							allFrames: true,
						},
						func: () => {
							var event = new Event("__touchenex_extension_installed__");
							window.dispatchEvent(event);
						},
                    }, () => {});
                }
            } catch (e) {
                console.log(e);
            }
        }
    });
});

if (chrome.runtime.onSuspend) {
    chrome.runtime.onSuspend.addListener(() => {
        if (port != null) {
            try {
                port.disconnect();
                port = null;
            } catch (e) {}
        }
    });
}

chrome.runtime.onConnect.addListener((port) => {
    port.onDisconnect.addListener(() => {});
});

chrome.runtime.onUpdateAvailable.addListener((new_version) => {
    chrome.runtime.reload();
});

chrome.runtime.onMessage.addListener((request, sender, sendResponse) => {
    if( checkRequest(request) == false) {
        console.log(product_id + " Parameter invalid Error");
        sendResponse(null);
        return;
    }
    if (request.cmd == "native" || request.cmd == "setcallback" || request.cmd == "init") {
        if (port == null) {
            port = chrome.runtime.connectNative(appid);

            port.onMessage.addListener((response) => {
                if (response.get_versions) {
                    chrome.tabs.sendMessage(
                        parseInt(response.tabid),
                        {
                            response: {
                                id: response.id,
                                tabid: response.tabid,
                                status: "TRUE",
                                reply: response,
                                callback: response.callback,
                            },
                        }, (res) => {}
                    );

                    if (response.reload == true) {
                        port.disconnect();
                        port = null;
                    }

                    // register tab info
                    managed_tabs.set(response.tabid, response.tabid);
                } else {
                    tabid = response.response.tabid;
                    if (response.response.id && response.response.id == product_id) {
                        return;
                    }
                    if(tabid != "") {
                        chrome.tabs.sendMessage(parseInt(tabid), response, (res) => {});
                    } else {
                        console.log(product_id + " Tabid is null");
                        return;
                    }
                }
            });
        }

        let json = null;

        if (request.exfunc == "get_extension_version") {
            sendResponse(chrome.runtime.getManifest().version);
            if (chrome.runtime.requestUpdateCheck) {
                chrome.runtime.requestUpdateCheck((status, details) => {
                    if (status == "update_available") {
                        chrome.runtime.reload();
                    }
                });
            }
            return;
        } else if (request.exfunc == "get_version" || request.exfunc == "get_versions") {
            json = { init: request.exfunc };
            if (request.m) json.m = request.m;
            if (request.lic) json.lic = request.lic;
            if (request.origin) json.origin = request.origin;
            if (request.id) json.id = request.id;
            if (request.pv) json.pv = request.pv;
        } else {
            if(sender.tab.id.toString() != managed_tabs.get(sender.tab.id.toString())){
                sendResponse(null);
                return;
            } else {
                json = request;
            }
        }

        json.tabid = sender.tab.id.toString();

        try {
            if (port != null) {
                port.postMessage(json);
            }
            sendResponse(null);
        } catch (e) {
            const errormsg = e.message;
            console.log("Port Error: " + errormsg);
            var reply = errormsg;
            sendResponse({
                id: json.id,
                tabid: json.tabid,
                status: "INTERNAL_ERROR",
                reply: reply,
                callback: json.callback,
            });
            port = null;
        }
    } else {
        sendResponse(null);
    }
});

const updateTab = (tabid, changeInfo, tab) => {
    if (changeInfo.status == "complete") {
        const _id = managed_tabs.get(tabid.toString());
        if (_id) {
            navigatePage(tabid, "update", tab);
        }
    }
}

const closeTab = (tabid, removeInfo) => {
    const _id = managed_tabs.get(tabid.toString());
    if (_id) {
        managed_tabs.delete(tabid.toString());
        navigatePage(tabid, "close", null);
    }

    if (managed_tabs.size == 0) {
        if (port != null) {
            try {
                port.disconnect();
                port = null;
            } catch (e) {}
        }
    }
}

const ReplacedTab = (addedTabId, removedTabId) => {
    const _id = managed_tabs.get(removedTabId.toString());
    if (_id) {
        managed_tabs.delete(removedTabId.toString());
        navigatePage(_id, "close", null);
    }
}

const navigatePage = (tabid, type, tab) => {
    let request = {};
    request.id = product_id;
    request.module = "_all_";
    request.cmd = "native";
    request.origin = "";
    request.exfunc = {};
    request.callback = "";
    request.tabid = tabid.toString();

    request.exfunc.fname = "__tab_status__";
    request.exfunc.args = [];

    if (type == "update") {
        request.exfunc.args = ["move", tab.url];
    } else {
        // tab is null
        request.exfunc.args = ["close"];
    }
    try {
        if (port) port.postMessage(request);
    } catch (e) {
        port = null;
    }
}

const checkRequest = (request) => {
    if (typeof request == "object" && typeof request.cmd != 'undefined' && request.cmd != "" 
        && typeof request.exfunc != 'undefined' && request.exfunc != "") {
        if (request.cmd == "init" && (request.exfunc == "get_version" || request.exfunc == "get_versions")) {
            if(typeof request.m != "undefined" && request.m != "" 
                && typeof request.origin != "undefined" && request.origin != "" 
                && typeof request.pv != "undefined" && request.pv != "" 
                && typeof request.lic != "undefined" && request.lic != ""){
                return true;
            }
        } else if( request.cmd == "native" || request.cmd =="setcallback") {
            if(typeof request.exfunc.fname != "undefined" && request.exfunc.fname != "" 
                && typeof request.id != "undefined" && request.id != "" 
                && typeof request.module != "undefined" && request.module != "" 
                && typeof request.origin != "undefined" && request.origin != "") {
                if(request.cmd == "native" && typeof request.callback == "string") {
                    request.callback = request.callback.replaceAll("<", "");
                    request.callback = request.callback.replaceAll(">", "");
                    request.callback = request.callback.replaceAll("/", "");
                    request.callback = request.callback.replaceAll("(", "");
                    request.callback = request.callback.replaceAll(")", "");
                    request.callback = request.callback.replaceAll("#", "");
                    request.callback = request.callback.replaceAll("&", "");
                    request.callback = request.callback.replaceAll(":", "");
                    request.callback = request.callback.replaceAll("javascript", "");
                    request.callback = request.callback.replaceAll("document", "");
                    request.callback = request.callback.replaceAll("onclick", "");
                    request.callback = request.callback.replaceAll("onerror", "");
                    return true;
                } else if(request.cmd == "setcallback" && typeof request.exfunc.args[0].callback == "string" ) {
                    request.exfunc.args[0].callback = request.exfunc.args[0].callback.replaceAll("<", "");
                    request.exfunc.args[0].callback = request.exfunc.args[0].callback.replaceAll(">", "");
                    request.exfunc.args[0].callback = request.exfunc.args[0].callback.replaceAll("/", "");
                    request.exfunc.args[0].callback = request.exfunc.args[0].callback.replaceAll("(", "");
                    request.exfunc.args[0].callback = request.exfunc.args[0].callback.replaceAll(")", "");
                    request.exfunc.args[0].callback = request.exfunc.args[0].callback.replaceAll("#", "");
                    request.exfunc.args[0].callback = request.exfunc.args[0].callback.replaceAll("&", "");
                    request.exfunc.args[0].callback = request.exfunc.args[0].callback.replaceAll(":", "");
                    request.exfunc.args[0].callback = request.exfunc.args[0].callback.replaceAll("javascript", "");
                    request.exfunc.args[0].callback = request.exfunc.args[0].callback.replaceAll("document", "");
                    request.exfunc.args[0].callback = request.exfunc.args[0].callback.replaceAll("onclick", "");
                    request.exfunc.args[0].callback = request.exfunc.args[0].callback.replaceAll("onerror", "");
                    return true;
                } else {
                    return false;
                }
            }
        } else if(request.cmd == "init" && request.exfunc == "get_extension_version") {
            return true;
        }
        return false;
    } else {
        return false;
    }
}

chrome.tabs.onUpdated.addListener(updateTab);
chrome.tabs.onRemoved.addListener(closeTab);
chrome.tabs.onReplaced.addListener(ReplacedTab);
