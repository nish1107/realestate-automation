package com.realestateauto.automation;

import io.github.bonigarcia.wdm.WebDriverManager;
import org.openqa.selenium.*;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.openqa.selenium.support.ui.ExpectedConditions;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 정부24(gov.kr)에서 건축물대장 자동 다운로드.
 * 주소 형식: "탑실로 152 203동 102호"
 */
public class Gov24Automation {

    private static final String GOV24_URL = "https://www.gov.kr";

    private final String id;
    private final String password;
    private final String savePath;
    private final String addressType; // "도로명" or "지번"
    private final Consumer<String> logger;
    private String currentAddress = "";

    public Gov24Automation(String id, String password, String savePath, Consumer<String> logger) {
        this(id, password, savePath, "도로명", logger);
    }

    public Gov24Automation(String id, String password, String savePath, String addressType, Consumer<String> logger) {
        this.id = id;
        this.password = password;
        this.savePath = savePath;
        this.addressType = (addressType != null && !addressType.isEmpty()) ? addressType : "도로명";
        this.logger = logger;
    }

    public void download(String address) throws Exception {
        this.currentAddress = address != null ? address.trim() : "";
        WebDriverManager.chromedriver().setup();

        ChromeOptions options = new ChromeOptions();
        options.addArguments("--ignore-certificate-errors");
        options.addArguments("--allow-insecure-localhost");
        options.addArguments("--no-sandbox");
        options.addArguments("--disable-dev-shm-usage");
        options.addArguments("--window-size=1280,900");
        options.addArguments("--window-position=0,0");
        options.addArguments("--start-maximized");
        options.addArguments("--disable-popup-blocking");
        options.addArguments("--disable-blink-features=AutomationControlled");
        options.setExperimentalOption("excludeSwitches", Arrays.asList("enable-automation"));
        options.setExperimentalOption("useAutomationExtension", false);

        Map<String, Object> prefs = new HashMap<>();
        prefs.put("download.default_directory", new File(savePath).getAbsolutePath());
        prefs.put("download.prompt_for_download", false);
        prefs.put("download.directory_upgrade", true);
        prefs.put("plugins.always_open_pdf_externally", true);
        options.setExperimentalOption("prefs", prefs);

        String profileDir = System.getProperty("user.home") + "/gov24-chrome-profile";
        new File(profileDir).mkdirs();
        // 이전 Chrome 비정상 종료 시 남은 락파일 자동 정리
        for (String lockFile : new String[]{"SingletonLock", "SingletonSocket", "SingletonCookie"}) {
            File f = new File(profileDir + "/" + lockFile);
            if (f.exists()) { f.delete(); logger.accept("[Chrome] 락파일 정리: " + lockFile); }
        }
        options.addArguments("--user-data-dir=" + profileDir);

        ChromeDriver driver = null;
        try {
            driver = new ChromeDriver(options);
            AddressParts parts = parseAddress(address);
            logger.accept("건물주소: " + parts.buildingAddress
                + (parts.dong.isEmpty() ? "" : " / " + parts.dong + "동")
                + (parts.ho.isEmpty() ? "" : " " + parts.ho + "호"));

            tryGov24(driver, parts);
        } finally {
            if (driver != null) { try { driver.quit(); } catch (Exception ignored) {} }
        }
    }

    /** 브라우저 native alert (공인인증서 모듈 알림 등) 닫기 */
    private void dismissBrowserAlert(ChromeDriver driver) {
        try {
            Alert alert = driver.switchTo().alert();
            String alertText = alert.getText();
            logger.accept("브라우저 알림 닫기: " + alertText.substring(0, Math.min(60, alertText.length())));
            alert.accept();
            Thread.sleep(300);
        } catch (Exception ignored) {}
    }

    /**
     * "아이디 로그인" 카드 클릭 (세움터/정부24 공통).
     * innerText 대신 textContent 사용 (overlay의 inert/visibility:hidden 우회).
     * 실패 시 XPath, scrollIntoView, dispatchEvent 순으로 폴백.
     */
    private boolean clickIdLoginCard(ChromeDriver driver, String site) {
        // 디버그: "아이디"를 포함하는 요소 정보 출력
        try {
            String debug = (String) ((JavascriptExecutor) driver).executeScript(
                "var result = [];" +
                "var all = document.querySelectorAll('*');" +
                "for (var i = 0; i < all.length; i++) {" +
                "  var tc = (all[i].textContent || '').replace(/[\\s\\n]+/g,' ').trim();" +
                "  if (tc.includes('아이디 로그인') && tc.length < 200) {" +
                "    var rect = all[i].getBoundingClientRect();" +
                "    result.push(all[i].tagName + '[w=' + Math.round(rect.width) + ',h=' + Math.round(rect.height) + '] \"' + tc.substring(0, 30) + '\"');" +
                "    if (result.length >= 5) break;" +
                "  }" +
                "}" +
                "return result.length > 0 ? result.join(' | ') : 'NOT FOUND';");
            logger.accept("[" + site + "] 아이디로그인 요소: " + debug);
        } catch (Exception ignored) {}

        // 방법 1: A/BUTTON 태그에서 textContent 검색 (소셜 로그인 URL 제외)
        try {
            String result = (String) ((JavascriptExecutor) driver).executeScript(
                "var links = document.querySelectorAll('a, button');" +
                "for (var i = 0; i < links.length; i++) {" +
                "  var el = links[i];" +
                "  var tc = (el.textContent || '').replace(/[\\s\\n]+/g,' ').trim();" +
                "  var rect = el.getBoundingClientRect();" +
                "  var href = el.getAttribute('href') || '';" +
                // 소셜 미디어 공유/로그인 URL 제외
                "  if (/x\\.com|twitter|kakao|naver\\.com\\/oauth|facebook|google/.test(href)) continue;" +
                "  if (tc.includes('아이디 로그인') && tc.length < 150) {" +
                "    el.scrollIntoView({block:'center'});" +
                "    var target = el.getAttribute('target') || 'same';" +
                "    el.click();" +
                "    return 'clicked|href=' + href + '|target=' + target;" +
                "  }" +
                "}" +
                "return null;");
            if (result != null) {
                logger.accept("[" + site + "] 방법1 성공: " + result);
                return true;
            }
        } catch (Exception ignored) {}

        // 방법 2: 모든 요소에서 textContent 검색, A/BUTTON 우선
        try {
            String result = (String) ((JavascriptExecutor) driver).executeScript(
                "var all = document.querySelectorAll('*');" +
                "var fallback = null;" +
                "for (var i = 0; i < all.length; i++) {" +
                "  var el = all[i];" +
                "  var tc = (el.textContent || '').replace(/[\\s\\n]+/g,' ').trim();" +
                "  var rect = el.getBoundingClientRect();" +
                "  if (rect.width > 0 && rect.height > 0 && tc.includes('아이디 로그인') && tc.length < 150) {" +
                "    var tag = el.tagName;" +
                "    if (tag === 'A' || tag === 'BUTTON') {" +
                "      el.scrollIntoView({block:'center'});" +
                "      el.click();" +
                "      return 'A/BUTTON|tag=' + tag;" +
                "    }" +
                "    if (!fallback) fallback = el;" +
                "  }" +
                "}" +
                "if (fallback) { fallback.scrollIntoView({block:'center'}); fallback.click(); return 'fallback|' + fallback.tagName; }" +
                "return null;");
            if (result != null) {
                logger.accept("[" + site + "] 방법2 성공: " + result);
                return true;
            }
        } catch (Exception ignored) {}

        // 방법 3: XPath - 직접 텍스트가 "아이디 로그인"인 요소
        try {
            List<WebElement> els = driver.findElements(By.xpath("//*[normalize-space(text())='아이디 로그인']"));
            for (WebElement el : els) {
                try {
                    ((JavascriptExecutor) driver).executeScript("arguments[0].scrollIntoView({block:'center'})", el);
                    Thread.sleep(200);
                    el.click();
                    logger.accept("[" + site + "] 방법3(XPath text) 성공");
                    return true;
                } catch (Exception ignored2) {}
            }
        } catch (Exception ignored) {}

        // 방법 4: XPath - "아이디 로그인"을 포함하는 링크/버튼
        try {
            List<WebElement> els = driver.findElements(By.xpath("//a[contains(.,'아이디 로그인')] | //button[contains(.,'아이디 로그인')]"));
            for (WebElement el : els) {
                try {
                    ((JavascriptExecutor) driver).executeScript("arguments[0].scrollIntoView({block:'center'})", el);
                    Thread.sleep(200);
                    ((JavascriptExecutor) driver).executeScript("arguments[0].click()", el);
                    logger.accept("[" + site + "] 방법4(XPath a/button) 성공");
                    return true;
                } catch (Exception ignored2) {}
            }
        } catch (Exception ignored) {}

        // 방법 5: 페이지 스크롤 후 재시도
        try {
            Thread.sleep(300);
            ((JavascriptExecutor) driver).executeScript("window.scrollTo(0, document.body.scrollHeight)");
            Thread.sleep(500);
            Boolean clicked = (Boolean) ((JavascriptExecutor) driver).executeScript(
                "var all = document.querySelectorAll('*');" +
                "for (var i = all.length - 1; i >= 0; i--) {" +
                "  var el = all[i];" +
                "  var tc = (el.textContent || '').replace(/[\\s\\n]+/g,' ').trim();" +
                "  var rect = el.getBoundingClientRect();" +
                "  if (rect.width > 0 && rect.height > 0 && tc.includes('아이디 로그인') && tc.length < 150) {" +
                "    el.click(); return true;" +
                "  }" +
                "}" +
                "return false;");
            if (Boolean.TRUE.equals(clicked)) { logger.accept("[" + site + "] 방법5(scroll+retry) 성공"); return true; }
        } catch (Exception ignored) {}

        logger.accept("[" + site + "] 모든 클릭 방법 실패");
        return false;
    }

    // ─── 정부24(gov.kr) ──────────────────────────────────────────────────

    private void tryGov24(ChromeDriver driver, AddressParts parts) throws InterruptedException {
        logger.accept("정부24 접속 중...");
        driver.get(GOV24_URL);
        waitForText(driver, 8000, "로그인", "로그아웃", "민원서비스");
        dismissPopups(driver);
        Thread.sleep(500);

        String pageText = getPageText(driver);
        // 점검 감지: 명확한 점검 문구 + 로그인/로그아웃 버튼도 없어야 진짜 점검
        boolean isMaintenance = (pageText.contains("서비스 점검") || pageText.contains("현재 점검") || pageText.contains("시스템 점검"))
                && !pageText.contains("로그인") && !pageText.contains("로그아웃");
        if (isMaintenance) {
            logger.accept("정부24 서비스 점검 중 - " + pageText.substring(0, Math.min(80, pageText.length())));
            throw new RuntimeException("정부24 서비스 점검 중입니다.\n잠시 후 다시 시도해주세요.");
        }

        if (!isLoggedIn(driver)) {
            if (!id.isEmpty() && !password.isEmpty()) {
                logger.accept("정부24 로그인 시도...");
                doGov24Login(driver);
            }
            if (!isLoggedIn(driver)) {
                logger.accept("━━━━━━━━━━━━━━━━━━━━━━━━");
                logger.accept("정부24 자동 로그인 실패 - 수동 로그인 필요");
                logger.accept("열린 창에서 직접 로그인해주세요. (최대 5분 대기)");
                logger.accept("━━━━━━━━━━━━━━━━━━━━━━━━");
                if (!waitForManualLogin(driver)) {
                    logger.accept("로그인 없이 종료.");
                    return;
                }
            }
        }
        logger.accept("로그인 확인됨.");

        logger.accept("건축물대장 서비스 이동 중...");
        navigateToBuildingService(driver);
        logger.accept("주소 검색: " + parts.buildingAddress + " [" + addressType + "]");
        boolean success = fillBuildingForm(driver, parts, "집합");
        if (!success) {
            logger.accept("[재시도] 집합 대장구분 검색 실패 - 일반으로 재시도...");
            navigateToBuildingService(driver);
            fillBuildingForm(driver, parts, "일반");
        }
    }

    /** 정부24 로그인: 로그인 방식 선택 → "아이디 로그인" 클릭 → ID/PW sendKeys */
    private void doGov24Login(ChromeDriver driver) throws InterruptedException {
        driver.get(GOV24_URL);
        Thread.sleep(1500);
        dismissPopups(driver);
        Thread.sleep(300);

        // 메인 헤더의 로그인 링크 클릭 → 로그인 방식 선택 페이지
        clickLinkByText(driver, "로그인");
        Thread.sleep(2000);
        dismissBrowserAlert(driver);

        // 페이지 로드 완료 대기
        for (int i = 0; i < 12; i++) {
            Thread.sleep(500);
            dismissBrowserAlert(driver);
            String bodyText = getPageText(driver);
            if (bodyText.contains("아이디 로그인")) break;
        }
        Thread.sleep(300);
        saveScreenshot(driver, "gov24_login_page");

        // "아이디 로그인" 클릭 (다중 전략)
        String gov24MainHandle = driver.getWindowHandle();
        boolean idLoginClicked = clickIdLoginCard(driver, "정부24");
        logger.accept("정부24 아이디 로그인 클릭: " + idLoginClicked);
        Thread.sleep(2500);
        dismissBrowserAlert(driver);

        // 새 창 확인 (아이디 로그인이 팝업/새탭으로 열리는 경우)
        Set<String> gov24Wins = driver.getWindowHandles();
        logger.accept("창 개수: " + gov24Wins.size() + ", 현재URL: " + driver.getCurrentUrl());
        if (gov24Wins.size() > 1) {
            for (String h : gov24Wins) {
                if (!h.equals(gov24MainHandle)) {
                    logger.accept("새 창 감지 - 아이디 로그인 폼 창으로 전환");
                    driver.switchTo().window(h);
                    Thread.sleep(1500);
                    dismissBrowserAlert(driver);
                    logger.accept("새 창 URL: " + driver.getCurrentUrl());
                    break;
                }
            }
        } else {
            Thread.sleep(500);
        }
        saveScreenshot(driver, "gov24_id_login_form");

        String pageText = getPageText(driver);
        if (!pageText.contains("아이디") && !pageText.contains("비밀번호")) {
            logger.accept("정부24 아이디 로그인 폼 로드 실패 - 페이지: " + pageText.substring(0, Math.min(100, pageText.length())));
            return;
        }

        try {
            // Step 1: ID 입력 → "다음" 클릭
            WebElement idInput = findVisibleInput(driver, "아이디", "id", "userId", "loginId");
            if (idInput != null) {
                idInput.clear();
                idInput.sendKeys(id);
                Thread.sleep(300);
                // "로그인 유지" 체크박스 클릭 (세션 30일 유지)
                try {
                    Boolean keepLogin = (Boolean) ((JavascriptExecutor) driver).executeScript(
                        "var cbs=document.querySelectorAll('input[type=checkbox]');" +
                        "for(var i=0;i<cbs.length;i++){" +
                        "  var lbl='';" +
                        "  if(cbs[i].labels&&cbs[i].labels[0]) lbl=cbs[i].labels[0].textContent||'';" +
                        "  else { var s=cbs[i].nextSibling; while(s){if(s.textContent) {lbl=s.textContent; break;} s=s.nextSibling;}}" +
                        "  lbl=lbl.replace(/\\s+/g,' ').trim();" +
                        "  if((lbl.includes('로그인 유지')||lbl.includes('자동 로그인')||lbl.includes('로그인유지'))&&!cbs[i].checked){" +
                        "    cbs[i].click(); return true;" +
                        "  }" +
                        "} return false;");
                    if (Boolean.TRUE.equals(keepLogin)) logger.accept("로그인 유지 체크박스 클릭 완료");
                } catch (Exception ignored) {}
                clickButtonByText(driver, "다음");
                Thread.sleep(2500);
                dismissBrowserAlert(driver);
                saveScreenshot(driver, "gov24_after_id_step");
                logger.accept("ID입력 후 URL: " + driver.getCurrentUrl());
            }
            // Step 2: 비밀번호 입력
            Thread.sleep(2000);
            // 이미 로그인된 경우 건너뜀
            if (isLoggedIn(driver)) {
                logger.accept("로그인 완료 확인 (비밀번호/CAPTCHA 불필요)");
            } else {
            String pwPageText = getPageText(driver);
            // "자동입력" 단독 제거 - false-positive 방지. 실제 CAPTCHA 텍스트만 체크
            boolean hasCaptcha = pwPageText.contains("아래의 숫자") || pwPageText.contains("보안문자")
                    || pwPageText.contains("자동입력방지코드") || pwPageText.contains("자동입력 방지코드")
                    || (pwPageText.contains("captcha") && !pwPageText.contains("로그아웃"));
            // PW 페이지의 "로그인 유지" 체크박스 (아직 안 눌렸을 수 있음)
            try {
                ((JavascriptExecutor) driver).executeScript(
                    "var cbs=document.querySelectorAll('input[type=checkbox]');" +
                    "for(var i=0;i<cbs.length;i++){" +
                    "  var lbl='';" +
                    "  if(cbs[i].labels&&cbs[i].labels[0]) lbl=cbs[i].labels[0].textContent||'';" +
                    "  else { var s=cbs[i].nextSibling; while(s){if(s.textContent) {lbl=s.textContent; break;} s=s.nextSibling;}}" +
                    "  lbl=lbl.replace(/\\s+/g,' ').trim();" +
                    "  if((lbl.includes('로그인 유지')||lbl.includes('자동 로그인')||lbl.includes('로그인유지'))&&!cbs[i].checked){" +
                    "    cbs[i].click();" +
                    "  }" +
                    "}");
            } catch (Exception ignored) {}
            List<WebElement> pwInputs = driver.findElements(By.cssSelector("input[type=password]"));
            for (WebElement el : pwInputs) {
                if (el.isDisplayed()) {
                    el.clear();
                    el.sendKeys(password);
                    Thread.sleep(300);
                    break;
                }
            }
            if (hasCaptcha) {
                saveScreenshot(driver, "gov24_captcha_waiting");
                beepAlert();
                waitForCaptchaAndEnter(driver, "초기로그인");
                // 사용자가 직접 로그인 버튼을 누름 - 코드는 대기만 함
            } else {
                clickLoginSubmitButton(driver, pwInputs);
            }
            waitForText(driver, 300000, "로그아웃");
            } // end !isLoggedIn block
            // 초기 로그인 후 비밀번호 변경 페이지 건너뛰기
            try {
                Thread.sleep(500);
                String initPostUrl = driver.getCurrentUrl();
                String initPostText = getPageText(driver);
                if (initPostUrl.contains("modifyPswd") || initPostUrl.contains("changePswd") || initPostUrl.contains("chgPswd")
                        || initPostText.contains("비밀번호 재설정") || initPostText.contains("비밀번호를 변경")) {
                    logger.accept("[초기로그인] 비밀번호 변경 페이지 감지 - 건너뛰기...");
                    Boolean skipBtn = (Boolean) ((JavascriptExecutor) driver).executeScript(
                        "var els=document.querySelectorAll('button,a,input[type=button]');" +
                        "for(var i=0;i<els.length;i++){" +
                        "  var t=(els[i].textContent||els[i].value||'').replace(/\\s+/g,' ').trim();" +
                        "  var r=els[i].getBoundingClientRect();" +
                        "  if(r.width>0&&r.height>0&&(t.includes('다음에')||t.includes('나중에')||t.includes('건너뛰기')||t.includes('skip')||t.includes('취소')||t.includes('닫기'))){" +
                        "    els[i].click(); return true;" +
                        "  }" +
                        "}" +
                        "return false;");
                    logger.accept("[초기로그인] 비밀번호 변경 건너뛰기: " + skipBtn);
                    Thread.sleep(2000);
                    dismissBrowserAlert(driver);
                }
            } catch (Exception e) {
                logger.accept("[초기로그인] 비밀번호 변경 처리 오류: " + e.getMessage());
            }
        } catch (Exception e) {
            logger.accept("정부24 자동 로그인 오류: " + e.getMessage());
        }
    }

