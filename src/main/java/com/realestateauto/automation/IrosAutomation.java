/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  io.github.bonigarcia.wdm.WebDriverManager
 *  org.openqa.selenium.Alert
 *  org.openqa.selenium.By
 *  org.openqa.selenium.JavascriptExecutor
 *  org.openqa.selenium.Keys
 *  org.openqa.selenium.UnhandledAlertException
 *  org.openqa.selenium.WebDriver
 *  org.openqa.selenium.WebElement
 *  org.openqa.selenium.chrome.ChromeDriver
 *  org.openqa.selenium.chrome.ChromeOptions
 *  org.openqa.selenium.interactions.Actions
 *  org.openqa.selenium.support.ui.Select
 *  org.openqa.selenium.support.ui.WebDriverWait
 */
package com.realestateauto.automation;

import io.github.bonigarcia.wdm.WebDriverManager;
import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.openqa.selenium.Alert;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.Keys;
import org.openqa.selenium.UnhandledAlertException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.support.ui.Select;
import org.openqa.selenium.support.ui.WebDriverWait;

public class IrosAutomation {
    private static final String IROS_URL = "https://www.iros.go.kr";
    private final String savePath;
    private final String irosId;
    private final String irosPassword;
    private final String paymentAccount;
    private final String paymentPassword;
    private final Consumer<String> logger;

    public IrosAutomation(String savePath, String irosId, String irosPassword, String paymentAccount, String paymentPassword, Consumer<String> logger) {
        this.savePath = savePath;
        this.irosId = irosId == null ? "" : irosId;
        this.irosPassword = irosPassword == null ? "" : irosPassword;
        this.paymentAccount = paymentAccount == null ? "" : paymentAccount;
        this.paymentPassword = paymentPassword == null ? "" : paymentPassword;
        this.logger = logger;
    }

    private boolean waitForText(ChromeDriver driver, int maxMs, String ... keywords) throws InterruptedException {
        long end = System.currentTimeMillis() + (long)maxMs;
        while (System.currentTimeMillis() < end) {
            String txt = this.getPageText(driver);
            for (String kw : keywords) {
                if (!txt.contains(kw)) continue;
                return true;
            }
            Thread.sleep(300L);
        }
        return false;
    }

    private void waitForTextGone(ChromeDriver driver, int maxMs, String text) throws InterruptedException {
        long end = System.currentTimeMillis() + (long)maxMs;
        while (System.currentTimeMillis() < end) {
            if (!this.getPageText(driver).contains(text)) {
                return;
            }
            Thread.sleep(300L);
        }
    }

    private void waitForPageChange(ChromeDriver driver, String prevText, int maxMs) throws InterruptedException {
        long end = System.currentTimeMillis() + (long)maxMs;
        while (System.currentTimeMillis() < end) {
            if (!this.getPageText(driver).equals(prevText)) {
                return;
            }
            Thread.sleep(300L);
        }
    }

    private long mark() {
        return System.currentTimeMillis();
    }