    private void navigateToBuildingService(ChromeDriver driver) throws InterruptedException {
        // 전략 1: 메인 페이지 "건축물대장" 바로가기 링크 클릭
        driver.get(GOV24_URL);
        Thread.sleep(2000);
        dismissPopups(driver);

        Boolean clicked = (Boolean) ((JavascriptExecutor) driver).executeScript(
            "var links = document.querySelectorAll('a');" +
            "for (var i = 0; i < links.length; i++) {" +
            "  var t = (links[i].textContent || '').replace(/[\\s\\n]+/g,' ').trim();" +
            "  var rect = links[i].getBoundingClientRect();" +
            "  if (rect.width > 0 && rect.height > 0 && t.includes('건축물대장')) {" +
            "    links[i].click(); return true;" +
            "  }" +
            "}" +
            "return false;");
        logger.accept("메인페이지 건축물대장 링크 클릭: " + clicked);

        if (Boolean.TRUE.equals(clicked)) {
            Thread.sleep(2500);
            dismissPopups(driver);
            saveScreenshot(driver, "gov24_building_service");
            logger.accept("서비스 이동 후 URL: " + driver.getCurrentUrl());
            String pageText = getPageText(driver);
            logger.accept("서비스페이지: " + pageText.substring(0, Math.min(120, pageText.length())).replaceAll("\\s+", " "));
            if (isOnBuildingServicePage(driver)) {
                logger.accept("건축물대장 서비스 페이지 로드 완료");
                return;
            }
        }

        // 전략 2: 검색창으로 "건축물대장" 검색
        driver.get(GOV24_URL);
        Thread.sleep(1500);
        dismissPopups(driver);

        // 검색창 찾기 (돋보기 버튼 토글이 필요한 경우 대비)
        WebElement searchInput = findVisibleInput(driver, "통합검색", "검색어", "search", "query", "검색");
        if (searchInput == null) {
            ((JavascriptExecutor) driver).executeScript(
                "var btns = document.querySelectorAll('button, a');" +
                "for (var i = 0; i < btns.length; i++) {" +
                "  var t = (btns[i].textContent || btns[i].getAttribute('aria-label') || '').trim();" +
                "  var rect = btns[i].getBoundingClientRect();" +
                "  if (rect.width > 0 && (t === '검색' || t.includes('통합검색') || t.includes('search'))) {" +
                "    btns[i].click(); return;" +
                "  }" +
                "}");
            Thread.sleep(800);
            searchInput = findFirstVisibleInput(driver);
        }

        if (searchInput != null) {
            searchInput.clear();
            searchInput.sendKeys("건축물대장");
            searchInput.sendKeys(Keys.ENTER);
            Thread.sleep(3000);
            dismissPopups(driver);
            saveScreenshot(driver, "gov24_search_result");

            // 검색 결과에서 정확히 "건축물대장" 텍스트인 링크 우선 클릭
            Boolean resultClicked = (Boolean) ((JavascriptExecutor) driver).executeScript(
                "var links = document.querySelectorAll('a');" +
                "var fallback = null;" +
                "for (var i = 0; i < links.length; i++) {" +
                "  var t = (links[i].textContent || '').replace(/[\\s\\n]+/g,' ').trim();" +
                "  var rect = links[i].getBoundingClientRect();" +
                "  if (rect.width === 0 || rect.height === 0) continue;" +
                "  if (t === '건축물대장') { links[i].click(); return true; }" +
                "  if (t.includes('건축물대장') && t.length < 30 && fallback === null) fallback = links[i];" +
                "}" +
                "if (fallback) { fallback.click(); return true; }" +
                "return false;");

            if (Boolean.TRUE.equals(resultClicked)) {
                Thread.sleep(2500);
                dismissPopups(driver);
                saveScreenshot(driver, "gov24_building_service");
                logger.accept("서비스 이동(검색) 후 URL: " + driver.getCurrentUrl());
                String pageText = getPageText(driver);
                logger.accept("서비스페이지(검색): " + pageText.substring(0, Math.min(120, pageText.length())).replaceAll("\\s+", " "));
                if (isOnBuildingServicePage(driver)) {
                    logger.accept("건축물대장 서비스 페이지 로드 완료 (검색)");
                    return;
                }
            }
        }

        saveScreenshot(driver, "gov24_building_service_notfound");
        throw new RuntimeException("건축물대장 서비스 페이지를 찾지 못했습니다. dbg_gov24_building_service_notfound.png 확인");
    }

    private boolean formHasAddressField(String pageText) {
        return pageText.contains("주소검색") || pageText.contains("주소 검색") ||
               pageText.contains("소재지") || pageText.contains("지번") ||
               pageText.contains("도로명") || pageText.contains("대장종류");
    }

    /** 실제 서비스 신청 가능 페이지인지 확인 */
    private boolean isOnBuildingServicePage(ChromeDriver driver) {
        String url = driver.getCurrentUrl();
        // CappBizCD URL이면 서비스 페이지로 간주 (info 또는 apply 페이지)
        if (url.contains("CappBizCD=") || url.contains("InfoCappView") || url.contains("InfoCappApply")) {
            logger.accept("서비스 페이지 URL 확인: " + url);
            return true;
        }
        // 페이지 텍스트 기반 확인 (주소검색/소재지번은 메뉴에 없음)
        String pageText = getPageText(driver);
        return pageText.contains("주소검색") || pageText.contains("소재지번") ||
               pageText.contains("대장종류") || pageText.contains("발급 신청");
    }

    private boolean fillBuildingForm(ChromeDriver driver, AddressParts parts, String daejangGubun) throws InterruptedException {
        // 서비스 정보 페이지 확인 및 "신청하기" 클릭
        saveScreenshot(driver, "gov24_before_apply");
        logger.accept("서비스 페이지 URL: " + driver.getCurrentUrl());
        String beforeText = getPageText(driver);
        logger.accept("신청 전 페이지: " + beforeText.substring(0, Math.min(150, beforeText.length())).replaceAll("\\s+", " "));

        // 클릭 전 현재 창 목록 기록 (새 탭 감지용)
        Set<String> handlesBeforeApply = driver.getWindowHandles();

        // 발급신청 버튼의 href 추출 → 직접 navigate (클릭 시 JS가 새 창을 열어 팝업 차단 문제 발생)
        String applyHref = (String) ((JavascriptExecutor) driver).executeScript(
            "var keywords = ['신청하기', '발급하기', '민원신청', '열람하기', '발급신청', '민원발급'];" +
            "var els = document.querySelectorAll('a, button');" +
            "for (var i = 0; i < els.length; i++) {" +
            "  var t = (els[i].textContent||'').replace(/[\\s\\n]+/g,' ').trim();" +
            "  var r = els[i].getBoundingClientRect();" +
            "  if (r.width > 0 && r.height > 0) {" +
            "    for (var k = 0; k < keywords.length; k++) {" +
            "      if (t === keywords[k]) {" +
            "        var h = els[i].getAttribute('href')||'';" +
            "        if (h && h !== '#' && h !== '#none' && h !== 'javascript:void(0)') return h;" +
            "      }" +
            "    }" +
            "  }" +
            "}" +
            "return null;");
        logger.accept("발급신청 href: " + applyHref);

        // href가 있으면 직접 navigate (클릭 시 페이지 JS가 #none으로 막아 팝업 미오픈)
        String jsResult = null;
        if (applyHref != null && !applyHref.isEmpty()) {
            String fullApplyUrl = applyHref.startsWith("http") ? applyHref : "https://www.gov.kr" + applyHref;
            logger.accept("발급신청 직접 이동: " + fullApplyUrl);
            driver.get(fullApplyUrl);
            Thread.sleep(3000);
            dismissBrowserAlert(driver);
            dismissPopups(driver);
            // 새 탭이 열렸으면 전환
            Set<String> handlesAfterNav = driver.getWindowHandles();
            if (handlesAfterNav.size() > handlesBeforeApply.size()) {
                for (String h : handlesAfterNav) {
                    if (!handlesBeforeApply.contains(h)) {
                        driver.switchTo().window(h);
                        Thread.sleep(1500);
                        logger.accept("발급신청 새 탭 전환: " + driver.getCurrentUrl());
                        break;
                    }
                }
            }
            jsResult = "클릭(href직접):" + applyHref;
            logger.accept("발급신청 이동 후 URL: " + driver.getCurrentUrl());
        } else {
            // href 없는 경우 네이티브 클릭 fallback
            List<WebElement> applyBtns = driver.findElements(By.cssSelector("a, button"));
            String[] applyKeywords = {"신청하기", "발급하기", "민원신청", "열람하기", "발급신청", "민원발급"};
            for (WebElement btn : applyBtns) {
                try {
                    String t = btn.getText().replace("\n", " ").trim();
                    if (!btn.isDisplayed()) continue;
                    for (String kw : applyKeywords) {
                        if (t.equals(kw)) {
                            ((JavascriptExecutor) driver).executeScript("arguments[0].scrollIntoView({block:'center'});", btn);
                            Thread.sleep(300);
                            btn.click();
                            jsResult = "클릭(네이티브):" + t + "|" + btn.getTagName();
                            logger.accept("신청하기 네이티브 클릭: " + jsResult);
                            break;
                        }
                    }
                    if (jsResult != null) break;
                } catch (Exception ignored) {}
            }
        }

        if (jsResult != null && jsResult.startsWith("클릭")) {
            Thread.sleep(2500);
            dismissBrowserAlert(driver);
            dismissPopups(driver);
            // 새 탭이 열렸으면 전환 (발급신청이 plus.gov.kr 신청폼을 새 탭으로 열 수 있음)
            try {
                Set<String> handlesAfterApply = driver.getWindowHandles();
                if (handlesAfterApply.size() > handlesBeforeApply.size()) {
                    for (String h : handlesAfterApply) {
                        if (!handlesBeforeApply.contains(h)) {
                            driver.switchTo().window(h);
                            Thread.sleep(1500);
                            dismissBrowserAlert(driver);
                            logger.accept("발급신청 새 탭 전환: " + driver.getCurrentUrl());
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                logger.accept("새 탭 감지 오류: " + e.getMessage());
            }
            // 회원/비회원 모달 감지 → "회원 신청하기" 클릭
            String modalCheck = getPageText(driver);
            if (modalCheck.contains("회원 신청하기") || modalCheck.contains("비회원 신청하기") || modalCheck.contains("신청가능 서비스")) {
                logger.accept("회원/비회원 모달 감지 - 회원 신청하기 클릭...");
                Set<String> handlesBeforeModal = driver.getWindowHandles();
                Boolean modalClicked = (Boolean) ((JavascriptExecutor) driver).executeScript(
                    "var els = document.querySelectorAll('button, a');" +
                    "for (var i = 0; i < els.length; i++) {" +
                    "  var t = (els[i].textContent || '').replace(/[\\s\\n]+/g,' ').trim();" +
                    "  var rect = els[i].getBoundingClientRect();" +
                    "  if (rect.width > 0 && rect.height > 0 && (t === '회원 신청하기' || t === '회원신청하기')) {" +
                    "    els[i].scrollIntoView({block:'center'}); els[i].click(); return true;" +
                    "  }" +
                    "}" +
                    "return false;");
                logger.accept("회원 신청하기 클릭: " + modalClicked);
                if (Boolean.TRUE.equals(modalClicked)) {
                    Thread.sleep(3000);
                    dismissBrowserAlert(driver);
                    // 새 창이 열렸으면 전환 (회원 신청하기가 새 탭/팝업 오픈)
                    try {
                        Set<String> newHandles = driver.getWindowHandles();
                        String currentHandle = null;
                        try { currentHandle = driver.getWindowHandle(); } catch (Exception ignored) {}
                        for (String h : newHandles) {
                            if (!handlesBeforeModal.contains(h)) {
                                driver.switchTo().window(h);
                                logger.accept("새 창 전환: " + driver.getCurrentUrl());
                                break;
                            }
                        }
                        if (currentHandle == null && !newHandles.isEmpty()) {
                            driver.switchTo().window(newHandles.iterator().next());
                        }
                    } catch (Exception e) {
                        logger.accept("창 전환 오류: " + e.getMessage());
                    }
                    dismissPopups(driver);
                    saveScreenshot(driver, "gov24_after_member_select");
                    logger.accept("회원 선택 후 URL: " + driver.getCurrentUrl());
                }
            }
        }
        saveScreenshot(driver, "gov24_apply_form");
        logger.accept("신청 후 URL: " + driver.getCurrentUrl());
        String applyPageText = getPageText(driver);
        logger.accept("신청 폼 페이지: " + applyPageText.substring(0, Math.min(200, applyPageText.length())).replaceAll("\\s+", " "));

        // 폼이 '신청할 서비스를 선택하세요' 화면이면 스크롤 다운하여 실제 폼 필드 확인
        if (applyPageText.contains("신청할 서비스") || applyPageText.contains("선택하세요") || applyPageText.contains("신청 내용")) {
            ((JavascriptExecutor) driver).executeScript("window.scrollBy(0, 1200);");
            Thread.sleep(700);
            saveScreenshot(driver, "gov24_form_scrolled");
            logger.accept("폼 스크롤 후 URL: " + driver.getCurrentUrl());
            applyPageText = getPageText(driver);
        }

        // 신청 폼이 로드되었는지 확인
        boolean hasAddressForm = formHasAddressField(applyPageText);

        // 회원 신청하기 후 재인증 리다이렉트 처리 (plus.gov.kr/login)
        if (!hasAddressForm) {
            String curUrl2 = driver.getCurrentUrl();
            if (curUrl2.contains("plus.gov.kr/login") || applyPageText.contains("로그인 방식")) {
                logger.accept("신청폼 재인증 필요 (회원신청 후 재로그인)...");
                String mainHandle2 = driver.getWindowHandle();
                boolean idCard2 = clickIdLoginCard(driver, "신청폼재인증");
                logger.accept("재인증 아이디 로그인 카드 클릭: " + idCard2);
                // loginIdPwd 페이지 로드 대기 (최대 10초)
                waitForUrl(driver, 10000, "loginIdPwd", "loginIdPwdTo");
                Thread.sleep(500);
                dismissBrowserAlert(driver);
                // 새 창 처리
                for (String h : driver.getWindowHandles()) {
                    if (!h.equals(mainHandle2)) { driver.switchTo().window(h); break; }
                }
                saveScreenshot(driver, "gov24_relogin_form");
                logger.accept("재인증 폼 URL: " + driver.getCurrentUrl());
                // Step 1: ID 입력 → "다음" (loginIdPwd 단계에서만)
                String reLoginUrl1 = "";
                try { reLoginUrl1 = driver.getCurrentUrl(); } catch (Exception ignored) {}
                if (reLoginUrl1.contains("loginIdPwd") && !reLoginUrl1.contains("loginIdPwdTo")) {
                    WebElement loginId2 = findVisibleInput(driver, "아이디", "id", "userId", "loginId");
                    if (loginId2 == null) loginId2 = findFirstVisibleInput(driver);
                    if (loginId2 != null) {
                        loginId2.clear(); loginId2.sendKeys(id); Thread.sleep(300);
                        clickButtonByText(driver, "다음");
                        waitForUrl(driver, 8000, "loginIdPwdTo");
                        Thread.sleep(500);
                        dismissBrowserAlert(driver);
                        logger.accept("재인증 ID 후 URL: " + driver.getCurrentUrl());
                    } else {
                        logger.accept("재인증 ID 입력 필드 없음 - 직접 loginIdPwdTo 이동");
                        driver.get("https://plus.gov.kr/login/loginIdPwdTo");
                        waitForUrl(driver, 5000, "loginIdPwdTo");
                        Thread.sleep(500);
                    }
                }
                // Step 2: 비밀번호 + CAPTCHA 여부 확인
                Thread.sleep(1000);
                String rePwText = getPageText(driver);
                boolean reHasCaptcha = rePwText.contains("아래의 숫자") || rePwText.contains("보안문자")
                        || rePwText.contains("자동입력방지코드") || rePwText.contains("자동입력 방지코드");
                List<WebElement> pw2 = driver.findElements(By.cssSelector("input[type=password]"));
                for (WebElement el : pw2) {
                    if (el.isDisplayed()) { el.clear(); el.sendKeys(password); Thread.sleep(300); break; }
                }
                if (reHasCaptcha) {
                    saveScreenshot(driver, "gov24_relogin_captcha");
                    beepAlert();
                    waitForCaptchaAndEnter(driver, "재인증");
                    // 사용자가 직접 로그인 버튼을 누름
                } else {
                    clickLoginSubmitButton(driver, pw2);
                    waitForUrl(driver, 30000, "gov.kr/mw", "gov.kr/main");
                }
                Thread.sleep(1500);
                dismissBrowserAlert(driver);
                // 비밀번호 변경 페이지 건너뛰기
                try {
                    String postUrl = driver.getCurrentUrl();
                    if (postUrl.contains("modifyPswd") || postUrl.contains("changePswd") || postUrl.contains("chgPswd")) {
                        logger.accept("비밀번호 변경 페이지 감지 - 건너뛰기...");
                        Boolean skipBtn = (Boolean) ((JavascriptExecutor) driver).executeScript(
                            "var els=document.querySelectorAll('button,a,input[type=button]');" +
                            "for(var i=0;i<els.length;i++){" +
                            "  var t=(els[i].textContent||els[i].value||'').replace(/\\s+/g,' ').trim();" +
                            "  var r=els[i].getBoundingClientRect();" +
                            "  if(r.width>0&&r.height>0&&(t.includes('다음에')||t.includes('나중에')||t.includes('건너뛰기')||t.includes('skip')||t.includes('취소')||t.includes('닫기'))) {" +
                            "    els[i].click(); return true;" +
                            "  }" +
                            "}" +
                            "return false;");
                        logger.accept("비밀번호 변경 건너뛰기 클릭: " + skipBtn);
                        Thread.sleep(2000);
                        dismissBrowserAlert(driver);
                        logger.accept("비밀번호 변경 후 URL: " + driver.getCurrentUrl());
                    }
                } catch (Exception e) {
                    logger.accept("비밀번호 변경 처리 오류: " + e.getMessage());
                }
                // 아직 로그인 페이지에 있으면 폼 URL로 직접 이동
                String postLoginUrl = "";
                try { postLoginUrl = driver.getCurrentUrl(); } catch (Exception ignored) {}
                if (postLoginUrl.contains("loginIdPwdTo")) {
                    // CAPTCHA 타임아웃 - 재인증 미완료
                    logger.accept("재인증 CAPTCHA 미완료 (타임아웃) - 현재 URL: " + postLoginUrl);
                    saveScreenshot(driver, "gov24_relogin_timeout");
                } else if (postLoginUrl.contains("plus.gov.kr/login") && !postLoginUrl.contains("modifyPswd")) {
                    // awqf=!2f 리다이렉트 또는 다른 로그인 페이지 - 폼 직접 이동 시도
                    logger.accept("로그인 후 폼으로 직접 이동 시도 (현재: " + postLoginUrl + ")");
                    String formUrl = "https://www.gov.kr/mw/AA040OfferMainFrm.do?capp_biz_cd=15000000098&HighCtgCD=A02004002&FAX_TYPE=B&selectedSeq=01&NEW_JUNIP=";
                    driver.get(formUrl);
                    Thread.sleep(3000);
                    dismissBrowserAlert(driver);
                    dismissPopups(driver);
                    String afterFormUrl = driver.getCurrentUrl();
                    logger.accept("직접 이동 후 URL: " + afterFormUrl);
                    // 여전히 로그인 페이지면 EgovPageLink 시도
                    if (afterFormUrl.contains("plus.gov.kr/login")) {
                        logger.accept("EgovPageLink 폼 URL로 재시도...");
                        driver.get("https://www.gov.kr/mw/EgovPageLink.do?link=minwon/AA040_std_form");
                        Thread.sleep(3000);
                        dismissBrowserAlert(driver);
                        dismissPopups(driver);
                        logger.accept("EgovPageLink 후 URL: " + driver.getCurrentUrl());
                    }
                }
                dismissPopups(driver);
                saveScreenshot(driver, "gov24_apply_after_relogin");
                logger.accept("재인증 후 URL: " + driver.getCurrentUrl());
                applyPageText = getPageText(driver);
                logger.accept("재인증 후: " + applyPageText.substring(0, Math.min(200, applyPageText.length())).replaceAll("\\s+", " "));
                hasAddressForm = formHasAddressField(applyPageText);
            }
        }

        // 아직 폼 미로드 - CappBizCD URL로 직접 이동
        if (!hasAddressForm) {
            logger.accept("신청 폼 미로드 - URL 직접 이동 시도");
            String currentUrl = driver.getCurrentUrl();
            if (currentUrl.contains("CappBizCD=") || currentUrl.contains("cappBizCd=")) {
                String bizCd = currentUrl.replaceAll("(?i).*(?:CappBizCD|cappBizCd)=([^&]+).*", "$1");
                String tpSeq = currentUrl.contains("tp_seq=") ? currentUrl.replaceAll(".*tp_seq=([^&]+).*", "$1") : "01";
                String applyUrl = "https://plus.gov.kr/minwon/apply/applyMinwonSrvcForm?cappBizCd=" + bizCd + "&tpSeq=" + tpSeq;
                logger.accept("신청 직접 URL: " + applyUrl);
                driver.get(applyUrl);
                Thread.sleep(2500);
                dismissBrowserAlert(driver);
                dismissPopups(driver);
                saveScreenshot(driver, "gov24_apply_direct");
                String afterUrl = driver.getCurrentUrl();
                applyPageText = getPageText(driver);
                logger.accept("직접이동 후 URL: " + afterUrl);
                logger.accept("직접이동 후: " + applyPageText.substring(0, Math.min(200, applyPageText.length())).replaceAll("\\s+", " "));
                hasAddressForm = formHasAddressField(applyPageText);
            }
        }

        // 주소 폼 없으면 조기 종료
        if (!hasAddressForm) {
            logger.accept("주소 폼 없음 - 현재 URL: " + driver.getCurrentUrl());
            saveScreenshot(driver, "gov24_no_address_field");
            return false;
        }

        // 페이지 내 버튼 목록 디버그
        try {
            String btnDebug = (String) ((JavascriptExecutor) driver).executeScript(
                "var r=[];" +
                "document.querySelectorAll('button,input[type=button],input[type=submit],a').forEach(function(e){" +
                "  var t=(e.textContent||e.value||'').replace(/\\s+/g,' ').trim();" +
                "  var rc=e.getBoundingClientRect();" +
                "  if(rc.width>0&&rc.height>0&&t.length>0&&t.length<40)r.push(t);" +
                "});" +
                "return r.join('|');");
            logger.accept("폼 버튼목록: " + btnDebug);
        } catch (Exception ignored) {}

        // ── 주소구분 → 도로명 선택 (단일 검색필드 팝업 사용) ──────────────────
        try {
            Boolean doroClicked = (Boolean) ((JavascriptExecutor) driver).executeScript(
                "var inputs=document.querySelectorAll('input[type=radio]');" +
                "for(var i=0;i<inputs.length;i++){" +
                "  var lbl='';" +
                "  if(inputs[i].id){var lfor=document.querySelector('label[for=\"'+inputs[i].id+'\"]');if(lfor)lbl=lfor.textContent||'';}" +
                "  if(!lbl&&inputs[i].labels&&inputs[i].labels[0])lbl=inputs[i].labels[0].textContent||'';" +
                "  if(!lbl){var sib=inputs[i].nextSibling;while(sib){if(sib.textContent&&sib.textContent.trim()){lbl=sib.textContent;break;}sib=sib.nextSibling;}}" +
                "  if(lbl.trim()==='도로명'){inputs[i].click();return true;}" +
                "}" +
                "return false;");
            logger.accept("도로명 주소구분 선택: " + doroClicked);
            Thread.sleep(600);
        } catch (Exception e) { logger.accept("도로명 주소구분 선택 오류: " + e.getMessage()); }

        // ── 대장구분 선택 (집합 or 일반) - WebDriver native click (React 이벤트 확실 인식) ──
        clickRadioByLabel(driver, daejangGubun, logger);
        Thread.sleep(1200); // React 리렌더링 대기 (대장종류 옵션 변경됨)
        saveScreenshot(driver, "gov24_after_gubun_select");

        // 대장종류: 전유부 항상 선택 (집합 선택 후 옵션이 바뀐 뒤 클릭)
        clickRadioByLabel(driver, "전유부", logger);
        Thread.sleep(600);
        Set<String> handlesBeforeAddr = driver.getWindowHandles();
        boolean addrBtnClicked = false;
        WebElement addrSearchBtn = null;
        // 0순위: 정확히 '검색' 텍스트인 button/a 탐색 (header의 '통합검색'과 구분)
        try {
            List<WebElement> exactSearchBtns = driver.findElements(By.xpath(
                "//button[normalize-space(.)='검색'] | " +
                "//a[normalize-space(.)='검색'] | " +
                "//input[@type='button' and normalize-space(@value)='검색']"));
            for (WebElement el : exactSearchBtns) {
                try {
                    if (el.isDisplayed()) {
                        addrSearchBtn = el;
                        logger.accept("'검색' 정확매칭 발견: " + el.getTagName() + " / " + el.getText().replace("\n"," "));
                        break;
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception e) { logger.accept("'검색' 정확매칭 오류: " + e.getMessage()); }
        // 1순위: 건물번호검색 버튼 (button/a/input 모두 시도, "건물번호란?" 링크는 검색 텍스트 포함 여부로 구분)
        try {
            List<WebElement> xpathCandidates = driver.findElements(By.xpath(
                "//button[contains(normalize-space(.),'건물번호검색')] | " +
                "//a[contains(normalize-space(.),'건물번호검색')] | " +
                "//input[@type='button' and contains(@value,'건물번호검색')] | " +
                "//input[@type='submit' and contains(@value,'건물번호검색')]"));
            for (WebElement el : xpathCandidates) {
                try {
                    if (el.isDisplayed()) {
                        addrSearchBtn = el;
                        logger.accept("건물번호검색 버튼 발견: " + el.getTagName() + " / " + el.getText().replace("\n"," "));
                        break;
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            logger.accept("XPath 건물번호검색 탐색 오류: " + e.getMessage());
        }
        // 2순위: "주소 검색" 관련 버튼 (button/a/input)
        if (addrSearchBtn == null) {
            try {
                List<WebElement> xpathCandidates2 = driver.findElements(By.xpath(
                    "//button[contains(normalize-space(.),'주소') and contains(normalize-space(.),'검색')] | " +
                    "//button[contains(normalize-space(.),'소재지')] | " +
                    "//a[contains(normalize-space(.),'소재지') and contains(normalize-space(.),'검색')] | " +
                    "//input[@type='button' and (contains(@value,'주소') or contains(@value,'소재지'))]"));
                for (WebElement el : xpathCandidates2) {
                    try { if (el.isDisplayed()) { addrSearchBtn = el; logger.accept("소재지검색 버튼 발견: " + el.getTagName() + " " + el.getText()); break; } } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}
        }
        // 3순위: 주소찾기/소재지번 텍스트 버튼 탐색 (button/a/input)
        if (addrSearchBtn == null) {
            try {
                List<WebElement> textBtns = driver.findElements(By.xpath(
                    "//button[contains(normalize-space(.),'주소찾기')] | " +
                    "//button[contains(normalize-space(.),'소재지번')] | " +
                    "//button[contains(normalize-space(.),'주소검색')] | " +
                    "//a[contains(normalize-space(.),'주소찾기')] | " +
                    "//a[contains(normalize-space(.),'소재지번')] | " +
                    "//a[contains(normalize-space(.),'주소검색')] | " +
                    "//input[@type='button' and (contains(@value,'주소찾기') or contains(@value,'소재지번') or contains(@value,'주소검색'))]"));
                for (WebElement el : textBtns) {
                    try { if (el.isDisplayed()) { addrSearchBtn = el; logger.accept("주소찾기 XPath 탐색 성공: " + el.getTagName() + " " + el.getText()); break; } } catch (Exception ignored) {}
                }
            } catch (Exception e) { logger.accept("주소찾기 XPath 오류: " + e.getMessage()); }
        }
        // 4순위: JS로 address input(readonly/disabled) 옆 검색 버튼 찾기 (절대 Y>300으로 헤더 제외)
        if (addrSearchBtn == null) {
            try {
                WebElement jsBtn = (WebElement) ((JavascriptExecutor) driver).executeScript(
                    "var scrollY=window.pageYOffset||document.documentElement.scrollTop;" +
                    "var inputs = document.querySelectorAll('input[placeholder*=\"주소\"],input[readonly]:not([type=hidden]),input[disabled]:not([type=hidden])');" +
                    "for (var i = 0; i < inputs.length; i++) {" +
                    "  var ir = inputs[i].getBoundingClientRect();" +
                    "  var absTop = ir.top + scrollY;" +
                    "  if (ir.height === 0 || absTop < 300) continue;" +
                    "  var p = inputs[i].parentElement;" +
                    "  for (var d = 0; d < 4 && p; d++, p = p.parentElement) {" +
                    "    var btns = p.querySelectorAll('button,input[type=button]');" +
                    "    for (var b = 0; b < btns.length; b++) {" +
                    "      var r = btns[b].getBoundingClientRect();" +
                    "      if (r.width > 0 && r.height > 0 && r.top+scrollY > 300) return btns[b];" +
                    "    }" +
                    "  }" +
                    "}" +
                    "return null;");
                if (jsBtn != null) { addrSearchBtn = jsBtn; logger.accept("주소검색 버튼 JS 탐색 성공 (absY>300)"); }
            } catch (Exception e) { logger.accept("JS 주소버튼 탐색 오류: " + e.getMessage()); }
        }
        // 5순위: disabled input(주소 표시필드) 옆 버튼 탐색 - placeholder/text 비교 없이 위치만으로 탐색
        if (addrSearchBtn == null) {
            try {
                WebElement nearbyBtn = (WebElement) ((JavascriptExecutor) driver).executeScript(
                    "var scrollY=window.pageYOffset||document.documentElement.scrollTop;" +
                    "var inputs=document.querySelectorAll('input[disabled]:not([type=hidden])');" +
                    "for(var i=0;i<inputs.length;i++){" +
                    "  if(!inputs[i].placeholder)continue;" +
                    "  var ir=inputs[i].getBoundingClientRect();" +
                    "  if(ir.height===0||ir.top+scrollY<300)continue;" +
                    "  var p=inputs[i].parentElement;" +
                    "  for(var d=0;d<5&&p;d++,p=p.parentElement){" +
                    "    var btns=p.querySelectorAll('button');" +
                    "    for(var b=0;b<btns.length;b++){" +
                    "      var r=btns[b].getBoundingClientRect();" +
                    "      if(r.width>0&&r.height>0&&r.top+scrollY>300)return btns[b];" +
                    "    }" +
                    "  }" +
                    "}" +
                    "return null;");
                if (nearbyBtn != null) { addrSearchBtn = nearbyBtn; logger.accept("disabled input 옆 버튼 발견 (5순위)"); }
            } catch (Exception e) { logger.accept("5순위 버튼 탐색 오류: " + e.getMessage()); }
        }
        String urlBeforeAddrClick = "";
        try { urlBeforeAddrClick = driver.getCurrentUrl(); } catch (Exception ignored) {}
        if (addrSearchBtn != null) {
            logger.accept("주소검색 버튼 태그/텍스트: " + addrSearchBtn.getTagName() + " / " + (addrSearchBtn.getText() + " " + safeAttr(addrSearchBtn, "value")).trim());
            try {
                ((JavascriptExecutor) driver).executeScript("arguments[0].scrollIntoView({block:'center'});", addrSearchBtn);
                Thread.sleep(300);
                addrSearchBtn.click();
                addrBtnClicked = true;
                logger.accept("주소검색 버튼 클릭 성공");
            } catch (Exception e) {
                try {
                    ((JavascriptExecutor) driver).executeScript("arguments[0].click();", addrSearchBtn);
                    addrBtnClicked = true;
                    logger.accept("주소검색 버튼 JS클릭 성공");
                } catch (Exception e2) {
                    logger.accept("주소검색 버튼 클릭 실패: " + e2.getMessage());
                }
            }
        } else {
            logger.accept("주소검색 버튼 없음 (모든 탐색 실패)");
        }
        Thread.sleep(2000);
        dismissBrowserAlert(driver);
        String urlAfterAddrClick = "";
        try { urlAfterAddrClick = driver.getCurrentUrl(); } catch (Exception ignored) {}
        logger.accept("주소버튼 클릭 전후 URL: [" + urlBeforeAddrClick + "] → [" + urlAfterAddrClick + "]");

        // 클릭 후 URL이 바뀌었으면 잘못된 버튼 클릭 감지 (네비게이션 발생)
        if (addrBtnClicked && !urlBeforeAddrClick.equals(urlAfterAddrClick) && !urlAfterAddrClick.contains("popup") && !urlAfterAddrClick.contains("Popup")) {
            logger.accept("★ 잘못된 버튼으로 페이지 이탈 감지! URL 변경: " + urlAfterAddrClick);
            saveScreenshot(driver, "gov24_wrong_btn_navigation");
        }

        Set<String> handlesAfterAddr = driver.getWindowHandles();
        boolean newWindowOpened = handlesAfterAddr.size() > handlesBeforeAddr.size();

        if (addrBtnClicked && newWindowOpened) {
            logger.accept("주소 검색 팝업(새창) 열림");
            boolean popupOk = handleBuildingSearchPopup(driver, parts, addressType);
            if (!popupOk) {
                logger.accept("팝업 주소 선택 실패 (대장구분: " + daejangGubun + ") - 재시도 신호");
                saveScreenshot(driver, "gov24_popup_fail_" + daejangGubun);
                return false;
            }
        } else if (addrBtnClicked) {
            logger.accept("주소 검색 버튼 클릭됨 (인페이지 팝업)");
            Thread.sleep(1200);
            saveScreenshot(driver, "gov24_addr_popup_inpage");
            handleInPageAddressPopup(driver, parts);
        } else {
            logger.accept("주소검색 버튼 없음 - 직접 입력 시도");
            WebElement addrInput = findVisibleInput(driver, "주소", "addr", "address", "소재지", "지번", "로명");
            if (addrInput != null) {
                try {
                    addrInput.clear();
                    addrInput.sendKeys(parts.buildingAddress);
                    Thread.sleep(300);
                    addrInput.sendKeys(Keys.ENTER);
                    logger.accept("주소 직접 입력: " + parts.buildingAddress);
                } catch (Exception e) {
                    logger.accept("주소 직접 입력 실패 - JS로 설정: " + e.getClass().getSimpleName());
                    try {
                        ((JavascriptExecutor) driver).executeScript(
                            "arguments[0].removeAttribute('readonly');" +
                            "arguments[0].removeAttribute('disabled');" +
                            "arguments[0].value=arguments[1];" +
                            "arguments[0].dispatchEvent(new Event('input',{bubbles:true}));" +
                            "arguments[0].dispatchEvent(new Event('change',{bubbles:true}));",
                            addrInput, parts.buildingAddress);
                        logger.accept("JS로 주소 설정: " + parts.buildingAddress);
                    } catch (Exception e2) {
                        logger.accept("JS 주소 설정도 실패: " + e2.getMessage());
                    }
                }
            } else {
                logger.accept("주소 입력 필드 없음");
            }
        }
        Thread.sleep(1500);
        dismissBrowserAlert(driver);
        saveScreenshot(driver, "gov24_after_address");
        logger.accept("주소 입력 후 URL: " + driver.getCurrentUrl());
        logger.accept("주소 입력 후 버튼: " + getVisibleButtonTexts(driver));

        // ── 주소 팝업 후 대장구분/대장종류 재선택 - WebDriver native click ───
        Thread.sleep(800);
        clickRadioByLabel(driver, daejangGubun, logger);
        Thread.sleep(1200); // 집합 클릭 후 대장종류 옵션 변경 대기
        saveScreenshot(driver, "gov24_after_reagubun_select");
        clickRadioByLabel(driver, "전유부", logger);
        Thread.sleep(600);

        // ── 건물동명칭 검색 → 선택 ─────────────────────────────────────────────
        Thread.sleep(600);
        saveScreenshot(driver, "gov24_before_dong_search");
        clickBuildingDongSearchAndSelect(driver, parts.dong);

        // ── 호명칭 검색 → 선택 ────────────────────────────────────────────────
        if (!parts.ho.isEmpty()) {
            Thread.sleep(600);
            clickHoSearchAndSelect(driver, parts.ho);
        }

        saveScreenshot(driver, "gov24_before_submit");
        logger.accept("신청 전 버튼: " + getVisibleButtonTexts(driver));

        // 신청하기 클릭 - id=requestBtn 우선, XPath 폴백
        WebElement submitBtn = null;
        try {
            List<WebElement> byId = driver.findElements(By.id("requestBtn"));
            for (WebElement el : byId) {
                if (el.isDisplayed()) { submitBtn = el; break; }
            }
        } catch (Exception ignored) {}
        if (submitBtn == null) {
            try {
                List<WebElement> submitList = driver.findElements(By.xpath(
                    "//button[normalize-space(.)='신청하기'] | " +
                    "//input[@type='button' and normalize-space(@value)='신청하기'] | " +
                    "//input[@type='submit' and normalize-space(@value)='신청하기']"));
                for (WebElement el : submitList) {
                    if (el.isDisplayed()) { submitBtn = el; break; }
                }
            } catch (Exception ignored) {}
        }
        if (submitBtn == null) {
            try {
                List<WebElement> submitList2 = driver.findElements(By.xpath(
                    "//button[contains(normalize-space(.),'신청하기')] | " +
                    "//input[@type='button' and contains(@value,'신청')]"));
                for (WebElement el : submitList2) {
                    if (el.isDisplayed()) { submitBtn = el; break; }
                }
            } catch (Exception ignored) {}
        }
        boolean submitClicked = false;
        if (submitBtn != null) {
            try {
                ((JavascriptExecutor) driver).executeScript("arguments[0].scrollIntoView({block:'center'});", submitBtn);
                Thread.sleep(300);
                submitBtn.click();
                submitClicked = true;
                logger.accept("신청하기 클릭 성공 (XPath)");
            } catch (Exception e) {
                try {
                    ((JavascriptExecutor) driver).executeScript("arguments[0].click();", submitBtn);
                    submitClicked = true;
                    logger.accept("신청하기 JS클릭 성공");
                } catch (Exception e2) {
                    logger.accept("신청하기 클릭 실패: " + e2.getMessage());
                }
            }
        } else {
            logger.accept("신청하기 버튼 없음");
        }
        logger.accept("신청하기 클릭: " + submitClicked);
        Thread.sleep(3000);
        dismissBrowserAlert(driver);
        dismissPopups(driver);
        saveScreenshot(driver, "gov24_after_submit");
        logger.accept("신청 후 URL: " + driver.getCurrentUrl());
        String submitPageText = getPageText(driver);
        logger.accept("신청 후 페이지: " + submitPageText.substring(0, Math.min(200, submitPageText.length())).replaceAll("\\s+", " "));

        // 빠른 실패 감지 (대장구분 불일치 오류)
        if (submitPageText.contains("조회 결과가 없") || submitPageText.contains("해당하는 건물") ||
            submitPageText.contains("대장이 없") || submitPageText.contains("건물이 없") ||
            submitPageText.contains("검색된 결과가 없") || submitPageText.contains("처리할 수 없")) {
            logger.accept("대장구분 오류 감지 (" + daejangGubun + "): " + submitPageText.substring(0, Math.min(80, submitPageText.length())).replaceAll("\\s+", " "));
            return false;
        }

        return downloadOrPrint(driver);
    }

    private boolean handleBuildingSearchPopup(ChromeDriver driver, AddressParts parts, String addrType) throws InterruptedException {
        String mainHandle = driver.getWindowHandle();
        Set<String> handles = driver.getWindowHandles();
        String popupHandle = mainHandle;
        for (String h : handles) { if (!h.equals(mainHandle)) { popupHandle = h; break; } }
        driver.switchTo().window(popupHandle);
        Thread.sleep(2000);
        saveScreenshot(driver, "gov24_building_popup");
        logger.accept("건물 검색 팝업 URL: " + driver.getCurrentUrl());
        logger.accept("건물 팝업 버튼: " + getVisibleButtonTexts(driver));

        // 팝업 초기 input 상태 로그
        logPopupInputs(driver, "팝업 초기");

        boolean anyResultSelected = false;

        // ── STEP 1: 주소 구분 탭 먼저 클릭 (검색 전에 탭 선택) ──────────────
        boolean tabClicked = clickAddressTypeTab(driver, addrType);
        logger.accept(addrType + " 탭 클릭: " + tabClicked);
        Thread.sleep(1500);  // React 렌더링 대기
        saveScreenshot(driver, "gov24_building_popup_after_tab");
        logPopupInputs(driver, "탭 클릭 후");

        // ── STEP 2: 단일 검색 필드에 "동 번지" 입력 ──────────────────────────
        String searchTerm = buildJibunSearchTerm(parts);
        logger.accept("팝업 검색어: " + searchTerm);
        WebElement popupSearchInput = findModalInput(driver);
        if (popupSearchInput == null) {
            // fallback: fillSingleSearchField 방식
            fillSingleSearchField(driver, searchTerm, addrType);
        } else {
            setReactInput(driver, popupSearchInput, searchTerm);
        }

        // ── STEP 3: 검색 버튼 클릭 ────────────────────────────────────────
        clickPopupSearchButton(driver);
        Thread.sleep(2500);
        saveScreenshot(driver, "gov24_building_search_result");
        logger.accept("검색 후 URL: " + driver.getCurrentUrl());
        logger.accept("검색 후 버튼: " + getVisibleButtonTexts(driver));

        // ── STEP 4: 처리기관 또는 결과 행 선택 ────────────────────────────
        try {
            // 결과 디버그
            String resultDebug = (String) ((JavascriptExecutor) driver).executeScript(
                "var r=[];document.querySelectorAll('a,td,li,tr').forEach(function(el){" +
                "  var t=(el.textContent||'').replace(/\\s+/g,' ').trim();" +
                "  var rc=el.getBoundingClientRect();" +
                "  if(rc.width>0&&rc.height>0&&t.length>0&&t.length<60)r.push(el.tagName+':'+t);" +
                "});return r.slice(0,20).join(' | ');");
            logger.accept("팝업 결과 DOM: " + resultDebug);

            String rowClick = (String) ((JavascriptExecutor) driver).executeScript(
                // 1순위: 행정처리기관 링크 (특별시/광역시/동 포함)
                "var els=document.querySelectorAll('a,td,li,tr');" +
                "for(var i=0;i<els.length;i++){" +
                "  var t=(els[i].textContent||'').replace(/\\s+/g,' ').trim();" +
                "  var rc=els[i].getBoundingClientRect();" +
                "  if(rc.width>0&&rc.height>5&&(t.includes('특별시')||t.includes('광역시')||t.includes('동(')||t.includes('면(')||t.includes('읍('))){" +
                "    els[i].click();return '처리기관:'+t.substring(0,50);" +
                "  }" +
                "}" +
                // 2순위: onclick 속성 행
                "var items=document.querySelectorAll('tr[onclick],td[onclick],li[onclick],table a,tbody a');" +
                "for(var i=0;i<items.length;i++){" +
                "  var rc=items[i].getBoundingClientRect();" +
                "  if(rc.width>0&&rc.height>5){items[i].click();return 'row:'+(items[i].textContent||'').trim().substring(0,40);}" +
                "}" +
                "return 'not_found';");
            logger.accept("결과 선택: " + rowClick);

            if (rowClick != null && !rowClick.equals("not_found")) {
                anyResultSelected = true;
                Thread.sleep(1500);
                // 처리기관 목록 등장 → 시/도 이름으로 시작하는 LI 클릭
                String choriClick = (String) ((JavascriptExecutor) driver).executeScript(
                    "function doClick(el){" +
                    "  var btn=el.querySelector('button');" +
                    "  if(btn){btn.click();return true;}" +
                    "  var keys=Object.getOwnPropertyNames(el);" +
                    "  for(var k=0;k<keys.length;k++){" +
                    "    if(keys[k].startsWith('__reactProps$')){" +
                    "      var p=el[keys[k]];" +
                    "      if(p&&typeof p.onClick==='function'){" +
                    "        p.onClick({type:'click',target:el,currentTarget:el,preventDefault:function(){},stopPropagation:function(){}});" +
                    "        return true;" +
                    "      }" +
                    "    }" +
                    "  }" +
                    "  el.click(); return true;" +
                    "}" +
                    "var PROV=/^(\\uC11C\\uC6B8|\\uBD80\\uC0B0|\\uB300\\uAD6C|\\uC778\\uCC9C|\\uAD11\\uC8FC|\\uB300\\uC804|\\uC6B8\\uC0B0|\\uC138\\uC885|\\uACBD\\uAE30|\\uAC15\\uC6D0|\\uCDA9\\uBD81|\\uCDA9\\uB0A8|\\uC804\\uBD81|\\uC804\\uB0A8|\\uACBD\\uBD81|\\uACBD\\uB0A8|\\uC81C\\uC8FC)/;" +
                    "var lis=document.querySelectorAll('li,a,tr,td');" +
                    "for(var i=0;i<lis.length;i++){" +
                    "  var el=lis[i];" +
                    "  var rc=el.getBoundingClientRect();" +
                    "  if(rc.width<5||rc.height<5)continue;" +
                    "  var t=(el.textContent||'').replace(/\\s+/g,' ').trim();" +
                    "  if(t.length<5||t.length>60)continue;" +
                    "  if(PROV.test(t)){doClick(el);return '처리기관:'+t.substring(0,50);}" +
                    "}" +
                    "return '처리기관없음';");
                logger.accept("처리기관 선택: " + choriClick);
                Thread.sleep(1000);
            } else {
                logger.accept("팝업 결과 없음");
            }
        } catch (Exception e) {
            logger.accept("결과 선택 오류: " + e.getMessage());
        }

        logger.accept("건물 결과 선택 완료: " + anyResultSelected);
        Thread.sleep(2000);
        saveScreenshot(driver, "gov24_after_building_select_popup");

        // 팝업 창이 자동으로 닫히길 기다리거나, 메인 창으로 복귀
        for (int w = 0; w < 10; w++) {
            Thread.sleep(500);
            try {
                if (!driver.getWindowHandles().contains(popupHandle)) { break; }
            } catch (Exception ignored) { break; }
        }
        try { driver.switchTo().window(mainHandle); } catch (Exception ignored) {}
        Thread.sleep(1000);
        dismissBrowserAlert(driver);
        saveScreenshot(driver, "gov24_after_building_select");
        logger.accept("건물 선택 후 폼 URL: " + driver.getCurrentUrl());
        logger.accept("건물 선택 후 버튼: " + getVisibleButtonTexts(driver));
        return anyResultSelected;
    }

    private boolean clickAddressTypeTab(ChromeDriver driver, String addrType) {
        // 방법1: JS exact textContent match
        try {
            String jsExact = (String) ((JavascriptExecutor) driver).executeScript(
                "var target=arguments[0];" +
                "var all=document.querySelectorAll('*');" +
                "for(var i=0;i<all.length;i++){" +
                "  var el=all[i];if(el.children.length>2)continue;" +
                "  var t=(el.textContent||'').trim();" +
                "  if(t===target){" +
                "    var rc=el.getBoundingClientRect();" +
                "    if(rc.width>0&&rc.height>0){el.click();return 'ok:'+el.tagName+'.'+el.className;}" +
                "  }" +
                "}" +
                "return 'not_found';", addrType);
            logger.accept(addrType + " 탭 JS exact: " + jsExact);
            if (jsExact != null && jsExact.startsWith("ok:")) return true;
        } catch (Exception ignored) {}

        // 방법2: linkText
        try {
            List<WebElement> lks = driver.findElements(By.linkText(addrType));
            for (WebElement lk : lks) {
                if (lk.isDisplayed()) { lk.click(); logger.accept(addrType + " 탭 linkText 성공"); return true; }
            }
        } catch (Exception ignored) {}

        // 방법3: XPath contains
        try {
            List<WebElement> els = driver.findElements(By.xpath(
                "//*[contains(text(),'" + addrType + "') and string-length(normalize-space(.))<10]"));
            for (WebElement el : els) {
                try {
                    if (el.isDisplayed()) { el.click(); logger.accept(addrType + " 탭 XPath 성공"); return true; }
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}

        // 방법4: JS includes 폴백
        try {
            String jsIncl = (String) ((JavascriptExecutor) driver).executeScript(
                "var target=arguments[0];" +
                "var all=document.querySelectorAll('*');" +
                "for(var i=0;i<all.length;i++){" +
                "  var el=all[i];var t=(el.textContent||'').trim();" +
                "  if(t.indexOf(target)>=0&&t.length<10){" +
                "    var rc=el.getBoundingClientRect();" +
                "    if(rc.width>0&&rc.height>0){el.click();return 'ok:'+el.tagName+'.'+el.className;}" +
                "  }" +
                "}" +
                "return 'not_found';", addrType);
            logger.accept(addrType + " 탭 JS includes: " + jsIncl);
            if (jsIncl != null && jsIncl.startsWith("ok:")) return true;
        } catch (Exception ignored) {}

        return false;
    }

    private boolean fillJibunFields(ChromeDriver driver, AddressParts parts) {
        try {
            List<WebElement> inputs = driver.findElements(
                By.cssSelector("input[type=text],input[type=search],input:not([type])"));
            List<WebElement> editables = new ArrayList<>();
            for (WebElement el : inputs) {
                try {
                    if (el.isDisplayed() && el.isEnabled()
                            && !"true".equals(el.getAttribute("readonly"))
                            && !"true".equals(el.getAttribute("disabled"))) {
                        editables.add(el);
                    }
                } catch (Exception ignored) {}
            }
            logger.accept("지번 입력 필드 수: " + editables.size());

            if (editables.isEmpty()) return false;

            if (editables.size() == 1) {
                editables.get(0).clear();
                editables.get(0).sendKeys(parts.buildingAddress);
                logger.accept("지번 단일 필드 입력: " + parts.buildingAddress);
                return true;
            }

            // 2개 이상: placeholder/id/name으로 법정동/본번/부번 구분
            WebElement dongField = null, mainField = null, subField = null;
            for (WebElement el : editables) {
                String ph = nvl(el.getAttribute("placeholder")).toLowerCase();
                String id = nvl(el.getAttribute("id")).toLowerCase();
                String nm = nvl(el.getAttribute("name")).toLowerCase();
                String hint = ph + " " + id + " " + nm;
                if (dongField == null && (hint.contains("동") || hint.contains("dong") || hint.contains("법정"))) {
                    dongField = el;
                } else if (mainField == null && (hint.contains("본번") || hint.contains("main") || hint.contains("번지"))) {
                    mainField = el;
                } else if (subField == null && (hint.contains("부번") || hint.contains("sub") || hint.contains("산"))) {
                    subField = el;
                }
            }
            // 힌트 매칭 실패 시 순서대로 배정
            if (dongField == null) dongField = editables.get(0);
            if (mainField == null && editables.size() >= 2) mainField = editables.get(1);
            if (subField == null && editables.size() >= 3) subField = editables.get(2);

            dongField.clear(); dongField.sendKeys(parts.jibunDong);
            logger.accept("법정동 입력: " + parts.jibunDong);
            if (mainField != null && !parts.jibunMain.isEmpty()) {
                mainField.clear(); mainField.sendKeys(parts.jibunMain);
                logger.accept("본번 입력: " + parts.jibunMain);
            }
            if (subField != null && !"0".equals(parts.jibunSub) && !parts.jibunSub.isEmpty()) {
                subField.clear(); subField.sendKeys(parts.jibunSub);
                logger.accept("부번 입력: " + parts.jibunSub);
            }
            return true;
        } catch (Exception e) { logger.accept("지번 필드 입력 오류: " + e.getMessage()); }
        return false;
    }

    private void fillSingleSearchField(ChromeDriver driver, String searchTerm, String addrType) {
        try {
            List<WebElement> inputs = driver.findElements(
                By.cssSelector("input[type=text],input[type=search],input:not([type])"));
            for (WebElement el : inputs) {
                try {
                    if (el.isDisplayed() && el.isEnabled()
                            && !"true".equals(el.getAttribute("readonly"))
                            && !"true".equals(el.getAttribute("disabled"))) {
                        el.clear(); el.sendKeys(searchTerm);
                        logger.accept("검색어 입력: " + searchTerm + " [" + addrType + "]");
                        return;
                    }
                } catch (Exception ignored) {}
            }
            logger.accept("검색 입력 필드 없음");
        } catch (Exception e) { logger.accept("단일 필드 입력 오류: " + e.getMessage()); }
    }

    private void clickPopupSearchButton(ChromeDriver driver) {
        try {
            List<WebElement> searchBtns = driver.findElements(By.xpath(
                "//button[normalize-space(.)='검색'] | " +
                "//input[@type='button' and normalize-space(@value)='검색'] | " +
                "//input[@type='submit' and normalize-space(@value)='검색']"));
            for (WebElement sb : searchBtns) {
                if (sb.isDisplayed()) { sb.click(); logger.accept("검색 버튼 클릭"); return; }
            }
        } catch (Exception ignored) {}
        // fallback: 편집 가능한 첫 번째 입력 필드에 ENTER
        try {
            List<WebElement> inputs = driver.findElements(
                By.cssSelector("input[type=text],input[type=search],input:not([type])"));
            for (WebElement el : inputs) {
                try {
                    if (el.isDisplayed() && el.isEnabled()) {
                        el.sendKeys(Keys.ENTER); logger.accept("검색버튼 없음 - ENTER 사용"); return;
                    }
                } catch (Exception ignored2) {}
            }
        } catch (Exception ignored) {}
        logger.accept("검색 버튼 클릭 실패");
    }

    private boolean selectHoInPopup(ChromeDriver driver, String ho) {
        // select 드롭다운에서 호 선택
        try {
            List<WebElement> selects = driver.findElements(By.tagName("select"));
            for (WebElement sel : selects) {
                try {
                    if (!sel.isDisplayed()) continue;
                    org.openqa.selenium.support.ui.Select selectEl = new org.openqa.selenium.support.ui.Select(sel);
                    for (WebElement opt : selectEl.getOptions()) {
                        if (opt.getText().replaceAll("[^0-9]", "").equals(ho)) {
                            selectEl.selectByVisibleText(opt.getText());
                            logger.accept("팝업 호 드롭다운 선택: " + opt.getText());
                            return true;
                        }
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        // 클릭 가능한 행/링크에서 호 번호 매칭
        try {
            String hoClick = (String) ((JavascriptExecutor) driver).executeScript(
                "var ho=arguments[0];" +
                "var items=document.querySelectorAll('tr[onclick],td[onclick],li[onclick],table a,tbody a');" +
                "for(var i=0;i<items.length;i++){" +
                "  var t=(items[i].textContent||'').trim();" +
                "  if(t.includes(ho+'호')||t.includes(ho+' 호')){" +
                "    var rc=items[i].getBoundingClientRect();" +
                "    if(rc.width>0&&rc.height>0){items[i].click();return 'ok:'+t.substring(0,30);}" +
                "  }" +
                "}" +
                "return 'not_found';", ho);
            logger.accept("팝업 호 클릭: " + hoClick);
            return hoClick != null && hoClick.startsWith("ok:");
        } catch (Exception e) { logger.accept("팝업 호 선택 오류: " + e.getMessage()); }
        return false;
    }

    private void handleInPageAddressPopup(ChromeDriver driver, AddressParts parts) throws InterruptedException {
        // plus.gov.kr 주소 검색: in-page 팝업, 단일 텍스트 필드 (ph=도로명+건물번호)
        Thread.sleep(1800);
        saveScreenshot(driver, "gov24_addr_popup_inpage");
        logPopupInputs(driver, "인페이지 팝업");

        String searchTerm = buildJibunSearchTerm(parts);
        logger.accept("주소 검색어: " + searchTerm);

        // 팝업 검색 input: placeholder에 "도로명" 또는 "지번" 포함된 editable input
        WebElement searchInput = (WebElement) ((JavascriptExecutor) driver).executeScript(
            "var inputs=document.querySelectorAll('input[type=text],input:not([type])');" +
            "for(var i=inputs.length-1;i>=0;i--){" +
            "  var ph=inputs[i].placeholder||'';" +
            "  if((ph.includes('도로명')||ph.includes('지번')||ph.includes('건물번호'))&&!inputs[i].readOnly&&!inputs[i].disabled){" +
            "    var rc=inputs[i].getBoundingClientRect();" +
            "    if(rc.width>50&&rc.height>0)return inputs[i];" +
            "  }" +
            "}" +
            "return null;");
        if (searchInput == null) {
            searchInput = findModalInput(driver);
        }
        if (searchInput == null) {
            logger.accept("팝업 검색 input 없음");
            return;
        }

        setReactInput(driver, searchInput, searchTerm);
        Thread.sleep(500);
        saveScreenshot(driver, "gov24_popup_before_search");

        // 팝업 컨테이너 내의 검색 버튼 먼저 시도 → 없으면 Enter
        final WebElement si = searchInput;
        Boolean searchBtnClicked = (Boolean) ((JavascriptExecutor) driver).executeScript(
            "var inp=arguments[0];" +
            "var p=inp.parentElement;" +
            "for(var d=0;d<6&&p;d++,p=p.parentElement){" +
            "  var btns=p.querySelectorAll('button');" +
            "  for(var i=0;i<btns.length;i++){" +
            "    var t=(btns[i].textContent||'').trim();" +
            "    var rc=btns[i].getBoundingClientRect();" +
            "    if(rc.width>0&&rc.height>0&&(t==='검색'||t==='조회')){btns[i].click();return true;}" +
            "  }" +
            "}" +
            "return false;", si);
        logger.accept("팝업 검색 버튼 클릭: " + searchBtnClicked);
        if (!Boolean.TRUE.equals(searchBtnClicked)) {
            searchInput.sendKeys(Keys.RETURN);
            logger.accept("팝업 검색 ENTER");
        }
        Thread.sleep(3000);
        saveScreenshot(driver, "gov24_popup_search_result");

        // 팝업 컨테이너 구조 디버그: 검색 input 기준 상위 컨테이너의 자식 요소 확인
        final String jibunDongFinal = parts.jibunDong;
        String popupStructure = (String) ((JavascriptExecutor) driver).executeScript(
            "var inp=null,inputs=document.querySelectorAll('input');" +
            "for(var i=0;i<inputs.length;i++){var ph=inputs[i].placeholder||'';" +
            "  if((ph.includes('도로명')||ph.includes('지번'))&&!inputs[i].readOnly){inp=inputs[i];break;}}" +
            "if(!inp)return 'no_input';" +
            "var p=inp; var r=[];" +
            "for(var d=0;d<20&&p;d++){" +
            "  p=p.parentElement; if(!p)break;" +
            "  var children=p.querySelectorAll('li,a,button,div[onclick],span[onclick]');" +
            "  var visible=[];" +
            "  children.forEach(function(c){" +
            "    var rc=c.getBoundingClientRect();" +
            "    if(rc.width>5&&rc.height>5){" +
            "      var t=(c.textContent||'').replace(/\\s+/g,' ').trim();" +
            "      if(t.length>2&&t.length<200&&!t.includes('목록닫기'))visible.push(c.tagName+'['+rc.width+'x'+rc.height+']:'+t.substring(0,50));" +
            "    }" +
            "  });" +
            "  if(visible.length>2&&visible.length<30){r.push('d'+d+'('+p.tagName+'):'+visible.join('|'));break;}" +
            "}" +
            "return r.join('\\n');");
        logger.accept("팝업구조: " + popupStructure);

        // 결과 클릭: 자식 요소 우선 + React props + native click 다층 전략
        String agencyClicked = (String) ((JavascriptExecutor) driver).executeScript(
            "var jd=arguments[0]; var st=arguments[1];" +
            // 헬퍼: React onClick 직접 호출
            "function reactClick(el){" +
            "  var keys=Object.getOwnPropertyNames(el);" +
            "  for(var k=0;k<keys.length;k++){" +
            "    var key=keys[k];" +
            "    if(key.startsWith('__reactProps$')||key.startsWith('__reactEventHandlers$')){" +
            "      var props=el[key];" +
            "      if(props&&typeof props.onClick==='function'){" +
            "        props.onClick({type:'click',target:el,currentTarget:el," +
            "          preventDefault:function(){},stopPropagation:function(){}});" +
            "        return true;" +
            "      }" +
            "    }" +
            "  }" +
            "  return false;" +
            "}" +
            // 헬퍼: 요소 클릭 (자식 a/button 우선, React props, native 순)
            "function tryClick(el){" +
            "  var t=(el.textContent||'').replace(/\\s+/g,' ').trim();" +
            "  var inner=el.querySelector('a,button');" +
            "  if(inner){inner.click();return '자식:'+t.substring(0,40);}" +
            "  if(reactClick(el))return 'react:'+t.substring(0,40);" +
            "  el.click();return 'native:'+t.substring(0,40);" +
            "}" +
            // 팝업 input 찾기
            "var inp=null,inputs=document.querySelectorAll('input');" +
            "for(var i=0;i<inputs.length;i++){var ph=inputs[i].placeholder||'';" +
            "  if((ph.includes('도로명')||ph.includes('지번'))&&!inputs[i].readOnly){inp=inputs[i];break;}}" +
            // Pass 1: 팝업 컨테이너 내 주소 포함 결과 항목 (input 상위 최대 20레벨)
            "if(inp){" +
            "  var p=inp;" +
            "  for(var d=0;d<20&&p;d++){" +
            "    p=p.parentElement; if(!p)break;" +
            "    var items=p.querySelectorAll('li,a,button,div[onclick]');" +
            "    for(var i=0;i<items.length;i++){" +
            "      var el=items[i];" +
            "      var rc=el.getBoundingClientRect();" +
            "      if(rc.width<5||rc.height<5)continue;" +
            "      var t=(el.textContent||'').replace(/\\s+/g,' ').trim();" +
            "      if(t.includes('목록닫기')||t.includes('검색'))continue;" +
            "      if(t.includes(st)||t.includes(jd)){" +
            "        return tryClick(el);" +
            "      }" +
            "    }" +
            "  }" +
            "}" +
            // Pass 2: 전체 DOM에서 jibunDong/searchTerm 패턴 찾기 - a,li 태그 우선
            "var prio=['a','li','button','td','tr','div'];" +
            "for(var p=0;p<prio.length;p++){" +
            "  var els=document.querySelectorAll(prio[p]);" +
            "  for(var i=0;i<els.length;i++){" +
            "    var el=els[i];" +
            "    var rc=el.getBoundingClientRect();" +
            "    if(rc.width<5||rc.height<5)continue;" +
            "    var t=(el.textContent||'').replace(/\\s+/g,' ').trim();" +
            "    if(t.length<10||t.length>300)continue;" +
            "    if(t.includes('닫기')||t.includes('검색')||t.includes('목록닫기'))continue;" +
            "    var nm=el.getAttribute('name')||'';" +
            "    if(nm.includes('datLnkBtn')||nm.includes('bldgAddrBtn'))continue;" +
            "    if(t.includes(st)||t.includes(jd)){" +
            "      return tryClick(el);" +
            "    }" +
            "  }" +
            "}" +
            "return 'not_found';", jibunDongFinal, searchTerm);
        logger.accept("처리기관 선택(1단계-주소): " + agencyClicked);
        Thread.sleep(2000);
        saveScreenshot(driver, "gov24_after_addr_select");

        // 2단계: 주소 선택 후 처리기관 목록 등장 → 시/도 이름으로 시작하는 LI 클릭
        // doClick: button 자식 → React props → native 순서 (stage1 tryClick과 동일)
        String choriClicked = (String) ((JavascriptExecutor) driver).executeScript(
            "function doClick(el){" +
            "  var btn=el.querySelector('button');" +
            "  if(btn){btn.click();return true;}" +
            "  var keys=Object.getOwnPropertyNames(el);" +
            "  for(var k=0;k<keys.length;k++){" +
            "    if(keys[k].startsWith('__reactProps$')){" +
            "      var p=el[keys[k]];" +
            "      if(p&&typeof p.onClick==='function'){" +
            "        p.onClick({type:'click',target:el,currentTarget:el,preventDefault:function(){},stopPropagation:function(){}});" +
            "        return true;" +
            "      }" +
            "    }" +
            "  }" +
            "  el.click(); return true;" +
            "}" +
            // 시/도 이름으로 시작하는 LI = 처리기관 항목 (내비게이션과 구별 가능)
            "var PROV=/^(\\uC11C\\uC6B8|\\uBD80\\uC0B0|\\uB300\\uAD6C|\\uC778\\uCC9C|\\uAD11\\uC8FC|\\uB300\\uC804|\\uC6B8\\uC0B0|\\uC138\\uC885|\\uACBD\\uAE30|\\uAC15\\uC6D0|\\uCDA9\\uBD81|\\uCDA9\\uB0A8|\\uC804\\uBD81|\\uC804\\uB0A8|\\uACBD\\uBD81|\\uACBD\\uB0A8|\\uC81C\\uC8FC)/;" +
            "var lis=document.querySelectorAll('li');" +
            "for(var i=0;i<lis.length;i++){" +
            "  var el=lis[i];" +
            "  var rc=el.getBoundingClientRect();" +
            "  if(rc.width<5||rc.height<5)continue;" +
            "  var t=(el.textContent||'').replace(/\\s+/g,' ').trim();" +
            "  if(t.length<5||t.length>60)continue;" +
            "  if(PROV.test(t)){doClick(el);return '처리기관:'+t.substring(0,50);}" +
            "}" +
            "return '처리기관없음';");
        logger.accept("처리기관 선택(2단계-기관): " + choriClicked);
        Thread.sleep(1500);
        saveScreenshot(driver, "gov24_after_agency_select");

        // 주소 필드 채워졌는지 확인
        try {
            String addrVal = (String) ((JavascriptExecutor) driver).executeScript(
                "var el=document.querySelector('input[name$=\"_address1\"]');" +
                "return el?el.value:'';");
            logger.accept("주소 필드 확인: '" + addrVal + "'");
        } catch (Exception ignored) {}
    }

    private List<WebElement> getEditableInputs(ChromeDriver driver) {
        List<WebElement> result = new ArrayList<>();
        try {
            List<WebElement> all = driver.findElements(By.cssSelector("input[type=text],input[type=search],input:not([type])"));
            for (WebElement el : all) {
                try {
                    if (!el.isDisplayed() || !el.isEnabled()) continue;
                    String ro = el.getAttribute("readonly");
                    String dis = el.getAttribute("disabled");
                    if ("true".equalsIgnoreCase(ro) || "readonly".equalsIgnoreCase(ro)) continue;
                    if ("true".equalsIgnoreCase(dis) || "disabled".equalsIgnoreCase(dis)) continue;
                    result.add(el);
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return result;
    }

    private void clickBuildingDongSearchAndSelect(ChromeDriver driver, String dong) throws InterruptedException {
        Set<String> beforeHandles = driver.getWindowHandles();
        String mainHandle = driver.getWindowHandle();

        // datLnkBtn 패턴으로 동명칭 검색 버튼 클릭 (input[type=button][name$='_datLnkBtn'] 첫 번째)
        boolean clicked = false;
        try {
            clicked = Boolean.TRUE.equals(((JavascriptExecutor) driver).executeScript(
                "var btns=document.querySelectorAll('input[type=button][name$=\"_datLnkBtn\"]');" +
                "for(var i=0;i<btns.length;i++){" +
                "  var rc=btns[i].getBoundingClientRect();" +
                "  if(rc.width>0&&rc.height>0){btns[i].scrollIntoView({block:'center'});btns[i].click();return true;}" +
                "}" +
                // 폴백: '검색' 텍스트인 <button> (팝업 내 버튼)
                "var b2=document.querySelectorAll('button');" +
                "for(var i=0;i<b2.length;i++){" +
                "  var t=(b2[i].textContent||'').trim();" +
                "  var rc=b2[i].getBoundingClientRect();" +
                "  if(rc.width>0&&rc.height>0&&t==='검색'){b2[i].click();return true;}" +
                "}" +
                "return false;"));
            logger.accept("건물동명칭 검색 클릭: " + clicked);
        } catch (Exception e) { logger.accept("건물동명칭 검색 클릭 오류: " + e.getMessage()); return; }

        Thread.sleep(1500);
        Set<String> afterHandles = driver.getWindowHandles();

        if (afterHandles.size() > beforeHandles.size()) {
            // 새 창에서 동명칭 목록 선택
            for (String h : afterHandles) {
                if (!beforeHandles.contains(h)) {
                    driver.switchTo().window(h);
                    Thread.sleep(1500);
                    logger.accept("동명칭 팝업창 URL: " + driver.getCurrentUrl());
                    saveScreenshot(driver, "gov24_dong_popup_window");
                    String result = (String) ((JavascriptExecutor) driver).executeScript(
                        // dong 매칭: 행에 dong+"동" 텍스트가 있으면 그 행의 '선택' 버튼 클릭
                        "var dong=arguments[0];" +
                        "if(dong){" +
                        "  var rows=document.querySelectorAll('tr');" +
                        "  for(var r=0;r<rows.length;r++){" +
                        "    var rt=(rows[r].textContent||'').trim();" +
                        "    if(rt.includes(dong+'동')){" +
                        "      var sb=rows[r].querySelector('button,input[type=button],input[type=submit]');" +
                        "      if(sb&&(sb.textContent||sb.value||'').trim()==='선택'){sb.click();return '행매칭:'+rt.substring(0,40);}" +
                        "      if(rows[r].onclick){rows[r].click();return '행onclick:'+rt.substring(0,40);}" +
                        "    }" +
                        "  }" +
                        "}" +
                        // 폴백: 첫 번째 visible '선택' 버튼
                        "var btns=document.querySelectorAll('button,input[type=button],input[type=submit]');" +
                        "for(var i=0;i<btns.length;i++){" +
                        "  var t=(btns[i].textContent||btns[i].value||'').trim();" +
                        "  var rc=btns[i].getBoundingClientRect();" +
                        "  if(rc.width>0&&rc.height>0&&t==='선택'){btns[i].click();return '선택버튼폴백:'+t;}" +
                        "}" +
                        // 2순위 폴백: onclick 행/링크 (구버전 HTML)
                        "var items=document.querySelectorAll('tr[onclick],td[onclick],a[onclick],tbody a');" +
                        "for(var i=0;i<items.length;i++){" +
                        "  var rc=items[i].getBoundingClientRect();" +
                        "  if(rc.width>0&&rc.height>5){items[i].click();return 'onclick:'+(items[i].textContent||'').trim().substring(0,30);}" +
                        "}" +
                        "return 'not_found';", dong);
                    logger.accept("동명칭 팝업 선택: " + result);
                    Thread.sleep(1000);
                    for (int w = 0; w < 10; w++) {
                        Thread.sleep(500);
                        try { if (!driver.getWindowHandles().contains(h)) break; } catch (Exception ignored) { break; }
                    }
                    try { driver.switchTo().window(mainHandle); } catch (Exception ignored) {}
                    break;
                }
            }
        } else {
            // 인페이지 팝업: 동명칭 선택 — button.list-btn 구조 (HTML 확인 완료)
            Thread.sleep(2500);
            saveScreenshot(driver, "gov24_dong_search_result");
            boolean dongSelected = false;

            // 1순위: button.list-btn 직접 클릭 (dong+"동" 매칭, 없으면 첫 번째 폴백)
            try {
                List<WebElement> listBtns = driver.findElements(By.cssSelector("button.list-btn"));
                logger.accept("동명칭 list-btn 개수: " + listBtns.size());
                if (!listBtns.isEmpty()) {
                    WebElement btn = listBtns.get(0);
                    if (!dong.isEmpty()) {
                        for (WebElement b : listBtns) {
                            if (b.getText().replace("\n", " ").trim().contains(dong + "동")) { btn = b; break; }
                        }
                    }
                    String btnText = btn.getText().replace("\n", " ").trim();
                    ((JavascriptExecutor) driver).executeScript("arguments[0].scrollIntoView({block:'center'});", btn);
                    Thread.sleep(300);
                    new Actions(driver).moveToElement(btn).click().perform();
                    dongSelected = true;
                    logger.accept("동명칭 list-btn Actions.click 완료: " + btnText.substring(0, Math.min(60, btnText.length())));
                }
            } catch (Exception e) {
                logger.accept("동명칭 list-btn 클릭 오류: " + e.getMessage());
            }

            // 2순위: JS fallback (dong+"동" 매칭, 없으면 첫 번째 폴백)
            if (!dongSelected) {
                String result = (String) ((JavascriptExecutor) driver).executeScript(
                    "var dong=arguments[0];" +
                    "var btns=document.querySelectorAll('button.list-btn');" +
                    "if(btns.length>0){" +
                    "  var sel=btns[0];" +
                    "  if(dong){for(var i=0;i<btns.length;i++){var t=(btns[i].textContent||'').trim();if(t.includes(dong+'동')){sel=btns[i];break;}}}" +
                    "  sel.scrollIntoView({block:'center'});sel.click();return 'js:'+btns.length;" +
                    "}" +
                    "return 'not_found';", dong);
                logger.accept("동명칭 JS fallback: " + result);
                if (!"not_found".equals(result)) dongSelected = true;
            }
            logger.accept("동명칭 선택 결과: " + (dongSelected ? "성공" : "실패"));
        }
        Thread.sleep(1500);
    }

    private void clickHoSearchAndSelect(ChromeDriver driver, String ho) throws InterruptedException {
        Set<String> beforeHandles = driver.getWindowHandles();
        String mainHandle = driver.getWindowHandle();

        // 호명칭 검색 버튼: 동명칭 선택 후 동 버튼은 display:none → 남은 마지막 visible datLnkBtn이 호명칭 버튼
        // 폴백: www.gov.kr 구버전은 <button> 태그 "검색" 사용
        boolean clicked = false;
        try {
            clicked = Boolean.TRUE.equals(((JavascriptExecutor) driver).executeScript(
                "var btns=document.querySelectorAll('input[type=button][name$=\"_datLnkBtn\"]');" +
                "var last=null;" +
                "for(var i=0;i<btns.length;i++){" +
                "  var rc=btns[i].getBoundingClientRect();" +
                "  if(rc.width>0&&rc.height>0) last=btns[i];" +
                "}" +
                "if(last){last.scrollIntoView({block:'center'});last.click();return true;}" +
                // 폴백: <button> 텍스트 "검색" 마지막 visible (동 버튼은 이미 hidden)
                "var b2=document.querySelectorAll('button');" +
                "var lastSrc=null;" +
                "for(var i=0;i<b2.length;i++){" +
                "  var t=(b2[i].textContent||'').trim();" +
                "  var rc=b2[i].getBoundingClientRect();" +
                "  if(rc.width>0&&rc.height>0&&t==='검색') lastSrc=b2[i];" +
                "}" +
                "if(lastSrc){lastSrc.scrollIntoView({block:'center'});lastSrc.click();return true;}" +
                "return false;"));
            logger.accept("호명칭 검색 클릭: " + clicked);
        } catch (Exception e) { logger.accept("호명칭 검색 클릭 오류: " + e.getMessage()); return; }

        Thread.sleep(1500);
        Set<String> afterHandles = driver.getWindowHandles();

        if (afterHandles.size() > beforeHandles.size()) {
            // 새 창에서 호명칭 목록 선택
            for (String h : afterHandles) {
                if (!beforeHandles.contains(h)) {
                    driver.switchTo().window(h);
                    Thread.sleep(2000);
                    logger.accept("호명칭 팝업창 URL: " + driver.getCurrentUrl());
                    saveScreenshot(driver, "gov24_ho_popup_window");
                    // 1순위: www.gov.kr 구버전 — ho 번호 포함 행의 "선택" 버튼
                    String result = (String) ((JavascriptExecutor) driver).executeScript(
                        "var ho=arguments[0];" +
                        "var rows=document.querySelectorAll('tr');" +
                        "for(var i=0;i<rows.length;i++){" +
                        "  var rowTxt=(rows[i].textContent||'').replace(/\\s+/g,' ').trim();" +
                        "  if(rowTxt.indexOf(ho)>=0){" +
                        "    var selBtns=rows[i].querySelectorAll('button,input[type=button],input[type=submit]');" +
                        "    for(var j=0;j<selBtns.length;j++){" +
                        "      var bt=(selBtns[j].textContent||selBtns[j].value||'').trim();" +
                        "      var rc=selBtns[j].getBoundingClientRect();" +
                        "      if(rc.width>0&&rc.height>0&&bt==='선택'){selBtns[j].click();return '행선택:'+rowTxt.substring(0,50);}" +
                        "    }" +
                        "  }" +
                        "}" +
                        // 2순위: button.list-btn (plus.gov.kr 신버전)
                        "var btns=document.querySelectorAll('button.list-btn');" +
                        "for(var i=0;i<btns.length;i++){" +
                        "  var t=(btns[i].textContent||'').replace(/\\s+/g,' ').trim();" +
                        "  if(t.indexOf(ho)>=0){btns[i].scrollIntoView({block:'center'});btns[i].click();return 'list-btn-match:'+t.substring(0,40);}" +
                        "}" +
                        "if(btns.length>0){btns[0].scrollIntoView({block:'center'});btns[0].click();return 'list-btn-first:'+btns.length;}" +
                        // 3순위: a,tr,td,li (이전 방식)
                        "var items=document.querySelectorAll('a,tr,td,li');" +
                        "for(var i=0;i<items.length;i++){" +
                        "  var t=(items[i].textContent||'').replace(/\\s+/g,' ').trim();" +
                        "  var rc=items[i].getBoundingClientRect();" +
                        "  if(rc.width>0&&rc.height>5&&t.indexOf(ho)>=0){items[i].click();return 'legacy:'+t.substring(0,20);}" +
                        "}" +
                        "return 'not_found';", ho);
                    logger.accept("호명칭 팝업 선택: " + result);
                    // 실패 시 HTML 덤프
                    if ("not_found".equals(result)) {
                        try {
                            String pageHtml = driver.getPageSource();
                            java.io.File hf = new java.io.File(savePath + java.io.File.separator + "dbg_ho_popup.html");
                            try (java.io.OutputStreamWriter w2 = new java.io.OutputStreamWriter(
                                    new java.io.FileOutputStream(hf), java.nio.charset.StandardCharsets.UTF_8)) {
                                w2.write(pageHtml);
                            }
                            logger.accept("호명칭 팝업창 HTML 저장: " + hf.getAbsolutePath());
                        } catch (Exception ignored2) {}
                    }
                    Thread.sleep(1000);
                    for (int w = 0; w < 10; w++) {
                        Thread.sleep(500);
                        try { if (!driver.getWindowHandles().contains(h)) break; } catch (Exception ignored) { break; }
                    }
                    try { driver.switchTo().window(mainHandle); } catch (Exception ignored) {}
                    break;
                }
            }
        } else {
            // 인페이지 팝업: 호명칭 선택
            Thread.sleep(2000);
            saveScreenshot(driver, "gov24_ho_search_result");

            boolean hoSelected = false;

            List<WebElement> listBtns = driver.findElements(By.cssSelector("#modal_sample_05 button.list-btn"));
            logger.accept("호명칭 버튼 수: " + listBtns.size());
            WebElement targetBtn = null;
            for (WebElement btn : listBtns) {
                String txt = btn.getText().replace("\n", " ").trim();
                if (txt.contains(ho)) { targetBtn = btn; break; }
            }
            logger.accept("호명칭 대상 버튼: " + (targetBtn != null
                ? targetBtn.getText().replace("\n"," ").trim().substring(0, Math.min(30, targetBtn.getText().replace("\n"," ").trim().length()))
                : "없음"));

            if (targetBtn != null) {
                final WebElement finalBtn = targetBtn;

                // 스크롤: footer 위 안전 영역에 버튼 배치
                String scrollInfo = (String) ((JavascriptExecutor) driver).executeScript(
                    "var btn=arguments[0];" +
                    "var el=btn.parentElement,sc=null;" +
                    "while(el&&el!==document.body){var s=window.getComputedStyle(el);" +
                    "  if(s.overflowY==='auto'||s.overflowY==='scroll'){sc=el;break;}el=el.parentElement;}" +
                    "if(!sc){btn.scrollIntoView({block:'center',behavior:'instant'});return 'scrollIntoView';}" +
                    "var scR=sc.getBoundingClientRect();" +
                    "var footer=document.querySelector('#modal_sample_05 .modal-footer');" +
                    "var safeBottom=footer?footer.getBoundingClientRect().top:scR.bottom;" +
                    "var safeCenter=scR.top+(safeBottom-scR.top)/2;" +
                    "var btnR=btn.getBoundingClientRect();" +
                    "sc.scrollTop+=(btnR.top+btnR.height/2)-safeCenter;" +
                    "return 'sc:'+sc.className.substring(0,30)+' safeBot='+Math.round(safeBottom)+' safeCenter='+Math.round(safeCenter);",
                    finalBtn);
                logger.accept("호명칭 스크롤: " + scrollInfo);
                Thread.sleep(400);

                // 방법 1: __vnode.props.onClick 직접 호출 (Vue.js 3 VNode 핸들러)
                try {
                    String vnodeResult = (String) ((JavascriptExecutor) driver).executeScript(
                        "var btn=arguments[0];" +
                        "var r=btn.getBoundingClientRect();" +
                        "var cx=r.left+r.width/2, cy=r.top+r.height/2;" +
                        "var ev=new MouseEvent('click',{bubbles:true,cancelable:true,clientX:cx,clientY:cy});" +
                        // 버튼의 __vnode.props.onClick 확인
                        "if(btn.__vnode&&btn.__vnode.props){" +
                        "  var pkeys=Object.keys(btn.__vnode.props).join(',');" +
                        "  var h=btn.__vnode.props.onClick;" +
                        "  if(h){h(ev);return '__vnode.props.onClick 호출, props='+pkeys;}" +
                        "  return '__vnode.props 있음 but no onClick: '+pkeys;" +
                        "}" +
                        // 부모 LI/__vueParentComponent에서 핸들러 탐색
                        "var el=btn.parentElement;" +
                        "while(el&&el.id!=='modal_sample_05'){" +
                        "  if(el.__vnode&&el.__vnode.props&&el.__vnode.props.onClick){" +
                        "    el.__vnode.props.onClick(ev);" +
                        "    return 'parent __vnode onClick on '+el.tagName+'.'+el.className.substring(0,20);" +
                        "  }" +
                        "  el=el.parentElement;" +
                        "}" +
                        "return 'no __vnode onClick found';",
                        finalBtn);
                    logger.accept("방법1 vnode: " + vnodeResult);
                    Thread.sleep(600);
                    String val1 = (String) ((JavascriptExecutor) driver).executeScript(
                        "var i=document.querySelector('[name=\"I_87A71317\"]');return i?i.value:'';");
                    String pop1 = (String) ((JavascriptExecutor) driver).executeScript(
                        "var m=document.querySelector('#modal_sample_05');return m&&m.classList.contains('on')?'open':'closed';");
                    logger.accept("방법1 후: val='" + val1 + "' popup=" + pop1);
                    if (val1 != null && !val1.isEmpty()) hoSelected = true;
                } catch (Exception e) { logger.accept("방법1 오류: " + e.getMessage()); }

                // 방법 2: Actions.moveToElement().click() + document 이벤트 전파 확인
                if (!hoSelected) {
                    try {
                        ((JavascriptExecutor) driver).executeScript(
                            "window.__capLog=[];window.__bubLog=[];" +
                            "document.addEventListener('click',function(e){" +
                            "  window.__capLog.push('trusted='+e.isTrusted+' tgt='+e.target.tagName+' cancel='+e.cancelBubble);" +
                            "},{capture:true,once:true});" +
                            "document.addEventListener('click',function(e){" +
                            "  window.__bubLog.push('trusted='+e.isTrusted+' tgt='+e.target.tagName+' cancel='+e.cancelBubble);" +
                            "},{capture:false,once:true});");
                        new Actions(driver).moveToElement(finalBtn).click().perform();
                        Thread.sleep(600);
                        String capLog = (String) ((JavascriptExecutor) driver).executeScript("return JSON.stringify(window.__capLog||[]);");
                        String bubLog = (String) ((JavascriptExecutor) driver).executeScript("return JSON.stringify(window.__bubLog||[]);");
                        String val2 = (String) ((JavascriptExecutor) driver).executeScript(
                            "var i=document.querySelector('[name=\"I_87A71317\"]');return i?i.value:'';");
                        String pop2 = (String) ((JavascriptExecutor) driver).executeScript(
                            "var m=document.querySelector('#modal_sample_05');return m&&m.classList.contains('on')?'open':'closed';");
                        logger.accept("방법2 capLog: " + capLog);
                        logger.accept("방법2 bubLog: " + bubLog);
                        logger.accept("방법2 후: val='" + val2 + "' popup=" + pop2);
                        if (val2 != null && !val2.isEmpty()) hoSelected = true;
                    } catch (Exception e) { logger.accept("방법2 오류: " + e.getMessage()); }
                }

                // 방법 3: JS btn.click() — untrusted 클릭 (isTrusted=false)
                if (!hoSelected) {
                    try {
                        ((JavascriptExecutor) driver).executeScript("arguments[0].click();", finalBtn);
                        Thread.sleep(600);
                        String val3 = (String) ((JavascriptExecutor) driver).executeScript(
                            "var i=document.querySelector('[name=\"I_87A71317\"]');return i?i.value:'';");
                        String pop3 = (String) ((JavascriptExecutor) driver).executeScript(
                            "var m=document.querySelector('#modal_sample_05');return m&&m.classList.contains('on')?'open':'closed';");
                        logger.accept("방법3 JS click 후: val='" + val3 + "' popup=" + pop3);
                        if (val3 != null && !val3.isEmpty()) hoSelected = true;
                    } catch (Exception e) { logger.accept("방법3 오류: " + e.getMessage()); }
                }

                // 방법 4: Space 키 (버튼 포커스 후 키보드 활성화)
                if (!hoSelected) {
                    try {
                        finalBtn.sendKeys(Keys.SPACE);
                        Thread.sleep(600);
                        String val4 = (String) ((JavascriptExecutor) driver).executeScript(
                            "var i=document.querySelector('[name=\"I_87A71317\"]');return i?i.value:'';");
                        String pop4 = (String) ((JavascriptExecutor) driver).executeScript(
                            "var m=document.querySelector('#modal_sample_05');return m&&m.classList.contains('on')?'open':'closed';");
                        logger.accept("방법4 Space키 후: val='" + val4 + "' popup=" + pop4);
                        if (val4 != null && !val4.isEmpty()) hoSelected = true;
                    } catch (Exception e) { logger.accept("방법4 오류: " + e.getMessage()); }
                }

                // 방법 5: __vueParentComponent.setupState 메서드 탐색 → 직접 호출
                if (!hoSelected) {
                    try {
                        String compResult = (String) ((JavascriptExecutor) driver).executeScript(
                            "var btn=arguments[0], ho=arguments[1];" +
                            "var el=btn;" +
                            "while(el){" +
                            "  var comp=el.__vueParentComponent;" +
                            "  if(comp){" +
                            "    var ss=comp.setupState||{};" +
                            "    var ssMethods=Object.keys(ss).filter(function(k){return typeof ss[k]==='function';});" +
                            "    var proxy=comp.proxy||{};" +
                            "    var pMethods=Object.keys(proxy).filter(function(k){return typeof proxy[k]==='function';});" +
                            // Try calling select/click/ho related method
                            "    var allMethods=ssMethods.concat(pMethods);" +
                            "    for(var i=0;i<allMethods.length;i++){" +
                            "      var nm=allMethods[i].toLowerCase();" +
                            "      if(nm.includes('select')||nm.includes('click')||nm.includes('choose')||nm.includes('pick')){" +
                            "        try{" +
                            "          (ss[allMethods[i]]||proxy[allMethods[i]])(btn);" +
                            "          return 'called:'+allMethods[i]+' on '+el.tagName+'#'+el.id;" +
                            "        }catch(e2){}" +
                            "      }" +
                            "    }" +
                            "    return 'comp found on '+el.tagName+'#'+el.id+' ss=['+ssMethods.join(',')+'] p=['+pMethods.slice(0,10).join(',')+']';" +
                            "  }" +
                            "  if(el.id==='modal_sample_05')break;" +
                            "  el=el.parentElement;" +
                            "}" +
                            "return 'no comp found';",
                            finalBtn, ho);
                        logger.accept("방법5 comp: " + compResult);
                        Thread.sleep(600);
                        String val5 = (String) ((JavascriptExecutor) driver).executeScript(
                            "var i=document.querySelector('[name=\"I_87A71317\"]');return i?i.value:'';");
                        String pop5 = (String) ((JavascriptExecutor) driver).executeScript(
                            "var m=document.querySelector('#modal_sample_05');return m&&m.classList.contains('on')?'open':'closed';");
                        logger.accept("방법5 후: val='" + val5 + "' popup=" + pop5);
                        if (val5 != null && !val5.isEmpty()) hoSelected = true;
                    } catch (Exception e) { logger.accept("방법5 오류: " + e.getMessage()); }
                }

                // 방법 7: 마지막 span.info ('선택' 텍스트) Actions.click() — 버튼 중앙 대신 선택 셀 직접 클릭
                if (!hoSelected) {
                    try {
                        List<WebElement> infoSpans = finalBtn.findElements(By.cssSelector("span.info"));
                        if (!infoSpans.isEmpty()) {
                            WebElement selectSpan = infoSpans.get(infoSpans.size() - 1);
                            String spanTxt = selectSpan.getText().trim();
                            ((JavascriptExecutor) driver).executeScript("arguments[0].scrollIntoView({block:'center'});", selectSpan);
                            Thread.sleep(300);
                            new Actions(driver).moveToElement(selectSpan).click().perform();
                            Thread.sleep(700);
                            String val7 = (String) ((JavascriptExecutor) driver).executeScript(
                                "var i=document.querySelector('[name=\"I_87A71317\"]');return i?i.value:'';");
                            String pop7 = (String) ((JavascriptExecutor) driver).executeScript(
                                "var m=document.querySelector('#modal_sample_05');return m&&m.classList.contains('on')?'open':'closed';");
                            logger.accept("방법7 선택span('" + spanTxt + "') 후: val='" + val7 + "' popup=" + pop7);
                            if (val7 != null && !val7.isEmpty()) hoSelected = true;
                        } else {
                            logger.accept("방법7: span.info 없음");
                        }
                    } catch (Exception e) { logger.accept("방법7 오류: " + e.getMessage()); }
                }

                // 방법 6: 직접 input 값 설정 + lnkRslt='true' + lnkDatWrap 주입 + 모달 닫기 (최후 수단)
                if (!hoSelected) {
                    try {
                        String directResult = (String) ((JavascriptExecutor) driver).executeScript(
                            "var btn=arguments[0], ho=arguments[1];" +
                            // Debug: read dong state to understand the injection pattern
                            "var dongWrap=document.querySelector('#lnkDatWrap_I_831387CF');" +
                            "var dongDbg=dongWrap?dongWrap.innerHTML.substring(0,120):'null';" +
                            // Set ho display input value
                            "var inp=document.querySelector('[name=\"I_87A71317\"]');" +
                            "if(!inp) return 'no input | dong='+dongDbg;" +
                            "var ns=Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value');" +
                            "if(ns&&ns.set){ns.set.call(inp,ho);}else{inp.value=ho;}" +
                            "inp.dispatchEvent(new Event('input',{bubbles:true}));" +
                            "inp.dispatchEvent(new Event('change',{bubbles:true}));" +
                            // KEY FIX 1: lnkRslt를 'true'로 설정 (폼 유효성 검사가 이 값 확인)
                            "var lr=document.querySelector('[name=\"I_87A71317_lnkRslt\"]');" +
                            "if(lr){" +
                            "  if(ns&&ns.set){ns.set.call(lr,'true');}else{lr.value='true';}" +
                            "  lr.dispatchEvent(new Event('input',{bubbles:true}));" +
                            "  lr.dispatchEvent(new Event('change',{bubbles:true}));" +
                            "}" +
                            // KEY FIX 2: lnkDatWrap에 hidden input 주입 (동 패턴: name=dngHoNm → 호: name=hoNm)
                            "var wrap=document.querySelector('#lnkDatWrap_I_87A71317');" +
                            "if(wrap&&!wrap.querySelector('input[type=hidden]')){" +
                            "  var h=document.createElement('input');" +
                            "  h.type='hidden';h.name='hoNm';h.value=ho;" +
                            "  wrap.appendChild(h);" +
                            "}" +
                            // Close modal
                            "var modal=document.querySelector('#modal_sample_05');" +
                            "if(modal){modal.classList.remove('on');}" +
                            "return 'done | dong='+dongDbg.substring(0,60)+' | lr='+(lr?lr.value:'null')+' | wrap='+(wrap?wrap.innerHTML.substring(0,70):'null')+' | inp='+inp.value;",
                            finalBtn, ho);
                        logger.accept("방법6 직접설정: " + directResult);
                        Thread.sleep(800);
                        String val6 = (String) ((JavascriptExecutor) driver).executeScript(
                            "var i=document.querySelector('[name=\"I_87A71317\"]');return i?i.value:'';");
                        String lnkRsltVal = (String) ((JavascriptExecutor) driver).executeScript(
                            "var lr=document.querySelector('[name=\"I_87A71317_lnkRslt\"]');return lr?lr.value:'null';");
                        String pop6 = (String) ((JavascriptExecutor) driver).executeScript(
                            "var m=document.querySelector('#modal_sample_05');return m&&m.classList.contains('on')?'open':'closed';");
                        logger.accept("방법6 후: val='" + val6 + "' lnkRslt='" + lnkRsltVal + "' popup=" + pop6);
                        if (val6 != null && !val6.isEmpty()) hoSelected = true;
                    } catch (Exception e) { logger.accept("방법6 오류: " + e.getMessage()); }
                }

                // 실패 시 HTML 덤프
                if (!hoSelected) {
                    try {
                        String pageHtml = driver.getPageSource();
                        java.io.File htmlFile = new java.io.File(savePath + java.io.File.separator + "dbg_ho_popup.html");
                        try (java.io.OutputStreamWriter w = new java.io.OutputStreamWriter(
                                new java.io.FileOutputStream(htmlFile), java.nio.charset.StandardCharsets.UTF_8)) {
                            w.write(pageHtml);
                        }
                        logger.accept("호명칭 팝업 HTML 저장: " + htmlFile.getAbsolutePath());
                    } catch (Exception ignored) {}
                }
            } else {
                // 버튼 못 찾은 경우 HTML 덤프
                try {
                    String pageHtml = driver.getPageSource();
                    java.io.File htmlFile = new java.io.File(savePath + java.io.File.separator + "dbg_ho_popup.html");
                    try (java.io.OutputStreamWriter w = new java.io.OutputStreamWriter(
                            new java.io.FileOutputStream(htmlFile), java.nio.charset.StandardCharsets.UTF_8)) {
                        w.write(pageHtml);
                    }
                    logger.accept("호명칭 팝업 HTML 저장: " + htmlFile.getAbsolutePath());
                } catch (Exception ignored) {}
            }

            logger.accept("호명칭 선택 결과: " + (hoSelected ? "성공" : "실패"));
        }
        Thread.sleep(1500);
    }

    /** 라디오 버튼 선택: label for→input 찾아서 input 직접 JS 클릭 (Nuxt/Vue 확실 업데이트) */
    private void clickRadioByLabel(ChromeDriver driver, String target, Consumer<String> logger) {
        // 1순위: for 속성 연결 label → 해당 radio input을 JS로 직접 클릭
        try {
            List<WebElement> labels = driver.findElements(By.xpath(
                "//label[contains(normalize-space(.), '" + target + "') and @for]"));
            for (WebElement lbl : labels) {
                String forId = lbl.getAttribute("for");
                if (forId == null || forId.isEmpty()) continue;
                try {
                    WebElement radio = driver.findElement(By.id(forId));
                    if (!"radio".equals(radio.getAttribute("type"))) continue;
                    // JS 클릭 후 change 이벤트 발생 (Vue.js 상태 업데이트)
                    ((JavascriptExecutor) driver).executeScript(
                        "var el=arguments[0];" +
                        "el.checked=true;" +
                        "el.dispatchEvent(new MouseEvent('click',{bubbles:true,cancelable:true}));" +
                        "el.dispatchEvent(new Event('change',{bubbles:true}));" +
                        "el.dispatchEvent(new Event('input',{bubbles:true}));", radio);
                    logger.accept("라디오 클릭(JS+events): " + target + " id=" + forId);
                    return;
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            logger.accept("라디오 클릭 1순위 오류: " + e.getMessage());
        }
        // 2순위: radio input parent 텍스트 매칭 → 같은 JS 클릭
        try {
            List<WebElement> radios = driver.findElements(By.xpath("//input[@type='radio']"));
            for (WebElement radio : radios) {
                try {
                    WebElement parent = radio.findElement(By.xpath(".."));
                    if (!parent.getText().contains(target)) continue;
                    ((JavascriptExecutor) driver).executeScript(
                        "var el=arguments[0];" +
                        "el.checked=true;" +
                        "el.dispatchEvent(new MouseEvent('click',{bubbles:true,cancelable:true}));" +
                        "el.dispatchEvent(new Event('change',{bubbles:true}));" +
                        "el.dispatchEvent(new Event('input',{bubbles:true}));", radio);
                    logger.accept("라디오 클릭(parent-JS): " + target);
                    return;
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            logger.accept("라디오 클릭 2순위 오류: " + e.getMessage());
        }
        // 3순위: WebDriver Actions.click (최후 수단)
        try {
            List<WebElement> labels2 = driver.findElements(By.xpath(
                "//label[contains(normalize-space(.), '" + target + "') and @for]"));
            for (WebElement lbl : labels2) {
                if (lbl.isDisplayed()) {
                    new Actions(driver).moveToElement(lbl).click().perform();
                    logger.accept("라디오 클릭(Actions-label): " + target);
                    return;
                }
            }
        } catch (Exception e) {
            logger.accept("라디오 클릭 3순위 오류: " + e.getMessage());
        }
        logger.accept("라디오 클릭 실패: " + target);
    }

    private void logPopupInputs(ChromeDriver driver, String label) {
        try {
            String info = (String) ((JavascriptExecutor) driver).executeScript(
                "var r=[];document.querySelectorAll('input').forEach(function(el){" +
                "  var rc=el.getBoundingClientRect();" +
                "  if(rc.width>0&&rc.height>0)r.push('['+el.type+'|id='+el.id+'|name='+el.name+'|ph='+el.placeholder+'|ro='+el.readOnly+'|val='+el.value.substring(0,15)+']');" +
                "});return r.join(' ');");
            logger.accept(label + " inputs: " + info);
        } catch (Exception ignored) {}
    }

    private String buildJibunSearchTerm(AddressParts parts) {
        if (!parts.jibunDong.isEmpty() && !parts.jibunMain.isEmpty()) {
            String term = parts.jibunDong + " " + parts.jibunMain;
            if (!"0".equals(parts.jibunSub) && !parts.jibunSub.isEmpty()) term += "-" + parts.jibunSub;
            return term;
        }
        return parts.buildingAddress;
    }

    private WebElement findModalInput(ChromeDriver driver) {
        try {
            return (WebElement) ((JavascriptExecutor) driver).executeScript(
                // 1순위: role=dialog, dialog 태그, .modal/.popup 안의 input
                "var containers=document.querySelectorAll('[role=dialog],dialog,.modal,.popup,.popup-wrap,.layer,.addr-search,.addr-popup');" +
                "for(var c=0;c<containers.length;c++){" +
                "  var rc=containers[c].getBoundingClientRect();" +
                "  if(rc.width<50||rc.height<50)continue;" +
                "  var inputs=containers[c].querySelectorAll('input[type=text],input[type=search],input:not([type])');" +
                "  for(var i=0;i<inputs.length;i++){" +
                "    var ir=inputs[i].getBoundingClientRect();" +
                "    if(ir.width>0&&ir.height>0&&!inputs[i].readOnly&&!inputs[i].disabled)return inputs[i];" +
                "  }" +
                "}" +
                // 2순위: position:fixed 안에 있는 input (overlay modal)
                "var inputs=Array.from(document.querySelectorAll('input[type=text],input[type=search],input:not([type])'));" +
                "for(var i=0;i<inputs.length;i++){" +
                "  var el=inputs[i];var rc=el.getBoundingClientRect();" +
                "  if(rc.width<50||rc.height===0||el.readOnly||el.disabled)continue;" +
                "  var p=el.parentElement;" +
                "  for(var d=0;d<10&&p;d++,p=p.parentElement){" +
                "    if(window.getComputedStyle(p).position==='fixed'){return el;}" +
                "  }" +
                "}" +
                // 3순위: DOM 뒤쪽의 visible input (팝업은 DOM 끝에 렌더링)
                "for(var i=inputs.length-1;i>=0;i--){" +
                "  var rc=inputs[i].getBoundingClientRect();" +
                "  if(rc.width>50&&rc.height>0&&!inputs[i].readOnly&&!inputs[i].disabled)return inputs[i];" +
                "}" +
                "return null;");
        } catch (Exception e) { logger.accept("findModalInput 오류: " + e.getMessage()); return null; }
    }

    private void setReactInput(ChromeDriver driver, WebElement el, String value) {
        try {
            el.clear();
            el.sendKeys(value);
            // React synthetic event 트리거
            ((JavascriptExecutor) driver).executeScript(
                "var el=arguments[0],v=arguments[1];" +
                "var setter=Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value').set;" +
                "setter.call(el,v);" +
                "el.dispatchEvent(new Event('input',{bubbles:true}));" +
                "el.dispatchEvent(new Event('change',{bubbles:true}));",
                el, value);
            logger.accept("입력 완료: " + value);
        } catch (Exception e) { logger.accept("setReactInput 오류: " + e.getMessage()); }
    }

    private String nvl(String s) { return s != null ? s : ""; }

    private String getVisibleButtonTexts(ChromeDriver driver) {
        try {
            return (String) ((JavascriptExecutor) driver).executeScript(
                "var r=[];document.querySelectorAll('button,input[type=button],input[type=submit],a').forEach(function(e){" +
                "  var t=(e.textContent||e.value||'').replace(/\\s+/g,' ').trim();" +
                "  var rc=e.getBoundingClientRect();" +
                "  if(rc.width>0&&rc.height>0&&t.length>0&&t.length<30)r.push(t);" +
                "});return r.slice(0,20).join('|');");
        } catch (Exception e) { return ""; }
    }

    // ─── 팝업 / 주소 팝업 ────────────────────────────────────────────────

    /** position:fixed 요소 포함하여 닫기 버튼 클릭. offsetParent 대신 getBoundingClientRect 사용. */
    private void dismissPopups(ChromeDriver driver) {
        try {
            ((JavascriptExecutor) driver).executeScript(
                "document.querySelectorAll('button, a').forEach(function(b) {" +
                "  var rect = b.getBoundingClientRect();" +
                "  if (rect.width === 0 && rect.height === 0) return;" +
                "  var t = (b.innerText || b.textContent || '').trim();" +
                "  if (t === '닫기' || t === '확인' || t === '×' || t === 'X' || t === 'close' || t === '오늘 하루 보지 않기') {" +
                "    b.click();" +
                "  }" +
                "});");
            Thread.sleep(400);
            try { driver.findElement(By.tagName("body")).sendKeys(Keys.ESCAPE); } catch (Exception ignored) {}
        } catch (Exception ignored) {}
    }

    private void handleAddressPopup(ChromeDriver driver, String address) throws InterruptedException {
        String mainHandle = driver.getWindowHandle();
        Set<String> handles = driver.getWindowHandles();
        String popupHandle = mainHandle;
        for (String h : handles) { if (!h.equals(mainHandle)) { popupHandle = h; break; } }
        driver.switchTo().window(popupHandle);

        WebElement input = findFirstVisibleInput(driver);
        if (input != null) {
            input.clear();
            input.sendKeys(address);
            input.sendKeys(Keys.ENTER);
            Thread.sleep(2000);
        }
        ((JavascriptExecutor) driver).executeScript(
            "var rows = document.querySelectorAll('tr, li, .result');" +
            "for (var i = 0; i < rows.length; i++) {" +
            "  var links = rows[i].querySelectorAll('a, button');" +
            "  var rect = links.length > 0 ? links[0].getBoundingClientRect() : null;" +
            "  if (rect && rect.width > 0) { links[0].click(); return; }" +
            "}");
        Thread.sleep(1000);
        driver.switchTo().window(mainHandle);
    }

    // ─── 다운로드 ────────────────────────────────────────────────────────

    private boolean downloadOrPrint(ChromeDriver driver) throws InterruptedException {
        saveScreenshot(driver, "05_before_download");
        logger.accept("다운로드/출력 버튼 탐색 중... URL: " + driver.getCurrentUrl());
        String curUrl0 = driver.getCurrentUrl();
        // 최대 90초 대기 (페이지 로딩 포함)
        long end = System.currentTimeMillis() + 90000;
        while (System.currentTimeMillis() < end) {
            String pageText = getPageText(driver);
            String curUrl1 = "";
            try { curUrl1 = driver.getCurrentUrl(); } catch (Exception ignored) {}
            // 결과 페이지 판단: 서비스 개요 페이지(신청방법/신청자격)는 제외
            boolean isServiceOverview = pageText.contains("신청방법") && pageText.contains("신청자격") && pageText.contains("서비스 개요");
            boolean onResultPage = !isServiceOverview && (
                pageText.contains("접수번호") || pageText.contains("처리결과") || pageText.contains("발급문서")
                || pageText.contains("민원결과") || pageText.contains("대장정보") || pageText.contains("문서확인")
                || pageText.contains("신청이 완료") || pageText.contains("접수가 완료")
                || curUrl1.contains("IssueResult") || curUrl1.contains("Result.do") || curUrl1.contains("result"));
            logger.accept("결과페이지여부=" + onResultPage + " (URL=" + curUrl1.substring(Math.max(0, curUrl1.length()-60)) + ")");
            if (onResultPage) {
                saveScreenshot(driver, "gov24_result_page");
                // 문서출력 버튼 탐색: "문서출력" 정확 일치 우선, 소셜 공유 버튼과 혼동 방지
                WebElement dlBtn = null;
                try {
                    // 1순위: 정확히 "문서출력" 텍스트인 버튼/링크
                    List<WebElement> exact = driver.findElements(By.xpath(
                        "//button[normalize-space(.)='문서출력'] | //a[normalize-space(.)='문서출력'] | " +
                        "//input[@type='button' and @value='문서출력']"));
                    for (WebElement el : exact) {
                        if (el.isDisplayed()) { dlBtn = el; break; }
                    }
                    // 2순위: PDF/다운로드 버튼 (소셜 공유 버튼 제외: href에 twitter/facebook/x.com 없는 것만)
                    if (dlBtn == null) {
                        List<WebElement> candidates = driver.findElements(By.xpath(
                            "//button[contains(normalize-space(.),'PDF')] | " +
                            "//button[contains(normalize-space(.),'다운')] | " +
                            "//button[contains(normalize-space(.),'저장')] | " +
                            "//button[contains(normalize-space(.),'문서확인')] | " +
                            "//a[contains(normalize-space(.),'PDF') and not(contains(@href,'twitter')) and not(contains(@href,'facebook')) and not(contains(@href,'x.com'))] | " +
                            "//a[contains(normalize-space(.),'다운') and not(contains(@href,'twitter')) and not(contains(@href,'facebook'))] | " +
                            "//input[@type='button' and (contains(@value,'PDF') or contains(@value,'다운'))]"));
                        for (WebElement el : candidates) {
                            if (el.isDisplayed()) { dlBtn = el; break; }
                        }
                    }
                } catch (Exception ignored) {}
                if (dlBtn != null) {
                    logger.accept("출력/다운로드 버튼 발견: " + dlBtn.getText().trim());
                    Set<String> beforeClick = driver.getWindowHandles();
                    try {
                        ((JavascriptExecutor) driver).executeScript("arguments[0].scrollIntoView({block:'center'});", dlBtn);
                        Thread.sleep(300);
                        dlBtn.click();
                    } catch (Exception e) {
                        ((JavascriptExecutor) driver).executeScript("arguments[0].click();", dlBtn);
                    }
                    logger.accept("클릭 완료, 다운로드 대기...");
                    Thread.sleep(3000);
                    dismissBrowserAlert(driver);
                    // gov.kr 뷰어 탭만 선택 (소셜 공유 탭 제외)
                    String mainHandle = driver.getWindowHandle();
                    Set<String> handles = driver.getWindowHandles();
                    boolean pdfSaved = false;
                    String viewerHandle = null;
                    for (String h : handles) {
                        if (!h.equals(mainHandle) && !beforeClick.contains(h)) {
                            driver.switchTo().window(h);
                            String tabUrl = "";
                            try { tabUrl = driver.getCurrentUrl(); } catch (Exception ignored2) {}
                            if (tabUrl.contains("gov.kr") || tabUrl.contains("ezpdfwv")) {
                                viewerHandle = h;
                            } else {
                                logger.accept("비-정부 탭 닫기: " + tabUrl.substring(0, Math.min(60, tabUrl.length())));
                                try { driver.close(); } catch (Exception ignored2) {}
                            }
                            driver.switchTo().window(mainHandle);
                        }
                    }
                    if (viewerHandle != null) {
                        driver.switchTo().window(viewerHandle);
                        Thread.sleep(3000);
                        String newUrl = driver.getCurrentUrl();
                        logger.accept("새 탭 URL: " + newUrl);
                        saveScreenshot(driver, "gov24_download_tab");
                        // 모든 페이지 렌더링 유도 (스크롤 다운)
                        try {
                            ((JavascriptExecutor) driver).executeScript(
                                "document.body.scrollTop=document.body.scrollHeight;" +
                                "document.documentElement.scrollTop=document.documentElement.scrollHeight;");
                            Thread.sleep(1500);
                        } catch (Exception ignored2) {}
                        // 1순위: CDP Page.printToPDF → PDF 파일 저장
                        try {
                            Map<String, Object> pdfParams = new HashMap<>();
                            pdfParams.put("landscape", false);
                            pdfParams.put("displayHeaderFooter", false);
                            pdfParams.put("printBackground", true);
                            pdfParams.put("preferCSSPageSize", true);
                            @SuppressWarnings("unchecked")
                            Map<String, Object> pdfResult = (Map<String, Object>) driver.executeCdpCommand("Page.printToPDF", pdfParams);
                            String base64Data = (String) pdfResult.get("data");
                            byte[] pdfBytes = java.util.Base64.getDecoder().decode(base64Data);
                            String safeAddr = currentAddress.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
                            if (safeAddr.isEmpty()) safeAddr = "건축물대장";
                            String pdfFileName = savePath + java.io.File.separator + safeAddr + ".pdf";
                            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(pdfFileName)) {
                                fos.write(pdfBytes);
                            }
                            logger.accept("PDF 저장 완료: " + pdfFileName);
                            pdfSaved = true;
                        } catch (Exception cdpEx) {
                            logger.accept("CDP PDF 오류: " + cdpEx.getMessage());
                            // 2순위: 인쇄 버튼 클릭 후 파일 대기
                            try {
                                WebElement printBtn = driver.findElement(By.xpath(
                                    "//*[(self::button or self::a or self::input) and normalize-space(.)='인쇄']"));
                                if (printBtn.isDisplayed()) {
                                    Set<String> printBefore = new File(savePath).list() != null
                                        ? new HashSet<>(Arrays.asList(new File(savePath).list()))
                                        : new HashSet<>();
                                    printBtn.click();
                                    Thread.sleep(3000);
                                    dismissBrowserAlert(driver);
                                    waitForNewFile(printBefore);
                                    pdfSaved = true;
                                    logger.accept("인쇄 버튼으로 저장됨");
                                }
                            } catch (Exception e2) {
                                logger.accept("인쇄 버튼 오류: " + e2.getMessage());
                            }
                        }
                        try { driver.switchTo().window(mainHandle); } catch (Exception ignored2) {}
                    }
                    if (!pdfSaved && viewerHandle != null) {
                        logger.accept("건축물대장 뷰어 열림. PDF 자동저장 실패 - dbg_gov24_download_tab.png 확인");
                        throw new RuntimeException("건축물대장 PDF 자동저장 실패.\n스크린샷(dbg_gov24_download_tab.png)을 확인하세요.");
                    }
                    logger.accept("건축물대장 다운로드 완료.");
                    return true;
                }
            }
            // 신청 완료 후 문서 조회 페이지 탐색
            if (pageText.contains("신청이 완료") || pageText.contains("접수 완료") || pageText.contains("문서를 열람")) {
                logger.accept("신청완료 페이지 감지 - 문서 조회 클릭 시도");
                saveScreenshot(driver, "gov24_apply_complete");
                try {
                    List<WebElement> viewBtns = driver.findElements(By.xpath(
                        "//button[contains(normalize-space(.),'문서')] | " +
                        "//button[contains(normalize-space(.),'확인')] | " +
                        "//button[contains(normalize-space(.),'조회')] | " +
                        "//a[contains(normalize-space(.),'문서')] | " +
                        "//a[contains(normalize-space(.),'확인')]"));
                    for (WebElement el : viewBtns) {
                        if (el.isDisplayed()) { el.click(); Thread.sleep(2000); break; }
                    }
                } catch (Exception ignored) {}
            }
            Thread.sleep(1000);
        }
        saveScreenshot(driver, "gov24_download_failed");
        logger.accept("다운로드 버튼 자동 탐색 실패. 스크린샷 저장됨.");
        logger.accept("현재 URL: " + driver.getCurrentUrl());
        logger.accept("현재 버튼: " + getVisibleButtonTexts(driver));
        Thread.sleep(5000);
        return false;
    }

    private void waitForNewFile(Set<String> beforeSet) throws InterruptedException {
        long end = System.currentTimeMillis() + 30000;
        while (System.currentTimeMillis() < end) {
            Thread.sleep(1000);
            String[] current = new File(savePath).list();
            if (current != null) {
                for (String f : current) {
                    if (!beforeSet.contains(f) && (f.endsWith(".pdf") || f.endsWith(".PDF"))) {
                        logger.accept("저장됨: " + f);
                        return;
                    }
                }
            }
        }
    }

    // ─── 헬퍼 ────────────────────────────────────────────────────────────

    private void clickLoginSubmitButton(ChromeDriver driver, List<WebElement> pwInputs) {
        // Use XPath to find <button> submit only — avoids nav "로그인" <a> link
        try {
            List<WebElement> btns = driver.findElements(By.xpath(
                "//button[normalize-space(text())='로그인'] | //input[@type='submit' and @value='로그인'] | //input[@type='button' and @value='로그인']"));
            for (WebElement b : btns) {
                if (b.isDisplayed()) { b.click(); return; }
            }
        } catch (Exception ignored) {}
        // Fallback: Enter on password field
        if (pwInputs != null) {
            for (WebElement el : pwInputs) {
                try { if (el.isDisplayed()) { el.sendKeys(Keys.ENTER); return; } } catch (Exception ignored) {}
            }
        }
    }

    private void waitForUrl(ChromeDriver driver, int maxMs, String... urlParts) throws InterruptedException {
        long end = System.currentTimeMillis() + maxMs;
        while (System.currentTimeMillis() < end) {
            try {
                String url = driver.getCurrentUrl();
                for (String part : urlParts) {
                    if (url.contains(part)) return;
                }
            } catch (Exception ignored) {}
            Thread.sleep(300);
        }
    }

    private void beepAlert() {
        try {
            java.awt.Toolkit.getDefaultToolkit().beep(); Thread.sleep(300);
            java.awt.Toolkit.getDefaultToolkit().beep(); Thread.sleep(300);
            java.awt.Toolkit.getDefaultToolkit().beep();
        } catch (Exception ignored) {}
    }

    private boolean waitForCaptchaAndEnter(ChromeDriver driver, String tag) throws InterruptedException {
        // 코드는 아무것도 하지 않음 - 사용자가 CAPTCHA 입력 후 로그인 버튼을 직접 누를 때까지 대기
        saveScreenshot(driver, "captcha_screenshot");
        logger.accept("[" + tag + "] ★ CAPTCHA 화면 - CAPTCHA 입력 후 로그인 버튼을 직접 눌러주세요 ★");
        for (int w = 0; w < 450; w++) {
            Thread.sleep(2000);
            if (isLoggedIn(driver)) {
                logger.accept("[" + tag + "] 로그인 완료 감지");
                return true;
            }
            if (w > 0 && w % 15 == 0) {
                try { saveScreenshot(driver, "captcha_screenshot"); } catch (Exception ignored) {}
                logger.accept("[" + tag + "] 로그인 대기 중... (" + (w * 2 / 60) + "분)");
            }
        }
        logger.accept("[" + tag + "] 로그인 대기 타임아웃");
        return false;
    }

    /** 실제 "로그아웃" 링크/버튼이 화면에 보이는 경우만 로그인으로 판단 */
    private boolean isLoggedIn(WebDriver driver) {
        try {
            Boolean result = (Boolean) ((JavascriptExecutor) driver).executeScript(
                "var els=document.querySelectorAll('a,button');" +
                "for(var i=0;i<els.length;i++){" +
                "  var t=(els[i].textContent||'').replace(/\\s+/g,' ').trim();" +
                "  var rc=els[i].getBoundingClientRect();" +
                "  if(rc.width>0&&rc.height>0&&(t==='로그아웃'||t==='로그 아웃'))return true;" +
                "}" +
                "return false;");
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean waitForManualLogin(ChromeDriver driver) throws InterruptedException {
        for (int i = 0; i < 60; i++) {
            Thread.sleep(5000);
            if (isLoggedIn(driver)) return true;
        }
        logger.accept("로그인 대기 시간 초과 (5분) - 다운로드 종료.");
        return false;
    }

    private WebElement findVisibleInput(ChromeDriver driver, String... hints) {
        try {
            List<WebElement> inputs = driver.findElements(
                By.cssSelector("input[type=text], input[type=search], input:not([type])"));
            for (WebElement el : inputs) {
                if (!el.isDisplayed()) continue;
                String ph = safeAttr(el, "placeholder").toLowerCase();
                String id2 = safeAttr(el, "id").toLowerCase();
                String name = safeAttr(el, "name").toLowerCase();
                for (String hint : hints) {
                    String h = hint.toLowerCase();
                    if (ph.contains(h) || id2.contains(h) || name.contains(h)) return el;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private WebElement findFirstVisibleInput(ChromeDriver driver) {
        try {
            List<WebElement> inputs = driver.findElements(
                By.cssSelector("input[type=text], input[type=search], input:not([type])"));
            for (WebElement el : inputs) { if (el.isDisplayed()) return el; }
        } catch (Exception ignored) {}
        return null;
    }

    private boolean clickLinkByText(ChromeDriver driver, String... texts) {
        try {
            List<WebElement> els = driver.findElements(By.cssSelector("a, button"));
            for (WebElement el : els) {
                if (!el.isDisplayed()) continue;
                String t = el.getText().trim();
                for (String text : texts) {
                    if (t.equals(text) || t.contains(text)) { el.click(); return true; }
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    private boolean clickButtonByText(ChromeDriver driver, String... texts) {
        try {
            List<WebElement> els = driver.findElements(
                By.cssSelector("button, a, input[type=submit], input[type=button]"));
            for (WebElement el : els) {
                if (!el.isDisplayed()) continue;
                String t = (el.getText() + " " + safeAttr(el, "value")).trim();
                for (String text : texts) {
                    if (t.equals(text) || t.contains(text)) { el.click(); return true; }
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    private String safeAttr(WebElement el, String attr) {
        try { String v = el.getAttribute(attr); return v != null ? v : ""; }
        catch (Exception e) { return ""; }
    }

    private void waitForText(ChromeDriver driver, int maxMs, String... texts) throws InterruptedException {
        long end = System.currentTimeMillis() + maxMs;
        while (System.currentTimeMillis() < end) {
            try {
                String pageText = getPageText(driver);
                for (String t : texts) { if (pageText.contains(t)) return; }
            } catch (Exception ignored) {}
            Thread.sleep(400);
        }
    }

    private String getPageText(WebDriver driver) {
        try {
            Object r = ((JavascriptExecutor) driver)
                .executeScript("return document.body ? (document.body.innerText || '') : ''");
            return r != null ? String.valueOf(r) : "";
        } catch (Exception e) { return ""; }
    }

    void saveScreenshot(ChromeDriver driver, String name) {
        try {
            File src = driver.getScreenshotAs(OutputType.FILE);
            File dest = new File(savePath + File.separator + "dbg_" + name + ".png");
            dest.getParentFile().mkdirs();
            Files.copy(src.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
            logger.accept("스크린샷: " + dest.getName());
        } catch (Exception e) {
            logger.accept("스크린샷 실패: " + e.getMessage());
        }
    }

    // ─── 주소 파싱 ────────────────────────────────────────────────────────

    static class AddressParts {
        String buildingAddress; // 팝업 검색어 (동호수 제외)
        String dong;            // 아파트 동 번호 (203)
        String ho;              // 아파트 호 번호 (101)
        String jibunDong;       // 지번 법정동 이름 (방이동, 공세동)
        String jibunMain;       // 지번 본번 (42, 714)
        String jibunSub;        // 지번 부번 (1, 0)
    }

    private AddressParts parseAddress(String address) {
        AddressParts p = new AddressParts();
        // 동호 추출 (X동 Y호)
        Matcher dongHo = Pattern.compile("(\\d+)동\\s*(\\d+)호").matcher(address);
        if (dongHo.find()) {
            p.dong = dongHo.group(1);
            p.ho = dongHo.group(2);
            p.buildingAddress = address.substring(0, dongHo.start()).trim();
        } else {
            Matcher ho = Pattern.compile("(\\d+)호").matcher(address);
            if (ho.find()) {
                p.ho = ho.group(1);
                p.dong = "";
                p.buildingAddress = address.substring(0, ho.start()).trim();
            } else {
                p.dong = "";
                p.ho = "";
                p.buildingAddress = address.trim();
            }
        }
        // 지번 파싱: "법정동 본번-부번" 추출 (뒤에 건물명 있어도 OK)
        // 예) "방이동 42-1" → jibunDong=방이동, jibunMain=42, jibunSub=1
        // 예) "방이동 46-2 사보이시티잠실" → jibunDong=방이동, jibunMain=46, jibunSub=2
        // 예) "공세동 714" → jibunDong=공세동, jibunMain=714, jibunSub=0
        Matcher jibun = Pattern.compile("^([가-힣]+)\\s+(\\d+)(?:-(\\d+))?(?:\\s+.*)?$").matcher(p.buildingAddress);
        if (jibun.matches()) {
            p.jibunDong = jibun.group(1).trim();
            p.jibunMain = jibun.group(2);
            p.jibunSub  = jibun.group(3) != null ? jibun.group(3) : "0";
        } else {
            p.jibunDong = p.buildingAddress;
            p.jibunMain = "";
            p.jibunSub  = "0";
        }
        return p;
    }
}