    private void elapsed(long start, String label) {
        this.logger.accept(String.format("[\ud0c0\uc774\ubc0d] %s: %.1f\ucd08", label, (double)(System.currentTimeMillis() - start) / 1000.0));
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    public void download(String address) throws Exception {
        WebDriverManager.chromedriver().setup();
        ChromeOptions options = new ChromeOptions();
        options.addArguments(new String[]{"--ignore-certificate-errors"});
        options.addArguments(new String[]{"--allow-insecure-localhost"});
        options.addArguments(new String[]{"--no-sandbox"});
        options.addArguments(new String[]{"--disable-dev-shm-usage"});
        options.addArguments(new String[]{"--window-size=1280,900"});
        options.addArguments(new String[]{"--disable-popup-blocking"});
        options.addArguments(new String[]{"--disk-cache-size=0"});
        options.addArguments(new String[]{"--disable-blink-features=AutomationControlled"});
        options.setExperimentalOption("excludeSwitches", Arrays.asList("enable-automation"));
        options.setExperimentalOption("useAutomationExtension", (Object)false);
        HashMap<String, Object> prefs = new HashMap<String, Object>();
        prefs.put("download.default_directory", new File(this.savePath).getAbsolutePath());
        prefs.put("download.prompt_for_download", false);
        prefs.put("download.directory_upgrade", true);
        prefs.put("plugins.always_open_pdf_externally", true);
        options.setExperimentalOption("prefs", prefs);
        String profileDir = System.getProperty("user.home") + "/iros-chrome-profile";
        new File(profileDir).mkdirs();
        for (String lockFile : new String[]{"SingletonLock", "SingletonSocket", "SingletonCookie"}) {
            File lf = new File(profileDir + "/" + lockFile);
            if (lf.exists()) { lf.delete(); this.logger.accept("[Chrome] 락파일 정리: " + lockFile); }
        }
        options.addArguments(new String[]{"--user-data-dir=" + profileDir});
        if (!isTouchEnInstalledInProfile(profileDir)) {
            File crx = downloadTouchEnCrx(profileDir);
            if (crx != null) {
                options.addExtensions(crx);
                this.logger.accept("[TouchEn] 확장 프로그램 설치 준비 완료");
            }
        } else {
            this.logger.accept("[TouchEn] 확장 프로그램 이미 설치됨");
        }
        ChromeDriver driver = new ChromeDriver(options);
        driver.executeScript("Object.defineProperty(navigator, 'webdriver', {get: () => undefined})", new Object[0]);
        WebDriverWait wait = new WebDriverWait((WebDriver)driver, Duration.ofSeconds(20L));
        try {
            long t0 = this.mark();
            this.logger.accept("\uc778\ud130\ub137\ub4f1\uae30\uc18c \uc811\uc18d \uc911...");
            driver.get(IROS_URL);
            this.waitForText(driver, 8000, "\ub85c\uadf8\uc544\uc6c3", "\ub85c\uadf8\uc778");
            this.elapsed(t0, "\ucd08\uae30 \ud398\uc774\uc9c0 \ub85c\ub4dc");
            String pageText0 = this.getPageText(driver);
            boolean hasLoginMenu = pageText0.contains("\ub85c\uadf8\uc778") || pageText0.contains("\ub85c\uadf8\uc544\uc6c3");
            if (!hasLoginMenu) {
                if (pageText0.contains("\uc11c\ube44\uc2a4 \uc810\uac80") || pageText0.contains("\uc2dc\uc2a4\ud15c \uc810\uac80") || pageText0.contains("\ud604\uc7ac \uc810\uac80") || pageText0.contains("\uc810\uac80 \uc911\uc785\ub2c8\ub2e4")) {
                    throw new RuntimeException("\uc778\ud130\ub137\ub4f1\uae30\uc18c \uc11c\ube44\uc2a4 \uc810\uac80 \uc911\uc785\ub2c8\ub2e4.\n\uc7a0\uc2dc \ud6c4 \ub2e4\uc2dc \uc2dc\ub3c4\ud574\uc8fc\uc138\uc694.\n(www.iros.go.kr \uc5d0\uc11c \uc810\uac80 \uc77c\uc815\uc744 \ud655\uc778\ud558\uc138\uc694)");
                }
                throw new RuntimeException("\uc778\ud130\ub137\ub4f1\uae30\uc18c \ud398\uc774\uc9c0 \ub85c\ub4dc \uc2e4\ud328\n\uc0ac\uc774\ud2b8\uac00 \uc810\uac80 \uc911\uc774\uac70\ub098 \ub124\ud2b8\uc6cc\ud06c \uc5f0\uacb0\uc744 \ud655\uc778\ud574\uc8fc\uc138\uc694.");
            }
            if (!this.isLoggedIn((WebDriver)driver)) {
                if (!this.irosId.isEmpty() && !this.irosPassword.isEmpty()) {
                    this.logger.accept("\uc790\ub3d9 \ub85c\uadf8\uc778 \uc2dc\ub3c4 \uc911...");
                    this.autoLogin(driver);
                }
                if (!this.isLoggedIn((WebDriver)driver)) {
                    this.logger.accept("\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501");
                    this.logger.accept("\uc790\ub3d9 \ub85c\uadf8\uc778 \uc2e4\ud328 - \uc218\ub3d9 \ub85c\uadf8\uc778 \ud544\uc694");
                    this.logger.accept("\uc5f4\ub9b0 \ud06c\ub86c \ucc3d\uc5d0\uc11c \uc9c1\uc811 \ub85c\uadf8\uc778\ud574\uc8fc\uc138\uc694. (\ucd5c\ub300 5\ubd84 \ub300\uae30)");
                    this.logger.accept("\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501\u2501");
                    this.waitForManualLogin(driver);
                }
                this.logger.accept("\ub85c\uadf8\uc778 \ud655\uc778\ub428.");
            } else {
                this.logger.accept("\ub85c\uadf8\uc778 \uc0c1\ud0dc \ud655\uc778\ub428.");
            }
            this.logger.accept("\uc8fc\uc18c \uac80\uc0c9 \uc911: " + address);
            this.searchAndDownload(driver, wait, address);
        }
        finally {
            try {
                driver.quit();
            }
            catch (Exception exception) {}
        }
    }

    private boolean isLoggedIn(WebDriver driver) {
        try {
            String pageText = (String)((JavascriptExecutor)driver).executeScript("return document.body.innerText || ''", new Object[0]);
            return pageText != null && pageText.contains("\ub85c\uadf8\uc544\uc6c3");
        }
        catch (Exception e) {
            return false;
        }
    }

    private void autoLogin(ChromeDriver driver) throws InterruptedException {
        long t0 = this.mark();
        String loginUrl = (String)driver.executeScript("var links = document.querySelectorAll('a');for(var i=0;i<links.length;i++){  var t=(links[i].innerText||links[i].textContent||'').trim();  var h=links[i].href||'';  if(t==='\ub85c\uadf8\uc778' && h && !h.includes('javascript') && h!==window.location.href){    return h;  }}return null;", new Object[0]);
        if (loginUrl != null && !loginUrl.isEmpty()) {
            this.logger.accept("\ub85c\uadf8\uc778 \ud398\uc774\uc9c0\ub85c \uc774\ub3d9: " + loginUrl);
            driver.get(loginUrl);
        } else {
            this.clickByTextAll(driver, "\ub85c\uadf8\uc778");
            Thread.sleep(800L);
            ArrayList<WebElement> loginLinks = new ArrayList<WebElement>();
            for (WebElement a : driver.findElements(By.tagName((String)"a"))) {
                try {
                    if (!"\ub85c\uadf8\uc778".equals(a.getText().trim()) || !a.isDisplayed()) continue;
                    loginLinks.add(a);
                }
                catch (Exception exception) {}
            }
            if (loginLinks.size() >= 2) {
                driver.executeScript("arguments[0].click();", new Object[]{loginLinks.get(1)});
            } else if (loginLinks.size() == 1) {
                driver.executeScript("arguments[0].click();", new Object[]{loginLinks.get(0)});
            }
        }
        this.waitForText(driver, 6000, "\uc544\uc774\ub514", "\ube44\ubc00\ubc88\ud638");
        Thread.sleep(500L);
        this.elapsed(t0, "\ub85c\uadf8\uc778 \ud398\uc774\uc9c0 \uc774\ub3d9");
        String setValueScript = "function setVal(el, val){  el.focus();  var setter=Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value').set;  setter.call(el,val);  el.dispatchEvent(new Event('input',{bubbles:true}));  el.dispatchEvent(new Event('change',{bubbles:true}));}var inputs=document.querySelectorAll('input[type=text],input:not([type])');for(var i=0;i<inputs.length;i++){  if(!inputs[i].offsetParent) continue;  var ph=inputs[i].placeholder||'';  var idA=(inputs[i].id||'').toLowerCase();  if(ph.includes('\uc544\uc774\ub514')||idA.includes('id')||ph.includes('ID')){    setVal(inputs[i],arguments[0]); return '\uc544\uc774\ub514:'+inputs[i].value;  }}return 'notfound';";
        String idResult = (String)driver.executeScript(setValueScript, new Object[]{this.irosId});
        this.logger.accept("\uc544\uc774\ub514 \uc785\ub825 \uacb0\uacfc: " + idResult);
        Thread.sleep(300L);
        String pwScript = "function setVal(el, val){  el.focus();  var setter=Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value').set;  setter.call(el,val);  el.dispatchEvent(new Event('input',{bubbles:true}));  el.dispatchEvent(new Event('change',{bubbles:true}));}var inputs=document.querySelectorAll('input[type=password]');for(var i=0;i<inputs.length;i++){  if(!inputs[i].offsetParent) continue;  setVal(inputs[i],arguments[0]);  return '\ube44\ubc00\ubc88\ud638:'+inputs[i].value.length+'\uc790';}return 'notfound';";
        String pwResult = (String)driver.executeScript(pwScript, new Object[]{this.irosPassword});
        this.logger.accept("\ube44\ubc00\ubc88\ud638 \uc785\ub825 \uacb0\uacfc: " + pwResult);
        Thread.sleep(300L);
        String submitResult = (String)driver.executeScript("var pws=document.querySelectorAll('input[type=password]');var pw=null;for(var k=0;k<pws.length;k++){if(pws[k].offsetParent){pw=pws[k];break;}}if(!pw)return 'no-pw';var pr=pw.getBoundingClientRect();var cx=pr.left+pr.width/2;var info=[];var skip=['\ucc3e\uae30','\uc800\uc7a5','\ud0a4\ud328\ub4dc','\uc548\ub0b4','\uc774\uc6a9','\ud68c\uc6d0','\uc778\uc99d','\uacf5\ub3d9'];for(var dy=130;dy<=350;dy+=10){  var el=document.elementFromPoint(cx,pr.bottom+dy);  if(!el)continue;  var tag=el.tagName;  var t=(el.innerText||el.textContent||el.value||'').trim().substring(0,15);  var ec=(el.className||'').toString().substring(0,30);  info.push(dy+':'+tag+':'+t+':'+ec);  if(tag==='A'||tag==='BUTTON'||tag==='INPUT'){    var skip2=false;for(var s=0;s<skip.length;s++){if(t.includes(skip[s])){skip2=true;break;}}    if(!skip2){el.click();return '\ud074\ub9ad:dy='+dy+':'+tag+':'+t;}  }}return 'scan:'+info.join('|');", new Object[0]);
        this.logger.accept("\ub85c\uadf8\uc778 \uc81c\ucd9c: " + submitResult);
        boolean loginOk = this.waitForText(driver, 10000, "\ub85c\uadf8\uc544\uc6c3");
        this.elapsed(t0, "\ub85c\uadf8\uc778 \uc804\uccb4");
        if (!loginOk) {
            this.logger.accept("\ub85c\uadf8\uc778 \uc751\ub2f5 \ub290\ub9bc - \ucd94\uac00 \ub300\uae30...");
            Thread.sleep(2000L);
        }
    }

    private void waitForManualLogin(ChromeDriver driver) throws InterruptedException {
        for (int i = 0; i < 60; ++i) {
            Thread.sleep(5000L);
            if (!this.isLoggedIn((WebDriver)driver)) continue;
            return;
        }
        throw new RuntimeException("\ub85c\uadf8\uc778 \ub300\uae30 \uc2dc\uac04 \ucd08\uacfc (5\ubd84)");
    }

    private void clearCartItems(ChromeDriver driver) throws InterruptedException {
        this.logger.accept("\uc7a5\ubc14\uad6c\ub2c8 \uae30\uc874 \ud56d\ubaa9 \uc0ad\uc81c \uc911...");
        driver.executeScript("var chks=document.querySelectorAll('input[type=checkbox]');for(var i=0;i<chks.length;i++){if(!chks[i].checked && chks[i].offsetParent) chks[i].click();}", new Object[0]);
        Thread.sleep(300L);
        Boolean deleted = (Boolean)driver.executeScript("var els=document.querySelectorAll('a,button,span,div,input');for(var i=0;i<els.length;i++){  var t=(els[i].innerText||els[i].value||'').trim();  if((t==='\uc120\ud0dd\uc0ad\uc81c'||t==='\uc804\uccb4\uc0ad\uc81c') && els[i].offsetParent){els[i].click();return true;}}return false;", new Object[0]);
        if (Boolean.TRUE.equals(deleted)) {
            this.logger.accept("\uc120\ud0dd\uc0ad\uc81c \ud074\ub9ad");
            Thread.sleep(500L);
            this.handlePopup(driver, "\ud655\uc778");
            Thread.sleep(1000L);
            this.logger.accept("\uc7a5\ubc14\uad6c\ub2c8 \uc0ad\uc81c \uc644\ub8cc");
        } else {
            this.logger.accept("\uc0ad\uc81c \ubc84\ud2bc \uc5c6\uc74c - \uac74\ub108\ub700");
        }
    }

    private boolean executeSearchAndSelect(ChromeDriver driver, String address, String aptUnit) throws InterruptedException {
        WebElement addrInput;
        String sido = this.extractSido(address);
        this.logger.accept("\uc2dc/\ub3c4 \uc120\ud0dd: " + sido);
        Select regionSelect = null;
        try {
            regionSelect = new Select(driver.findElement(By.id((String)"mf_wfm_potal_main_wfm_content_sel_smpl_admin_regn1")));
            if (sido.isEmpty()) {
                regionSelect.selectByIndex(0);
                this.logger.accept("\uc2dc/\ub3c4 \ubbf8\uc778\uc2dd - \uccab \ubc88\uc9f8 \uc635\uc158(\uc804\uccb4) \uc120\ud0dd");
            } else {
                regionSelect.selectByVisibleText(sido);
            }
        }
        catch (Exception e) {
            this.logger.accept("\uc2dc/\ub3c4 \uc120\ud0dd \uc2e4\ud328 (" + sido + "): " + e.getMessage());
            // \uac15\uc6d0\ub3c4\u2194\uac15\uc6d0\ud2b9\ubcc4\uc790\uce58\ub3c4(2023), \uc804\ub77c\ubd81\ub3c4\u2194\uc804\ubd81\ud2b9\ubcc4\uc790\uce58\ub3c4(2024) \uac1c\uba85: \ub4dc\ub86d\ub2e4\uc6b4 \ubc84\uc804\uc5d0 \ub530\ub77c \uad6c/\uc2e0\uba85\uce6d \uc911 \ud558\ub098\ub9cc \uc874\uc7ac\ud560 \uc218 \uc788\uc73c\ubbc0\ub85c \ubc18\ub300 \uba85\uce6d\uc73c\ub85c \uc7ac\uc2dc\ub3c4
            if (regionSelect != null) {
                String alias = null;
                if ("\uac15\uc6d0\ud2b9\ubcc4\uc790\uce58\ub3c4".equals(sido)) alias = "\uac15\uc6d0\ub3c4";
                else if ("\uac15\uc6d0\ub3c4".equals(sido)) alias = "\uac15\uc6d0\ud2b9\ubcc4\uc790\uce58\ub3c4";
                else if ("\uc804\ubd81\ud2b9\ubcc4\uc790\uce58\ub3c4".equals(sido)) alias = "\uc804\ub77c\ubd81\ub3c4";
                else if ("\uc804\ub77c\ubd81\ub3c4".equals(sido)) alias = "\uc804\ubd81\ud2b9\ubcc4\uc790\uce58\ub3c4";
                if (alias != null) {
                    try {
                        regionSelect.selectByVisibleText(alias);
                        this.logger.accept("\uc2dc/\ub3c4 \ubcc4\uce6d \uc120\ud0dd: " + alias);
                    } catch (Exception e2) {
                        this.logger.accept("\uc2dc/\ub3c4 \ubcc4\uce6d\ub3c4 \uc2e4\ud328: " + alias);
                    }
                }
            }
        }
        Thread.sleep(500L);
        String roadAddress = this.extractRoadAddress(address);
        this.logger.accept("\uc8fc\uc18c \uc785\ub825: " + roadAddress);
        try {
            addrInput = driver.findElement(By.id((String)"mf_wfm_potal_main_wfm_content_sbx_smpl_swrd___input"));
        }
        catch (Exception e) {
            this.logger.accept("\uc8fc\uc18c \uc785\ub825\ucc3d \uc5c6\uc74c - \uac80\uc0c9 \ud398\uc774\uc9c0\uac00 \uc544\ub2d0 \uc218 \uc788\uc74c: " + e.getMessage());
            return false;
        }
        // WebSquare \uac80\uc0c9: Actions \ud074\ub9ad \u2192 Ctrl+A \u2192 \uc785\ub825 \u2192 \uc774\ubca4\ud2b8 dispatch
        new Actions((WebDriver)driver).click(addrInput).pause(java.time.Duration.ofMillis(200))
            .keyDown(Keys.CONTROL).sendKeys("a").keyUp(Keys.CONTROL)
            .sendKeys(roadAddress).pause(java.time.Duration.ofMillis(300)).perform();
        // WebSquare \ucef4\ud3ec\ub10c\ud2b8 \uc774\ubca4\ud2b8 \uac15\uc81c dispatch
        driver.executeScript(
            "var inp=arguments[0];" +
            "['input','change','keyup','keydown'].forEach(function(e){" +
            "  inp.dispatchEvent(new Event(e,{bubbles:true,cancelable:true}));});",
            addrInput);
        Thread.sleep(500L);
        // \uc785\ub825\uac12 \ud655\uc778 (HTML DOM \ub808\ubca8)
        String inputVal = (String) driver.executeScript("return arguments[0].value||'';", addrInput);
        this.logger.accept("[\uc785\ub825\ud655\uc778] \uc8fc\uc18c\ud544\ub4dc='" + inputVal + "'");
        // \uc785\ub825\uc774 \ube44\uc5b4\uc788\uc73c\uba74 JS native setter\ub85c \uac15\uc81c \uc124\uc815
        if (inputVal == null || inputVal.trim().isEmpty()) {
            this.logger.accept("[\uc785\ub825\ubcf4\uc815] JS native setter\ub85c \uc7ac\uc785\ub825 \uc2dc\ub3c4");
            driver.executeScript(
                "var inp=arguments[0], val=arguments[1];" +
                "var setter=Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value').set;" +
                "setter.call(inp,val);" +
                "['input','change','keyup','keydown'].forEach(function(e){" +
                "  inp.dispatchEvent(new Event(e,{bubbles:true,cancelable:true}));});",
                addrInput, roadAddress);
            Thread.sleep(300L);
            inputVal = (String) driver.executeScript("return arguments[0].value||'';", addrInput);
            this.logger.accept("[\uc785\ub825\ubcf4\uc815\uacb0\uacfc] \uc8fc\uc18c\ud544\ub4dc='" + inputVal + "'");
        }
        long tSearch = this.mark();
        // \uac80\uc0c9 \uc804 \uae30\uc900\uc810 (\ubcc0\ud654 \uac10\uc9c0\uc6a9)
        String urlBefore = driver.getCurrentUrl();
        int textLenBefore = this.getPageText(driver).length();
        this.logger.accept("\uac80\uc0c9 \uc2e4\ud589 \uc911...");
        boolean searchBtnClicked = false;
        // 1\ucc28: WebSquare SearchBox\uc758 ___img \uc544\uc774\ucf58 \ubc84\ud2bc (SearchBox \ucef4\ud3ec\ub10c\ud2b8 \ud45c\uc900 \uad6c\uc870)
        String[] wqBtnIds = {
            "mf_wfm_potal_main_wfm_content_sbx_smpl_swrd___img",
            "mf_wfm_potal_main_wfm_content_btn_smpl_srch",
            "mf_wfm_potal_main_wfm_content_ibtn_smpl_srch",
            "mf_wfm_potal_main_wfm_content_btn_srch"
        };
        for (String wqId : wqBtnIds) {
            try {
                WebElement wqBtn = driver.findElement(By.id(wqId));
                driver.executeScript("arguments[0].click();", wqBtn);
                this.logger.accept("\uac80\uc0c9\ubc84\ud2bc \ud074\ub9ad: " + wqId);
                searchBtnClicked = true;
                break;
            } catch (Exception ignored) {}
        }
        if (!searchBtnClicked) {
            // 2\ucc28: \ud14d\uc2a4\ud2b8\ub85c \ubc84\ud2bc \ud0d0\uc0c9 (\uc9e7\uc740 \ud14d\uc2a4\ud2b8 \uc694\uc18c)
            Boolean res1 = (Boolean) driver.executeScript(
                "var els=document.querySelectorAll('button,input[type=submit],input[type=button],a,span,div,img');" +
                "for(var i=0;i<els.length;i++){" +
                "  var t=(els[i].textContent||els[i].value||els[i].title||els[i].alt||'').trim().replace(/\\s+/g,' ');" +
                "  var rc=els[i].getBoundingClientRect();" +
                "  if(rc.width>0&&rc.height>0&&t.length<8&&(t==='\uac80\uc0c9'||t==='\uc870\ud68c'||t==='\uac80 \uc0c9')){els[i].click();return true;}" +
                "}" +
                "return false;");
            if (Boolean.TRUE.equals(res1)) {
                this.logger.accept("\uac80\uc0c9\ubc84\ud2bc \ud074\ub9ad \uc644\ub8cc (\ud14d\uc2a4\ud2b8)");
                searchBtnClicked = true;
            }
        }
        if (!searchBtnClicked) {
            // 3\ucc28: \uc785\ub825\ud544\ub4dc \uc624\ub978\ucabd \uc778\uc811 \uc694\uc18c \ud074\ub9ad (SearchBox \uc544\uc774\ucf58 \uc704\uce58 \ucd94\uc815)
            Object res2 = driver.executeScript(
                "var inp=arguments[0], r=inp.getBoundingClientRect();" +
                "var els=document.querySelectorAll('span,div,img,a');" +
                "for(var i=0;i<els.length;i++){" +
                "  var er=els[i].getBoundingClientRect();" +
                "  if(er.width>0&&er.height>0&&Math.abs(er.top-r.top)<30&&er.left>=r.right&&er.left<r.right+80){" +
                "    els[i].click();" +
                "    return 'nearby:'+els[i].tagName+'#'+(els[i].id||'').substring(0,25);" +
                "  }" +
                "}" +
                "return false;",
                addrInput);
            if (res2 != null && !Boolean.FALSE.equals(res2)) {
                this.logger.accept("\uac80\uc0c9\ubc84\ud2bc \ud074\ub9ad \uc644\ub8cc (\uc778\uc811): " + res2);
                searchBtnClicked = true;
            }
        }
        if (!searchBtnClicked) {
            // 4\ucc28: scwin (WebSquare 2) \ub610\ub294 nexacro (WebSquare 5) \uac80\uc0c9 \ud568\uc218 \ud638\ucd9c
            String nexResult = (String) driver.executeScript(
                "try{" +
                "if(typeof scwin!=='undefined'){" +
                "  var fns2=['fn_smpl_srch','fn_search','fn_srch','fn_Search'];" +
                "  for(var j=0;j<fns2.length;j++){" +
                "    if(typeof scwin[fns2[j]]==='function'){scwin[fns2[j]]();return 'scwin:'+fns2[j];}" +
                "  }" +
                "}" +
                "if(typeof nexacro!=='undefined'){" +
                "  var app=nexacro.getApplication();" +
                "  if(app){" +
                "    var frm=app.mainframe&&app.mainframe.currentForm;" +
                "    if(frm){" +
                "      var fns=['fn_search','fn_srch','fn_Search','fn_selectSe','fn_inqire'];" +
                "      for(var i=0;i<fns.length;i++){" +
                "        if(typeof frm[fns[i]]==='function'){frm[fns[i]]();return 'nexacro:'+fns[i];}" +
                "      }" +
                "    }" +
                "  }" +
                "}" +
                "}catch(e){return 'err:'+e.message;}" +
                "return false;");
            this.logger.accept("[WQ\uac80\uc0c9\ud568\uc218] " + nexResult);
            if (nexResult != null && !Boolean.FALSE.equals(nexResult) && !nexResult.startsWith("err")) {
                searchBtnClicked = true;
            }
        }
        if (!searchBtnClicked) {
            // 5\ucc28: Actions ENTER (\ub9c8\uc9c0\ub9c9 \uc218\ub2e8)
            this.logger.accept("\uac80\uc0c9\ubc84\ud2bc \uc5c6\uc74c - Actions ENTER \uc0ac\uc6a9");
            new Actions((WebDriver)driver).click(addrInput)
                .pause(java.time.Duration.ofMillis(200))
                .sendKeys(Keys.ENTER)
                .pause(java.time.Duration.ofMillis(100)).perform();
        }
        Thread.sleep(1500L);
        this.waitForTextGone(driver, 20000, "\ucc98\ub9ac \uc911");
        // \ud398\uc774\uc9c0 \ubcc0\ud654 \uac10\uc9c0\ub85c \uac80\uc0c9 \uc2e4\ud589 \uc5ec\ubd80 \ud655\uc778 (\ucd5c\ub300 10\ucd08, \uba54\ub274 \ud0a4\uc6cc\ub4dc \ub300\uc2e0 \uc2e4\uc81c \ubcc0\ud654 \uac10\uc9c0)
        long changeWaitEnd = System.currentTimeMillis() + 10000;
        boolean pageChanged = false;
        while (System.currentTimeMillis() < changeWaitEnd) {
            String curText = this.getPageText(driver);
            int curLen = curText.length();
            if (!driver.getCurrentUrl().equals(urlBefore) ||
                Math.abs(curLen - textLenBefore) > 80 ||
                curText.contains("\uacb0\uacfc\uac00 \uc5c6") ||
                curText.contains("\uac74 \uac80\uc0c9\uacb0\uacfc") ||
                curText.contains("\ud0d1\uc2e4\ub85c")) {
                pageChanged = true;
                break;
            }
            Thread.sleep(400L);
        }
        this.elapsed(tSearch, "\uac80\uc0c9 \uc644\ub8cc");
        this.logger.accept("[\uac80\uc0c9\uc2e4\ud589\ud655\uc778] \ud398\uc774\uc9c0\ubcc0\ud654=" + pageChanged + " URL=" + driver.getCurrentUrl());
        Thread.sleep(300L);
        String afterSearchText = this.getPageText(driver);
        int textLenAfter = afterSearchText.length();
        this.logger.accept("[\uac80\uc0c9\uc9c4\ub2e8] \uc804=" + textLenBefore + " \ud6c4=" + textLenAfter + " \ucc28=" + (textLenAfter - textLenBefore));
        if (afterSearchText.contains("\ub9ce\uc2b5\ub2c8\ub2e4") || afterSearchText.contains("\ub9ce\uc544") || afterSearchText.contains("\ucd08\uacfc\ud569\ub2c8\ub2e4") || afterSearchText.contains("\ud558\uc2dc\uaca0\uc2b5\ub2c8\uae4c")) {
            this.logger.accept("\uac80\uc0c9 \ud31d\uc5c5 \uac10\uc9c0 (\uacb0\uacfc \ub9ce\uc74c) - \ud655\uc778 \ud074\ub9ad");
            this.handlePopup(driver, "\ud655\uc778");
            Thread.sleep(500L);
            this.waitForTextGone(driver, 10000, "\ucc98\ub9ac \uc911");
            Thread.sleep(500L);
            afterSearchText = this.getPageText(driver);
        }
        if (afterSearchText.contains("\uacb0\uc81c\ub300\uc0c1 \ud655\uc778")) {
            this.logger.accept("\uac80\uc0c9 \ud6c4 \uacb0\uc81c\ub300\uc0c1 \ud655\uc778 \ud398\uc774\uc9c0 - \uac80\uc0c9 \uacb0\uacfc \uc5c6\uc74c");
            return false;
        }
        if (this.dismissExistingCartPopup(driver)) {
            Thread.sleep(500L);
            this.waitForTextGone(driver, 5000, "\ucc98\ub9ac \uc911");
            Thread.sleep(300L);
        }
        // WebSquare \uadf8\ub9ac\ub4dc \ub80c\ub354\ub9c1 \ub300\uae30: JS innerText\ub85c \ud14d\uc2a4\ud2b8 \uac10\uc9c0 (\ucd5c\ub300 12\ucd08)
        long gridWaitEnd = System.currentTimeMillis() + 12000;
        while (System.currentTimeMillis() < gridWaitEnd) {
            Boolean hasContent = (Boolean) driver.executeScript(
                "var trs=document.querySelectorAll('tr');" +
                "for(var i=0;i<trs.length;i++){" +
                "  if((trs[i].innerText||trs[i].textContent||'').trim().length>5) return true;" +
                "}" +
                "return false;");
            if (Boolean.TRUE.equals(hasContent)) { this.logger.accept("[\uadf8\ub9ac\ub4dc] \uacb0\uacfc \ud14d\uc2a4\ud2b8 \uac10\uc9c0 (JS)"); break; }
            Thread.sleep(500L);
        }
        this.logger.accept("\ub3d9\ud638\uc218 \ub9e4\uce6d: " + aptUnit);
        boolean sel = this.selectResultByUnit(driver, aptUnit, true);
        Thread.sleep(500L);
        this.dismissExistingCartPopup(driver);
        return sel;
    }

    private void searchAndDownload(ChromeDriver driver, WebDriverWait wait, String address) throws Exception {
        String aptUnit;
        boolean found;
        long t0 = this.mark();
        this.logger.accept("\ubd80\ub3d9\uc0b0 \uc5f4\ub78c\u00b7\ubc1c\uae09 \uba54\ub274 \ud074\ub9ad \uc911...");
        String prevText = this.getPageText(driver);
        this.clickByTextAll(driver, "\ubd80\ub3d9\uc0b0 \uc5f4\ub78c\u00b7\ubc1c\uae09");
        this.waitForPageChange(driver, prevText, 7000);
        Thread.sleep(2500L); // WebSquare \ucef4\ud3ec\ub10c\ud2b8 \ucd08\uae30\ud654 \ub300\uae30
        this.elapsed(t0, "\uba54\ub274 \uc9c4\uc785");
        String menuLandText = this.getPageText(driver);
        if (menuLandText.contains("\uacb0\uc81c\ub300\uc0c1 \ud655\uc778")) {
            this.logger.accept("[\uc7a5\ubc14\uad6c\ub2c8] \uc774\uc804 \uacb0\uc81c \ud56d\ubaa9 \ubc1c\uacac - \uc0ad\uc81c \ud6c4 \uc7ac\uc774\ub3d9");
            this.clearCartItems(driver);
            prevText = this.getPageText(driver);
            this.clickByTextAll(driver, "\ubd80\ub3d9\uc0b0 \uc5f4\ub78c\u00b7\ubc1c\uae09");
            this.waitForPageChange(driver, prevText, 7000);
            Thread.sleep(500L);
        }
        if (!(found = this.executeSearchAndSelect(driver, address, aptUnit = this.extractAptUnit(address))) && this.getPageText(driver).contains("\uacb0\uc81c\ub300\uc0c1 \ud655\uc778")) {
            this.logger.accept("[\uc7ac\uc2dc\ub3c4] \uc7a5\ubc14\uad6c\ub2c8 \uc815\ub9ac \ud6c4 \uc7ac\uac80\uc0c9...");
            this.clearCartItems(driver);
            Thread.sleep(500L);
            prevText = this.getPageText(driver);
            this.clickByTextAll(driver, "\ubd80\ub3d9\uc0b0 \uc5f4\ub78c\u00b7\ubc1c\uae09");
            this.waitForPageChange(driver, prevText, 7000);
            Thread.sleep(500L);
            found = this.executeSearchAndSelect(driver, address, aptUnit);
        }
        if (!found) {
            this.logger.accept("\ud574\ub2f9 \ub3d9\ud638\uc218 \uacb0\uacfc\ub97c \ucc3e\uc9c0 \ubabb\ud588\uc2b5\ub2c8\ub2e4: " + aptUnit);
            this.logger.accept("\uc2a4\ud06c\ub9b0\uc0f7 \uc800\uc7a5\ub428. \ube0c\ub77c\uc6b0\uc800 \ucc3d\uc744 \ud655\uc778\ud574\uc8fc\uc138\uc694.");
            return;
        }
        this.logger.accept("\uacb0\uacfc \uc120\ud0dd \uc644\ub8cc. \ub2e4\uc74c(1) \ud074\ub9ad...");
        Thread.sleep(1000L);
        long tNext1 = this.mark();
        prevText = this.getPageText(driver);
        this.clickByTextAll(driver, "\ub2e4\uc74c");
        Thread.sleep(500L);
        this.dismissExistingCartPopup(driver);
        this.waitForPageChange(driver, prevText, 6000);
        Thread.sleep(300L);
        this.elapsed(tNext1, "\ub2e4\uc74c(1) \ud398\uc774\uc9c0 \uc804\ud658");
        this.logger.accept("\uc18c\uc7ac\uc9c0\ubc88 \uc120\ud0dd \ud654\uba74 \uc9c4\uc785. \ud56d\ubaa9 \uc120\ud0dd \uc911...");
        this.selectResultByUnit(driver, aptUnit, true);
        Thread.sleep(500L);
        this.dismissExistingCartPopup(driver);
        Thread.sleep(500L);
        long tNext2 = this.mark();
        prevText = this.getPageText(driver);
        this.logger.accept("\ub2e4\uc74c(2) \ud074\ub9ad...");
        this.clickByTextAll(driver, "\ub2e4\uc74c");
        Thread.sleep(500L);
        this.dismissExistingCartPopup(driver);
        this.waitForPageChange(driver, prevText, 8000);
        Thread.sleep(300L);
        this.elapsed(tNext2, "\ub2e4\uc74c(2) \ud398\uc774\uc9c0 \uc804\ud658");
        this.logger.accept("\ubc1c\uae09 \uc2e0\uccad \ud654\uba74 URL: " + driver.getCurrentUrl());
        this.handleIssuancePage(driver, wait, aptUnit, address);
    }

    private String parseDong(String aptUnit) {
        return aptUnit.replaceAll("\ub3d9.*", "").trim();
    }

    private String parseHo(String aptUnit) {
        return aptUnit.replaceAll(".*\ub3d9\\s*", "").replaceAll("[^0-9]", "");
    }

    private boolean selectResultByUnit(ChromeDriver driver, String aptUnit, boolean skipIfSelected) {
        if (aptUnit.isEmpty()) {
            return false;
        }
        String dong = this.parseDong(aptUnit);
        String ho = this.parseHo(aptUnit);
        try {
            List<WebElement> rows = driver.findElements(By.cssSelector((String)"tr"));
            for (WebElement row : rows) {
                Boolean checked;
                List tds;
                String text = "";
                try {
                    // WebSquare 그리드는 JS innerText로 읽어야 함 (Selenium getText()는 빈 값 반환)
                    Object jsText = driver.executeScript("return (arguments[0].innerText||arguments[0].textContent||'').trim();", row);
                    text = jsText != null ? jsText.toString() : "";
                }
                catch (Exception e) {
                    continue;
                }
                if (!text.contains(dong + "\ub3d9") || !text.contains(ho + "\ud638") || (tds = row.findElements(By.tagName((String)"td"))).isEmpty()) continue;
                if (skipIfSelected && Boolean.TRUE.equals(checked = (Boolean)driver.executeScript("var inputs = arguments[0].querySelectorAll('input');for(var i=0;i<inputs.length;i++){if(inputs[i].checked)return true;}return false;", new Object[]{row}))) {
                    this.logger.accept("\ud589 \uc774\ubbf8 \uc120\ud0dd\ub428(\uc2a4\ud0b5): " + text.substring(0, Math.min(60, text.length())));
                    return true;
                }
                List radios = row.findElements(By.cssSelector((String)"input[type='radio'], input[type='checkbox']"));
                if (!radios.isEmpty()) {
                    driver.executeScript("arguments[0].click();", new Object[]{radios.get(0)});
                    this.logger.accept("\ub77c\ub514\uc624 \ud074\ub9ad: " + text.substring(0, Math.min(60, text.length())));
                } else {
                    ((WebElement)tds.get(0)).click();
                    this.logger.accept("\ud589 \uc120\ud0dd(\uc140\ud074\ub9ad): " + text.substring(0, Math.min(60, text.length())));
                }
                Thread.sleep(500L);
                return true;
            }
        }
        catch (Exception e) {
            this.logger.accept("\ud589 \uc120\ud0dd \uc624\ub958: " + e.getMessage());
        }
        return false;
    }

    private String getPageText(ChromeDriver driver) {
        try {
            return (String)driver.executeScript("return document.body.innerText || ''", new Object[0]);
        }
        catch (Exception e) {
            return "";
        }
    }

    /*
     * Enabled aggressive block sorting
     */
    private void handleIssuancePage(ChromeDriver driver, WebDriverWait wait, String aptUnit, String address) throws InterruptedException {
        String prev;
        long t;
        this.logger.accept("=== \uc5f4\ub78c\u00b7\ubc1c\uae09 \uc2e0\uccad \ud750\ub984 \uc2dc\uc791 ===");
        Thread.sleep(800L);
        String pageText = this.getPageText(driver);
        if (pageText.contains("\uc6a9\ub3c4 \ubc0f \ucd94\uac00\uc0ac\ud56d") || pageText.contains("\ub4f1\uae30\uae30\ub85d\uc720\ud615")) {
            t = this.mark();
            this.logger.accept("[\ub2e8\uacc41] \uc6a9\ub3c4 \uc120\ud0dd \ud654\uba74 - \ub2e4\uc74c \ud074\ub9ad");
            prev = pageText;
            this.clickByTextAll(driver, "\ub2e4\uc74c");
            this.waitForPageChange(driver, prev, 5000);
            Thread.sleep(300L);
            this.elapsed(t, "\ub2e8\uacc41 \uc6a9\ub3c4\uc120\ud0dd");
            pageText = this.getPageText(driver);
        }
        if (pageText.contains("\uacf5\uac1c\uc5ec\ubd80") || pageText.contains("\ub4f1\ub85d\ubc88\ud638 \uacf5\uac1c")) {
            t = this.mark();
            this.logger.accept("[\ub2e8\uacc42] \uacf5\uac1c\uc5ec\ubd80 \ud655\uc778 \ud654\uba74 - \ub2e4\uc74c \ud074\ub9ad");
            prev = pageText;
            this.clickByTextAll(driver, "\ub2e4\uc74c");
            this.waitForPageChange(driver, prev, 5000);
            Thread.sleep(300L);
            this.elapsed(t, "\ub2e8\uacc42 \uacf5\uac1c\uc5ec\ubd80");
            pageText = this.getPageText(driver);
        }
        if (pageText.contains("\uacb0\uc81c\ub300\uc0c1 \ud655\uc778") || pageText.contains("\uc77c\uad04\uacb0\uc81c") || pageText.contains("\uc7a5\ubc14\uad6c\ub2c8\ubaa9\ub85d")) {
            t = this.mark();
            this.logger.accept("[\ub2e8\uacc43] \uacb0\uc81c\ub300\uc0c1 \ud655\uc778 \ud654\uba74 - \uacb0\uc81c \ud074\ub9ad");
            prev = pageText;
            if (!(this.clickByTextAll(driver, "\uc77c\uad04\uacb0\uc81c") || this.clickByTextAll(driver, "\uacb0\uc81c\ud558\uae30") || this.clickByTextAll(driver, "\uacb0\uc81c"))) {
                this.clickByTextAll(driver, "\ub2e4\uc74c");
                this.logger.accept("\uacb0\uc81c \ubc84\ud2bc \uc5c6\uc74c - \ub2e4\uc74c \ud074\ub9ad \uc2dc\ub3c4");
            }
            Thread.sleep(800L);
            this.handlePopup(driver, "\ud655\uc778");
            this.logger.accept("\uacb0\uc81c \ud655\uc778 \ud31d\uc5c5 \ucc98\ub9ac");
            this.waitForPageChange(driver, prev, 6000);
            Thread.sleep(300L);
            this.elapsed(t, "\ub2e8\uacc43 \uacb0\uc81c \uc9c4\uc785");
            pageText = this.getPageText(driver);
        }
        if (pageText.contains("\uc911\ubcf5\uacb0\uc81c")) {
            this.logger.accept("[\uc911\ubcf5\uacb0\uc81c] \uc774\ubbf8 \uacb0\uc81c\ub41c \ud56d\ubaa9 \uac10\uc9c0 - \uc774\ub3d9 \ubc84\ud2bc \ud074\ub9ad");
            this.clickByTextAll(driver, "\uc774\ub3d9");
            Thread.sleep(1000L);
            String afterMoveText = this.getPageText(driver);
            if (afterMoveText.contains("\uacb0\uc81c\ub300\uc0c1 \ud655\uc778") || afterMoveText.contains("\uc7a5\ubc14\uad6c\ub2c8")) {
                this.logger.accept("[\uc911\ubcf5\uacb0\uc81c] \uacb0\uc81c\ub300\uc0c1 \ud655\uc778 \ud398\uc774\uc9c0 - \uacb0\uc81c \uc2e4\ud589");
                String prevCart = afterMoveText;
                if (!(this.clickByTextAll(driver, "\uc77c\uad04\uacb0\uc81c") || this.clickByTextAll(driver, "\uacb0\uc81c\ud558\uae30") || this.clickByTextAll(driver, "\uacb0\uc81c"))) {
                    this.clickByTextAll(driver, "\ub2e4\uc74c");
                    this.logger.accept("\uacb0\uc81c \ubc84\ud2bc \uc5c6\uc74c - \ub2e4\uc74c \ud074\ub9ad \uc2dc\ub3c4");
                }
                Thread.sleep(800L);
                this.handlePopup(driver, "\ud655\uc778");
                this.waitForPageChange(driver, prevCart, 6000);
                Thread.sleep(300L);
                if (this.paymentAccount.isEmpty()) {
                    this.logger.accept("\uc120\ubd88 \uacc4\uc815\ubc88\ud638 \ubbf8\uc124\uc815. \uc218\ub3d9 \uacb0\uc81c \ud544\uc694.");
                    return;
                }
                this.handlePrepaidPayment(driver);
            } else {
                this.waitForText(driver, 6000, "\uc2e0\uccad\uacb0\uacfc", "\ubbf8\uc5f4\ub78c", "\uc7ac\uc5f4\ub78c", "\uc5f4\ub78c\uac00\ub2a5");
            }
            this.handlePostPaymentView(driver, aptUnit, address);
            return;
        }
        if (!this.paymentAccount.isEmpty()) {
            this.handlePrepaidPayment(driver);
            this.handlePostPaymentView(driver, aptUnit, address);
            return;
        }
        this.logger.accept("\uc120\ubd88 \uacc4\uc815\ubc88\ud638 \ubbf8\uc124\uc815. \uacc4\uc815 \uc124\uc815\uc5d0\uc11c \uc785\ub825 \ud6c4 \uc800\uc7a5\ud574\uc8fc\uc138\uc694.");
    }

    private void handlePrepaidPayment(ChromeDriver driver) throws InterruptedException {
        long t0 = this.mark();
        String dbgPageText = this.getPageText(driver);
        this.logger.accept("[\uacb0\uc81c\ud398\uc774\uc9c0\uc0c1\ud0dc] URL=" + driver.getCurrentUrl());
        this.logger.accept("[\uacb0\uc81c\ud398\uc774\uc9c0\ud14d\uc2a4\ud2b8] " + dbgPageText.replace("\n"," ").substring(0, Math.min(300, dbgPageText.length())));
        boolean tabClicked = this.clickByTextAll(driver, "\uc120\ubd88\uc804\uc790\uc9c0\uae09\uc218\ub2e8");
        if (!tabClicked) {
            for (WebElement el : driver.findElements(By.tagName((String)"li"))) {
                try {
                    if (!el.getText().trim().contains("\uc120\ubd88\uc804\uc790\uc9c0\uae09\uc218\ub2e8") || !el.isDisplayed()) continue;
                    driver.executeScript("arguments[0].click();", new Object[]{el});
                    tabClicked = true;
                    this.logger.accept("\uc120\ubd88\uc804\uc790\uc9c0\uae09\uc218\ub2e8 \ud0ed \ud074\ub9ad (li)");
                    break;
                }
                catch (Exception exception) {
                }
            }
        }
        if (!tabClicked) {
            this.logger.accept("\uc120\ubd88\uc804\uc790\uc9c0\uae09\uc218\ub2e8 \ud0ed\uc744 \ucc3e\uc9c0 \ubabb\ud588\uc2b5\ub2c8\ub2e4. \uc218\ub3d9\uc73c\ub85c \uc120\ud0dd\ud574\uc8fc\uc138\uc694.");
            return;
        }
        this.logger.accept("\uc120\ubd88\uc804\uc790\uc9c0\uae09\uc218\ub2e8 \ud0ed \uc120\ud0dd");
        this.waitForText(driver, 4000, "\uacc4\uc815\ubc88\ud638", "\ube44\ubc00\ubc88\ud638");
        Thread.sleep(300L);
        this.elapsed(t0, "\ud0ed \uc804\ud658");
        String[] accountParts = this.paymentAccount.split("-", 2);
        String part1 = accountParts[0];
        String part2 = accountParts.length > 1 ? accountParts[1] : "";
        List<WebElement> allInputs = driver.findElements(By.cssSelector((String)"input[type='text'], input[type='password'], input:not([type])"));
        ArrayList<WebElement> visible = new ArrayList<WebElement>();
        for (WebElement inp : allInputs) {
            try {
                if (!inp.isDisplayed() || !inp.isEnabled()) continue;
                visible.add((WebElement)inp);
            }
            catch (Exception exception) {}
        }
        this.logger.accept("\uc785\ub825 \ud544\ub4dc " + visible.size() + "\uac1c \ubc1c\uacac");
        if (visible.size() >= 3) {
            ((WebElement)visible.get(0)).clear();
            ((WebElement)visible.get(0)).sendKeys(new CharSequence[]{part1});
            Thread.sleep(150L);
            ((WebElement)visible.get(1)).clear();
            ((WebElement)visible.get(1)).sendKeys(new CharSequence[]{part2});
            Thread.sleep(150L);
            ((WebElement)visible.get(2)).clear();
            ((WebElement)visible.get(2)).sendKeys(new CharSequence[]{this.paymentPassword});
            this.logger.accept("\uacc4\uc815\ubc88\ud638(" + part1 + "-" + part2 + ")/\ube44\ubc00\ubc88\ud638 \uc785\ub825 \uc644\ub8cc");
        } else if (visible.size() == 2) {
            ((WebElement)visible.get(0)).clear();
            ((WebElement)visible.get(0)).sendKeys(new CharSequence[]{part1});
            Thread.sleep(150L);
            ((WebElement)visible.get(1)).clear();
            ((WebElement)visible.get(1)).sendKeys(new CharSequence[]{this.paymentPassword});
            this.logger.accept("\uacc4\uc815\ubc88\ud638 \uc55e\ubd80\ubd84/\ube44\ubc00\ubc88\ud638 \uc785\ub825 (\ud544\ub4dc 2\uac1c)");
        } else if (visible.size() == 1) {
            ((WebElement)visible.get(0)).clear();
            ((WebElement)visible.get(0)).sendKeys(new CharSequence[]{this.paymentAccount});
            this.logger.accept("\uacc4\uc815\ubc88\ud638 \uc785\ub825 (\ud544\ub4dc 1\uac1c)");
        } else {
            this.logger.accept("\uc785\ub825 \ud544\ub4dc\ub97c \ucc3e\uc9c0 \ubabb\ud588\uc2b5\ub2c8\ub2e4. \uc218\ub3d9\uc73c\ub85c \uc785\ub825\ud574\uc8fc\uc138\uc694.");
            return;
        }
        Thread.sleep(300L);
        Boolean agreed = (Boolean)driver.executeScript("var all = document.querySelectorAll('*');for(var i=0;i<all.length;i++){  try{    var t = all[i].childElementCount === 0 ? (all[i].innerText||'').trim() : '';    if(t === '\uc804\uccb4\ub3d9\uc758' && all[i].offsetParent !== null){      all[i].click(); return true;    }  }catch(e){}}return false;", new Object[0]);
        if (Boolean.TRUE.equals(agreed)) {
            this.logger.accept("\uc804\uccb4\ub3d9\uc758 \ud074\ub9ad (JS)");
        } else {
            try {
                for (WebElement lbl : driver.findElements(By.tagName((String)"label"))) {
                    if (!lbl.getText().contains("\uc804\uccb4\ub3d9\uc758") || !lbl.isDisplayed()) continue;
                    driver.executeScript("arguments[0].click();", new Object[]{lbl});
                    this.logger.accept("\uc804\uccb4\ub3d9\uc758 \ud074\ub9ad (label)");
                    break;
                }
            }
            catch (Exception e) {
                this.logger.accept("\uc804\uccb4\ub3d9\uc758 \uc790\ub3d9 \uccb4\ud06c \uc2e4\ud328 - \uc218\ub3d9\uc73c\ub85c \uccb4\ud06c\ud574\uc8fc\uc138\uc694.");
            }
        }
        Thread.sleep(300L);
        String prevPayText = this.getPageText(driver);
        String payClickResult = (String)driver.executeScript("function fire(node) {  ['mousedown','mouseup','click'].forEach(function(ev){    node.dispatchEvent(new MouseEvent(ev,{bubbles:true,cancelable:true,view:window}));  });}var tags=['a','button','span','div','input'];var found=null, foundTag='';for(var t=0;t<tags.length;t++){  var els=document.querySelectorAll(tags[t]);  for(var i=0;i<els.length;i++){    var el=els[i];    if(!el.offsetParent) continue;    var txt=(el.childElementCount===0?(el.innerText||el.value||'').trim():(el.innerText||'').trim());    if(txt==='\uacb0\uc81c'){found=el; foundTag=el.tagName+'.'+el.className.split(' ')[0];}  }}if(!found) return 'notfound';found.scrollIntoView({block:'center'});fire(found);var p=found.parentElement;for(var i=0;i<5;i++){if(!p)break; fire(p); p=p.parentElement;}return 'clicked:'+foundTag;", new Object[0]);
        this.logger.accept("\uacb0\uc81c JS \ud074\ub9ad: " + payClickResult);
        Thread.sleep(800L);
        if ("notfound".equals(payClickResult)) {
            this.logger.accept("\uacb0\uc81c \ubc84\ud2bc\uc744 \ucc3e\uc9c0 \ubabb\ud588\uc2b5\ub2c8\ub2e4.");
        } else {
            block32: {
                try {
                    List<WebElement> allPayEls = driver.findElements(By.cssSelector((String)"a,button,span,div,input[type=button],input[type=submit]"));
                    WebElement payBtn = null;
                    for (WebElement el : allPayEls) {
                        try {
                            String t = el.getText().trim();
                            if (!"\uacb0\uc81c".equals(t) || !el.isDisplayed()) continue;
                            payBtn = el;
                        }
                        catch (Exception exception) {}
                    }
                    if (payBtn == null) break block32;
                    try {
                        payBtn.click();
                        this.logger.accept("\uacb0\uc81c WebElement \ud074\ub9ad");
                    }
                    catch (Exception exception) {
                        // empty catch block
                    }
                    Thread.sleep(300L);
                    if (this.getPageText(driver).equals(prevPayText)) {
                        new Actions((WebDriver)driver).moveToElement(payBtn).click().perform();
                        this.logger.accept("\uacb0\uc81c Actions \ud074\ub9ad");
                    }
                }
                catch (Exception e) {
                    this.logger.accept("\uacb0\uc81c Actions \uc2e4\ud328: " + e.getMessage());
                }
            }
            this.waitForPageChange(driver, prevPayText, 12000);
            String afterPayText = this.getPageText(driver);
            if (afterPayText.contains("\uacb0\uc81c\ub97c \uc694\uccad\ud558\uc2dc\uaca0\uc2b5\ub2c8\uae4c") || afterPayText.contains("\uc218\uc218\ub8cc \uacb0\uc81c")) {
                this.logger.accept("\uacb0\uc81c \ud655\uc778 \ud31d\uc5c5 \uac10\uc9c0 - \ud655\uc778 \ud074\ub9ad");
                this.handlePopup(driver, "\ud655\uc778");
                Thread.sleep(2000L);
                this.waitForTextGone(driver, 30000, "\ucc98\ub9ac \uc911");
                Thread.sleep(500L);
                String postConfirmText = this.getPageText(driver);
                if (postConfirmText.contains("\uacb0\uc81c\uacb0\uacfc\ud655\uc778") || postConfirmText.contains("\uc2b9\uc778\uc774 \uc644\ub8cc")) {
                    Thread.sleep(800L);
                    this.logger.accept("\uacb0\uc81c\uacb0\uacfc\ud655\uc778 \ud31d\uc5c5 - \ud655\uc778 \ud074\ub9ad");
                    this.handlePopup(driver, "\ud655\uc778");
                    Thread.sleep(800L);
                }
                this.waitForText(driver, 10000, "\ubbf8\uc5f4\ub78c\u00b7\ubbf8\ubc1c\uae09", "\ucc98\ub9ac\uc644\ub8cc");
            }
            this.elapsed(t0, "\uacb0\uc81c \uc804\uccb4");
        }
    }

    private boolean waitForTableData(ChromeDriver driver, int maxMs) throws InterruptedException {
        long end = System.currentTimeMillis() + (long)maxMs;
        while (System.currentTimeMillis() < end) {
            Boolean found = (Boolean)driver.executeScript(
                "var V=['\uc5f4\ub78c','\uc5f4\ub78c\ucd9c\ub825','\ubc1c\uae09','\ucd9c\ub825','\ud655\uc778','\uc5f4\ub78c\ud558\uae30','\ubb38\uc11c\uc5f4\ub78c'];" +
                "var rows=document.querySelectorAll('tr');" +
                "for(var i=0;i<rows.length;i++){" +
                "  if(!rows[i].querySelector('td')) continue;" +
                "  var els=rows[i].querySelectorAll('a,button,span,div,input[type=button]');" +
                "  for(var j=0;j<els.length;j++){" +
                "    if(!els[j].offsetParent) continue;" +
                "    var t=els[j].tagName==='INPUT'?(els[j].value||'').trim():(els[j].innerText||'').trim();" +
                "    if(V.indexOf(t)>=0) return true;" +
                "  }" +
                "}" +
                "return false;", new Object[0]);
            if (Boolean.TRUE.equals(found)) {
                return true;
            }
            Thread.sleep(300L);
        }
        return false;
    }

    private void handlePostPaymentView(ChromeDriver driver, String aptUnit, String address) throws InterruptedException {
        Boolean hasQueryBtn;
        this.logger.accept("=== \uc5f4\ub78c \ub2e8\uacc4 \uc2dc\uc791 ===");
        Thread.sleep(1000L);
        String pageText = this.getPageText(driver);
        if (pageText.contains("\uacb0\uc81c\uacb0\uacfc\ud655\uc778") || pageText.contains("\uc2b9\uc778\uc774 \uc644\ub8cc")) {
            Thread.sleep(800L);
            this.logger.accept("\uacb0\uc81c\uacb0\uacfc\ud655\uc778 \ud31d\uc5c5 \uac10\uc9c0 - \ud655\uc778 \ud074\ub9ad");
            this.handlePopup(driver, "\ud655\uc778");
            Thread.sleep(800L);
            pageText = this.getPageText(driver);
        }
        if (pageText.contains("\ucc98\ub9ac \uc911")) {
            this.logger.accept("\ucc98\ub9ac \uc911 \ub300\uae30...");
            this.waitForTextGone(driver, 20000, "\ucc98\ub9ac \uc911");
            Thread.sleep(500L);
            pageText = this.getPageText(driver);
        }
        if (!Boolean.TRUE.equals(hasQueryBtn = (Boolean)driver.executeScript("var els=document.querySelectorAll('a,button,span,div,input');for(var i=0;i<els.length;i++){  if(!els[i].offsetParent) continue;  var t=els[i].tagName==='INPUT'?(els[i].value||'').trim():         els[i].childElementCount===0?(els[i].innerText||'').trim():'';  if(t==='\uc870\ud68c') return true;}return false;", new Object[0]))) {
            this.logger.accept("\uc2e0\uccad\uacb0\uacfc \ud398\uc774\uc9c0 \uc774\ub3d9 \uc2dc\ub3c4 (\uc870\ud68c \ubc84\ud2bc \uc5c6\uc74c)...");
            boolean navClicked = this.clickByTextAll(driver, "\uc2e0\uccad\uacb0\uacfc\ud655\uc778");
            if (!navClicked) {
                navClicked = Boolean.TRUE.equals(driver.executeScript("var links=document.querySelectorAll('a');for(var i=0;i<links.length;i++){  var t=(links[i].innerText||'').trim();  if(t.includes('\uc2e0\uccad\uacb0\uacfc') && links[i].offsetParent){links[i].click();return true;}}return false;", new Object[0]));
            }
            if (navClicked) {
                Thread.sleep(500L);
                this.waitForText(driver, 10000, "\ubbf8\uc5f4\ub78c\u00b7\ubbf8\ubc1c\uae09", "\ucc98\ub9ac\uc644\ub8cc");
                Thread.sleep(300L);
                pageText = this.getPageText(driver);
            }
        }
        this.logger.accept("\uc2e0\uccad\uacb0\uacfc \uc870\ud68c \ud074\ub9ad...");
        String queryResult = (String)driver.executeScript("function fire(el){  ['mousedown','mouseup','click'].forEach(function(ev){    el.dispatchEvent(new MouseEvent(ev,{bubbles:true,cancelable:true,view:window}));  });}var candidates=document.querySelectorAll('a,button,span,div,input');for(var i=0;i<candidates.length;i++){  var el=candidates[i];  if(!el.offsetParent) continue;  var txt;  if(el.tagName==='INPUT'){txt=(el.value||'').trim();}  else if(el.childElementCount===0){txt=(el.innerText||'').trim();}  else{txt='';}  if(txt==='\uc870\ud68c'){    el.scrollIntoView({block:'center'});    fire(el);    return '\ud074\ub9ad:'+el.tagName+'.'+el.className.split(' ')[0];  }}return 'notfound';", new Object[0]);
        this.logger.accept("\uc870\ud68c \ud074\ub9ad \uacb0\uacfc: " + queryResult);
        if ("notfound".equals(queryResult)) {
            this.logger.accept("\uc870\ud68c \ubc84\ud2bc(JS) \ubbf8\ubc1c\uacac - clickByTextAll \uc2dc\ub3c4");
            this.clickByTextAll(driver, "\uc870\ud68c");
        }
        Thread.sleep(800L);
        this.waitForTextGone(driver, 8000, "\ucc98\ub9ac \uc911");
        Thread.sleep(300L);
        boolean dataReady = this.waitForTableData(driver, 20000);
        if (!dataReady) {
            this.logger.accept("\uc2e0\uccad\uacb0\uacfc \ub370\uc774\ud130 \ub85c\ub4dc \uc2dc\uac04 \ucd08\uacfc - \uacc4\uc18d \uc9c4\ud589");
        }
        String dong = this.parseDong(aptUnit);
        String ho = this.parseHo(aptUnit);
        this.logger.accept("\uc2e0\uccad\uacb0\uacfc\uc5d0\uc11c \ub9e4\uce6d: \ub3d9=" + dong + " \ud638=" + ho);
        Object viewResult = driver.executeScript(
            "function fire(el){['mousedown','mouseup','click'].forEach(function(ev){el.dispatchEvent(new MouseEvent(ev,{bubbles:true,cancelable:true,view:window}));});}" +
            "var V=['\uc5f4\ub78c','\uc5f4\ub78c\ucd9c\ub825','\ubc1c\uae09','\ucd9c\ub825','\ud655\uc778','\uc5f4\ub78c\ud558\uae30','\ubb38\uc11c\uc5f4\ub78c'];" +
            "var dong=arguments[0], ho=arguments[1];" +
            "var rows=document.querySelectorAll('tr');" +
            "var firstBtn=null, firstRow='';" +
            "for(var i=0;i<rows.length;i++){" +
            "  if(!rows[i].querySelector('td')) continue;" +
            "  var t=rows[i].innerText||'';" +
            "  var els=rows[i].querySelectorAll('a,button,span,div,input[type=button]');" +
            "  var viewBtn=null, anyEl=null;" +
            "  for(var j=0;j<els.length;j++){" +
            "    if(!els[j].offsetParent) continue;" +
            "    var bt=els[j].tagName==='INPUT'?(els[j].value||'').trim():(els[j].innerText||'').trim();" +
            "    if(V.indexOf(bt)>=0){viewBtn=els[j];break;}" +
            "    if(anyEl===null&&(els[j].tagName==='A'||els[j].tagName==='BUTTON'||els[j].tagName==='INPUT')) anyEl=els[j];" +
            "  }" +
            "  var btn=viewBtn||anyEl;" +
            "  if(!btn) continue;" +
            "  if(firstBtn===null){firstBtn=btn; firstRow=t.substring(0,40);}" +
            "  if(dong&&ho&&t.includes(dong+'\ub3d9')&&t.includes(ho+'\ud638')){fire(btn);return '\ub9e4\uce6d:'+(viewBtn?'\uc5f4\ub78c':'\uccab\uc694\uc18c')+':'+t.substring(0,50);}" +
            "}" +
            "if(firstBtn){fire(firstBtn); return '\uccab\ud589:'+firstRow;}" +
            "return false;", new Object[]{dong, ho});
        boolean clicked = false;
        if (viewResult instanceof String) {
            this.logger.accept("\uc5f4\ub78c \ubc84\ud2bc \ud074\ub9ad: " + String.valueOf(viewResult));
            Thread.sleep(500L);
            try {
                Alert alert = driver.switchTo().alert();
                String alertText = alert.getText();
                alert.accept();
                this.logger.accept("\ud31d\uc5c5 \ucc98\ub9ac \ud6c4 \uc885\ub8cc: " + alertText.substring(0, Math.min(30, alertText.length())));
                return;
            }
            catch (Exception alert) {
                this.handlePopup(driver, "\ud655\uc778");
                clicked = true;
            }
        }
        if (!clicked) {
            this.logger.accept("\uc5f4\ub78c \ubc84\ud2bc\uc744 \ucc3e\uc9c0 \ubabb\ud588\uc2b5\ub2c8\ub2e4. \uc218\ub3d9\uc73c\ub85c \ud074\ub9ad\ud574\uc8fc\uc138\uc694.");
            return;
        }
        Thread.sleep(3000L);
        String mainHandle = driver.getWindowHandle();
        Set<String> handles = driver.getWindowHandles();
        String viewerHandle = mainHandle;
        if (handles.size() > 1) {
            for (String h : handles) {
                if (h.equals(mainHandle)) continue;
                driver.switchTo().window(h);
                viewerHandle = h;
                Thread.sleep(1000L);
                break;
            }
        }
        long tSave = this.mark();
        this.logger.accept("\ubb38\uc11c \uc800\uc7a5 \ubc84\ud2bc \ud074\ub9ad...");
        Set<String> filesBefore = this.listDownloadedFiles(this.savePath);
        boolean saveClicked = this.clickByTextAll(driver, "\uc800\uc7a5");
        if (!saveClicked) {
            saveClicked = Boolean.TRUE.equals(driver.executeScript("var els=document.querySelectorAll('button,a,input[type=button]');for(var i=0;i<els.length;i++){  var t=(els[i].innerText||els[i].value||'').trim();  if(t==='\uc800\uc7a5'&&els[i].offsetParent){els[i].click();return true;}}return false;", new Object[0]));
        }
        if (saveClicked) {
            this.logger.accept("\uc800\uc7a5 \ud074\ub9ad \uc644\ub8cc. \ub2e4\uc6b4\ub85c\ub4dc \ub300\uae30 \uc911...");
            Thread.sleep(1000L);
            Set<String> newHandles = driver.getWindowHandles();
            for (String h : newHandles) {
                if (h.equals(mainHandle) || h.equals(viewerHandle)) continue;
                driver.switchTo().window(h);
                Thread.sleep(2000L);
                driver.close();
                driver.switchTo().window(mainHandle);
                break;
            }
            String downloaded = this.waitForNewFile(this.savePath, filesBefore, 30000);
            this.elapsed(tSave, "\uc800\uc7a5 \uc644\ub8cc");
            if (downloaded != null) {
                // \ud30c\uc77c\uba85\uc744 aptUnit \uc8fc\uc18c\ub85c \ubcc0\uacbd
                String ext = downloaded.contains(".") ? downloaded.substring(downloaded.lastIndexOf('.')) : "";
                String safeName = address.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
                File src = new File(this.savePath, downloaded);
                File dst = new File(this.savePath, safeName + ext);
                if (src.renameTo(dst)) {
                    this.logger.accept("=== \ud30c\uc77c \uc800\uc7a5 \uc644\ub8cc: " + dst.getName() + " ===");
                } else {
                    this.logger.accept("=== \ud30c\uc77c \ub2e4\uc6b4\ub85c\ub4dc \uc644\ub8cc (\uc774\ub984 \ubcc0\uacbd \uc2e4\ud328): " + downloaded + " ===");
                }
            } else {
                this.logger.accept("=== \uc800\uc7a5 \uc644\ub8cc (\ud30c\uc77c \uc704\uce58 \ud655\uc778: " + this.savePath + ") ===");
            }
        } else {
            this.logger.accept("\uc800\uc7a5 \ubc84\ud2bc\uc744 \ucc3e\uc9c0 \ubabb\ud588\uc2b5\ub2c8\ub2e4. \uc5f4\ub78c \ud654\uba74\uc5d0\uc11c \uc218\ub3d9\uc73c\ub85c \uc800\uc7a5\ud574\uc8fc\uc138\uc694.");
        }
    }

    private Set<String> listDownloadedFiles(String dir) {
        HashSet<String> files = new HashSet<String>();
        String[] list = new File(dir).list();
        if (list != null) {
            for (String f : list) {
                if (f.startsWith("screenshot_")) continue;
                files.add(f);
            }
        }
        return files;
    }

    private String waitForNewFile(String dir, Set<String> filesBefore, int maxMs) throws InterruptedException {
        long end = System.currentTimeMillis() + (long)maxMs;
        while (System.currentTimeMillis() < end) {
            Thread.sleep(500L);
            for (String f : this.listDownloadedFiles(dir)) {
                if (filesBefore.contains(f) || f.endsWith(".crdownload")) continue;
                this.logger.accept("\uc0c8 \ud30c\uc77c: " + f);
                return f;
            }
        }
        return null;
    }

    private void handlePopup(ChromeDriver driver, String buttonText) {
        try {
            Alert nativeAlert = driver.switchTo().alert();
            if ("\ucde8\uc18c".equals(buttonText)) {
                nativeAlert.dismiss();
                this.logger.accept("\ube0c\ub77c\uc6b0\uc800 \uc54c\ub9bc \ucde8\uc18c \ud074\ub9ad");
            } else {
                nativeAlert.accept();
                this.logger.accept("\ube0c\ub77c\uc6b0\uc800 \uc54c\ub9bc \ud655\uc778 \ud074\ub9ad");
            }
            return;
        }
        catch (Exception nativeAlert) {
            try {
                for (String sel : new String[]{"button", "a", "span", "div"}) {
                    for (WebElement el : driver.findElements(By.tagName((String)sel))) {
                        try {
                            if (!el.getText().trim().equals(buttonText) || !el.isDisplayed()) continue;
                            try {
                                el.click();
                            } catch (Exception ex) {
                                driver.executeScript("arguments[0].click();", new Object[]{el});
                            }
                            this.logger.accept("\ud31d\uc5c5 [" + buttonText + "] \ud074\ub9ad (" + sel + ")");
                            return;
                        }
                        catch (Exception exception) {
                        }
                    }
                }
                // isDisplayed() \uac00 false \uc5ec\ub3c4 offsetParent \uae30\uc900\uc73c\ub85c \uc2dc\uac01\uc801\uc73c\ub85c \ubcf4\uc774\ub294 \uc694\uc18c \ud074\ub9ad \uc2dc\ub3c4
                String jsResult = (String) driver.executeScript(
                    "var bt=arguments[0];" +
                    "var tags=['button','a','span','div','input'];" +
                    "for(var t=0;t<tags.length;t++){" +
                    "  var els=document.querySelectorAll(tags[t]);" +
                    "  for(var i=0;i<els.length;i++){" +
                    "    if(!els[i].offsetParent) continue;" +
                    "    var tx=(els[i].tagName==='INPUT'?(els[i].value||''):(els[i].innerText||'')).trim();" +
                    "    if(tx===bt){els[i].click();return 'JS-fb:'+els[i].tagName+'.'+els[i].className;}" +
                    "  }" +
                    "}" +
                    "return null;", buttonText);
                if (jsResult != null) {
                    this.logger.accept("\ud31d\uc5c5 [" + buttonText + "] \ud074\ub9ad (" + jsResult + ")");
                }
            }
            catch (Exception e) {
                this.logger.accept("\ud31d\uc5c5 \ucc98\ub9ac \uc2e4\ud328: " + e.getMessage());
            }
            return;
        }
    }

    private boolean dismissExistingCartPopup(ChromeDriver driver) throws InterruptedException {
        try {
            String txt = this.getPageText(driver);
            if (!txt.contains("\ub4f1\uae30\uc0ac\ud56d\uc99d\uba85\uc11c\uac00 \uc874\uc7ac\ud569\ub2c8\ub2e4") && !txt.contains("\ucd94\uac00\ub97c \uc6d0\ud558\uc2dc\uba74")) {
                return false;
            }
            this.logger.accept("\uae30\uc874 \uacb0\uc81c \ud31d\uc5c5 \uac10\uc9c0 - \ud655\uc778(\uc0c8\ub85c \uacb0\uc81c) \ud074\ub9ad");
            for (String sel : new String[]{"button", "a", "span", "div"}) {
                for (WebElement el : driver.findElements(By.tagName((String)sel))) {
                    try {
                        if (!"\ud655\uc778".equals(el.getText().trim()) || !el.isDisplayed()) continue;
                        try {
                            el.click();
                        } catch (Exception ex) {
                            driver.executeScript("arguments[0].click();", new Object[]{el});
                        }
                        this.logger.accept("\ud655\uc778 \ud074\ub9ad (" + sel + ")");
                        Thread.sleep(500L);
                        return true;
                    }
                    catch (Exception exception) {
                    }
                }
            }
            driver.executeScript("var els=document.querySelectorAll('*');for(var i=0;i<els.length;i++){  var t=(els[i].innerText||'').trim();  if(t==='\ud655\uc778' && els[i].offsetParent){els[i].click();return;}}", new Object[0]);
            Thread.sleep(500L);
            return true;
        }
        catch (Exception e) {
            this.logger.accept("\uae30\uc874 \uacb0\uc81c \ud31d\uc5c5 \ucc98\ub9ac \uc2e4\ud328: " + e.getMessage());
            return false;
        }
    }

    private String extractAptUnit(String address) {
        Matcher m = Pattern.compile("(\\d+\ub3d9\\s*\\d+\ud638)").matcher(address);
        if (m.find()) {
            return m.group(1).replaceAll("\\s+", " ").trim();
        }
        m = Pattern.compile("(\\d+\ub3d9)").matcher(address);
        if (m.find()) {
            return m.group(1);
        }
        return "";
    }

    private String extractSido(String address) {
        // {\uc8fc\uc18c \ud0a4\uc6cc\ub4dc, \uc8fc\uc18c \uc811\ub450\uc0ac, IROS \ub4dc\ub86d\ub2e4\uc6b4 \ud14d\uc2a4\ud2b8}
        // \uac15\uc6d0\ub3c4\u2192\uac15\uc6d0\ud2b9\ubcc4\uc790\uce58\ub3c4(2023), \uc804\ub77c\ubd81\ub3c4\u2192\uc804\ubd81\ud2b9\ubcc4\uc790\uce58\ub3c4(2024) \uac1c\uba85 \ubc18\uc601
        String[][] sidoMap = {
            {"\uc11c\uc6b8\ud2b9\ubcc4\uc2dc", "\uc11c\uc6b8", "\uc11c\uc6b8\ud2b9\ubcc4\uc2dc"},
            {"\ubd80\uc0b0\uad11\uc5ed\uc2dc", "\ubd80\uc0b0", "\ubd80\uc0b0\uad11\uc5ed\uc2dc"},
            {"\ub300\uad6c\uad11\uc5ed\uc2dc", "\ub300\uad6c", "\ub300\uad6c\uad11\uc5ed\uc2dc"},
            {"\uc778\ucc9c\uad11\uc5ed\uc2dc", "\uc778\ucc9c", "\uc778\ucc9c\uad11\uc5ed\uc2dc"},
            {"\uad11\uc8fc\uad11\uc5ed\uc2dc", "\uad11\uc8fc", "\uad11\uc8fc\uad11\uc5ed\uc2dc"},
            {"\ub300\uc804\uad11\uc5ed\uc2dc", "\ub300\uc804", "\ub300\uc804\uad11\uc5ed\uc2dc"},
            {"\uc6b8\uc0b0\uad11\uc5ed\uc2dc", "\uc6b8\uc0b0", "\uc6b8\uc0b0\uad11\uc5ed\uc2dc"},
            {"\uc138\uc885\ud2b9\ubcc4\uc790\uce58\uc2dc", "\uc138\uc885", "\uc138\uc885\ud2b9\ubcc4\uc790\uce58\uc2dc"},
            {"\uacbd\uae30\ub3c4", "\uacbd\uae30", "\uacbd\uae30\ub3c4"},
            {"\uac15\uc6d0\ud2b9\ubcc4\uc790\uce58\ub3c4", "\uac15\uc6d0", "\uac15\uc6d0\ud2b9\ubcc4\uc790\uce58\ub3c4"},
            {"\uac15\uc6d0\ub3c4", "\uac15\uc6d0", "\uac15\uc6d0\ud2b9\ubcc4\uc790\uce58\ub3c4"},
            {"\ucda9\uccad\ubd81\ub3c4", "\ucda9\ubd81", "\ucda9\uccad\ubd81\ub3c4"},
            {"\ucda9\uccad\ub0a8\ub3c4", "\ucda9\ub0a8", "\ucda9\uccad\ub0a8\ub3c4"},
            {"\uc804\ubd81\ud2b9\ubcc4\uc790\uce58\ub3c4", "\uc804\ubd81", "\uc804\ubd81\ud2b9\ubcc4\uc790\uce58\ub3c4"},
            {"\uc804\ub77c\ubd81\ub3c4", "\uc804\ubd81", "\uc804\ubd81\ud2b9\ubcc4\uc790\uce58\ub3c4"},
            {"\uc804\ub77c\ub0a8\ub3c4", "\uc804\ub0a8", "\uc804\ub77c\ub0a8\ub3c4"},
            {"\uacbd\uc0c1\ubd81\ub3c4", "\uacbd\ubd81", "\uacbd\uc0c1\ubd81\ub3c4"},
            {"\uacbd\uc0c1\ub0a8\ub3c4", "\uacbd\ub0a8", "\uacbd\uc0c1\ub0a8\ub3c4"},
            {"\uc81c\uc8fc\ud2b9\ubcc4\uc790\uce58\ub3c4", "\uc81c\uc8fc", "\uc81c\uc8fc\ud2b9\ubcc4\uc790\uce58\ub3c4"},
        };
        for (String[] sido : sidoMap) {
            if (address.contains(sido[0]) || address.startsWith(sido[1])) {
                return sido[2];
            }
        }
        return "";
    }

    private String extractRoadAddress(String address) {
        String cleaned = address.replaceAll("(\uc11c\uc6b8\ud2b9\ubcc4\uc2dc|\ubd80\uc0b0\uad11\uc5ed\uc2dc|\ub300\uad6c\uad11\uc5ed\uc2dc|\uc778\ucc9c\uad11\uc5ed\uc2dc|\uad11\uc8fc\uad11\uc5ed\uc2dc|\ub300\uc804\uad11\uc5ed\uc2dc|\uc6b8\uc0b0\uad11\uc5ed\uc2dc|\uc138\uc885\ud2b9\ubcc4\uc790\uce58\uc2dc|\uacbd\uae30\ub3c4|\uac15\uc6d0\ub3c4|\ucda9\uccad\ubd81\ub3c4|\ucda9\uccad\ub0a8\ub3c4|\uc804\ub77c\ubd81\ub3c4|\uc804\ub77c\ub0a8\ub3c4|\uacbd\uc0c1\ubd81\ub3c4|\uacbd\uc0c1\ub0a8\ub3c4|\uc81c\uc8fc\ud2b9\ubcc4\uc790\uce58\ub3c4)\\s*", "").replaceAll("[\uac00-\ud7a3]+\uc2dc\\s+[\uac00-\ud7a3]+\uad6c\\s+", "").replaceAll("[\uac00-\ud7a3]+\uc2dc\\s+[\uac00-\ud7a3]+\uad70\\s+", "").replaceAll("[\uac00-\ud7a3]+\uc2dc\\s+", "").trim();
        cleaned = cleaned.replaceAll("([\uac00-\ud7a3]+(\ub85c|\uae38|\ub300\ub85c))(\\d+)", "$1 $3");
        cleaned = cleaned.replaceAll("(\\d+\ub3d9)(\\d+\ud638)", "$1 $2");
        return cleaned.replaceAll("\\s+", " ").trim();
    }

    private boolean clickByTextAll(ChromeDriver driver, String text) {
        try {
            driver.switchTo().alert().accept();
        }
        catch (Exception exception) {
            // empty catch block
        }
        for (WebElement el : driver.findElements(By.tagName((String)"a"))) {
            try {
                if (!el.getText().trim().equals(text)) continue;
                driver.executeScript("arguments[0].click();", new Object[]{el});
                return true;
            }
            catch (UnhandledAlertException uae) {
                try {
                    driver.switchTo().alert().accept();
                }
                catch (Exception exception) {}
            }
            catch (Exception exception) {
            }
        }
        for (WebElement el : driver.findElements(By.tagName((String)"button"))) {
            try {
                if (!el.getText().trim().equals(text) || !el.isDisplayed()) continue;
                driver.executeScript("arguments[0].click();", new Object[]{el});
                return true;
            }
            catch (Exception exception) {
            }
        }
        for (WebElement el : driver.findElements(By.cssSelector((String)"span, div"))) {
            try {
                if (!el.getText().trim().equals(text) || !el.isDisplayed()) continue;
                driver.executeScript("arguments[0].click();", new Object[]{el});
                return true;
            }
            catch (Exception exception) {
            }
        }
        return false;
    }

    private static final String[] TOUCHEN_EXT_IDS = {
        "dncepekefegjiljlfbihljgogephdhph",
        "gkdodgbccnhahcihaiakdkdfhchiahnf",
        "dcpajfpljdghlmibcekolainmngcbpmb"
    };

    private boolean isTouchEnInstalledInProfile(String profileDir) {
        for (String id : TOUCHEN_EXT_IDS) {
            File extPath = new File(profileDir + "/Default/Extensions/" + id);
            if (extPath.exists() && extPath.isDirectory()) return true;
        }
        return false;
    }

    private File downloadTouchEnCrx(String profileDir) {
        File crxFile = new File(profileDir, "touchen_nxkey.crx");
        if (crxFile.exists() && crxFile.length() > 5000) {
            this.logger.accept("[TouchEn] 캐시된 CRX 사용");
            return crxFile;
        }
        this.logger.accept("[TouchEn] CRX 다운로드 중...");
        for (String id : TOUCHEN_EXT_IDS) {
            try {
                String url = "https://clients2.google.com/service/update2/crx"
                    + "?response=redirect&prodversion=149.0.0.0&acceptformat=crx3"
                    + "&x=id%3D" + id + "%26uc";
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                    new java.net.URL(url).openConnection();
                conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(30000);
                conn.setInstanceFollowRedirects(true);
                conn.connect();
                if (conn.getResponseCode() == 200) {
                    try (java.io.InputStream in = conn.getInputStream();
                         java.io.FileOutputStream out = new java.io.FileOutputStream(crxFile)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                    }
                    if (crxFile.length() > 5000) {
                        this.logger.accept("[TouchEn] 다운로드 완료: " + (crxFile.length() / 1024) + "KB");
                        return crxFile;
                    }
                }
            } catch (Exception e) {
                this.logger.accept("[TouchEn] 다운로드 실패 (" + id.substring(0, 8) + "...): " + e.getMessage());
            }
        }
        this.logger.accept("[TouchEn] CRX 다운로드 실패 - 결제 단계에서 수동 진행 필요");
        return null;
    }
}
